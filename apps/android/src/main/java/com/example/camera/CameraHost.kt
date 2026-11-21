package com.example.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import engine.tools.scan.BarcodeDecoder
import engine.tools.scan.ScanResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** What the host is currently doing with the camera. */
enum class CameraMode {
    /** Waits for the user to press the shutter and returns one still frame. */
    Capture,

    /** Keeps analysing frames until a symbology decodes, then returns its payload. */
    Scan
}

/** A completed camera interaction. */
sealed class CameraOutcome {
    data class Photo(val bytes: ByteArray) : CameraOutcome()
    data class Scanned(val value: String, val format: String) : CameraOutcome()
    data object Cancelled : CameraOutcome()
    data class Failed(val message: String) : CameraOutcome()
}

/**
 * A live camera surface the whole app can drive.
 *
 * Tool calls (`tools://camera/capture`, `tools://scan/scan`) cannot own a camera: they run
 * without an Activity, a lifecycle, or a surface to render into. This registry is the bridge.
 * A tool call parks a request here, [CameraHostOverlay] renders a real CameraX preview with a
 * shutter button, and the result is handed back to the suspended caller. Nothing is fabricated:
 * if the camera cannot be opened the caller receives [CameraOutcome.Failed].
 */
object CameraHost {

    private var activeRequest by mutableStateOf<Request?>(null)

    /** True while a preview is on screen and the camera is in use. */
    val isActive: Boolean get() = activeRequest != null

    /** The pending request, observed by [CameraHostOverlay]. */
    val request: Request? get() = activeRequest

    class Request(
        val mode: CameraMode,
        val facing: String,
        val maxDimension: Int,
        private val onResult: (CameraOutcome) -> Unit
    ) {
        private var settled = false

        /** Delivers the outcome exactly once; later frames are ignored. */
        fun deliver(outcome: CameraOutcome) {
            if (settled) return
            settled = true
            activeRequest = null
            onResult(outcome)
        }
    }

    /**
     * Opens the camera and suspends until the interaction completes. Safe to call from any
     * dispatcher; the request is published on the main thread so the overlay recomposes, and the
     * result is delivered on the main thread.
     */
    suspend fun run(
        mode: CameraMode,
        facing: String,
        maxDimension: Int
    ): CameraOutcome =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val request = Request(mode, facing, maxDimension) { outcome ->
                if (cont.isActive) cont.resume(outcome)
            }
            main.post { activeRequest = request }
            cont.invokeOnCancellation { main.post { activeRequest = null } }
        }

    /** Dismisses the preview without producing a frame. */
    fun cancel() {
        activeRequest?.deliver(CameraOutcome.Cancelled)
    }
}

/**
 * Hosts the camera preview when [CameraHost] has a pending request and renders nothing
 * otherwise, so it is safe to place once at the root of the composition.
 */
@Composable
fun CameraHostOverlay() {
    val request = CameraHost.request ?: return
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            request.deliver(
                CameraOutcome.Failed("Camera permission denied. Enable it in system settings.")
            )
        }
    }

    LaunchedEffect(request) {
        if (!hasPermission && !askedOnce) {
            askedOnce = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        CameraPreview(request)
    }
}

@Composable
private fun CameraPreview(request: CameraHost.Request) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRequest by rememberUpdatedState(request)
    val executor = remember { Executors.newSingleThreadExecutor() }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var bound by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    LaunchedEffect(torchOn, camera) {
        val cam = camera ?: return@LaunchedEffect
        runCatching { cam.cameraControl.enableTorch(torchOn) }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val selector = if (request.facing.equals("front", true)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val useCases = mutableListOf<UseCase>(preview, imageCapture)

                if (request.mode == CameraMode.Scan) {
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            it.setAnalyzer(
                                executor,
                                BarcodeAnalyzer { value, format ->
                                    currentRequest.deliver(CameraOutcome.Scanned(value, format))
                                }
                            )
                        }
                    analysis = analyzer
                    useCases += analyzer
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                bound = true
            } catch (e: Exception) {
                val message = e.message ?: "Camera unavailable"
                errorText = message
                currentRequest.deliver(CameraOutcome.Failed(message))
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            analysis?.clearAnalyzer()
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when {
                    errorText != null -> errorText!!
                    request.mode == CameraMode.Scan -> "Point the camera at a barcode or QR code"
                    !bound -> "Starting camera…"
                    else -> "Frame the shot, then tap Capture"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            if (request.mode == CameraMode.Capture && errorText == null) {
                Button(
                    onClick = { takePhoto(context, imageCapture, currentRequest) },
                    enabled = bound
                ) { Text("Capture") }
            }

            if (hasFlash) {
                Button(onClick = { torchOn = !torchOn }) {
                    Text(if (torchOn) "Torch On" else "Torch Off")
                }
            }

            TextButton(onClick = { CameraHost.cancel() }) {
                Text("Cancel", color = Color.White)
            }
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    request: CameraHost.Request
) {
    val scratch = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(scratch).build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bytes = runCatching { scratch.readBytes() }.getOrNull()
                scratch.delete()
                if (bytes == null || bytes.isEmpty()) {
                    request.deliver(CameraOutcome.Failed("Captured frame was empty"))
                } else {
                    request.deliver(CameraOutcome.Photo(bytes))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                scratch.delete()
                request.deliver(CameraOutcome.Failed(exception.message ?: "Camera capture failed"))
            }
        }
    )
}

/**
 * Decodes the Y plane of each camera frame. Only luminance is needed for ZXing, which keeps the
 * per-frame cost low enough to run at camera frame rates.
 */
private class BarcodeAnalyzer(
    private val onDecoded: (value: String, format: String) -> Unit
) : ImageAnalysis.Analyzer {

    private var delivered = false

    override fun analyze(image: ImageProxy) {
        try {
            if (delivered) return
            val decoded: ScanResult = decodeYPlane(image) ?: return
            delivered = true
            onDecoded(decoded.rawValue, decoded.format)
        } finally {
            image.close()
        }
    }

    private fun decodeYPlane(image: ImageProxy): ScanResult? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height

        // The Y plane can carry row padding; repack into a dense width*height buffer because
        // ZXing's PlanarYUVLuminanceSource requires contiguous rows.
        val rowStride = plane.rowStride
        if (rowStride < width) return null
        val out = ByteArray(width * height)
        if (rowStride == width) {
            if (buffer.remaining() < out.size) return null
            buffer.get(out, 0, out.size)
        } else {
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                if (buffer.remaining() < rowStride) return null
                buffer.get(row, 0, rowStride)
                System.arraycopy(row, 0, out, y * width, width)
            }
        }

        // Camera sensors are mounted rotated, so the buffer arrives landscape while the user
        // holds the device upright. A 1D barcode is a single scan line: if the frame is not
        // rotated back, that line is vertical in the buffer and ZXing's linear readers cannot
        // find it at all. QR survives (it is square and rotation-independent), which is exactly
        // the symptom of QR scanning while barcodes never resolve.
        val rotation = image.imageInfo.rotationDegrees
        return BarcodeDecoder.decodeLuminance(out, width, height, rotation)
    }
}
