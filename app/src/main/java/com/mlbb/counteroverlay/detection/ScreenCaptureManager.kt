package com.mlbb.counteroverlay.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds an active [MediaProjection] session and captures one full-resolution
 * screen frame as a [Bitmap] on demand.
 *
 * Lifecycle: call [start] with a projection obtained from
 * [android.media.projection.MediaProjectionManager], then call [captureFrame]
 * whenever you need a screenshot.  Call [stop] (or just let the [MediaProjection]
 * be torn down by the system) to clean up.
 *
 * Robustness notes:
 *  - The capture size follows the real display size on every [captureFrame], so rotating the
 *    phone (portrait grant -> landscape game) gives correctly sized frames.
 *  - Frames queued before the request are discarded, so you always get the screen as it is NOW.
 *  - If the screen is static and no frame arrives, the surface is re-attached to force one.
 *  - If nothing arrives within [CAPTURE_TIMEOUT_MS], an [IllegalStateException] is thrown
 *    instead of waiting forever.
 */
class ScreenCaptureManager(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capW = 0
    private var capH = 0
    private var capDpi = 0

    /** Background thread used for all ImageReader callbacks. */
    private val ioThread = HandlerThread("ScreenCapture").also { it.start() }
    private val ioHandler = Handler(ioThread.looper)

    /** Real (full-screen, rotation-aware) display size in pixels plus density. */
    private fun realSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        } else {
            val r = context.resources.displayMetrics
            Triple(r.widthPixels, r.heightPixels, r.densityDpi)
        }
    }

    /**
     * Initialise the virtual display.
     *
     * @param mp   A fresh [MediaProjection] returned by
     *             [android.media.projection.MediaProjectionManager.getMediaProjection].
     * @param onStopped Called (on the capture thread) if the system ends the session, e.g. the
     *             user taps the screen-sharing chip. [isRunning] is already false by then.
     */
    fun start(mp: MediaProjection, onStopped: () -> Unit = {}) {
        stop() // tear down any previous session
        projection = mp

        // Android 14+ throws from createVirtualDisplay() unless a callback is registered first.
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup(stopProjection = false)
                onStopped()
            }
        }
        projectionCallback = cb
        mp.registerCallback(cb, ioHandler)

        val (w, h, dpi) = realSize()
        capW = w; capH = h; capDpi = dpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, /* maxImages= */ 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "AceCounterCapture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            /* callback= */ null,
            ioHandler,
        )

        Log.d(TAG, "VirtualDisplay created ${w}x${h}@${dpi}")
    }

    /** Resizes the virtual display + reader if the phone was rotated since the last capture. */
    private fun ensureSize() {
        val vd = virtualDisplay ?: return
        val (w, h, dpi) = realSize()
        if (w == capW && h == capH && dpi == capDpi) return
        val old = imageReader
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd.resize(w, h, dpi)
        vd.surface = reader.surface
        imageReader = reader
        old?.close()
        capW = w; capH = h; capDpi = dpi
        Log.d(TAG, "VirtualDisplay resized ${w}x${h}@${dpi}")
    }

    /**
     * Captures one fresh screen frame and returns it as a newly allocated [Bitmap].
     *
     * Throws [IllegalStateException] if [start] was never called or no frame arrives in time.
     */
    suspend fun captureFrame(): Bitmap {
        ensureSize()
        val reader = imageReader
            ?: throw IllegalStateException("ScreenCaptureManager not started")

        // Drop frames that were queued long before this request (they show an old screen).
        while (true) {
            val stale = reader.acquireLatestImage() ?: break
            stale.close()
        }

        return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Bitmap> { cont ->
                cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
                reader.setOnImageAvailableListener({ r ->
                    // One frame only.
                    r.setOnImageAvailableListener(null, null)
                    val image = r.acquireLatestImage()
                    if (image == null) {
                        if (cont.isActive) cont.resumeWithException(RuntimeException("acquireLatestImage returned null"))
                        return@setOnImageAvailableListener
                    }
                    try {
                        val bmp = toBitmap(image)
                        if (cont.isActive) cont.resume(bmp) else bmp.recycle()
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }, ioHandler)
                // A completely static screen produces no new frame; re-attaching forces one.
                ioHandler.postDelayed({
                    if (cont.isActive) runCatching { virtualDisplay?.surface = reader.surface }
                }, NUDGE_MS)
            }
        } ?: throw IllegalStateException("Timed out waiting for a screen frame")
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val padded = Bitmap.createBitmap(
            image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        // Crop to exact display dimensions in case stride > width.
        if (padded.width == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    fun stop() = cleanup(stopProjection = true)

    private fun cleanup(stopProjection: Boolean) {
        ioHandler.removeCallbacksAndMessages(null)
        imageReader?.close(); imageReader = null
        virtualDisplay?.release(); virtualDisplay = null
        val mp = projection; projection = null
        val cb = projectionCallback; projectionCallback = null
        if (mp != null) {
            cb?.let { runCatching { mp.unregisterCallback(it) } }   // avoid onStop() re-entering us
            if (stopProjection) runCatching { mp.stop() }
        }
        capW = 0; capH = 0; capDpi = 0
        Log.d(TAG, "ScreenCaptureManager stopped")
    }

    fun isRunning() = projection != null

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CAPTURE_TIMEOUT_MS = 3000L
        private const val NUDGE_MS = 250L
    }
}
