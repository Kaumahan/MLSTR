package com.mlbb.counteroverlay.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Holds an active [MediaProjection] session and captures one full-resolution
 * screen frame as a [Bitmap] on demand.
 *
 * Lifecycle: call [start] with a projection obtained from
 * [android.media.projection.MediaProjectionManager], then call [captureFrame]
 * whenever you need a screenshot.  Call [stop] (or just let the [MediaProjection]
 * be torn down by the system) to clean up.
 *
 * Threading: [captureFrame] is a suspend function that posts to an internal
 * background [Handler] so the ImageReader callback never blocks the main thread.
 */
class ScreenCaptureManager(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /** Background thread used for all ImageReader callbacks. */
    private val ioThread = HandlerThread("ScreenCapture").also { it.start() }
    private val ioHandler = Handler(ioThread.looper)

    /**
     * Initialise the virtual display.  Must be called from the main thread
     * (or at least from the thread that owns the [MediaProjection]).
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

        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val dpi = dm.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, /* maxImages= */ 2)

        virtualDisplay = mp.createVirtualDisplay(
            "AceCounterCapture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            /* callback= */ null,
            ioHandler,
        )

        Log.d(TAG, "VirtualDisplay created ${w}x${h}@${dpi}")
    }

    /**
     * Captures one screen frame and returns it as a freshly-allocated [Bitmap].
     *
     * The function suspends until a frame is available (usually < 100 ms after
     * [start]).  Throws [IllegalStateException] if [start] was never called.
     */
    suspend fun captureFrame(): Bitmap = suspendCancellableCoroutine { cont ->
        val reader = imageReader
            ?: run { cont.resumeWithException(IllegalStateException("ScreenCaptureManager not started")); return@suspendCancellableCoroutine }

        reader.setOnImageAvailableListener({ r ->
            // Remove the listener immediately so we only capture one frame.
            r.setOnImageAvailableListener(null, null)
            val image = r.acquireLatestImage()
            if (image == null) {
                cont.resumeWithException(RuntimeException("acquireLatestImage returned null"))
                return@setOnImageAvailableListener
            }
            try {
                val plane = image.planes[0]
                val bitmap = Bitmap.createBitmap(
                    image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(plane.buffer)
                // Crop to exact display dimensions in case stride > width.
                val cropped = if (bitmap.width == image.width) bitmap
                else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                if (cropped !== bitmap) bitmap.recycle()
                cont.resume(cropped)
            } finally {
                image.close()
            }
        }, ioHandler)
    }

    fun stop() = cleanup(stopProjection = true)

    private fun cleanup(stopProjection: Boolean) {
        imageReader?.close(); imageReader = null
        virtualDisplay?.release(); virtualDisplay = null
        val mp = projection; projection = null
        val cb = projectionCallback; projectionCallback = null
        if (mp != null) {
            cb?.let { runCatching { mp.unregisterCallback(it) } }   // avoid onStop() re-entering us
            if (stopProjection) runCatching { mp.stop() }
        }
        Log.d(TAG, "ScreenCaptureManager stopped")
    }

    fun isRunning() = projection != null

    companion object {
        private const val TAG = "ScreenCapture"
    }
}
