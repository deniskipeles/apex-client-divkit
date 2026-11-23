package com.example.camera

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the user chose to do when asked for an image. */
sealed class ImageSourceChoice {
    /** The user picked an existing file; [bytes] are the decoded file contents. */
    data class File(val bytes: ByteArray, val displayName: String) : ImageSourceChoice()

    /** The user asked to take a photo; the caller opens the camera. */
    data object Camera : ImageSourceChoice()

    data object Cancelled : ImageSourceChoice()
}

/**
 * Asks the user where an image should come from: an existing file, or the camera.
 *
 * Vision tools (`tools://scan/ocr`, `tools://MobileNetV4-small/classify`, ...) need real pixels
 * but run with no Activity, so they cannot open a picker themselves. This registry is the
 * bridge, mirroring [CameraHost]: a tool parks a request, [ImageSourceOverlay] shows the chooser,
 * and the choice is handed back to the suspended caller. Nothing is synthesised on cancel.
 */
object ImageSourceHost {

    private var activeRequest by mutableStateOf<Request?>(null)

    /** The pending request, observed by [ImageSourceOverlay]. */
    val request: Request? get() = activeRequest

    class Request(val allowCamera: Boolean, private val onResult: (ImageSourceChoice) -> Unit) {
        private var settled = false

        fun deliver(choice: ImageSourceChoice) {
            if (settled) return
            settled = true
            activeRequest = null
            onResult(choice)
        }
    }

    /** Suspends until the user picks a file, asks for the camera, or cancels. */
    suspend fun choose(allowCamera: Boolean = true): ImageSourceChoice =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val request = Request(allowCamera) { choice ->
                if (cont.isActive) cont.resume(choice)
            }
            main.post { activeRequest = request }
            cont.invokeOnCancellation { main.post { activeRequest = null } }
        }

    fun cancel() {
        activeRequest?.deliver(ImageSourceChoice.Cancelled)
    }
}

/**
 * Renders the source chooser while [ImageSourceHost] has a pending request, and nothing
 * otherwise. Place once at the root of the composition.
 */
@Composable
fun ImageSourceOverlay() {
    val request = ImageSourceHost.request ?: return
    val context = LocalContext.current

    BackHandler { ImageSourceHost.cancel() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            request.deliver(ImageSourceChoice.Cancelled)
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            request.deliver(ImageSourceChoice.Cancelled)
        } else {
            request.deliver(
                ImageSourceChoice.File(bytes, uri.lastPathSegment ?: "selected image")
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Choose an image source",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            if (request.allowCamera) {
                Button(onClick = { request.deliver(ImageSourceChoice.Camera) }) {
                    Text("Take Photo")
                }
            }
            Button(onClick = { picker.launch("image/*") }) { Text("Choose File") }
            TextButton(onClick = { ImageSourceHost.cancel() }) {
                Text("Cancel", color = Color.White)
            }
        }
    }
}
