@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.playback

import com.grayvines.roche.core.parseDurationToSecondsLong
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.model.InboxEntry
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaSessionCommand {
    PLAY,
    PAUSE,
    TOGGLE_PLAY_PAUSE,
    REWIND_15S,
    FAST_FORWARD_30S,
    SKIP_TO_NEXT,
    STOP,
}

enum class ProgressSaveReason {
    PERIODIC_TICK,
    SEEK_OR_SKIP,
    PAUSE,
    FINISH,
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
}

interface PodcastMediaSessionBridge {
    fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks)

    fun unbindCallbacks(callbacks: PodcastMediaSessionCallbacks) = Unit

    fun updatePlaybackSession(snapshot: MediaSessionMetadataSnapshot?)

    fun release()
}

object NoOpPodcastMediaSessionBridge : PodcastMediaSessionBridge {
    override fun bindCallbacks(callbacks: PodcastMediaSessionCallbacks) = Unit

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

    val sleepTimerRemainingMinutes: Int?
        get() = sleepTimerRemainingSec?.let { ((it + 59) / 60).coerceAtLeast(1) }
}

@Suppress("ComplexInterface")
interface AudioPlayerBackend {
    @Suppress("LongParameterList")
    fun loadAndPlay(
        source: String,
        initialPositionMs: Long,
        fallbackDurationMs: Long,
        speed: Float,
        skipSilenceEnabled: Boolean = false,
        playWhenReady: Boolean = true,
        onPrepared: (durationMs: Long) -> Unit,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    )

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun setSkipSilence(enabled: Boolean) = Unit

    fun bindExternalPlayStateListener(listener: ((Boolean) -> Unit)?) = Unit

    suspend fun syncPositionFromPlayer(): Long = currentPositionMs()

    fun currentPositionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean

    fun release()
}

@Suppress("LongParameterList")
class PodcastPlayerController(
    private val scope: CoroutineScope,
    private val backend: AudioPlayerBackend = NoOpAudioPlayerBackend(),
    private val mediaSessionBridge: PodcastMediaSessionBridge = NoOpPodcastMediaSessionBridge,
    private val onProgressChanged: (entryId: String, progress: Double) -> Unit = { _, _ -> },
    private val onProgressSaved:
        (entryId: String, progress: Double, reason: ProgressSaveReason) -> Unit =
        { entryId, progress, _ ->
            onProgressChanged(entryId, progress)
        },
    private val onEpisodeCompleted: (controller: PodcastPlayerController, entryId: String) -> Unit =
        { _, _ ->
        },
    private val onSkipToNextRequested: (controller: PodcastPlayerController) -> Unit = {},
    private val onSpeedChangedFromSession:
        (controller: PodcastPlayerController, speed: Float) -> Unit =
        { _, _ ->
        },
    private val onPlaybackError: (String) -> Unit = {},
) : PodcastMediaSessionCallbacks {
    private val _state = MutableStateFlow(PodcastPlayerState())
    val state: StateFlow<PodcastPlayerState> = _state.asStateFlow()

    @Volatile private var tickerJob: Job? = null
    @Volatile private var pausedAtEpochMs: Long? = null
    @Volatile private var pendingResumeProgress: Double? = null
    @Volatile private var activeSourceUriOrPath: String = ""
    @Volatile private var backendFailed: Boolean = false
    @Volatile private var skipSilenceSyncedOnce: Boolean = false
    @Volatile private var lastPersistedEntryId: String? = null
    @Volatile private var lastPersistedProgress: Double = -1.0
    private val uiErrorListeners = CopyOnWriteArraySet<(String) -> Unit>()

    init {
        mediaSessionBridge.bindCallbacks(this)
        backend.bindExternalPlayStateListener(::handleBackendExternalPlayState)
    }

    internal fun setUiErrorListener(listener: ((String) -> Unit)?) {
        if (listener != null) {
            uiErrorListeners.add(listener)
        }
    }

    internal fun clearUiErrorListener(listener: (String) -> Unit) {
        uiErrorListeners.remove(listener)
    }

    internal fun emitUiMessage(message: String) {
        onPlaybackError(message)
        uiErrorListeners.forEach { it.invoke(message) }
    }

    private fun handleBackendExternalPlayState(isNowPlaying: Boolean) {
        val current = _state.value
        if (!current.hasActiveEpisode || current.isPlaying == isNowPlaying) return
        if (isNowPlaying) {
            pausedAtEpochMs = null
            _state.update { it.copy(isPlaying = true) }
            syncMediaSessionBridge()
            startTicker()
        } else {
            val pos = backend.currentPositionMs().coerceAtLeast(0L)
            pausedAtEpochMs = System.currentTimeMillis()
            tickerJob?.cancel()
            _state.update { it.copy(isPlaying = false, positionMs = pos) }
            syncMediaSessionBridge()
            persistCurrentProgress(ProgressSaveReason.PAUSE)
        }
    }

    fun playEntry(
        entry: InboxEntry,
        sourceUriOrPath: String,
        speed: Float,
        skipSilenceEnabled: Boolean,
        hasNextQueuedAudio: Boolean = _state.value.hasNextQueuedAudio,
        smartRewindEnabled: Boolean = _state.value.smartRewindEnabled,
    ) {
        tickerJob?.cancel()
        val clampedSpeed = clampPlaybackSpeed(speed)
        val bounds = resolveInitialBounds(entry)
        pendingResumeProgress = bounds.pendingResume
        val parsedChapters = extractBestChaptersFromEntry(entry)
        val resolvedArtworkUrl = resolveArtworkUrl(entry)
        pausedAtEpochMs = null
        activeSourceUriOrPath = sourceUriOrPath
        backendFailed = false
        lastPersistedEntryId = entry.id
        lastPersistedProgress = entry.readProgress.coerceIn(0.0, 1.0)
        _state.update { current ->
            current.copy(
                currentEntryId = entry.id,
                currentEpisodeTitle = entry.title,
                currentPodcastTitle = entry.feedTitle,
                currentArtworkUrl = resolvedArtworkUrl,
                hasNextQueuedAudio = hasNextQueuedAudio,
                isPlaying = true,
                isBuffering = true,
                positionMs = bounds.initialPositionMs,
                durationMs = bounds.estimatedDurationMs,
                playbackSpeed = clampedSpeed,
                skipSilenceEnabled = skipSilenceEnabled,
                smartRewindEnabled = smartRewindEnabled,
                chapters = parsedChapters,
            )
        }
        syncMediaSessionBridge()
        backend.loadAndPlay(
            source = sourceUriOrPath,
            initialPositionMs = bounds.initialPositionMs,
            fallbackDurationMs = bounds.estimatedDurationMs,
            speed = clampedSpeed,
            skipSilenceEnabled = skipSilenceEnabled,
            playWhenReady = true,
            onPrepared = { dur -> handleBackendPrepared(entry.id, dur) },
            onCompletion = { handleActiveEpisodeFinished() },
            onError = { handleBackendError(entry.id) },
        )
    }

    private fun resolveInitialBounds(entry: InboxEntry): InitialPlaybackBounds {
        val parsedDurationSeconds = parseDurationToSecondsLong(entry.duration)
        val estimatedDurationMs =
            parsedDurationSeconds?.times(1_000L) ?: DEFAULT_FALLBACK_DURATION_MS
        val hasValidResumeProgress = entry.readProgress in 0.01..0.98
        val pendingResume =
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
        return InitialPlaybackBounds(estimatedDurationMs, initialPositionMs, pendingResume)
    }

    private fun resolveArtworkUrl(entry: InboxEntry): String? =
        entry.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: entry.effectiveThumbnailUrl
            ?: entry.thumbnailMediaId?.takeIf { it.isNotBlank() }

    private fun handleBackendError(entryId: String) {
        if (_state.value.currentEntryId != entryId) return
        backendFailed = true
        tickerJob?.cancel()
        _state.update { it.copy(isBuffering = false, isPlaying = false) }
        syncMediaSessionBridge()
        emitUiMessage(DEFAULT_PLAYBACK_ERROR_MESSAGE)
    }

    private fun handleBackendPrepared(entryId: String, durationMs: Long) {
        if (_state.value.currentEntryId != entryId) return
        backendFailed = false
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
        if (!stillPlaying) {
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
        if (current.isPlaying) {
            pausePlaybackInternal(nowEpochMs = nowEpochMs)
        } else {
            applySmartRewindIfEligible(
                current = current,
                smartRewindEnabled = smartRewindEnabled,
                nowEpochMs = nowEpochMs,
            )
            pausedAtEpochMs = null
            if (backendFailed) {
                reloadAfterError()
            } else {
                backend.play()
                _state.update { it.copy(isPlaying = true) }
                syncMediaSessionBridge()
                startTicker()
            }
        }
    }

    private fun reloadAfterError() {
        val refreshed = _state.value
        val entryId = refreshed.currentEntryId ?: return
        backendFailed = false
        _state.update { it.copy(isBuffering = true, isPlaying = true) }
        syncMediaSessionBridge()
        backend.loadAndPlay(
            source = activeSourceUriOrPath,
            initialPositionMs = refreshed.positionMs,
            fallbackDurationMs = refreshed.durationMs,
            speed = refreshed.playbackSpeed,
            skipSilenceEnabled = refreshed.skipSilenceEnabled,
            playWhenReady = true,
            onPrepared = { dur -> handleBackendPrepared(entryId, dur) },
            onCompletion = { handleActiveEpisodeFinished() },
            onError = { handleBackendError(entryId) },
        )
    }

    private fun pausePlaybackInternal(nowEpochMs: Long) {
        tickerJob?.cancel()
        backend.pause()
        pausedAtEpochMs = nowEpochMs
        _state.update { it.copy(isPlaying = false) }
        syncMediaSessionBridge()
        persistCurrentProgress(ProgressSaveReason.PAUSE)
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
        persistCurrentProgress(ProgressSaveReason.SEEK_OR_SKIP)
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
        skipSilenceSyncedOnce = true
        backend.setSkipSilence(enabled)
        _state.update { it.copy(skipSilenceEnabled = enabled) }
    }

    internal fun syncSkipSilenceFromSettings(enabled: Boolean) {
        if (!skipSilenceSyncedOnce || _state.value.skipSilenceEnabled != enabled) {
            setSkipSilence(enabled)
        }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        val seconds = minutes?.takeIf { it > 0 }?.times(60)
        _state.update { it.copy(sleepTimerRemainingSec = seconds) }
    }

    fun completeActiveEpisodeNow() {
        handleActiveEpisodeFinished()
    }

    internal fun flushCurrentProgressIfNeeded() {
        val s = _state.value
        val hasValidBounds = s.positionMs > 0L && s.durationMs > 0L
        if (!s.isBuffering && s.hasActiveEpisode && hasValidBounds) {
            persistCurrentProgress(ProgressSaveReason.SEEK_OR_SKIP)
        }
    }

    internal fun cancelAutoTickerForTest() {
        tickerJob?.cancel()
    }

    fun stopAndClear() {
        tickerJob?.cancel()
        pendingResumeProgress = null
        activeSourceUriOrPath = ""
        backendFailed = false
        lastPersistedEntryId = null
        lastPersistedProgress = -1.0
        backend.release()
        _state.value = PodcastPlayerState()
        mediaSessionBridge.updatePlaybackSession(null)
    }

    fun releaseController() {
        stopAndClear()
        skipSilenceSyncedOnce = false
        backend.bindExternalPlayStateListener(null)
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
            MediaSessionCommand.SKIP_TO_NEXT -> onSkipToNextRequested(this)
            MediaSessionCommand.STOP -> stopAndClear()
        }
    }

    override fun onSeekToMs(positionMs: Long) {
        seekToMs(positionMs)
    }

    override fun onSetPlaybackSpeed(speed: Float) {
        setPlaybackSpeed(speed)
        onSpeedChangedFromSession(this, _state.value.playbackSpeed)
    }

    private fun handleActiveEpisodeFinished() {
        val snapshotBeforeFinish = _state.value
        val completedId = snapshotBeforeFinish.currentEntryId ?: return
        tickerJob?.cancel()
        pendingResumeProgress = null
        if (snapshotBeforeFinish.hasNextQueuedAudio) {
            // Keep the active MediaSession / foreground service alive while transitioning to the
            // next queued episode so Android 12+ never rejects background transition starts.
            backend.pause()
            _state.update {
                it.copy(
                    isBuffering = true,
                    positionMs = it.durationMs,
                )
            }
            onEpisodeCompleted(this, completedId)
        } else {
            backend.release()
            _state.update {
                it.copy(
                    isPlaying = false,
                    positionMs = it.durationMs,
                )
            }
            onEpisodeCompleted(this, completedId)
            if (_state.value.currentEntryId == completedId && !_state.value.isPlaying) {
                syncMediaSessionBridge()
            }
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

    private fun persistCurrentProgress(reason: ProgressSaveReason) {
        val s = _state.value
        val entryId = s.currentEntryId ?: return
        if (s.durationMs <= 0L) return
        val progress = (s.positionMs.toDouble() / s.durationMs.toDouble()).coerceIn(0.0, 1.0)
        if (reason == ProgressSaveReason.PERIODIC_TICK) {
            val sameEntry = lastPersistedEntryId == entryId
            val delta = abs(progress - lastPersistedProgress)
            if (sameEntry && delta < MIN_PERIODIC_PROGRESS_DELTA && progress < 0.98) {
                return
            }
        }
        lastPersistedEntryId = entryId
        lastPersistedProgress = progress
        onProgressSaved(entryId, progress, reason)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var tickCount = 0
            while (isActive && _state.value.isPlaying) {
                withContext(Dispatchers.Default) { delay(1_000L) }
                backend.syncPositionFromPlayer()
                tickCount++
                if (!advanceSingleTick(tickCount)) {
                    break
                }
            }
        }
    }

    internal fun advanceSingleTick(tickCount: Int): Boolean {
        val s = _state.value
        var keepRunning = s.isPlaying
        if (keepRunning) {
            val nextPos = backend.currentPositionMs().coerceAtLeast(0L)
            val updatedDuration = maxOf(s.durationMs, nextPos)
            val nextSleep = s.sleepTimerRemainingSec?.let { (it - 1).coerceAtLeast(0) }
            if (nextSleep == 0) {
                stopForSleepTimer(nextPos, updatedDuration)
                keepRunning = false
            } else {
                val positionChanged = nextPos != s.positionMs
                if (shouldEmitTickState(s, nextPos, updatedDuration, nextSleep)) {
                    _state.update {
                        it.copy(
                            positionMs = nextPos,
                            durationMs = updatedDuration,
                            sleepTimerRemainingSec = nextSleep,
                        )
                    }
                }
                if (positionChanged && tickCount % PERIODIC_CHECKPOINT_TICKS == 0) {
                    persistCurrentProgress(ProgressSaveReason.PERIODIC_TICK)
                }
            }
        }
        return keepRunning
    }

    private fun shouldEmitTickState(
        current: PodcastPlayerState,
        nextPos: Long,
        updatedDuration: Long,
        nextSleep: Int?,
    ): Boolean =
        nextPos / 1_000L != current.positionMs / 1_000L ||
            updatedDuration != current.durationMs ||
            nextSleep != current.sleepTimerRemainingSec

    private fun stopForSleepTimer(nextPos: Long, updatedDuration: Long) {
        pausedAtEpochMs = System.currentTimeMillis()
        backend.pause()
        _state.update {
            it.copy(
                positionMs = nextPos,
                durationMs = updatedDuration,
                isPlaying = false,
                sleepTimerRemainingSec = null,
            )
        }
        syncMediaSessionBridge()
        persistCurrentProgress(ProgressSaveReason.PAUSE)
    }

    companion object {
        internal const val DEFAULT_PLAYBACK_ERROR_MESSAGE =
            "Playback failed — check connection or download episode"
        internal const val PERIODIC_CHECKPOINT_TICKS = 15
        private const val MIN_PERIODIC_PROGRESS_DELTA = 0.005
        private const val SMART_REWIND_SHORT_PAUSE_MS = 60_000L
        private const val SMART_REWIND_LONG_PAUSE_MS = 600_000L
        private const val SMART_REWIND_SHORT_MS = 3_000L
        private const val SMART_REWIND_LONG_MS = 10_000L
    }
}

private data class InitialPlaybackBounds(
    val estimatedDurationMs: Long,
    val initialPositionMs: Long,
    val pendingResume: Double?,
)
