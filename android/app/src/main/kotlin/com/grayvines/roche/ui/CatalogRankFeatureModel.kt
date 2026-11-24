package com.grayvines.roche.ui

import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface CollectionDrawLaunchEffect {
    data class ProbeOrLaunchMusicAlbum(val draw: CollectionDrawSnapshot) :
        CollectionDrawLaunchEffect

    data class LaunchEnrichedMusicAlbum(val draw: CollectionDrawSnapshot) :
        CollectionDrawLaunchEffect

    data class OpenExternalUrl(val url: String) : CollectionDrawLaunchEffect
}

internal fun CollectionDrawSnapshot.musicResolveKey(): String = entryId.ifBlank {
    "$creator|$title"
}

internal data class CatalogProjections(val catalog: CatalogSnapshot = CatalogSnapshot())

internal class CatalogRankFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
) {
    private var musicResolveJob: Job? = null

    private val _resolvingMusicEntryId = MutableStateFlow<String?>(null)
    val resolvingMusicEntryId: StateFlow<String?> = _resolvingMusicEntryId.asStateFlow()

    private val _drawLaunchEffects = Channel<CollectionDrawLaunchEffect>(Channel.BUFFERED)
    val drawLaunchEffects: Flow<CollectionDrawLaunchEffect> = _drawLaunchEffects.receiveAsFlow()

    fun launchCollectionDraw(
        draw: CollectionDrawSnapshot,
        readableEntryIds: Set<String>,
        onOpenReader: (String) -> Unit,
        onOpenList: (String) -> Unit,
    ) {
        val activeResolveKey = _resolvingMusicEntryId.value
        if (draw.isMusicKind && activeResolveKey == draw.musicResolveKey()) return
        if (activeResolveKey != null) cancelPendingResolve()
        if (draw.isMusicKind) {
            _drawLaunchEffects.trySend(CollectionDrawLaunchEffect.ProbeOrLaunchMusicAlbum(draw))
            return
        }
        val extUrl = draw.primaryExternalUrl
        when {
            draw.entryId in readableEntryIds -> onOpenReader(draw.entryId)
            !extUrl.isNullOrBlank() ->
                _drawLaunchEffects.trySend(CollectionDrawLaunchEffect.OpenExternalUrl(extUrl))
            draw.collectionId.isNotBlank() -> onOpenList(draw.collectionId)
        }
    }

    fun enrichAndLaunchMusicDraw(draw: CollectionDrawSnapshot) {
        if (!draw.isMusicKind) return
        val entryKey = draw.musicResolveKey()
        if (_resolvingMusicEntryId.value == entryKey) return
        musicResolveJob?.cancel()
        _resolvingMusicEntryId.value = entryKey
        musicResolveJob = scope.launch {
            try {
                val resolved = runSuspendCatching {
                    withTimeoutOrNull(ALBUM_RESOLVE_TIMEOUT_MS) {
                        repository.resolveCollectionDrawStreaming(draw)
                    }
                }
                val enriched = resolved.getOrNull() ?: draw
                _drawLaunchEffects.trySend(
                    CollectionDrawLaunchEffect.LaunchEnrichedMusicAlbum(enriched)
                )
            } finally {
                if (_resolvingMusicEntryId.value == entryKey) {
                    _resolvingMusicEntryId.value = null
                }
            }
        }
    }

    fun cancelPendingResolve() {
        musicResolveJob?.cancel()
        musicResolveJob = null
        _resolvingMusicEntryId.value = null
    }

    companion object {
        private const val ALBUM_RESOLVE_TIMEOUT_MS = 5_000L
    }
}

internal fun buildCatalogProjections(catalog: CatalogSnapshot): CatalogProjections =
    CatalogProjections(catalog = catalog)
