@file:Suppress("TooManyFunctions", "MagicNumber", "MatchingDeclarationName")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ui.components.BottomTabIcon
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheSectionHeader
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors

internal data class DockSlotBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private data class ActiveNavDragState(
    val tab: AppTab,
    val sourceSlotIndex: Int?,
    val pointerInRoot: Offset,
    val hoveredSlot: Int? = null,
)

private data class NavDragCallbacks(
    val onStart: (AppTab, Int?, Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
internal fun SettingsNavigationBarPage(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onBack: () -> Unit,
) {
    val currentTabs = AppTab.normalizeBottomTabs(state.settings.bottomNavTabs)
    var selectedSlotIndex by rememberSaveable { mutableIntStateOf(0) }
    var activeDrag by remember { mutableStateOf<ActiveNavDragState?>(null) }
    val slotBounds = remember { mutableStateMapOf<Int, DockSlotBounds>() }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val dragCallbacks =
        rememberNavDragCallbacks(
            activeDrag = activeDrag,
            slotBounds = slotBounds,
            onActiveDragChange = { activeDrag = it },
            onDropOntoSlot = { slotIdx, tab ->
                selectedSlotIndex = slotIdx
                onAction(MainAction.SetBottomNavSlot(slotIdx, tab))
            },
        )

    Box(
        modifier =
            Modifier.fillMaxSize().onGloballyPositioned { coords ->
                containerOrigin = coords.positionInRoot()
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsSubPageHeader(title = "Navigation Bar", onBack = onBack) {
                HeaderActionButton(
                    label = "Reset",
                    onClick = { onAction(MainAction.ResetBottomNavTabs) },
                    modifier = Modifier.testTag("reset_bottom_nav_btn"),
                )
            }
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState(), enabled = activeDrag == null)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RocheSectionHeader(label = "Bottom Bar (5 Slots)")
                VisualDockCustomizerCard(
                    currentTabs,
                    selectedSlotIndex,
                    activeDrag,
                    { selectedSlotIndex = it },
                    { slotIdx, tab ->
                        selectedSlotIndex = slotIdx
                        onAction(MainAction.SetBottomNavSlot(slotIdx, tab))
                    },
                    { idx, bounds -> slotBounds[idx] = bounds },
                    dragCallbacks,
                )
            }
        }
        activeDrag?.let { drag ->
            FloatingNavDragPill(drag = drag, containerOrigin = containerOrigin)
        }
    }
}

@Composable
private fun rememberNavDragCallbacks(
    activeDrag: ActiveNavDragState?,
    slotBounds: Map<Int, DockSlotBounds>,
    onActiveDragChange: (ActiveNavDragState?) -> Unit,
    onDropOntoSlot: (Int, AppTab) -> Unit,
): NavDragCallbacks {
    val haptic = LocalHapticFeedback.current
    val curDrag by rememberUpdatedState(activeDrag)
    return remember(haptic) {
        NavDragCallbacks(
            onStart = { tab, fromSlot, pointerInRoot ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val hovered =
                    resolveHoveredDockSlot(pointerInRoot.x, pointerInRoot.y, fromSlot, slotBounds)
                onActiveDragChange(ActiveNavDragState(tab, fromSlot, pointerInRoot, hovered))
            },
            onDrag = { delta ->
                curDrag?.let { drag ->
                    val nextPtr = drag.pointerInRoot + delta
                    val nextHover =
                        resolveHoveredDockSlot(
                            nextPtr.x,
                            nextPtr.y,
                            drag.sourceSlotIndex,
                            slotBounds,
                        )
                    if (nextHover != drag.hoveredSlot && nextHover != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onActiveDragChange(drag.copy(pointerInRoot = nextPtr, hoveredSlot = nextHover))
                }
            },
            onEnd = {
                val drag = curDrag
                onActiveDragChange(null)
                drag?.hoveredSlot?.let { target -> onDropOntoSlot(target, drag.tab) }
            },
            onCancel = { onActiveDragChange(null) },
        )
    }
}

@Composable
private fun VisualDockCustomizerCard(
    currentTabs: List<AppTab>,
    selectedSlotIndex: Int,
    activeDrag: ActiveNavDragState?,
    onSelectSlot: (Int) -> Unit,
    onAssignSlot: (Int, AppTab) -> Unit,
    onSlotBounds: (Int, DockSlotBounds) -> Unit,
    dragCallbacks: NavDragCallbacks,
) {
    val colors = LocalRocheColors.current
    val safeSlotIndex = selectedSlotIndex.coerceIn(0, currentTabs.lastIndex)
    val spares = remember(currentTabs) { AppTab.bottomTabCandidates.filter { it !in currentTabs } }

    RocheSurfaceCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text =
                    "Drag slots left or right to reorder your bottom bar, or drag any spare destination below onto a slot to swap it in.",
                color = colors.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            DockPreviewTray(
                currentTabs = currentTabs,
                selectedSlotIndex = safeSlotIndex,
                activeDrag = activeDrag,
                onSelectSlot = onSelectSlot,
                onSwapSlot = onAssignSlot,
                onSlotBounds = onSlotBounds,
                dragCallbacks = dragCallbacks,
            )
            AvailableDestinationsShelf(
                spares = spares,
                selectedSlotIndex = safeSlotIndex,
                draggingTab = activeDrag?.takeIf { it.sourceSlotIndex == null }?.tab,
                onTapSpare = { tab -> onAssignSlot(safeSlotIndex, tab) },
                dragCallbacks = dragCallbacks,
            )
        }
    }
}

@Composable
private fun DockPreviewTray(
    currentTabs: List<AppTab>,
    selectedSlotIndex: Int,
    activeDrag: ActiveNavDragState?,
    onSelectSlot: (Int) -> Unit,
    onSwapSlot: (Int, AppTab) -> Unit,
    onSlotBounds: (Int, DockSlotBounds) -> Unit,
    dragCallbacks: NavDragCallbacks,
) {
    val colors = LocalRocheColors.current
    val trayShape = RoundedCornerShape(13.dp)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(trayShape)
                .background(colors.background)
                .border(1.dp, colors.border, trayShape)
                .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        currentTabs.forEachIndexed { index, tab ->
            DockPreviewSlot(
                tab = tab,
                slotIndex = index,
                slotCount = currentTabs.size,
                isSelected = selectedSlotIndex == index,
                isDropHovered = activeDrag?.hoveredSlot == index,
                isDraggingSource = activeDrag?.sourceSlotIndex == index,
                onClick = { onSelectSlot(index) },
                onMoveLeft = { if (index > 0) onSwapSlot(index - 1, tab) },
                onMoveRight = { if (index + 1 < currentTabs.size) onSwapSlot(index + 1, tab) },
                onBoundsChanged = { onSlotBounds(index, it) },
                dragCallbacks = dragCallbacks,
                modifier = Modifier.testTag("dock_slot_$index"),
            )
        }
    }
}

@Composable
private fun RowScope.DockPreviewSlot(
    tab: AppTab,
    slotIndex: Int,
    slotCount: Int,
    isSelected: Boolean,
    isDropHovered: Boolean,
    isDraggingSource: Boolean,
    onClick: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onBoundsChanged: (DockSlotBounds) -> Unit,
    dragCallbacks: NavDragCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val curIndex by rememberUpdatedState(slotIndex)
    val curCallbacks by rememberUpdatedState(dragCallbacks)
    var slotOrigin by remember { mutableStateOf(Offset.Zero) }
    val slotActions = dockSlotA11yActions(slotIndex, slotCount, onMoveLeft, onMoveRight)
    val active = isSelected || isDropHovered
    val iconTint = if (active) colors.accentBlue else colors.textSecondary
    val textTint = if (active) colors.accentBlue else colors.textPrimary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .weight(1f)
                .dockSlotChrome(isSelected, isDropHovered, isDraggingSource, colors)
                .reportSlotBounds({ slotOrigin = it }, onBoundsChanged)
                .dockTabDragGesture(tab, { curIndex }, { slotOrigin }, { curCallbacks })
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    selected = isSelected
                    customActions = slotActions
                }
                .padding(vertical = 9.dp, horizontal = 2.dp),
    ) {
        ThreeDotDragIndicator(tint = iconTint.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(4.dp))
        BottomTabIcon(tab = tab, tint = iconTint, size = 18.dp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            color = textTint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.reportSlotBounds(
    onOrigin: (Offset) -> Unit,
    onBounds: (DockSlotBounds) -> Unit,
): Modifier = onGloballyPositioned { coords ->
    val pos = coords.positionInRoot()
    onOrigin(pos)
    onBounds(DockSlotBounds(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height))
}

private fun Modifier.dockTabDragGesture(
    tab: AppTab,
    slotIndexProvider: () -> Int?,
    originProvider: () -> Offset,
    callbacksProvider: () -> NavDragCallbacks,
): Modifier =
    pointerInput(tab) {
        detectDragGestures(
            onDragStart = { local ->
                callbacksProvider().onStart(tab, slotIndexProvider(), originProvider() + local)
            },
            onDrag = { change, amount ->
                change.consume()
                callbacksProvider().onDrag(amount)
            },
            onDragEnd = { callbacksProvider().onEnd() },
            onDragCancel = { callbacksProvider().onCancel() },
        )
    }

private fun Modifier.dockSlotChrome(
    isSelected: Boolean,
    isDropHovered: Boolean,
    isDraggingSource: Boolean,
    colors: RocheColors,
): Modifier {
    val shape = RoundedCornerShape(9.dp)
    val bg =
        when {
            isDropHovered -> colors.accentBlue.copy(alpha = 0.24f)
            isSelected -> colors.accentBlue.copy(alpha = 0.16f)
            else -> colors.surface
        }
    val borderColor = if (isSelected || isDropHovered) colors.accentBlue else colors.border
    val borderMod =
        if (isDropHovered || isDraggingSource) {
            Modifier.dashedSlotBorder(colors.accentBlue, 9.dp)
        } else {
            Modifier.border(1.dp, borderColor, shape)
        }
    val scale = if (isDropHovered) 1.03f else 1f
    return graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .alpha(if (isDraggingSource) 0.25f else 1f)
        .clip(shape)
        .background(bg)
        .then(borderMod)
}

private fun dockSlotA11yActions(
    slotIndex: Int,
    slotCount: Int,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
): List<CustomAccessibilityAction> = buildList {
    if (slotIndex > 0) {
        add(
            CustomAccessibilityAction("Move left") {
                onMoveLeft()
                true
            }
        )
    }
    if (slotIndex + 1 < slotCount) {
        add(
            CustomAccessibilityAction("Move right") {
                onMoveRight()
                true
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvailableDestinationsShelf(
    spares: List<AppTab>,
    selectedSlotIndex: Int,
    draggingTab: AppTab?,
    onTapSpare: (AppTab) -> Unit,
    dragCallbacks: NavDragCallbacks,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AVAILABLE DESTINATIONS",
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = "Slot #${selectedSlotIndex + 1} selected",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            spares.forEach { candidate ->
                DraggableSpareChip(
                    candidate = candidate,
                    isDragging = draggingTab == candidate,
                    onTap = { onTapSpare(candidate) },
                    dragCallbacks = dragCallbacks,
                )
            }
        }
    }
}

@Composable
private fun DraggableSpareChip(
    candidate: AppTab,
    isDragging: Boolean,
    onTap: () -> Unit,
    dragCallbacks: NavDragCallbacks,
) {
    val colors = LocalRocheColors.current
    val curCallbacks by rememberUpdatedState(dragCallbacks)
    var chipOrigin by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(9.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .alpha(if (isDragging) 0.28f else 1f)
                .clip(shape)
                .background(colors.background)
                .border(1.dp, if (isDragging) colors.accentBlue else colors.border, shape)
                .onGloballyPositioned { coords -> chipOrigin = coords.positionInRoot() }
                .dockTabDragGesture(candidate, { null }, { chipOrigin }, { curCallbacks })
                .clickable(onClick = onTap)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("dock_candidate_${candidate.name.lowercase()}"),
    ) {
        ThreeDotDragIndicator(tint = colors.textMuted.copy(alpha = 0.6f))
        BottomTabIcon(tab = candidate, tint = colors.textSecondary, size = 15.dp)
        Text(
            text = candidate.label,
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun FloatingNavDragPill(
    drag: ActiveNavDragState,
    containerOrigin: Offset,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier =
            Modifier.zIndex(10f)
                .graphicsLayer {
                    translationX = drag.pointerInRoot.x - containerOrigin.x - GHOST_OFFSET_X
                    translationY = drag.pointerInRoot.y - containerOrigin.y - GHOST_OFFSET_Y
                    scaleX = 1.04f
                    scaleY = 1.04f
                    shadowElevation = 18f
                }
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.5.dp, colors.accentBlue, shape)
                .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        BottomTabIcon(tab = drag.tab, tint = colors.accentBlue, size = 16.dp)
        Text(
            text = drag.tab.label,
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ThreeDotDragIndicator(tint: Color) {
    Canvas(modifier = Modifier.size(width = 13.dp, height = 4.dp)) {
        val r = 1.25.dp.toPx()
        val cy = size.height * 0.5f
        listOf(0.15f, 0.5f, 0.85f).forEach { frac ->
            drawCircle(color = tint, radius = r, center = Offset(size.width * frac, cy))
        }
    }
}

private fun Modifier.dashedSlotBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val px = radius.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(px, px),
        style =
            Stroke(
                width = 1.4.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 7f)),
            ),
    )
}

internal fun resolveHoveredDockSlot(
    pointerX: Float,
    pointerY: Float,
    sourceSlotIndex: Int?,
    slotBounds: Map<Int, DockSlotBounds>,
): Int? =
    slotBounds.entries
        .firstOrNull { (idx, b) ->
            idx != sourceSlotIndex &&
                pointerX >= b.left - SLOT_HIT_PAD_X &&
                pointerX <= b.right + SLOT_HIT_PAD_X &&
                pointerY >= b.top - SLOT_HIT_PAD_Y &&
                pointerY <= b.bottom + SLOT_HIT_PAD_Y
        }
        ?.key

private const val SLOT_HIT_PAD_X = 6f
private const val SLOT_HIT_PAD_Y = 24f
private const val GHOST_OFFSET_X = 48f
private const val GHOST_OFFSET_Y = 24f
