package com.grayvines.roche

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.provider.MediaStore
import androidx.core.net.toUri
import com.grayvines.roche.core.SCHEME_SPOTIFY
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.MusicTrackLaunchRequest
import com.grayvines.roche.data.catalog.candidateStreamingUrls
import com.grayvines.roche.data.catalog.hasYoutubeAlbumBrowseId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MusicUrlCandidate(
    val packageName: String,
    val activityName: String,
    val url: String,
    val isUserSelectedForHost: Boolean,
    val isVerifiedForHost: Boolean = false,
)

internal sealed interface MusicUrlResolution {
    data object None : MusicUrlResolution

    data class SingleTarget(val target: MusicUrlCandidate) : MusicUrlResolution

    data class ChooserTargets(val targets: List<MusicUrlCandidate>) : MusicUrlResolution
}

internal sealed interface InitialAlbumLaunchPlan {
    data class LaunchDirect(val resolution: MusicUrlResolution) : InitialAlbumLaunchPlan

    data object NeedsEnrichment : InitialAlbumLaunchPlan

    data object FallbackSearchOrBrowser : InitialAlbumLaunchPlan
}

internal suspend fun resolveInitialAlbumLaunchPlan(
    context: Context,
    album: CollectionDrawSnapshot,
): InitialAlbumLaunchPlan =
    withContext(Dispatchers.IO) {
        val browserPackages = queryBrowserPackages(context.packageManager)
        val directResolution =
            selectMusicUrlResolution(
                candidates = queryStreamingUrlCandidates(context, album),
                selfPackageName = context.packageName,
                browserPackages = browserPackages,
            )
        val needsUpgrade =
            !album.hasYoutubeAlbumBrowseId && usesYoutubePlaylistUrl(directResolution)
        when {
            !needsUpgrade && directResolution != MusicUrlResolution.None ->
                InitialAlbumLaunchPlan.LaunchDirect(directResolution)
            hasInstalledMusicApp(context, browserPackages) -> InitialAlbumLaunchPlan.NeedsEnrichment
            else -> InitialAlbumLaunchPlan.FallbackSearchOrBrowser
        }
    }

internal suspend fun resolveEnrichedAlbumLaunch(
    context: Context,
    album: CollectionDrawSnapshot,
): MusicUrlResolution =
    withContext(Dispatchers.IO) {
        val browserPackages = queryBrowserPackages(context.packageManager)
        selectMusicUrlResolution(
            candidates = queryStreamingUrlCandidates(context, album),
            selfPackageName = context.packageName,
            browserPackages = browserPackages,
        )
    }

internal fun launchAlbumSearchOrFallback(
    context: Context,
    album: CollectionDrawSnapshot,
    onOpenFallbackUrl: (String) -> Unit,
) {
    if (!tryLaunchMediaSearch(context, album)) {
        onOpenFallbackUrl(album.youtubeMusicUrl)
    }
}

internal fun usesYoutubePlaylistUrl(resolution: MusicUrlResolution): Boolean =
    when (resolution) {
        MusicUrlResolution.None -> false
        is MusicUrlResolution.SingleTarget -> isYoutubeMusicPlaylistUrl(resolution.target.url)
        is MusicUrlResolution.ChooserTargets ->
            resolution.targets.any { isYoutubeMusicPlaylistUrl(it.url) }
    }

private fun isYoutubeMusicPlaylistUrl(url: String): Boolean =
    url.startsWith(YT_MUSIC_PLAYLIST_URL_PREFIX)

private fun hasInstalledMusicApp(
    context: Context,
    browserPackages: Set<String>,
): Boolean {
    val candidates = queryUrlListCandidates(context, PLATFORM_PROBE_URLS)
    val resolution =
        selectMusicUrlResolution(
            candidates = candidates,
            selfPackageName = context.packageName,
            browserPackages = browserPackages,
        )
    return resolution != MusicUrlResolution.None
}

internal fun selectMusicUrlResolution(
    candidates: List<MusicUrlCandidate>,
    selfPackageName: String,
    browserPackages: Set<String>,
): MusicUrlResolution {
    val uniqueByPackage = LinkedHashMap<String, MusicUrlCandidate>()
    for (candidate in candidates) {
        val pkg = candidate.packageName
        val excluded =
            pkg.isBlank() ||
                pkg == selfPackageName ||
                pkg in browserPackages ||
                isNonMusicVideoPackage(pkg)
        if (excluded) {
            continue
        }
        val existing = uniqueByPackage[pkg]
        if (existing == null || candidatePriority(candidate) > candidatePriority(existing)) {
            uniqueByPackage[pkg] = candidate
        }
    }
    val deduped = uniqueByPackage.values.toList()
    val verified = deduped.filter { it.isVerifiedForHost }
    val userSelected = deduped.filter { it.isUserSelectedForHost }
    val preferred = verified.ifEmpty { userSelected }.ifEmpty { deduped }
    return when (preferred.size) {
        0 -> MusicUrlResolution.None
        1 -> MusicUrlResolution.SingleTarget(preferred.single())
        else -> MusicUrlResolution.ChooserTargets(preferred)
    }
}

private fun candidatePriority(candidate: MusicUrlCandidate): Int =
    when {
        candidate.isVerifiedForHost -> 2
        candidate.isUserSelectedForHost -> 1
        else -> 0
    }

internal fun queryStreamingUrlCandidates(
    context: Context,
    album: CollectionDrawSnapshot,
): List<MusicUrlCandidate> = queryUrlListCandidates(context, album.candidateStreamingUrls())

private fun queryUrlListCandidates(
    context: Context,
    urls: List<String>,
): List<MusicUrlCandidate> {
    val pm = context.packageManager
    val dvm = context.getSystemService(DomainVerificationManager::class.java)
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
    val discovered = mutableListOf<MusicUrlCandidate>()

    for (url in urls) {
        val uri = url.toUri()
        val host = uri.host?.lowercase().orEmpty()
        val probe = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching {
            pm.queryIntentActivities(probe, flags)
        }
            .getOrDefault(emptyList())
        resolved.mapNotNullTo(discovered) { info ->
            toMusicUrlCandidate(info = info, dvm = dvm, url = url, host = host)
        }
    }
    return discovered
}

internal fun toMusicUrlCandidate(
    info: ResolveInfo,
    dvm: DomainVerificationManager?,
    url: String,
    host: String,
): MusicUrlCandidate? {
    val activityInfo = info.activityInfo ?: return null
    val pkg = activityInfo.packageName.orEmpty()
    val isVideoCategory = activityInfo.applicationInfo?.category == ApplicationInfo.CATEGORY_VIDEO
    if (isVideoCategory || isNonMusicVideoPackage(pkg)) return null
    val userState = runCatching { dvm?.getDomainVerificationUserState(pkg) }.getOrNull()
    if (userState?.isLinkHandlingAllowed == false) return null
    return MusicUrlCandidate(
        packageName = pkg,
        activityName = activityInfo.name.orEmpty(),
        url = url,
        isUserSelectedForHost = isHostUserSelected(userState, host),
        isVerifiedForHost = isHostDomainVerified(userState?.hostToStateMap.orEmpty(), host),
    )
}

internal fun isHostUserSelected(
    userState: DomainVerificationUserState?,
    host: String,
): Boolean {
    if (userState == null || host.isBlank()) return false
    return isHostDomainSelected(userState.hostToStateMap, host)
}

internal fun isHostDomainVerified(
    hostStates: Map<String, Int>,
    host: String,
): Boolean = matchesDomainState(hostStates, host, DomainVerificationUserState.DOMAIN_STATE_VERIFIED)

internal fun isHostDomainSelected(
    hostStates: Map<String, Int>,
    host: String,
): Boolean =
    matchesDomainState(hostStates, host, DomainVerificationUserState.DOMAIN_STATE_VERIFIED) ||
        matchesDomainState(hostStates, host, DomainVerificationUserState.DOMAIN_STATE_SELECTED)

private fun matchesDomainState(
    hostStates: Map<String, Int>,
    host: String,
    expectedState: Int,
): Boolean {
    if (host.isBlank()) return false
    val parentDomain = host.substringAfter('.', missingDelimiterValue = "")
    val wildcardHost =
        parentDomain.takeIf { it.isNotBlank() && it != SHARED_VIDEO_PARENT_DOMAIN }?.let { "*.$it" }
    return hostStates[host] == expectedState ||
        wildcardHost != null && hostStates[wildcardHost] == expectedState
}

internal fun isNonMusicVideoPackage(packageName: String): Boolean {
    val clean = packageName.trim()
    return clean in NON_MUSIC_VIDEO_PACKAGES ||
        NON_MUSIC_VIDEO_PACKAGE_PREFIXES.any { prefix ->
            clean == prefix || clean.startsWith("$prefix.")
        }
}

internal fun queryBrowserPackages(packageManager: PackageManager): Set<String> {
    val probe =
        Intent(Intent.ACTION_VIEW, BROWSER_PROBE_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
    return runCatching { packageManager.queryIntentActivities(probe, flags) }
        .getOrDefault(emptyList())
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
}

internal fun launchStreamingResolution(
    context: Context,
    album: CollectionDrawSnapshot,
    resolution: MusicUrlResolution,
): Boolean =
    when (resolution) {
        MusicUrlResolution.None -> {
            false
        }
        is MusicUrlResolution.SingleTarget -> {
            runCatching {
                    context.startActivity(buildExplicitViewIntent(resolution.target))
                    true
                }
                .getOrDefault(false)
        }
        is MusicUrlResolution.ChooserTargets -> {
            val intents = resolution.targets.map(::buildExplicitViewIntent)
            val chooserTitle =
                if (album.creator.isNotBlank()) "${album.title} — ${album.creator}" else album.title
            val chooser =
                Intent.createChooser(intents.first(), chooserTitle).apply {
                    val extras = intents.drop(1).toTypedArray()
                    if (extras.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extras)
                    }
                }
            runCatching {
                    context.startActivity(chooser)
                    true
                }
                .getOrDefault(false)
        }
    }

internal fun buildExplicitViewIntent(target: MusicUrlCandidate): Intent {
    val effectiveUrl = rewriteUrlForTargetPackage(target.packageName, target.url)
    return Intent(Intent.ACTION_VIEW, effectiveUrl.toUri()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (target.activityName.isNotBlank()) {
            setClassName(target.packageName, target.activityName)
        } else {
            setPackage(target.packageName)
        }
    }
}

internal fun rewriteUrlForTargetPackage(packageName: String, url: String): String {
    val cleanPkg = packageName.trim()
    val isMetrolist =
        cleanPkg == METROLIST_PACKAGE ||
            cleanPkg.startsWith("$METROLIST_PACKAGE.") ||
            cleanPkg.startsWith(METROLIST_ALT_PACKAGE_PREFIX)
    return if (isMetrolist && url.startsWith(YT_MUSIC_OLAK_PLAYLIST_PREFIX)) {
        url.replaceFirst("https://music.youtube.com/", "https://www.youtube.com/")
    } else {
        url
    }
}

internal fun tryLaunchMediaSearch(
    context: Context,
    album: CollectionDrawSnapshot,
): Boolean {
    val actions =
        listOf(
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH,
            MediaStore.INTENT_ACTION_MEDIA_SEARCH,
        )
    return actions.any { action ->
        runCatching {
                context.startActivity(buildAlbumSearchIntent(action, album))
                true
            }
            .getOrDefault(false)
    }
}

internal fun buildAlbumSearchIntent(
    action: String,
    album: CollectionDrawSnapshot,
): Intent {
    val query = "${album.creator} ${album.title}".trim().ifBlank { album.title }
    return Intent(action).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
        putExtra(MediaStore.EXTRA_MEDIA_ALBUM, album.title)
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, album.creator)
        putExtra(SearchManager.QUERY, query)
    }
}

private sealed interface TrackLaunchPlan {
    data class Streaming(val resolution: MusicUrlResolution) : TrackLaunchPlan

    data class ExplicitSearch(val intent: Intent) : TrackLaunchPlan

    data object BrowserFallback : TrackLaunchPlan
}

internal suspend fun launchMusicTrack(
    context: Context,
    track: MusicTrackLaunchRequest,
    onOpenFallbackUrl: (String) -> Unit,
) {
    val plan = withContext(Dispatchers.IO) { resolveTrackLaunchPlan(context, track) }
    withContext(Dispatchers.Main.immediate) {
        val launched =
            when (plan) {
                is TrackLaunchPlan.Streaming ->
                    launchTrackStreaming(context, track, plan.resolution)
                is TrackLaunchPlan.ExplicitSearch ->
                    runCatching {
                            context.startActivity(plan.intent)
                            true
                        }
                        .getOrDefault(false)
                TrackLaunchPlan.BrowserFallback -> false
            }
        if (!launched) {
            val query = "${track.artist} ${track.trackTitle}".trim().ifBlank { track.trackTitle }
            onOpenFallbackUrl("https://music.youtube.com/search?q=${android.net.Uri.encode(query)}")
        }
    }
}

private fun launchTrackStreaming(
    context: Context,
    track: MusicTrackLaunchRequest,
    resolution: MusicUrlResolution,
): Boolean =
    launchStreamingResolution(
        context,
        CollectionDrawSnapshot(title = track.trackTitle, creator = track.artist),
        resolution,
    )

private fun resolveTrackLaunchPlan(
    context: Context,
    track: MusicTrackLaunchRequest,
): TrackLaunchPlan {
    val browserPackages = queryBrowserPackages(context.packageManager)
    val trackUrl =
        track.spotifyUri
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { externalIdentifierUrl(SCHEME_SPOTIFY, it, "song") }
    if (trackUrl != null) {
        val resolution =
            selectMusicUrlResolution(
                candidates = queryUrlListCandidates(context, listOf(trackUrl)),
                selfPackageName = context.packageName,
                browserPackages = browserPackages,
            )
        if (resolution != MusicUrlResolution.None) {
            return TrackLaunchPlan.Streaming(resolution)
        }
    }
    val explicitSearch = resolveMusicSearchIntent(context, track, browserPackages)
    return if (explicitSearch != null) {
        TrackLaunchPlan.ExplicitSearch(explicitSearch)
    } else {
        TrackLaunchPlan.BrowserFallback
    }
}

private fun resolveMusicSearchIntent(
    context: Context,
    track: MusicTrackLaunchRequest,
    browserPackages: Set<String>,
): Intent? {
    val pm = context.packageManager
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
    val actions =
        listOf(
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH,
            MediaStore.INTENT_ACTION_MEDIA_SEARCH,
        )
    for (action in actions) {
        val baseIntent = buildTrackSearchIntent(action, track)
        val candidates = runCatching {
            pm.queryIntentActivities(baseIntent, flags)
        }
            .getOrDefault(emptyList())
        val musicTarget = candidates.firstOrNull { info ->
            val activity = info.activityInfo
            if (activity == null) {
                false
            } else {
                val pkg = activity.packageName.orEmpty()
                val isVideoCategory =
                    activity.applicationInfo?.category == ApplicationInfo.CATEGORY_VIDEO
                pkg.isNotBlank() &&
                    pkg != context.packageName &&
                    pkg !in browserPackages &&
                    !isVideoCategory &&
                    !isNonMusicVideoPackage(pkg)
            }
        }
        if (musicTarget?.activityInfo != null) {
            return baseIntent.apply {
                setClassName(musicTarget.activityInfo.packageName, musicTarget.activityInfo.name)
            }
        }
    }
    return null
}

internal fun buildTrackSearchIntent(
    action: String,
    track: MusicTrackLaunchRequest,
): Intent {
    val query = "${track.artist} ${track.trackTitle}".trim().ifBlank { track.trackTitle }
    return Intent(action).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putExtra(MediaStore.EXTRA_MEDIA_TITLE, track.trackTitle)
        putExtra(MediaStore.EXTRA_MEDIA_ALBUM, track.albumTitle)
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
        putExtra(SearchManager.QUERY, query)
    }
}

private const val BROWSER_PROBE_URL = "https://example.invalid"
private const val SHARED_VIDEO_PARENT_DOMAIN = "youtube.com"
private const val YT_MUSIC_PLAYLIST_URL_PREFIX = "https://music.youtube.com/playlist?list="
private const val YT_MUSIC_OLAK_PLAYLIST_PREFIX = "https://music.youtube.com/playlist?list=OLAK5uy_"
private const val METROLIST_PACKAGE = "com.metrolist.music"
private const val METROLIST_ALT_PACKAGE_PREFIX = "com.metrolist.android"

private val NON_MUSIC_VIDEO_PACKAGES =
    setOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
        "com.vanced.android.youtube",
        "com.github.libretube",
        "com.futo.platformplayer",
    )

private val NON_MUSIC_VIDEO_PACKAGE_PREFIXES =
    listOf(
        "org.schabi.newpipe",
        "org.polymorphicshade.tubular",
        "org.polymorphicshade.newpipe",
        "InfinityLoop1309.NewPipeEnhanced",
        "org.bravenewpipe",
        "free.rm.skytube",
    )

private val PLATFORM_PROBE_URLS =
    listOf(
        "https://music.youtube.com/playlist?list=OLAK5uy_probe",
        "https://music.youtube.com/browse/MPREb_probe",
        "https://open.spotify.com/album/0probe",
        "https://music.apple.com/album/1",
        "https://tidal.com/browse/album/1",
        "https://listen.tidal.com/album/1",
        "https://open.qobuz.com/album/1",
        "https://www.deezer.com/album/1",
        "https://music.amazon.com/albums/B000000000",
    )
