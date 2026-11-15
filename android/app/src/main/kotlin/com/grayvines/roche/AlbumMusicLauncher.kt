package com.grayvines.roche

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.provider.MediaStore
import androidx.core.net.toUri
import com.grayvines.roche.data.DailyAlbumSnapshot
import com.grayvines.roche.data.candidateStreamingUrls

internal data class MusicUrlCandidate(
    val packageName: String,
    val activityName: String,
    val url: String,
    val isUserSelectedForHost: Boolean,
)

internal sealed interface MusicUrlResolution {
    data object None : MusicUrlResolution

    data class SingleTarget(val target: MusicUrlCandidate) : MusicUrlResolution

    data class ChooserTargets(val targets: List<MusicUrlCandidate>) : MusicUrlResolution
}

internal fun openAlbumInMusicApp(
    context: Context,
    album: DailyAlbumSnapshot,
    onOpenFallbackUrl: (String) -> Unit,
) {
    val candidates = queryStreamingUrlCandidates(context, album)
    val resolution =
        selectMusicUrlResolution(
            candidates = candidates,
            selfPackageName = context.packageName,
            browserPackages = queryBrowserPackages(context.packageManager),
        )
    if (launchStreamingResolution(context, album, resolution)) {
        return
    }
    if (tryLaunchMediaSearch(context, album)) {
        return
    }
    onOpenFallbackUrl(album.youtubeMusicUrl)
}

internal fun selectMusicUrlResolution(
    candidates: List<MusicUrlCandidate>,
    selfPackageName: String,
    browserPackages: Set<String>,
): MusicUrlResolution {
    val uniqueByPackage = LinkedHashMap<String, MusicUrlCandidate>()
    for (candidate in candidates) {
        val pkg = candidate.packageName
        if (pkg.isBlank() || pkg == selfPackageName || pkg in browserPackages) {
            continue
        }
        val existing = uniqueByPackage[pkg]
        if (
            existing == null || !existing.isUserSelectedForHost && candidate.isUserSelectedForHost
        ) {
            uniqueByPackage[pkg] =
                if (existing != null) {
                    existing.copy(isUserSelectedForHost = true)
                } else {
                    candidate
                }
        }
    }
    val deduped = uniqueByPackage.values.toList()
    val userSelected = deduped.filter { it.isUserSelectedForHost }
    val preferred = userSelected.ifEmpty { deduped }
    return when (preferred.size) {
        0 -> MusicUrlResolution.None
        1 -> MusicUrlResolution.SingleTarget(preferred.single())
        else -> MusicUrlResolution.ChooserTargets(preferred)
    }
}

internal fun queryStreamingUrlCandidates(
    context: Context,
    album: DailyAlbumSnapshot,
): List<MusicUrlCandidate> {
    val pm = context.packageManager
    val dvm = context.getSystemService(DomainVerificationManager::class.java)
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
    val discovered = mutableListOf<MusicUrlCandidate>()

    for (url in album.candidateStreamingUrls()) {
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
    val userState = runCatching { dvm?.getDomainVerificationUserState(pkg) }.getOrNull()
    if (userState?.isLinkHandlingAllowed == false) return null
    return MusicUrlCandidate(
        packageName = pkg,
        activityName = activityInfo.name.orEmpty(),
        url = url,
        isUserSelectedForHost = isHostUserSelected(userState, host),
    )
}

internal fun isHostUserSelected(
    userState: DomainVerificationUserState?,
    host: String,
): Boolean {
    if (userState == null || host.isBlank()) return false
    val hostStates = userState.hostToStateMap
    val wildcardHost = "*.${host.substringAfter('.', missingDelimiterValue = "")}"
    return hostStates[host] == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
        hostStates[wildcardHost] == DomainVerificationUserState.DOMAIN_STATE_SELECTED
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
    album: DailyAlbumSnapshot,
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
            val chooserTitle = "${album.name} — ${album.artist}"
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

internal fun buildExplicitViewIntent(target: MusicUrlCandidate): Intent =
    Intent(Intent.ACTION_VIEW, target.url.toUri()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (target.activityName.isNotBlank()) {
            setClassName(target.packageName, target.activityName)
        } else {
            setPackage(target.packageName)
        }
    }

internal fun tryLaunchMediaSearch(
    context: Context,
    album: DailyAlbumSnapshot,
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
    album: DailyAlbumSnapshot,
): Intent {
    val query = "${album.artist} ${album.name}".trim().ifBlank { album.name }
    return Intent(action).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
        putExtra(MediaStore.EXTRA_MEDIA_ALBUM, album.name)
        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, album.artist)
        putExtra(SearchManager.QUERY, query)
    }
}

private const val BROWSER_PROBE_URL = "https://example.invalid"
