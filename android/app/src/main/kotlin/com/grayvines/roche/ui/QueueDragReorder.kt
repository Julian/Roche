package com.grayvines.roche.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.DragHandleIcon
import com.grayvines.roche.ui.theme.LocalRocheColors

internal data class QueueItemSlot(
    val index: Int,
    val key: String,
    val offset: Int,
    val size: Int,
)

internal data class QueueSwapMatch(
    val targetIndex: Int,
    val layoutOffsetDelta: Float,
)

internal enum class QueueRowBadgeTone {
    ACTIVE_AUDIO,
    UP_NEXT_AUDIO,
}

internal data class QueueRowStatusBadge(
    val label: String,
    val tone: QueueRowBadgeTone,
)

internal fun resolveQueueRowStatusBadge(
    isPlayingAudio: Boolean,
    isAudioActuallyPlaying: Boolean,
    isQueuedNextAudio: Boolean,
): QueueRowStatusBadge? =
    when {
        isPlayingAudio && isAudioActuallyPlaying ->
            QueueRowStatusBadge("▶ PLAYING", QueueRowBadgeTone.ACTIVE_AUDIO)
        isPlayingAudio -> QueueRowStatusBadge("⏸ PAUSED", QueueRowBadgeTone.ACTIVE_AUDIO)
        isQueuedNextAudio -> QueueRowStatusBadge("UP NEXT", QueueRowBadgeTone.UP_NEXT_AUDIO)
        else -> null
    }

internal fun buildQueueRowStateDescription(
    position: Int,
    totalCount: Int,
    isPlayingAudio: Boolean,
    isAudioActuallyPlaying: Boolean,
    isQueuedNextAudio: Boolean,
    isFavorite: Boolean,
): String = buildList {
    add("Position $position of $totalCount")
    when {
        isPlayingAudio && isAudioActuallyPlaying -> add("Now playing")
        isPlayingAudio -> add("Paused in player")
        isQueuedNextAudio -> add("Up next in audio queue")
    }
    if (isFavorite) add("Starred")
}
    .joinToString(", ")

internal fun moveItemInList(ids: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    if (ids.size <= 1) return ids
    if (fromIndex !in ids.indices) return ids
    val clampedTarget = toIndex.coerceIn(0, ids.lastIndex)
    if (fromIndex == clampedTarget) return ids
    val next = ids.toMutableList()
    val moved = next.removeAt(fromIndex)
    next.add(clampedTarget, moved)
    return next
}

internal fun findQueueSwapTarget(
    currentIndex: Int,
    draggedOffset: Int,
    draggedSize: Int,
    dragOffsetY: Float,
    visibleSlots: List<QueueItemSlot>,
): QueueSwapMatch? {
    if (dragOffsetY == 0f || draggedSize <= 0) return null
    val draggedCenterY = draggedOffset + dragOffsetY + draggedSize * MIDPOINT_FRACTION
    return if (dragOffsetY > 0f) {
        val target = visibleSlots.lastOrNull { slot ->
            slot.index > currentIndex &&
                draggedCenterY > slot.offset + slot.size * MIDPOINT_FRACTION
        }
        target?.let {
            QueueSwapMatch(
                targetIndex = it.index,
                layoutOffsetDelta = (it.offset + it.size - draggedSize - draggedOffset).toFloat(),
            )
        }
    } else {
        val target = visibleSlots.firstOrNull { slot ->
            slot.index < currentIndex &&
                draggedCenterY < slot.offset + slot.size * MIDPOINT_FRACTION
        }
        target?.let {
            QueueSwapMatch(
                targetIndex = it.index,
                layoutOffsetDelta = (it.offset - draggedOffset).toFloat(),
            )
        }
    }
}

internal class QueueDragReorderState(
    val listState: LazyListState,
    private val getSourceEntries: () -> List<InboxEntry>,
    private val onCommitReorder: (List<String>) -> Unit,
    private val onHapticGrab: () -> Unit,
    private val onHapticMove: () -> Unit,
) {
    var draggedEntryId by mutableStateOf<String?>(null)
        private set

    var dragOffsetY by mutableFloatStateOf(0f)
        private set

    private var workingOrderIds by mutableStateOf<List<String>?>(null)
    private var initialOrderIds: List<String>? = null

    fun resolveDisplayEntries(sourceEntries: List<InboxEntry>): List<InboxEntry> {
        val activeOrder = workingOrderIds ?: return sourceEntries
        val byId = sourceEntries.associateBy { it.id }
        val ordered = activeOrder.mapNotNull { byId[it] }
        return if (ordered.size == sourceEntries.size) ordered else sourceEntries
    }

    fun syncWithExternalEntries(sourceEntries: List<InboxEntry>) {
        if (draggedEntryId != null) return
        val pending = workingOrderIds ?: return
        val sourceIds = sourceEntries.map { it.id }
        if (sourceIds == pending || sourceIds.toSet() != pending.toSet()) {
            workingOrderIds = null
        }
    }

    fun startDrag(entryId: String) {
        if (draggedEntryId != null) return
        val currentIds = resolveDisplayEntries(getSourceEntries()).map { it.id }
        if (currentIds.size < 2 || entryId !in currentIds) return
        draggedEntryId = entryId
        dragOffsetY = 0f
        initialOrderIds = currentIds
        workingOrderIds = currentIds
        onHapticGrab()
    }

    fun onDragBy(deltaY: Float) {
        if (draggedEntryId == null) return
        dragOffsetY += deltaY
        maybeSwapVisibleItem()
    }

    fun maybeSwapVisibleItem() {
        val activeId = draggedEntryId ?: return
        val order = workingOrderIds ?: return
        val currentIndex = order.indexOf(activeId)
        if (currentIndex < 0) return
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visibleItems.firstOrNull { it.key == activeId } ?: return
        if (draggedInfo.index != currentIndex) return
        val slots = visibleItems.mapNotNull { info ->
            (info.key as? String)?.let { key ->
                QueueItemSlot(
                    index = info.index,
                    key = key,
                    offset = info.offset,
                    size = info.size,
                )
            }
        }
        val match =
            findQueueSwapTarget(
                currentIndex = currentIndex,
                draggedOffset = draggedInfo.offset,
                draggedSize = draggedInfo.size,
                dragOffsetY = dragOffsetY,
                visibleSlots = slots,
            ) ?: return
        val targetIndex = match.targetIndex.coerceIn(0, order.lastIndex)
        if (targetIndex == currentIndex) return
        val pinnedIndex = listState.firstVisibleItemIndex
        val pinnedOffset = listState.firstVisibleItemScrollOffset
        workingOrderIds = moveItemInList(order, currentIndex, targetIndex)
        dragOffsetY -= match.layoutOffsetDelta
        if (currentIndex == pinnedIndex || targetIndex == pinnedIndex) {
            listState.requestScrollToItem(pinnedIndex, pinnedOffset)
        }
        onHapticMove()
    }

    fun computeEdgeAutoScrollPx(edgeZonePx: Float, maxStepPx: Float): Float {
        val activeId = draggedEntryId ?: return 0f
        val layoutInfo = listState.layoutInfo
        val draggedInfo =
            layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeId } ?: return 0f
        val top = draggedInfo.offset + dragOffsetY
        val bottom = top + draggedInfo.size
        val viewportTop = layoutInfo.viewportStartOffset.toFloat()
        val viewportBottom = layoutInfo.viewportEndOffset.toFloat()
        return when {
            top < viewportTop + edgeZonePx && listState.canScrollBackward -> {
                val strength =
                    ((viewportTop + edgeZonePx - top) / edgeZonePx).coerceIn(
                        MIN_EDGE_SCROLL_STRENGTH,
                        1f,
                    )
                -maxStepPx * strength
            }
            bottom > viewportBottom - edgeZonePx && listState.canScrollForward -> {
                val strength =
                    ((bottom - (viewportBottom - edgeZonePx)) / edgeZonePx).coerceIn(
                        MIN_EDGE_SCROLL_STRENGTH,
                        1f,
                    )
                maxStepPx * strength
            }
            else -> {
                0f
            }
        }
    }

    fun applyAutoScroll(consumedPx: Float) {
        if (draggedEntryId == null || consumedPx == 0f) return
        dragOffsetY += consumedPx
        maybeSwapVisibleItem()
    }

    fun endDrag() {
        if (draggedEntryId == null) return
        val finalOrder = workingOrderIds
        val startOrder = initialOrderIds
        draggedEntryId = null
        dragOffsetY = 0f
        initialOrderIds = null
        if (finalOrder != null && startOrder != null && finalOrder != startOrder) {
            onCommitReorder(finalOrder)
        } else {
            workingOrderIds = null
        }
    }

    fun cancelDrag() {
        if (draggedEntryId == null) return
        draggedEntryId = null
        dragOffsetY = 0f
        initialOrderIds = null
        workingOrderIds = null
    }
}

@Composable
internal fun rememberQueueDragReorderState(
    listState: LazyListState,
    entries: List<InboxEntry>,
    onCommitReorder: (List<String>) -> Unit,
): QueueDragReorderState {
    val haptic = LocalHapticFeedback.current
    val currentEntries by rememberUpdatedState(entries)
    val currentOnCommit by rememberUpdatedState(onCommitReorder)
    val dragState =
        remember(listState, haptic) {
            QueueDragReorderState(
                listState = listState,
                getSourceEntries = { currentEntries },
                onCommitReorder = { orderedIds -> currentOnCommit(orderedIds) },
                onHapticGrab = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onHapticMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            )
        }
    LaunchedEffect(entries) { dragState.syncWithExternalEntries(entries) }
    DisposableEffect(dragState) { onDispose { dragState.cancelDrag() } }
    val density = LocalDensity.current
    val edgeZonePx = with(density) { EDGE_SCROLL_ZONE_DP.dp.toPx() }
    val maxStepPx = with(density) { EDGE_SCROLL_MAX_STEP_DP.dp.toPx() }
    LaunchedEffect(dragState.draggedEntryId) {
        while (dragState.draggedEntryId != null) {
            withFrameMillis {}
            val step = dragState.computeEdgeAutoScrollPx(edgeZonePx, maxStepPx)
            if (step != 0f) {
                val consumed = listState.scrollBy(step)
                dragState.applyAutoScroll(consumed)
            }
        }
    }
    return dragState
}

internal fun Modifier.queueLongPressDrag(
    enabled: Boolean,
    entryId: String,
    dragState: QueueDragReorderState,
): Modifier {
    if (!enabled) return this
    return pointerInput(entryId, dragState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val drag = awaitLongPressOrCancellation(down.id)
            if (drag != null) {
                drag.consume()
                dragState.startDrag(entryId)
                var finished = false
                try {
                    finished = trackQueueDragAfterLongPress(drag.id, dragState)
                } finally {
                    if (finished) dragState.endDrag() else dragState.cancelDrag()
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.trackQueueDragAfterLongPress(
    pointerId: PointerId,
    dragState: QueueDragReorderState,
): Boolean {
    var keepTracking = true
    var finished = false
    while (keepTracking) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            keepTracking = false
        } else if (change.changedToUpIgnoreConsumed()) {
            change.consume()
            finished = true
            keepTracking = false
        } else {
            val delta = change.positionChange()
            if (delta != Offset.Zero) {
                change.consume()
                dragState.onDragBy(delta.y)
            }
        }
    }
    return finished
}

@Composable
internal fun QueueDragGripHandle(
    entryId: String,
    isDragging: Boolean,
    dragState: QueueDragReorderState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val currentDragState by rememberUpdatedState(dragState)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .pointerInput(entryId) {
                    detectVerticalDragGestures(
                        onDragStart = { currentDragState.startDrag(entryId) },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            currentDragState.onDragBy(dragAmount)
                        },
                        onDragEnd = { currentDragState.endDrag() },
                        onDragCancel = { currentDragState.cancelDrag() },
                    )
                }
                .clearAndSetSemantics {}
                .testTag("queue_drag_handle_$entryId"),
    ) {
        DragHandleIcon(
            tint = if (isDragging) colors.accentBlue else colors.textMuted,
            size = 18.dp,
        )
    }
}

private const val MIDPOINT_FRACTION = 0.5f
private const val MIN_EDGE_SCROLL_STRENGTH = 0.15f
private const val EDGE_SCROLL_ZONE_DP = 56
private const val EDGE_SCROLL_MAX_STEP_DP = 14
