package engine.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared helpers for the vector/vision tools. */
object VectorIO {
    /** Encodes a float vector as little-endian base64 so it survives JSON transport. */
    fun packFloats(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    fun unpackFloats(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.DEFAULT)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buf.getFloat() }
    }

    /** Loads a bitmap from a data URL, content URI, file path, or raw base64 payload. */
    fun decodeImage(ctx: Context, reference: String): Bitmap? {
        val raw = reference.trim()
        if (raw.isEmpty()) return null
        val bytes: ByteArray = when {
            raw.startsWith("data:image") -> Base64.decode(raw.substringAfter("base64,"), Base64.DEFAULT)
            raw.startsWith("content://") ->
                ctx.contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes() }
                    ?: return null
            raw.startsWith("file://") -> File(raw.removePrefix("file://")).readBytes()
            raw.startsWith("/") -> File(raw).readBytes()
            else -> Base64.decode(raw, Base64.DEFAULT)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
