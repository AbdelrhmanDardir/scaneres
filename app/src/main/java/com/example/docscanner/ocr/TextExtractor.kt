package com.example.docscanner.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR with Google ML Kit (on-device, offline).
 * Note: the bundled model reads Latin-script text (English, French, German, ...). It does not read Arabic.
 */
class TextExtractor : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Returns the recognised text: lines of one block are separated by "\n",
     * and blocks (paragraphs) are separated by a blank line.
     */
    suspend fun extract(bitmap: Bitmap): String {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks
            .joinToString(separator = "\n\n") { block ->
                block.lines.joinToString(separator = "\n") { it.text }
            }
            .trim()
    }

    override fun close() {
        recognizer.close()
    }
}
