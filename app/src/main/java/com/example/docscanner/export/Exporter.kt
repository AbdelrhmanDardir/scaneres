package com.example.docscanner.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val PDF_MIME = "application/pdf"

private const val APP_FOLDER = "DocScanner"

/**
 * Creates a Word (.docx) file from OCR text and saves it to Documents/DocScanner on the phone.
 *
 * Uses MediaStore (scoped storage), so no storage permission is needed on Android 10+
 * and it works the same on Android 11, 13, 14, 15 and 16.
 *
 * @return the content Uri of the saved file, or a failure with a readable message.
 */
suspend fun exportToWord(extractedText: String, context: Context): Result<Uri> =
    withContext(Dispatchers.IO) {
        runCatching {
            if (extractedText.isBlank()) throw IOException("لا يوجد نص لتصديره")
            saveToDocuments(context, "Scan_${timestamp()}.docx", DOCX_MIME) { out ->
                DocxWriter.writeTo(extractedText, out)
            }
        }
    }

/** Saves a scanned page as a PDF in Documents/DocScanner. */
suspend fun exportToPdf(page: Bitmap, context: Context): Result<Uri> =
    withContext(Dispatchers.IO) {
        runCatching {
            saveToDocuments(context, "Scan_${timestamp()}.pdf", PDF_MIME) { out ->
                writePdf(page, out)
            }
        }
    }

private fun timestamp(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/**
 * Inserts a pending row in MediaStore, streams the file into it, then publishes it.
 * If anything fails the half-written row is deleted.
 */
private fun saveToDocuments(
    context: Context,
    fileName: String,
    mimeType: String,
    write: (OutputStream) -> Unit,
): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$APP_FOLDER")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: throw IOException("تعذر إنشاء الملف في وحدة التخزين")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("تعذر فتح الملف للكتابة")
        stream.use { write(it) }
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, published, null, null)
        return uri
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw t
    }
}

private fun writePdf(source: Bitmap, out: OutputStream) {
    val pageWidth = 595                                  // A4 width in PDF points
    val targetPixels = min(source.width, 1654)           // ~200 dpi on A4: keeps the file size reasonable
    val bitmap = if (source.width > targetPixels) {
        Bitmap.createScaledBitmap(
            source,
            targetPixels,
            (source.height * targetPixels.toFloat() / source.width).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    val pageHeight = (bitmap.height * pageWidth.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)

    val pdf = PdfDocument()
    try {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        page.canvas.drawBitmap(
            bitmap,
            null,
            RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        pdf.finishPage(page)
        pdf.writeTo(out)
    } finally {
        pdf.close()
        if (bitmap !== source) bitmap.recycle()
    }
}
