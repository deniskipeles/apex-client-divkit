package engine.tools.vector

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrBlock(val text: String, val bbox: IntArray)
data class OcrResult(val text: String, val blocks: List<OcrBlock>, val meanConfidence: Float)

object OcrExtractor {
    // Bundled Latin model ≈ 4.1 MB. Use com.google.mlkit:text-recognition:16.0.1
    // If you need to shrink app size further, swap to the Play-Services-backed
    // variant (:text-recognition-play-services) — 0 MB app footprint, downloaded on demand.
    fun extract(bitmap: Bitmap): OcrResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val visionText = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val blocks = visionText.textBlocks.map { b ->
            OcrBlock(b.text, b.boundingBox?.let { intArrayOf(it.left, it.top, it.right, it.bottom) } ?: intArrayOf())
        }
        val confs = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }.map { it.confidence }
        return OcrResult(visionText.text, blocks, confs.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f)
    }
}