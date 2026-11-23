package com.example.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import engine.tools.impl.ScreenTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Real screen capture host: one MediaProjection permission prompt, then screenshots through
 * ImageReader and MP4 recording through MediaRecorder. Both write into the workspace media
 * directory so the result is a normal `file://` URI the DivKit card can preview.
 */
object ScreenCaptureHost {
    private const val TAG = "ScreenCaptureHost"

    private var activity: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: kotlinx.coroutines.CancellableContinuation<Intent?>? = null
    private var resultCode: Int = Activity.RESULT_CANCELED
    private var projection: MediaProjection? = null
    private var session: Session? = null

    private data class Session(
        val recorder: MediaRecorder,
        val display: VirtualDisplay,
        val file: File,
        val id: String,
        val startedAt: Long,
        val width: Int,
        val height: Int
    )

    fun attach(activity: ComponentActivity) {
        this.activity = activity
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            resultCode = result.resultCode
            val continuation = pending
            pending = null
            continuation?.resume(if (result.resultCode == Activity.RESULT_OK) result.data else null)
        }
    }

    suspend fun screenshot(tenantId: String, format: String, quality: Int): ScreenTool.Screenshot? {
        val host = activity ?: return null.also { Log.e(TAG, "screenshot: no activity") }
        val projection = try {
            ensureProjection()
        } catch (t: Throwable) {
            Log.e(TAG, "screenshot: ensureProjection failed", t)
            return null
        } ?: return null.also { Log.e(TAG, "screenshot: no projection") }
        val metrics = host.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        return try {
            // ImageReader creates a Handler from the calling thread's Looper, so construct it
            // (and the virtual display it feeds) on the main thread; the tool runs on a worker.
            val reader = withContext(Dispatchers.Main.immediate) {
                ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            }
            val display = withContext(Dispatchers.Main.immediate) {
                projection.createVirtualDisplay(
                    "apex-screenshot",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null
                )
            }
            try {
                val image = withTimeoutOrNull(15_000) {
                    while (true) {
                        val frame = reader.acquireLatestImage()
                        if (frame != null) return@withTimeoutOrNull frame
                        delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                } ?: return null.also { Log.e(TAG, "screenshot: no frame within timeout") }
                Log.d(TAG, "screenshot: frame acquired ${width}x${height}")
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val paddedWidth = width + rowPadding / plane.pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                    if (bitmap !== padded) padded.recycle()

                    val media = mediaDir(host, tenantId)
                    val id = "screen_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                    val extension = if (format == "jpg" || format == "jpeg") "jpg" else "png"
                    val file = File(media, "${id}_screenshot.$extension")
                    val compressFormat = if (extension == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    file.outputStream().use { bitmap.compress(compressFormat, quality, it) }
                    ScreenTool.Screenshot(
                        path = file.absolutePath,
                        uri = "file://${file.absolutePath}",
                        width = width,
                        height = height,
                        bytes = file.length(),
                        imageId = id
                    ).also { bitmap.recycle() }
                } finally {
                    image.close()
                }
            } finally {
                display?.release()
                reader.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "screenshot failed", t)
            null
        }
    }

    suspend fun startRecording(tenantId: String): ScreenTool.RecordingStart? {
        val host = activity ?: return null
        if (session != null) {
            val active = session!!
            return ScreenTool.RecordingStart(active.id, active.startedAt, active.width, active.height)
        }
        val projection = ensureProjection() ?: return null
        val metrics = host.resources.displayMetrics
        val width = metrics.widthPixels and -2
        val height = metrics.heightPixels and -2
        val media = mediaDir(host, tenantId)
        val id = "screen_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val file = File(media, "${id}_recording.mp4")

        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(host) else MediaRecorder()
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            val display = projection.createVirtualDisplay(
                "apex-recording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            ) ?: run {
                runCatching { recorder.release() }
                runCatching { if (file.exists()) file.delete() }
                return null
            }
            recorder.start()
            val startedAt = System.currentTimeMillis()
            session = Session(recorder, display, file, id, startedAt, width, height)
            return ScreenTool.RecordingStart(id, startedAt, width, height)
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            runCatching { if (file.exists()) file.delete() }
            return null
        }
    }

    suspend fun stopRecording(): ScreenTool.RecordingStop? {
        val active = session ?: return null
        session = null
        runCatching { active.recorder.stop() }
        runCatching { active.recorder.reset() }
        runCatching { active.recorder.release() }
        runCatching { active.display.release() }
        releaseProjection()
        val duration = (System.currentTimeMillis() - active.startedAt).coerceAtLeast(0L)
        return ScreenTool.RecordingStop(
            path = active.file.absolutePath,
            uri = "file://${active.file.absolutePath}",
            durationMs = duration,
            width = active.width,
            height = active.height,
            bytes = active.file.length(),
            id = active.id
        )
    }

    private suspend fun ensureProjection(): MediaProjection? {
        projection?.let { return it }
        val host = activity ?: return null
        val manager = host.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = suspendCancellableCoroutine<Intent?> { continuation ->
            pending = continuation
            launcher?.launch(manager.createScreenCaptureIntent())
        }
        if (data == null || resultCode != Activity.RESULT_OK) return null

        try {
            ScreenProjectionService.start(host)
        } catch (t: Throwable) {
            Log.e(TAG, "foreground service start failed", t)
            return null
        }
        repeat(20) { if (!ScreenProjectionService.active) delay(100) }
        if (!ScreenProjectionService.active) Log.w(TAG, "projection service not active; continuing")
        // MediaProjection constructs a Handler, so the token must be redeemed on a Looper thread.
        val created = try {
            withContext(Dispatchers.Main.immediate) {
                manager.getMediaProjection(resultCode, data)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed (code=$resultCode)", t)
            null
        }
        if (created == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return null
        }
        projection = created
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                session?.let {
                    runCatching { it.recorder.stop() }
                    runCatching { it.recorder.release() }
                    runCatching { it.display.release() }
                }
                session = null
                ScreenProjectionService.stop(host)
            }
        }, Handler(Looper.getMainLooper()))
        return created
    }

    private fun releaseProjection() {
        projection?.stop()
        projection = null
        activity?.let { ScreenProjectionService.stop(it) }
    }

    private fun mediaDir(context: Context, tenantId: String): File =
        File(context.filesDir, "workspaces/$tenantId/media").apply { mkdirs() }
}
