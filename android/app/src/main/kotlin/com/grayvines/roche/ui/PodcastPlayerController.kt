@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.grayvines.roche.data.parseDurationToSecondsLong
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.InboxEntry
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PlayerDrawerTab(val label: String) {
    SHOW_NOTES("Show Notes"),
    CHAPTERS("Chapters"),
    TRANSCRIPT("Transcript"),
}

data class PodcastChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
) {
    val formattedStart: String
        get() = formatPlaybackClock(startMs)
}

enum class MediaSessionCommand {
    PLAY,
    PAUSE,
    TOGGLE_PLAY_PAUSE,
    REWIND_15S,
    FAST_FORWARD_30S,
    SKIP_TO_NEXT,
    STOP,
}

data class MediaSessionMetadataSnapshot(
    val entryId: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val hasNextQueuedAudio: Boolean,
)

interface PodcastMediaSessionCallbacks {
    fun onMediaCommand(command: MediaSessionCommand)

    fun onSeekToMs(positionMs: Long)

    fun onSetPlaybackSpeed(speed: Float)

    fun onAudioFocusLost(transient: Boolean)

    fun onAudioFocusGained()

    fun onAudioBecomingNoisy()
}

interface PodcastMediaSessionBridge {
    fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks)

    fun unbindCallbacks(callbacks: PodcastMediaSessionCallbacks) = Unit

    fun requestAudioFocus(): Boolean

    fun abandonAudioFocus()

    fun updatePlaybackSession(snapshot: MediaSessionMetadataSnapshot?)

    fun release()
}

object NoOpPodcastMediaSessionBridge : PodcastMediaSessionBridge {
    override fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks) = Unit

    override fun requestAudioFocus(): Boolean = true

    override fun abandonAudioFocus() = Unit

    override fun updatePlaybackSession(snapshot: MediaSessionMetadataSnapshot?) = Unit

    override fun release() = Unit
}

data class PodcastPlayerState(
    val currentEntryId: String? = null,
    val currentEpisodeTitle: String = "",
    val currentPodcastTitle: String = "",
    val currentArtworkUrl: String? = null,
    val hasNextQueuedAudio: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = Settings.DEFAULT_PLAYBACK_SPEED,
    val skipSilenceEnabled: Boolean = false,
    val smartRewindEnabled: Boolean = true,
    val sleepTimerRemainingSec: Int? = null,
    val isFullscreenOpen: Boolean = false,
    val isSpeedSheetOpen: Boolean = false,
    val activeDrawerTab: PlayerDrawerTab? = null,
    val chapters: List<PodcastChapter> = emptyList(),
) {
    val hasActiveEpisode: Boolean
        get() = !currentEntryId.isNullOrBlank()

    val progressFraction: Float
        get() =
            if (durationMs <= 0L) {
                0f
            } else {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            }

    val currentChapter: PodcastChapter?
        get() = chapters.lastOrNull { it.startMs <= positionMs } ?: chapters.firstOrNull()

    val formattedElapsed: String
        get() = formatPlaybackClock(positionMs)

    val formattedRemaining: String
        get() {
            val rem = (durationMs - positionMs).coerceAtLeast(0L)
            return "-${formatPlaybackClock(rem)}"
        }

    val formattedSpeedAdjustedRemaining: String
        get() {
            val remMs = (durationMs - positionMs).coerceAtLeast(0L)
            val safeSpeed = playbackSpeed.coerceIn(0.50f, 3.50f)
            val adjustedMinutes =
                (remMs / 1000.0 / safeSpeed / 60.0).roundToLong().coerceAtLeast(0L)
            val speedLabel = formatPlaybackSpeedLabel(safeSpeed)
            return if (adjustedMinutes >= 60L) {
                val h = adjustedMinutes / 60L
                val m = adjustedMinutes % 60L
                "${h}h ${m}m left (at $speedLabel)"
            } else {
                "${adjustedMinutes}m left (at $speedLabel)"
            }
        }

    val formattedMiniRemaining: String
        get() {
            val remMs = (durationMs - positionMs).coerceAtLeast(0L)
            val safeSpeed = playbackSpeed.coerceIn(0.50f, 3.50f)
            val adjustedMinutes =
                (remMs / 1000.0 / safeSpeed / 60.0).roundToLong().coerceAtLeast(1L)
            return if (adjustedMinutes >= 60L) {
                val h = adjustedMinutes / 60L
                val m = adjustedMinutes % 60L
                "${h}h ${m}m left"
            } else {
                "${adjustedMinutes}m left"
            }
        }

    val elapsedFormatted: String
        get() = formattedElapsed

    val remainingFormatted: String
        get() = formattedRemaining

    val speedAdjustedRemainingLabel: String
        get() = formattedMiniRemaining

    val sleepTimerRemainingMinutes: Int?
        get() = sleepTimerRemainingSec?.let { ((it + 59) / 60).coerceAtLeast(1) }
}

interface AudioPlayerBackend {
    fun loadAndPlay(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        playWhenReady: Boolean = true,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    )

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun currentPositionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean

    fun isUsingNativePlayer(): Boolean = false

    fun release()
}

internal class ResilientMediaPlayerBackend : AudioPlayerBackend {
    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared: Boolean = false
    private var simulatedPositionMs: Long = 0L
    private var simulatedDurationMs: Long = DEFAULT_FALLBACK_DURATION_MS
    private var simulatedIsPlaying: Boolean = false
    private var currentSpeed: Float = 1.0f

    override fun loadAndPlay(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        playWhenReady: Boolean,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    ) {
        release()
        if (source.isBlank()) {
            onError()
            return
        }
        currentSpeed = speed
        simulatedDurationMs = fallbackDurationMs.takeIf { it > 0L } ?: DEFAULT_FALLBACK_DURATION_MS
        simulatedPositionMs = initialPositionMs.coerceIn(0L, simulatedDurationMs)
        simulatedIsPlaying = playWhenReady

        val startedNative = runCatching {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(source)
            mp.setOnPreparedListener { player ->
                isPrepared = true
                val dur = player.duration.toLong().takeIf { it > 0L } ?: simulatedDurationMs
                simulatedDurationMs = dur
                val seekTarget = simulatedPositionMs.coerceIn(0L, dur)
                if (seekTarget in 1 until dur) {
                    player.seekTo(seekTarget.toInt())
                }
                if (simulatedIsPlaying) {
                    applyPlaybackSpeedBestEffort(player, currentSpeed)
                    player.start()
                }
                onPrepared(dur)
            }
            mp.setOnCompletionListener {
                simulatedIsPlaying = false
                onCompletion()
            }
            mp.setOnErrorListener { player, _, _ ->
                isPrepared = false
                runCatching { player.release() }
                if (mediaPlayer === player) {
                    mediaPlayer = null
                }
                onPrepared(simulatedDurationMs)
                true
            }
            mp.prepareAsync()
            true
        }
            .getOrDefault(false)

        if (!startedNative) {
            release()
            simulatedIsPlaying = playWhenReady
            onPrepared(simulatedDurationMs)
        }
    }

    override fun play() {
        simulatedIsPlaying = true
        if (!isPrepared) return
        runCatching {
            mediaPlayer?.let { player ->
                applyPlaybackSpeedBestEffort(player, currentSpeed)
                player.start()
            }
        }
    }

    override fun pause() {
        simulatedIsPlaying = false
        if (!isPrepared) return
        runCatching {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                simulatedPositionMs = player.currentPosition.toLong().coerceAtLeast(0L)
                player.pause()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, simulatedDurationMs)
        simulatedPositionMs = clamped
        if (!isPrepared) return
        runCatching { mediaPlayer?.seekTo(clamped.toInt()) }
    }

    override fun setSpeed(speed: Float) {
        currentSpeed = speed
        if (!isPrepared || !simulatedIsPlaying) return
        runCatching { mediaPlayer?.let { applyPlaybackSpeedBestEffort(it, speed) } }
    }

    override fun currentPositionMs(): Long {
        if (!isPrepared) return simulatedPositionMs
        val nativePos = runCatching {
            mediaPlayer?.currentPosition?.toLong()?.takeIf { it >= 0L }
        }
            .getOrNull()
        return nativePos ?: simulatedPositionMs
    }

    override fun durationMs(): Long {
        if (!isPrepared) return simulatedDurationMs
        val nativeDur = runCatching {
            mediaPlayer?.duration?.toLong()?.takeIf { it > 0L }
        }
            .getOrNull()
        return nativeDur ?: simulatedDurationMs
    }

    override fun isPlaying(): Boolean {
        if (!isPrepared) return simulatedIsPlaying
        return runCatching { mediaPlayer?.isPlaying }.getOrNull() ?: simulatedIsPlaying
    }

    override fun isUsingNativePlayer(): Boolean = mediaPlayer != null && isPrepared

    override fun release() {
        isPrepared = false
        simulatedIsPlaying = false
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching {
                player.setOnPreparedListener(null)
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
            }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    private fun applyPlaybackSpeedBestEffort(player: MediaPlayer, speed: Float) {
        runCatching {
            player.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.50f, 3.50f))
        }
    }
}

class PodcastPlayerController(
    private val scope: CoroutineScope,
    private val backend: AudioPlayerBackend = ResilientMediaPlayerBackend(),
    private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
    private val onProgressChanged: (entryId: String, progress: Double) -> Unit = { _, _ -> },
    private val onEpisodeCompleted: (entryId: String) -> Unit = {},
    private val onSkipToNextRequested: () -> Unit = {},
    private val onSpeedChangedFromSession: (Float) -> Unit = {},
) : PodcastMediaSessionCallbacks {
    private val _state = MutableStateFlow(PodcastPlayerState())
    val state: StateFlow<PodcastPlayerState> = _state.asStateFlow()

    private var tickerJob: Job? = null
    private var pausedAtEpochMs: Long? = null
    private var resumeAfterTransientFocusLoss: Boolean = false
    private var pendingResumeProgress: Double? = null

    init {
        mediaSessionBridge.bindCallbacks(this)
    }

    fun playEntry(
        entry: InboxEntry,
        sourceUriOrPath: String,
        speed: Float,
        skipSilenceEnabled: Boolean,
        openFullscreen: Boolean = true,
        hasNextQueuedAudio: Boolean = _state.value.hasNextQueuedAudio,
        smartRewindEnabled: Boolean = _state.value.smartRewindEnabled,
    ) {
        tickerJob?.cancel()
        val hasFocus = mediaSessionBridge.requestAudioFocus()
        val clampedSpeed = clampPlaybackSpeed(speed)
        val parsedDurationSeconds = parseDurationToSecondsLong(entry.duration)
        val estimatedDurationMs =
            parsedDurationSeconds?.times(1_000L) ?: DEFAULT_FALLBACK_DURATION_MS
        val hasValidResumeProgress = entry.readProgress in 0.01..0.98
        pendingResumeProgress =
            if (parsedDurationSeconds == null && hasValidResumeProgress) {
                entry.readProgress
            } else {
                null
            }
        val initialPositionMs =
            if (hasValidResumeProgress) {
                (estimatedDurationMs * entry.readProgress).roundToLong()
            } else {
                0L
            }
        val parsedChapters = extractBestChaptersFromEntry(entry)
        pausedAtEpochMs = null
        resumeAfterTransientFocusLoss = false
        _state.update { current ->
            current.copy(
                currentEntryId = entry.id,
                currentEpisodeTitle = entry.title,
                currentPodcastTitle = entry.feedTitle,
                currentArtworkUrl = entry.thumbnailUrl,
                hasNextQueuedAudio = hasNextQueuedAudio,
                isPlaying = hasFocus,
                isBuffering = true,
                positionMs = initialPositionMs,
                durationMs = estimatedDurationMs,
                playbackSpeed = clampedSpeed,
                skipSilenceEnabled = skipSilenceEnabled,
                smartRewindEnabled = smartRewindEnabled,
                isFullscreenOpen = openFullscreen,
                activeDrawerTab = null,
                chapters = parsedChapters,
            )
        }
        syncMediaSessionBridge()
        backend.loadAndPlay(
            source = sourceUriOrPath,
            initialPositionMs = initialPositionMs,
            fallbackDurationMs = estimatedDurationMs,
            speed = clampedSpeed,
            playWhenReady = hasFocus,
            onPrepared = { dur -> handleBackendPrepared(entry.id, dur) },
            onCompletion = { handleActiveEpisodeFinished() },
            onError = {
                tickerJob?.cancel()
                mediaSessionBridge.abandonAudioFocus()
                _state.update { it.copy(isBuffering = false, isPlaying = false) }
                syncMediaSessionBridge()
            },
        )
    }

    private fun handleBackendPrepared(entryId: String, durationMs: Long) {
        if (_state.value.currentEntryId != entryId) return
        val safeDurationMs = durationMs.coerceAtLeast(1_000L)
        val resumeProgress = pendingResumeProgress
        pendingResumeProgress = null
        val correctedPositionMs =
            if (resumeProgress != null) {
                val target =
                    (safeDurationMs * resumeProgress).roundToLong().coerceIn(0L, safeDurationMs)
                backend.seekTo(target)
                target
            } else {
                _state.value.positionMs.coerceIn(0L, safeDurationMs)
            }
        val stillPlaying = _state.value.isPlaying
        if (stillPlaying) {
            backend.play()
        } else {
            backend.pause()
        }
        _state.update {
            it.copy(
                isBuffering = false,
                isPlaying = stillPlaying,
                positionMs = correctedPositionMs,
                durationMs = safeDurationMs,
            )
        }
        syncMediaSessionBridge()
        if (stillPlaying) {
            startTicker()
        }
    }

    fun updateHasNextQueuedAudio(hasNext: Boolean) {
        if (_state.value.hasNextQueuedAudio != hasNext) {
            _state.update { it.copy(hasNextQueuedAudio = hasNext) }
            syncMediaSessionBridge()
        }
    }

    fun updateSmartRewindEnabled(enabled: Boolean) {
        if (_state.value.smartRewindEnabled != enabled) {
            _state.update { it.copy(smartRewindEnabled = enabled) }
        }
    }

    fun refreshChaptersFromEntry(entry: InboxEntry) {
        if (_state.value.currentEntryId != entry.id) return
        val updated = extractBestChaptersFromEntry(entry)
        if (updated.isNotEmpty() && updated != _state.value.chapters) {
            _state.update { it.copy(chapters = updated) }
        }
    }

    fun refreshChaptersFromNotes(entryId: String, vararg notesCandidates: String?) {
        if (_state.value.currentEntryId != entryId) return
        val updated =
            notesCandidates
                .filterNotNull()
                .firstNotNullOfOrNull { notes ->
                    parseChaptersFromEpisodeNotes(notes).takeIf { it.isNotEmpty() }
                }
                .orEmpty()
        if (updated.isNotEmpty() && updated != _state.value.chapters) {
            _state.update { it.copy(chapters = updated) }
        }
    }

    fun togglePlayPause(
        smartRewindEnabled: Boolean = _state.value.smartRewindEnabled,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val current = _state.value
        if (!current.hasActiveEpisode) return
        resumeAfterTransientFocusLoss = false
        if (current.isPlaying) {
            pausePlaybackInternal(nowEpochMs = nowEpochMs, abandonFocus = true)
        } else {
            if (!mediaSessionBridge.requestAudioFocus()) return
            applySmartRewindIfEligible(
                current = current,
                smartRewindEnabled = smartRewindEnabled,
                nowEpochMs = nowEpochMs,
            )
            pausedAtEpochMs = null
            backend.play()
            _state.update { it.copy(isPlaying = true) }
            syncMediaSessionBridge()
            startTicker()
        }
    }

    private fun pausePlaybackInternal(nowEpochMs: Long, abandonFocus: Boolean) {
        backend.pause()
        if (abandonFocus) {
            mediaSessionBridge.abandonAudioFocus()
        }
        pausedAtEpochMs = nowEpochMs
        _state.update { it.copy(isPlaying = false) }
        syncMediaSessionBridge()
        persistCurrentProgress()
    }

    private fun applySmartRewindIfEligible(
        current: PodcastPlayerState,
        smartRewindEnabled: Boolean,
        nowEpochMs: Long,
    ) {
        val pausedAt = pausedAtEpochMs ?: return
        if (!smartRewindEnabled) return
        val pausedDurationMs = (nowEpochMs - pausedAt).coerceAtLeast(0L)
        val rewindMs =
            when {
                pausedDurationMs >= SMART_REWIND_LONG_PAUSE_MS -> SMART_REWIND_LONG_MS
                pausedDurationMs >= SMART_REWIND_SHORT_PAUSE_MS -> SMART_REWIND_SHORT_MS
                else -> 0L
            }
        if (rewindMs > 0L) {
            val target = (current.positionMs - rewindMs).coerceAtLeast(0L)
            backend.seekTo(target)
            _state.update { it.copy(positionMs = target) }
        }
    }

    fun seekToFraction(fraction: Float) {
        val current = _state.value
        if (!current.hasActiveEpisode || current.durationMs <= 0L) return
        val targetMs = (current.durationMs * fraction.coerceIn(0f, 1f)).roundToLong()
        seekToMs(targetMs)
    }

    fun seekToMs(positionMs: Long) {
        val current = _state.value
        if (!current.hasActiveEpisode) return
        val clamped = positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(1L))
        backend.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
        syncMediaSessionBridge()
        persistCurrentProgress()
    }

    fun skipBySeconds(deltaSeconds: Int) {
        val current = _state.value
        if (!current.hasActiveEpisode) return
        val target =
            (current.positionMs + deltaSeconds * 1_000L).coerceIn(
                0L,
                current.durationMs.coerceAtLeast(1L),
            )
        seekToMs(target)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = clampPlaybackSpeed(speed)
        backend.setSpeed(clamped)
        _state.update { it.copy(playbackSpeed = clamped) }
        syncMediaSessionBridge()
    }

    fun setSkipSilence(enabled: Boolean) {
        _state.update { it.copy(skipSilenceEnabled = enabled) }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        val seconds = minutes?.takeIf { it > 0 }?.times(60)
        _state.update { it.copy(sleepTimerRemainingSec = seconds) }
    }

    fun openFullscreen() {
        if (_state.value.hasActiveEpisode) {
            _state.update { it.copy(isFullscreenOpen = true) }
        }
    }

    fun closeFullscreen() {
        _state.update {
            it.copy(
                isFullscreenOpen = false,
                isSpeedSheetOpen = false,
                activeDrawerTab = null,
            )
        }
    }

    fun setSpeedSheetOpen(open: Boolean) {
        _state.update { it.copy(isSpeedSheetOpen = open) }
    }

    fun selectDrawerTab(tab: PlayerDrawerTab?) {
        _state.update { current ->
            val next = if (current.activeDrawerTab == tab) null else tab
            current.copy(activeDrawerTab = next)
        }
    }

    fun completeActiveEpisodeNow() {
        handleActiveEpisodeFinished()
    }

    fun stopAndClear() {
        tickerJob?.cancel()
        resumeAfterTransientFocusLoss = false
        pendingResumeProgress = null
        mediaSessionBridge.abandonAudioFocus()
        backend.release()
        _state.value = PodcastPlayerState()
        mediaSessionBridge.updatePlaybackSession(null)
    }

    fun releaseController() {
        stopAndClear()
        mediaSessionBridge.unbindCallbacks(this)
    }

    override fun onMediaCommand(command: MediaSessionCommand) {
        when (command) {
            MediaSessionCommand.PLAY ->
                if (!_state.value.isPlaying && _state.value.hasActiveEpisode) {
                    togglePlayPause()
                }
            MediaSessionCommand.PAUSE ->
                if (_state.value.isPlaying) {
                    togglePlayPause()
                }
            MediaSessionCommand.TOGGLE_PLAY_PAUSE -> togglePlayPause()
            MediaSessionCommand.REWIND_15S -> skipBySeconds(-15)
            MediaSessionCommand.FAST_FORWARD_30S -> skipBySeconds(30)
            MediaSessionCommand.SKIP_TO_NEXT -> onSkipToNextRequested()
            MediaSessionCommand.STOP -> stopAndClear()
        }
    }

    override fun onSeekToMs(positionMs: Long) {
        seekToMs(positionMs)
    }

    override fun onSetPlaybackSpeed(speed: Float) {
        setPlaybackSpeed(speed)
        onSpeedChangedFromSession(_state.value.playbackSpeed)
    }

    override fun onAudioFocusLost(transient: Boolean) {
        val current = _state.value
        if (current.isPlaying) {
            resumeAfterTransientFocusLoss = transient
            pausePlaybackInternal(
                nowEpochMs = System.currentTimeMillis(),
                abandonFocus = !transient,
            )
        } else if (!transient) {
            resumeAfterTransientFocusLoss = false
            mediaSessionBridge.abandonAudioFocus()
        }
    }

    override fun onAudioFocusGained() {
        val current = _state.value
        if (resumeAfterTransientFocusLoss && current.hasActiveEpisode && !current.isPlaying) {
            resumeAfterTransientFocusLoss = false
            togglePlayPause()
        }
    }

    override fun onAudioBecomingNoisy() {
        resumeAfterTransientFocusLoss = false
        if (_state.value.isPlaying) {
            pausePlaybackInternal(nowEpochMs = System.currentTimeMillis(), abandonFocus = true)
        }
    }

    private fun handleActiveEpisodeFinished() {
        val completedId = _state.value.currentEntryId ?: return
        tickerJob?.cancel()
        resumeAfterTransientFocusLoss = false
        pendingResumeProgress = null
        backend.release()
        _state.update {
            it.copy(
                isPlaying = false,
                positionMs = it.durationMs,
            )
        }
        onEpisodeCompleted(completedId)
        if (_state.value.currentEntryId == completedId && !_state.value.isPlaying) {
            syncMediaSessionBridge()
        }
    }

    private fun syncMediaSessionBridge() {
        val s = _state.value
        val entryId = s.currentEntryId
        if (entryId.isNullOrBlank()) {
            mediaSessionBridge.updatePlaybackSession(null)
            return
        }
        mediaSessionBridge.updatePlaybackSession(
            MediaSessionMetadataSnapshot(
                entryId = entryId,
                episodeTitle = s.currentEpisodeTitle,
                podcastTitle = s.currentPodcastTitle,
                artworkUrl = s.currentArtworkUrl,
                isPlaying = s.isPlaying,
                isBuffering = s.isBuffering,
                positionMs = s.positionMs,
                durationMs = s.durationMs,
                playbackSpeed = s.playbackSpeed,
                hasNextQueuedAudio = s.hasNextQueuedAudio,
            )
        )
    }

    private fun persistCurrentProgress() {
        val s = _state.value
        val entryId = s.currentEntryId ?: return
        if (s.durationMs <= 0L) return
        val progress = (s.positionMs.toDouble() / s.durationMs.toDouble()).coerceIn(0.0, 1.0)
        onProgressChanged(entryId, progress)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var tickCount = 0
            while (isActive && _state.value.isPlaying) {
                delay(1_000L)
                tickCount++
                if (!advanceSingleTick(tickCount)) {
                    break
                }
            }
        }
    }

    private fun advanceSingleTick(tickCount: Int): Boolean {
        val s = _state.value
        var keepRunning = s.isPlaying
        if (keepRunning) {
            val usingNative = backend.isUsingNativePlayer()
            val nextPos = computeNextTickPositionMs(s, usingNative)
            val updatedDuration = maxOf(s.durationMs, nextPos)
            val nextSleep = s.sleepTimerRemainingSec?.let { (it - 1).coerceAtLeast(0) }
            if (nextSleep != null && nextSleep == 0) {
                resumeAfterTransientFocusLoss = false
                pausedAtEpochMs = System.currentTimeMillis()
                backend.pause()
                mediaSessionBridge.abandonAudioFocus()
                _state.update {
                    it.copy(
                        positionMs = nextPos,
                        durationMs = updatedDuration,
                        isPlaying = false,
                        sleepTimerRemainingSec = null,
                    )
                }
                syncMediaSessionBridge()
                persistCurrentProgress()
                keepRunning = false
            } else {
                _state.update {
                    it.copy(
                        positionMs = nextPos,
                        durationMs = updatedDuration,
                        sleepTimerRemainingSec = nextSleep,
                    )
                }
                if (tickCount % 5 == 0) {
                    syncMediaSessionBridge()
                    persistCurrentProgress()
                }
                if (!usingNative && updatedDuration > 0L && nextPos >= updatedDuration) {
                    handleActiveEpisodeFinished()
                    keepRunning = false
                }
            }
        }
        return keepRunning
    }

    private fun computeNextTickPositionMs(s: PodcastPlayerState, usingNative: Boolean): Long {
        val stepMs = (1_000f * s.playbackSpeed).roundToLong()
        val backendPos = backend.currentPositionMs()
        return when {
            usingNative -> backendPos.coerceAtLeast(0L)
            backendPos > s.positionMs -> backendPos
            else -> (s.positionMs + stepMs).coerceAtMost(s.durationMs)
        }
    }

    companion object {
        private const val SMART_REWIND_SHORT_PAUSE_MS = 60_000L
        private const val SMART_REWIND_LONG_PAUSE_MS = 600_000L
        private const val SMART_REWIND_SHORT_MS = 3_000L
        private const val SMART_REWIND_LONG_MS = 10_000L
    }
}

internal fun extractBestChaptersFromEntry(entry: InboxEntry): List<PodcastChapter> =
    listOfNotNull(entry.contentHtml, entry.contentText, entry.summary)
        .firstNotNullOfOrNull { notes ->
            parseChaptersFromEpisodeNotes(notes).takeIf { it.isNotEmpty() }
        }
        .orEmpty()

internal fun formatPlaybackClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    val clamped = clampPlaybackSpeed(speed)
    val hundredths = (clamped * 100f).roundToLong()
    return if (hundredths % 10L == 0L) {
        String.format(Locale.US, "%.1fx", clamped)
    } else {
        String.format(Locale.US, "%.2fx", clamped)
    }
}

internal fun parseChaptersFromEpisodeNotes(rawHtmlOrText: String): List<PodcastChapter> {
    if (rawHtmlOrText.isBlank()) return emptyList()
    val plainLines =
        rawHtmlOrText
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(p|li|div|h[1-6])>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .lines()

    return plainLines
        .mapNotNull { line -> parseSingleChapterLine(line) }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .mapIndexed { idx, (startMs, title) ->
            PodcastChapter(index = idx + 1, title = title, startMs = startMs)
        }
}

private fun parseSingleChapterLine(line: String): Pair<Long, String>? {
    val match = CHAPTER_LINE_REGEX.find(line.trim())
    val timeToken = match?.groupValues?.get(1).orEmpty()
    val title =
        match?.groupValues?.get(2)?.trim()?.trimStart('-', '–', '—', ':', '·')?.trim().orEmpty()
    val seconds =
        if (title.isBlank()) {
            null
        } else {
            parseDurationToSecondsLong(timeToken)
                ?: if (timeToken == "00:00" || timeToken == "0:00" || timeToken == "00:00:00") {
                    0L
                } else {
                    null
                }
        }
    return seconds?.takeIf { it >= 0L }?.let { it * 1_000L to title }
}

private val CHAPTER_LINE_REGEX =
    Regex("""^(?:[\[(])?(\d{1,2}:\d{2}(?::\d{2})?)(?:[\])])?\s+(.+)$""")
private const val DEFAULT_FALLBACK_DURATION_MS = 45L * 60L * 1_000L
