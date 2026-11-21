package engine.fs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Hands a workspace file to an external app through `ACTION_VIEW`.
 *
 * Files live in the app's private storage, so they are exposed as `content://` URIs from the
 * manifest-registered [FileProvider] with a one-shot read grant. The user always picks the
 * target app through the system chooser, so "Open with" stays honest: it never silently
 * claims a file was opened by an app that cannot handle it.
 */
object FileOpenWith {

    sealed interface Outcome {
        data class Launched(val mimeType: String) : Outcome
        data object NoHandler : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val extensionMimeTypes = mapOf(
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "yml" to "text/yaml",
        "yaml" to "text/yaml",
        "html" to "text/html",
        "pdf" to "application/pdf",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive"
    )

    fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension.isEmpty()) return "*/*"
        extensionMimeTypes[extension]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    /** `content://` URI for [file], or null when the file sits outside the provider roots. */
    fun contentUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    fun open(context: Context, file: File): Outcome {
        if (!file.exists()) return Outcome.Failed("${file.name} no longer exists")
        val uri = contentUri(context, file)
            ?: return Outcome.Failed("${file.name} is outside the shared workspace area")
        val mimeType = mimeTypeFor(file.name)

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, "Open ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(chooser)
            Outcome.Launched(mimeType)
        } catch (_: ActivityNotFoundException) {
            Outcome.NoHandler
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Could not open ${file.name}")
        }
    }
}
