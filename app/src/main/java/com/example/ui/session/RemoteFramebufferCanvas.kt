package com.example.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.model.InputControlMode
import com.example.protocol.ActiveRemoteSession
import com.example.protocol.ScalingMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class RenderedDesktopBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val dstLeftInt: Int get() = left.roundToInt()
    val dstTopInt: Int get() = top.roundToInt()
    val dstWidthInt: Int get() = width.roundToInt().coerceAtLeast(1)
    val dstHeightInt: Int get() = height.roundToInt().coerceAtLeast(1)
}

data class NormalizedRemoteCoordinate(
    val normX: Float,
    val normY: Float,
    val remotePixelX: Int,
    val remotePixelY: Int,
    val isInsideRenderedDesktop: Boolean
)

/**
 * Dynamic Coordinate Normalizer that maps touch events on the Android Canvas directly to
 * the remote RDP/VNC server's exact screen resolution (`desktopW × desktopH`), accounting
 * for letterbox/pillarbox aspect-ratio bounds, scaling modes (`FIT_WINDOW`, `STRETCH`,
 * `ONE_TO_ONE`, `CUSTOM_ZOOM`), and `graphicsLayer` zoom/pan transformations.
 */
object RemoteCoordinateNormalizer {

    fun computeRenderedDesktopBounds(
        containerW: Float,
        containerH: Float,
        desktopW: Float,
        desktopH: Float,
        scalingMode: ScalingMode
    ): RenderedDesktopBounds {
        val safeContainerW = containerW.coerceAtLeast(1f)
        val safeContainerH = containerH.coerceAtLeast(1f)
        val safeDesktopW = desktopW.coerceAtLeast(1f)
        val safeDesktopH = desktopH.coerceAtLeast(1f)
        val targetAspect = safeDesktopW / safeDesktopH

        return when (scalingMode) {
            ScalingMode.STRETCH -> {
                RenderedDesktopBounds(
                    left = 0f,
                    top = 0f,
                    width = safeContainerW,
                    height = safeContainerH
                )
            }
            ScalingMode.ONE_TO_ONE -> {
                // If the remote desktop is larger than the mobile container in 1:1 mode, fit cleanly unless panned
                val w1 = safeDesktopW
                val h1 = safeDesktopH
                val left = (safeContainerW - w1) / 2f
                val top = (safeContainerH - h1) / 2f
                RenderedDesktopBounds(left = left, top = top, width = w1, height = h1)
            }
            ScalingMode.FIT_WINDOW, ScalingMode.CUSTOM_ZOOM -> {
                val containerAspect = safeContainerW / safeContainerH
                if (containerAspect > targetAspect) {
                    val hFit = safeContainerH
                    val wFit = (hFit * targetAspect).coerceAtLeast(1f)
                    val left = (safeContainerW - wFit) / 2f
                    RenderedDesktopBounds(left = left, top = 0f, width = wFit, height = hFit)
                } else {
                    val wFit = safeContainerW
                    val hFit = (wFit / targetAspect).coerceAtLeast(1f)
                    val top = (safeContainerH - hFit) / 2f
                    RenderedDesktopBounds(left = 0f, top = top, width = wFit, height = hFit)
                }
            }
        }
    }

    fun mapCanvasTouchToRemoteResolution(
        touchX: Float,
        touchY: Float,
        containerW: Float,
        containerH: Float,
        desktopW: Float,
        desktopH: Float,
        scalingMode: ScalingMode,
        zoomScale: Float,
        panOffsetX: Float,
        panOffsetY: Float
    ): NormalizedRemoteCoordinate {
        val bounds = computeRenderedDesktopBounds(containerW, containerH, desktopW, desktopH, scalingMode)
        val pivotX = containerW / 2f
        val pivotY = containerH / 2f
        val safeZoom = zoomScale.coerceAtLeast(0.1f)

        // Invert graphicsLayer(scaleX = zoomScale, scaleY = zoomScale, translationX = panOffsetX, translationY = panOffsetY)
        val unzoomedCanvasX = ((touchX - pivotX - panOffsetX) / safeZoom) + pivotX
        val unzoomedCanvasY = ((touchY - pivotY - panOffsetY) / safeZoom) + pivotY

        val renderedLeft = bounds.dstLeftInt.toFloat()
        val renderedTop = bounds.dstTopInt.toFloat()
        val renderedWidth = bounds.dstWidthInt.toFloat()
        val renderedHeight = bounds.dstHeightInt.toFloat()

        val rawNormX = (unzoomedCanvasX - renderedLeft) / renderedWidth
        val rawNormY = (unzoomedCanvasY - renderedTop) / renderedHeight

        val inside = rawNormX in -0.04f..1.04f && rawNormY in -0.04f..1.04f
        val clampedNormX = rawNormX.coerceIn(0f, 1f)
        val clampedNormY = rawNormY.coerceIn(0f, 1f)

        val maxPixelX = (desktopW.roundToInt() - 1).coerceAtLeast(1)
        val maxPixelY = (desktopH.roundToInt() - 1).coerceAtLeast(1)
        val remotePixelX = (clampedNormX * maxPixelX).roundToInt().coerceIn(0, maxPixelX)
        val remotePixelY = (clampedNormY * maxPixelY).roundToInt().coerceIn(0, maxPixelY)

        return NormalizedRemoteCoordinate(
            normX = clampedNormX,
            normY = clampedNormY,
            remotePixelX = remotePixelX,
            remotePixelY = remotePixelY,
            isInsideRenderedDesktop = inside
        )
    }

    fun mapCanvasDeltaToNormalizedDelta(
        deltaX: Float,
        deltaY: Float,
        containerW: Float,
        containerH: Float,
        desktopW: Float,
        desktopH: Float,
        scalingMode: ScalingMode,
        zoomScale: Float
    ): Pair<Float, Float> {
        val bounds = computeRenderedDesktopBounds(containerW, containerH, desktopW, desktopH, scalingMode)
        val effectiveW = (bounds.dstWidthInt * zoomScale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
        val effectiveH = (bounds.dstHeightInt * zoomScale.coerceAtLeast(0.1f)).coerceAtLeast(1f)
        return Pair(deltaX / effectiveW, deltaY / effectiveH)
    }
}

fun computeRenderedDesktopBounds(
    containerW: Float,
    containerH: Float,
    desktopW: Float,
    desktopH: Float,
    scalingMode: ScalingMode
): RenderedDesktopBounds = RemoteCoordinateNormalizer.computeRenderedDesktopBounds(
    containerW = containerW,
    containerH = containerH,
    desktopW = desktopW,
    desktopH = desktopH,
    scalingMode = scalingMode
)

fun screenTouchToNormalizedDesktop(
    touchX: Float,
    touchY: Float,
    containerW: Float,
    containerH: Float,
    desktopW: Float,
    desktopH: Float,
    scalingMode: ScalingMode,
    zoomScale: Float,
    panOffsetX: Float,
    panOffsetY: Float
): Pair<Float, Float> {
    val mapped = RemoteCoordinateNormalizer.mapCanvasTouchToRemoteResolution(
        touchX = touchX,
        touchY = touchY,
        containerW = containerW,
        containerH = containerH,
        desktopW = desktopW,
        desktopH = desktopH,
        scalingMode = scalingMode,
        zoomScale = zoomScale,
        panOffsetX = panOffsetX,
        panOffsetY = panOffsetY
    )
    return Pair(mapped.normX, mapped.normY)
}

@Composable
fun RemoteFramebufferCanvas(
    session: ActiveRemoteSession,
    onDynamicViewportResize: (Int, Int) -> Unit,
    onTapNormalized: (Float, Float) -> Unit,
    onLongPressNormalized: (Float, Float) -> Unit,
    onMiddleClickNormalized: (Float, Float) -> Unit = { _, _ -> },
    onScrollWheelNormalized: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onHoverMoveNormalized: (Float, Float) -> Unit = { _, _ -> },
    onDragNormalized: (Float, Float) -> Unit,
    onDragStartNormalized: (Float, Float) -> Unit = { _, _ -> },
    onDragMoveNormalized: (Float, Float, Float, Float) -> Unit = { _, _, dx, dy -> onDragNormalized(dx, dy) },
    onDragEndNormalized: (Float, Float) -> Unit = { _, _ -> },
    onZoomAndPan: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onSendChordMacro: (String) -> Unit,
    onOpenResolutionDialog: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onDismissContextMenu: () -> Unit,
    onRequestKeyboardFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var canvasWidthPx by remember { mutableIntStateOf(1) }
    var canvasHeightPx by remember { mutableIntStateOf(1) }
    val bitmap = session.framebufferBitmap
    val currentSession by rememberUpdatedState(session)
    val currentOnTap by rememberUpdatedState(onTapNormalized)
    val currentOnLongPress by rememberUpdatedState(onLongPressNormalized)
    val currentOnMiddleClick by rememberUpdatedState(onMiddleClickNormalized)
    val currentOnScrollWheel by rememberUpdatedState(onScrollWheelNormalized)
    val currentOnHoverMove by rememberUpdatedState(onHoverMoveNormalized)
    val currentOnDragStart by rememberUpdatedState(onDragStartNormalized)
    val currentOnDragMove by rememberUpdatedState(onDragMoveNormalized)
    val currentOnDragEnd by rememberUpdatedState(onDragEndNormalized)
    val currentOnZoomAndPan by rememberUpdatedState(onZoomAndPan)
    val currentOnRequestKeyboardFocus by rememberUpdatedState(onRequestKeyboardFocus)

    val density = LocalDensity.current
    // Tight 7.dp drag threshold so grabbing thin remote window title bars and scrollbars is effortless
    val precisionDragSlopPx = remember(density) { with(density) { 7.dp.toPx() } }
    val longPressThresholdMs = 460L

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .onSizeChanged { sz ->
                canvasWidthPx = sz.width.coerceAtLeast(1)
                canvasHeightPx = sz.height.coerceAtLeast(1)
                onDynamicViewportResize(canvasWidthPx, canvasHeightPx)
            }
            // Dedicated Hardware Mouse Scroll Wheel, Right/Middle Click, and Hover Motion Listener
            .pointerInput(session.sessionId) {
                awaitPointerEventScope {
                    var wasSecondaryPressed = false
                    var wasTertiaryPressed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val firstChange = event.changes.firstOrNull() ?: continue
                        val sNow = currentSession
                        val curDesktopW = (sNow.framebufferBitmap?.width ?: sNow.remoteWidth).toFloat()
                        val curDesktopH = (sNow.framebufferBitmap?.height ?: sNow.remoteHeight).toFloat()
                        val mapped = RemoteCoordinateNormalizer.mapCanvasTouchToRemoteResolution(
                            touchX = firstChange.position.x,
                            touchY = firstChange.position.y,
                            containerW = size.width.toFloat(),
                            containerH = size.height.toFloat(),
                            desktopW = curDesktopW,
                            desktopH = curDesktopH,
                            scalingMode = sNow.scalingMode,
                            zoomScale = sNow.zoomScale,
                            panOffsetX = sNow.panOffsetX,
                            panOffsetY = sNow.panOffsetY
                        )

                        // 1. Hardware Mouse Scroll Wheel or 2-finger Trackpad Scroll
                        if (event.type == PointerEventType.Scroll) {
                            currentOnRequestKeyboardFocus()
                            val scrollDelta = firstChange.scrollDelta
                            if (abs(scrollDelta.y) > 0.005f || abs(scrollDelta.x) > 0.005f) {
                                currentOnScrollWheel(
                                    mapped.normX,
                                    mapped.normY,
                                    scrollDelta.y,
                                    scrollDelta.x
                                )
                                firstChange.consume()
                            }
                            continue
                        }

                        // 2. Hardware Mouse Right-Click (Secondary) & Middle-Click (Tertiary) immediate detection!
                        val secNow = event.buttons.isSecondaryPressed
                        val terNow = event.buttons.isTertiaryPressed
                        if (secNow && !wasSecondaryPressed) {
                            wasSecondaryPressed = true
                            currentOnRequestKeyboardFocus()
                            currentOnLongPress(mapped.normX, mapped.normY)
                            event.changes.forEach { it.consume() }
                            continue
                        } else if (!secNow) {
                            wasSecondaryPressed = false
                        }

                        if (terNow && !wasTertiaryPressed) {
                            wasTertiaryPressed = true
                            currentOnRequestKeyboardFocus()
                            currentOnMiddleClick(mapped.normX, mapped.normY)
                            event.changes.forEach { it.consume() }
                            continue
                        } else if (!terNow) {
                            wasTertiaryPressed = false
                        }

                        // 3. Hardware Mouse Hover Movement (when moving mouse without holding buttons)
                        if (firstChange.type == PointerType.Mouse &&
                            event.type == PointerEventType.Move &&
                            !event.buttons.isPrimaryPressed &&
                            !secNow &&
                            !terNow
                        ) {
                            currentOnHoverMove(mapped.normX, mapped.normY)
                        }
                    }
                }
            }
            .pointerInput(session.sessionId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnRequestKeyboardFocus()

                    val downPosition = down.position
                    val downTimeMs = System.currentTimeMillis()
                    val sInitial = currentSession
                    val initDesktopW = (sInitial.framebufferBitmap?.width ?: sInitial.remoteWidth).toFloat()
                    val initDesktopH = (sInitial.framebufferBitmap?.height ?: sInitial.remoteHeight).toFloat()

                    val downMapped = RemoteCoordinateNormalizer.mapCanvasTouchToRemoteResolution(
                        touchX = downPosition.x,
                        touchY = downPosition.y,
                        containerW = size.width.toFloat(),
                        containerH = size.height.toFloat(),
                        desktopW = initDesktopW,
                        desktopH = initDesktopH,
                        scalingMode = sInitial.scalingMode,
                        zoomScale = sInitial.zoomScale,
                        panOffsetX = sInitial.panOffsetX,
                        panOffsetY = sInitial.panOffsetY
                    )

                    // If hardware mouse right-click or middle-click initiated this gesture, it was already dispatched
                    // immediately by the hardware mouse listener above — wait for release and do not treat as left click!
                    val initialEvent = currentEvent
                    if (initialEvent.buttons.isSecondaryPressed || initialEvent.buttons.isTertiaryPressed) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }

                    var prevPosition = downPosition
                    var lastMapped = downMapped
                    var isDragging = false
                    var isLongPressTriggered = false
                    var isTwoFingerGesture = false
                    val isMousePointer = down.type == PointerType.Mouse
                    val effectiveDragSlopPx = if (isMousePointer) 2f else precisionDragSlopPx

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            // If user clicks right mouse button while moving/holding
                            if (event.buttons.isSecondaryPressed && !isLongPressTriggered && !isDragging) {
                                isLongPressTriggered = true
                                currentOnLongPress(lastMapped.normX, lastMapped.normY)
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val activeChanges = event.changes.filter { it.pressed }

                            if (activeChanges.isEmpty()) {
                                // All fingers / mouse buttons lifted
                                break
                            }

                            // 1. Check for 2-finger pinch-to-zoom / pan gesture
                            if (activeChanges.size >= 2 && !isDragging) {
                                isTwoFingerGesture = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                if (abs(zoomChange - 1f) > 0.005f || panChange.getDistance() > 0.5f || centroidSize > 0f) {
                                    currentOnZoomAndPan(zoomChange, panChange.x, panChange.y)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }

                            if (isTwoFingerGesture) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }

                            val primaryChange: PointerInputChange = activeChanges.first()
                            val currentPos = primaryChange.position
                            val totalDistanceFromDown = hypot(
                                currentPos.x - downPosition.x,
                                currentPos.y - downPosition.y
                            )
                            val elapsedMs = System.currentTimeMillis() - downTimeMs

                            val sNow = currentSession
                            val curDesktopW = (sNow.framebufferBitmap?.width ?: sNow.remoteWidth).toFloat()
                            val curDesktopH = (sNow.framebufferBitmap?.height ?: sNow.remoteHeight).toFloat()

                            // 2. For Touch/Stylus only: if finger held stationary for >= longPressThresholdMs -> Right Click
                            if (!isMousePointer && !isDragging && !isLongPressTriggered &&
                                totalDistanceFromDown <= effectiveDragSlopPx &&
                                elapsedMs >= longPressThresholdMs
                            ) {
                                isLongPressTriggered = true
                                primaryChange.consume()
                                currentOnLongPress(downMapped.normX, downMapped.normY)
                                continue
                            }

                            if (isLongPressTriggered) {
                                primaryChange.consume()
                                continue
                            }

                            // 3. Check if pointer crossed drag threshold -> Start Left-Mouse-Button Drag at EXACT downPosition!
                            if (!isDragging && totalDistanceFromDown > effectiveDragSlopPx) {
                                isDragging = true
                                // Start drag at the exact initial touch-down pixel (e.g. window title bar)
                                currentOnDragStart(downMapped.normX, downMapped.normY)
                            }

                            if (isDragging) {
                                primaryChange.consume()
                                val curMapped = RemoteCoordinateNormalizer.mapCanvasTouchToRemoteResolution(
                                    touchX = currentPos.x,
                                    touchY = currentPos.y,
                                    containerW = size.width.toFloat(),
                                    containerH = size.height.toFloat(),
                                    desktopW = curDesktopW,
                                    desktopH = curDesktopH,
                                    scalingMode = sNow.scalingMode,
                                    zoomScale = sNow.zoomScale,
                                    panOffsetX = sNow.panOffsetX,
                                    panOffsetY = sNow.panOffsetY
                                )
                                val (deltaNormX, deltaNormY) = RemoteCoordinateNormalizer.mapCanvasDeltaToNormalizedDelta(
                                    deltaX = currentPos.x - prevPosition.x,
                                    deltaY = currentPos.y - prevPosition.y,
                                    containerW = size.width.toFloat(),
                                    containerH = size.height.toFloat(),
                                    desktopW = curDesktopW,
                                    desktopH = curDesktopH,
                                    scalingMode = sNow.scalingMode,
                                    zoomScale = sNow.zoomScale
                                )
                                prevPosition = currentPos
                                lastMapped = curMapped
                                currentOnDragMove(
                                    curMapped.normX,
                                    curMapped.normY,
                                    deltaNormX,
                                    deltaNormY
                                )
                            }
                        }
                    } finally {
                        if (isDragging) {
                            // Always release Left Mouse Button when drag ends or is cancelled
                            currentOnDragEnd(lastMapped.normX, lastMapped.normY)
                        }
                    }

                    // 4. If lifted without dragging or right-clicking -> Left Click (or touch long-press if finger held)
                    if (!isDragging && !isLongPressTriggered && !isTwoFingerGesture) {
                        val elapsedMs = System.currentTimeMillis() - downTimeMs
                        if (!isMousePointer && elapsedMs >= longPressThresholdMs) {
                            currentOnLongPress(downMapped.normX, downMapped.normY)
                        } else {
                            currentOnTap(downMapped.normX, downMapped.normY)
                        }
                    }
                }
            }
            .testTag("remote_framebuffer_canvas")
    ) {
        if (bitmap != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = session.zoomScale,
                        scaleY = session.zoomScale,
                        translationX = session.panOffsetX,
                        translationY = session.panOffsetY,
                        compositingStrategy = CompositingStrategy.Offscreen
                    )
            ) {
                // Observe framebufferFrameCount inside DrawScope so double-buffered ImageBitmap swaps
                // deterministically trigger a hardware-accelerated GPU RenderNode redraw on every frame.
                val frameGeneration = session.framebufferFrameCount
                val bounds = RemoteCoordinateNormalizer.computeRenderedDesktopBounds(
                    containerW = size.width,
                    containerH = size.height,
                    desktopW = bitmap.width.toFloat(),
                    desktopH = bitmap.height.toFloat(),
                    scalingMode = session.scalingMode
                )

                drawRect(color = Color(0xFF0A0A0A))

                val dstLeft = bounds.dstLeftInt.toFloat()
                val dstTop = bounds.dstTopInt.toFloat()
                val dstW = bounds.dstWidthInt
                val dstH = bounds.dstHeightInt
                // Use hardware bilinear filtering when downscaling high-res (1080p/1440p/4K) streams for
                // 60-120 FPS throughput without pixel shimmer, and bicubic High filtering when zoomed in.
                val effectiveFilterQuality = if (session.zoomScale > 1.05f || dstW >= bitmap.width) {
                    FilterQuality.High
                } else {
                    FilterQuality.Medium
                }

                withTransform({
                    translate(left = dstLeft, top = dstTop)
                }) {
                    if (frameGeneration >= 0) {
                        drawImage(
                            image = bitmap,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bitmap.width, bitmap.height),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(dstW, dstH),
                            filterQuality = effectiveFilterQuality
                        )
                    }

                    val cx = session.cursorXNorm * dstW.toFloat()
                    val cy = session.cursorYNorm * dstH.toFloat()

                    // If actively dragging a window or selection, draw a subtle drag trajectory vector from start to current
                    val startXNorm = session.dragStartXNorm
                    val startYNorm = session.dragStartYNorm
                    if (session.isLeftButtonDragging && startXNorm != null && startYNorm != null) {
                        val sx = startXNorm * dstW.toFloat()
                        val sy = startYNorm * dstH.toFloat()
                        drawCircle(
                            color = Color(0xAA2EC27E),
                            radius = 5f,
                            center = Offset(sx, sy),
                            style = Stroke(width = 1.5f)
                        )
                        drawLine(
                            color = Color(0x992EC27E),
                            start = Offset(sx, sy),
                            end = Offset(cx, cy),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                        )
                    }

                    // Render precision pointer cursor in Trackpad mode or target reticle in Direct Touch mode
                    if (session.inputMode == InputControlMode.TRACKPAD) {
                        val cursorPath = Path().apply {
                            moveTo(cx, cy)
                            lineTo(cx, cy + 18f)
                            lineTo(cx + 5f, cy + 14f)
                            lineTo(cx + 10f, cy + 21f)
                            lineTo(cx + 13f, cy + 19f)
                            lineTo(cx + 8f, cy + 12f)
                            lineTo(cx + 14f, cy + 12f)
                            close()
                        }
                        drawPath(
                            cursorPath,
                            color = if (session.isLeftButtonDragging) Color(0xFF2EC27E) else Color.White
                        )
                        drawPath(cursorPath, color = Color.Black, style = Stroke(width = 1.5f))
                    } else {
                        val ringColor = if (session.isLeftButtonDragging) Color(0xDD2EC27E) else Color(0xAA3584E4)
                        val ringRadius = if (session.isLeftButtonDragging) 11f else 7f
                        drawCircle(
                            color = ringColor,
                            radius = ringRadius,
                            center = Offset(cx, cy),
                            style = Stroke(width = if (session.isLeftButtonDragging) 2.2f else 1.5f)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 2.2f,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }

            // Compact Coordinate & Scaling HUD Pill in bottom-right corner
            Surface(
                color = Color(0xB812161F),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = "${session.lastPointerAction} • ${bitmap.width}×${bitmap.height} (${session.scalingMode.shortLabel})",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (session.isLeftButtonDragging) Color(0xFF2EC27E) else Color(0xFFB0BEC5),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        } else {
            // Waiting for the first real bitmap PDU from the remote host
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = session.protocol.badgeColor)
                Text(
                    text = "Awaiting initial ${session.protocol.displayName} framebuffer from ${session.profile.server}:${session.profile.port}...",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "Negotiated ${session.remoteWidth}×${session.remoteHeight} (${session.colorDepthBpp} bpp)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9A9996)
                )
            }
        }

        // Right-Click Context Menu Overlay
        session.showContextMenuAt?.let { (nx, ny) ->
            val bmpW = (bitmap?.width ?: session.remoteWidth).toFloat()
            val bmpH = (bitmap?.height ?: session.remoteHeight).toFloat()
            val bounds = RemoteCoordinateNormalizer.computeRenderedDesktopBounds(
                containerW = canvasWidthPx.toFloat(),
                containerH = canvasHeightPx.toFloat(),
                desktopW = bmpW,
                desktopH = bmpH,
                scalingMode = session.scalingMode
            )
            val screenX = (bounds.dstLeftInt + nx * bounds.dstWidthInt).roundToInt()
            val screenY = (bounds.dstTopInt + ny * bounds.dstHeightInt).roundToInt()
            val offsetX = screenX.coerceIn(8, (canvasWidthPx - 220).coerceAtLeast(8))
            val offsetY = screenY.coerceIn(8, (canvasHeightPx - 200).coerceAtLeast(8))

            Surface(
                modifier = Modifier
                    .offset { IntOffset(offsetX, offsetY) }
                    .width(215.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "RemMobile Session Actions",
                        style = MaterialTheme.typography.labelSmall,
                        color = session.protocol.badgeColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    HorizontalDivider()
                    ContextMenuItem(Icons.Default.AspectRatio, "Manage Resolution (${session.remoteWidth}×${session.remoteHeight})") {
                        onDismissContextMenu()
                        onOpenResolutionDialog()
                    }
                    ContextMenuItem(Icons.Default.Fullscreen, if (session.isFullScreen) "Exit Fullscreen" else "Enter Fullscreen (Host+F)") {
                        onDismissContextMenu()
                        onToggleFullScreen()
                    }
                    ContextMenuItem(Icons.Default.Computer, "Send Ctrl+Alt+Del") {
                        onSendChordMacro("CTRL_ALT_DEL")
                        onDismissContextMenu()
                    }
                    ContextMenuItem(Icons.Default.Refresh, "Close Menu") {
                        onDismissContextMenu()
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
