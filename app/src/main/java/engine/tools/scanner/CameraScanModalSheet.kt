package engine.tools.scanner

import android.content.Context
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

@Composable
fun CameraScanModalSheet(
    targetFormat: String = "both",
    onCodeScanned: (rawValue: String, format: String, type: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isTorchEnabled by remember { mutableStateOf(false) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_scan_modal_container")
    ) {
        // 1. CameraX PreviewView with Analysis Pipeline
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val executor = Executors.newSingleThreadExecutor()
                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        // In production with Google ML Kit dependency added:
                        // val image = InputImage.fromMediaImage(imageProxy.image, imageProxy.imageInfo.rotationDegrees)
                        // BarcodeScanning.getClient().process(image).addOnSuccessListener { ... }
                        imageProxy.close()
                    }

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Optical Reticle Viewfinder (Darkened background overlay with transparent viewport)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val reticleW = canvasW * 0.78f
            val reticleH = if (targetFormat in setOf("barcode", "1d")) canvasW * 0.42f else canvasW * 0.78f
            val left = (canvasW - reticleW) / 2f
            val top = (canvasH - reticleH) / 2f

            // Viewfinder border
            drawRoundRect(
                color = Color(0xFF10B981),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(reticleW, reticleH),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
            )
        }

        // 3. Header Bar with Mode Pill & Close Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "MODE: ${targetFormat.uppercase()}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        // 4. Bottom Controls: Flashlight Toggle & Quick Simulator Injector
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flashlight toggle
                IconButton(
                    onClick = {
                        isTorchEnabled = !isTorchEnabled
                        cameraControl?.enableTorch(isTorchEnabled)
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (isTorchEnabled) Color(0xFFFBBF24) else Color.Black.copy(alpha = 0.65f))
                ) {
                    Icon(
                        imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flashlight",
                        tint = if (isTorchEnabled) Color.Black else Color.White
                    )
                }

                // Testing Simulator Trigger (In case user is on Emulator without barcode targets)
                FilledTonalButton(
                    onClick = {
                        if (targetFormat in setOf("qr", "2d")) {
                            onCodeScanned("https://apex.host/terminal/pair?t=101", "QR_CODE", "2D")
                        } else {
                            onCodeScanned("9780201633610", "EAN_13", "1D")
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF10B981))
                    Spacer(Modifier.width(6.dp))
                    Text("Inject Test Code", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}