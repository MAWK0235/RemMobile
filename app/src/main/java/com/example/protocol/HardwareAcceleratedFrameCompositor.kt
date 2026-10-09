package com.example.protocol

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Represents a dirty rectangle updated by an RDP Fast-Path tile, Surface Command, or VNC rectangle.
 */
data class DirtyRectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left + 1).coerceAtLeast(0)
    val height: Int get() = (bottom - top + 1).coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Snapshot emitted to the UI layer after hardware-accelerated back-buffer compositing and
 * `Bitmap.prepareToDraw()` GPU texture pre-upload.
 */
data class CompositedFrameSnapshot(
    val imageBitmap: ImageBitmap,
    val frameNumber: Int,
    val measuredFps: Int,
    val dirtyAreaRatio: Float,
    val coalescedTilesInFrame: Int
)

/**
 * Hardware-Accelerated Double-Buffered Frame Compositor for high-resolution RDP/VNC display streams.
 *
 * Eliminates visual glitches, mid-frame tearing, and GC frame drops on high-resolution targets
 * (1080p, 1440p, 4K) via:
 * 1. **Double-Buffered Opaque ARGB_8888 Surface Pool (`frontBuffer` / `backBuffer`)**:
 *    Never mutates the `Bitmap` currently bound by Compose's `RenderThread` on the GPU.
 *    Marks bitmaps with `setHasAlpha(false)` so Android's HWUI/Skia Vulkan/OpenGL backend
 *    blits opaque pixels without alpha-blending overhead.
 * 2. **Dirty-Rectangle Sub-Region Synchronization**:
 *    Instead of copying all `width * height` pixels on every 64x64 tile update, tracks the union
 *    bounding box of dirty tiles (`curDirtyRect`) plus the previous frame's dirty box (`prevBufferDirtyRect`)
 *    and invokes `Bitmap.setPixels(...)` strictly on the modified scanline bounds.
 * 3. **Asynchronous GPU Texture Pre-Upload (`Bitmap.prepareToDraw()`)**:
 *    Triggers HWUI `RenderThread` texture upload on the decode thread before dispatching to UI state.
 * 4. **RDP Frame Marker (`SURFACECMD_FRAMEACTION_BEGIN` / `END`) & VSync Coalescing**:
 *    Coalesces burst Fast-Path tile updates into atomic frames at up to 120 FPS (8ms–16ms cadence).
 */
class HardwareAcceleratedFrameCompositor(
    initialWidth: Int,
    initialHeight: Int,
    private val minFrameIntervalMs: Long = 12L // ~83 FPS ceiling to prevent UI state flood while staying ultra-smooth
) {
    private val lock = Any()

    var width: Int = initialWidth.coerceIn(64, 4096)
        private set
    var height: Int = initialHeight.coerceIn(64, 4096)
        private set

    private var bufferA: Bitmap = createHardwareReadyBitmap(width, height)
    private var bufferB: Bitmap = createHardwareReadyBitmap(width, height)
    private var imageBitmapA: ImageBitmap = bufferA.asImageBitmap()
    private var imageBitmapB: ImageBitmap = bufferB.asImageBitmap()
    private var useBufferAAsBack: Boolean = true

    // Dirty region accumulated for the current frame being decoded
    private var hasDirtyRegion: Boolean = false
    private var dirtyLeft: Int = Int.MAX_VALUE
    private var dirtyTop: Int = Int.MAX_VALUE
    private var dirtyRight: Int = Int.MIN_VALUE
    private var dirtyBottom: Int = Int.MIN_VALUE
    private var pendingTilesCount: Int = 0

    // Dirty region that was written to the other buffer on the previous flip, so the secondary buffer catches up
    private var prevFlipDirtyRect: Rect? = null
    private var needsFullSyncOnBothBuffers: Boolean = true

    // RDP Surface Command Frame Marker state (MS-RDPEGFX / MS-RDPBCGR 2.2.9.2.3)
    private var insideFrameMarker: Boolean = false
    private var frameMarkerStartMs: Long = 0L
    var lastCompletedFrameId: Int = -1
        private set

    // Timing & FPS telemetry
    private var lastPresentTimeMs: Long = 0L
    private var presentedFrameCount: Int = 0
    private val recentFrameTimestampsMs = ArrayDeque<Long>(128)

    private fun createHardwareReadyBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // Opaque remote desktop surfaces do not use per-pixel alpha; disabling alpha blending
        // allows Android's HWUI OpenGL/Vulkan pipeline to use fast opaque blitting shaders.
        bmp.setHasAlpha(false)
        return bmp
    }

    /**
     * Ensures the double-buffered bitmaps match `(newWidth, newHeight)`.
     */
    fun ensureDimensions(newWidth: Int, newHeight: Int) {
        val targetW = newWidth.coerceIn(64, 4096)
        val targetH = newHeight.coerceIn(64, 4096)
        synchronized(lock) {
            if (targetW == width && targetH == height) return
            width = targetW
            height = targetH
            bufferA = createHardwareReadyBitmap(width, height)
            bufferB = createHardwareReadyBitmap(width, height)
            imageBitmapA = bufferA.asImageBitmap()
            imageBitmapB = bufferB.asImageBitmap()
            useBufferAAsBack = true
            needsFullSyncOnBothBuffers = true
            prevFlipDirtyRect = null
            markFullFrameDirtyLocked()
        }
    }

    /**
     * Records a dirty rectangle modified by a decoded RDP/VNC tile.
     */
    fun markDirtyRect(left: Int, top: Int, right: Int, bottom: Int) {
        synchronized(lock) {
            val cl = left.coerceIn(0, width - 1)
            val ct = top.coerceIn(0, height - 1)
            val cr = right.coerceIn(cl, width - 1)
            val cb = bottom.coerceIn(ct, height - 1)
            if (!hasDirtyRegion) {
                dirtyLeft = cl
                dirtyTop = ct
                dirtyRight = cr
                dirtyBottom = cb
                hasDirtyRegion = true
            } else {
                dirtyLeft = min(dirtyLeft, cl)
                dirtyTop = min(dirtyTop, ct)
                dirtyRight = max(dirtyRight, cr)
                dirtyBottom = max(dirtyBottom, cb)
            }
            pendingTilesCount++
        }
    }

    fun markFullFrameDirty() {
        synchronized(lock) {
            markFullFrameDirtyLocked()
        }
    }

    private fun markFullFrameDirtyLocked() {
        dirtyLeft = 0
        dirtyTop = 0
        dirtyRight = width - 1
        dirtyBottom = height - 1
        hasDirtyRegion = true
        pendingTilesCount = max(1, pendingTilesCount + 1)
    }

    fun onFrameMarkerBegin(frameId: Int) {
        synchronized(lock) {
            insideFrameMarker = true
            frameMarkerStartMs = System.currentTimeMillis()
            lastCompletedFrameId = frameId
        }
    }

    fun onFrameMarkerEnd(frameId: Int) {
        synchronized(lock) {
            insideFrameMarker = false
            lastCompletedFrameId = frameId
        }
    }

    /**
     * Determines whether the compositor should flush the accumulated dirty region to the GPU now,
     * or continue coalescing incoming Fast-Path PDUs that are already buffered in the socket.
     */
    fun shouldFlushToGpu(
        hasMoreBytesInSocketBuffer: Boolean,
        forceImmediate: Boolean = false
    ): Boolean {
        synchronized(lock) {
            if (!hasDirtyRegion) return false
            if (forceImmediate || presentedFrameCount == 0) return true

            val now = System.currentTimeMillis()
            // If server is inside a TS_FRAME_MARKER batch, wait for FRAMEACTION_END unless 32ms safety cap elapsed
            if (insideFrameMarker && (now - frameMarkerStartMs) < 32L) {
                return false
            }

            val elapsedSinceLastPresent = now - lastPresentTimeMs
            // If more Fast-Path tile bytes are immediately available in the socket buffer, keep decoding
            // until the VSync frame interval (minFrameIntervalMs) is reached so all tiles appear atomically!
            if (hasMoreBytesInSocketBuffer && elapsedSinceLastPresent < minFrameIntervalMs) {
                return false
            }

            // Even if socket buffer is momentarily empty, coalesce sub-millisecond tile bursts up to 6ms
            if (elapsedSinceLastPresent < 6L && pendingTilesCount < 16) {
                return false
            }
            return true
        }
    }

    /**
     * Uploads only the dirty pixel region from [framebufferPixels] into the back-buffer hardware-ready
     * [Bitmap], invokes `prepareToDraw()` to pre-upload the GPU texture on Android's RenderThread,
     * swaps buffers, and returns a [CompositedFrameSnapshot].
     */
    fun compositeAndSwapBuffers(framebufferPixels: IntArray): CompositedFrameSnapshot? {
        synchronized(lock) {
            if (!hasDirtyRegion || framebufferPixels.size < width * height) {
                return null
            }

            val backBitmap = if (useBufferAAsBack) bufferA else bufferB
            val backImageBitmap = if (useBufferAAsBack) imageBitmapA else imageBitmapB

            val curRect = Rect(
                dirtyLeft.coerceIn(0, width - 1),
                dirtyTop.coerceIn(0, height - 1),
                dirtyRight.coerceIn(0, width - 1),
                dirtyBottom.coerceIn(0, height - 1)
            )

            // Union the current dirty rect with the previous flip's dirty rect so the back buffer
            // is 100% identical to `framebufferPixels` while only copying changed scanlines!
            val prevRect = prevFlipDirtyRect
            val uploadLeft: Int
            val uploadTop: Int
            val uploadRight: Int
            val uploadBottom: Int

            if (needsFullSyncOnBothBuffers || prevRect == null) {
                uploadLeft = 0
                uploadTop = 0
                uploadRight = width - 1
                uploadBottom = height - 1
            } else {
                uploadLeft = min(curRect.left, prevRect.left).coerceIn(0, width - 1)
                uploadTop = min(curRect.top, prevRect.top).coerceIn(0, height - 1)
                uploadRight = max(curRect.right, prevRect.right).coerceIn(0, width - 1)
                uploadBottom = max(curRect.bottom, prevRect.bottom).coerceIn(0, height - 1)
            }

            val uploadW = (uploadRight - uploadLeft + 1).coerceAtLeast(1)
            val uploadH = (uploadBottom - uploadTop + 1).coerceAtLeast(1)
            val startOffset = uploadTop * width + uploadLeft

            // Sub-region native pixel transfer into back-buffer Bitmap
            backBitmap.setPixels(
                framebufferPixels,
                startOffset,
                width,
                uploadLeft,
                uploadTop,
                uploadW,
                uploadH
            )

            // Trigger asynchronous HWUI RenderThread texture upload to GPU VRAM before Compose draws
            backBitmap.prepareToDraw()

            // If this was the first frame after resize, also sync the secondary buffer on next flip
            if (needsFullSyncOnBothBuffers) {
                prevFlipDirtyRect = Rect(0, 0, width - 1, height - 1)
                needsFullSyncOnBothBuffers = false
            } else {
                prevFlipDirtyRect = curRect
            }

            val coalescedTiles = pendingTilesCount
            val dirtyAreaRatio = ((curRect.width() + 1).toFloat() * (curRect.height() + 1).toFloat()) /
                (width.toFloat() * height.toFloat()).coerceAtLeast(1f)

            // Reset current dirty accumulators and swap buffers
            hasDirtyRegion = false
            dirtyLeft = Int.MAX_VALUE
            dirtyTop = Int.MAX_VALUE
            dirtyRight = Int.MIN_VALUE
            dirtyBottom = Int.MIN_VALUE
            pendingTilesCount = 0
            useBufferAAsBack = !useBufferAAsBack

            val now = System.currentTimeMillis()
            lastPresentTimeMs = now
            presentedFrameCount++

            recentFrameTimestampsMs.addLast(now)
            while (recentFrameTimestampsMs.size > 1 && (now - recentFrameTimestampsMs.first()) > 1000L) {
                recentFrameTimestampsMs.removeFirst()
            }
            val measuredFps = if (recentFrameTimestampsMs.size >= 2) {
                val spanMs = (now - recentFrameTimestampsMs.first()).coerceAtLeast(16L)
                (((recentFrameTimestampsMs.size - 1) * 1000f) / spanMs).roundToInt().coerceIn(1, 120)
            } else {
                60
            }

            return CompositedFrameSnapshot(
                imageBitmap = backImageBitmap,
                frameNumber = presentedFrameCount,
                measuredFps = measuredFps,
                dirtyAreaRatio = dirtyAreaRatio.coerceIn(0f, 1f),
                coalescedTilesInFrame = coalescedTiles
            )
        }
    }
}
