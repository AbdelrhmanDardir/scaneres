package com.example.docscanner

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.docscanner.cv.DocumentDetector
import com.example.docscanner.cv.ImageProcessor
import com.example.docscanner.cv.ScanFilter
import com.example.docscanner.export.DOCX_MIME
import com.example.docscanner.export.PDF_MIME
import com.example.docscanner.export.exportToPdf
import com.example.docscanner.export.exportToWord
import com.example.docscanner.ocr.TextExtractor
import com.example.docscanner.util.BitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.io.File

/** One-shot message for the UI. If [openUri] is set, the snackbar offers an "Open" action. */
data class UiEvent(val message: String, val openUri: Uri? = null, val mimeType: String? = null)

data class CropState(
    val loading: Boolean = false,
    val bitmap: Bitmap? = null,
    /** Corners in bitmap pixel coordinates: TL, TR, BR, BL. */
    val corners: List<Offset> = emptyList(),
    val autoDetected: Boolean = true,
    /** Changes whenever the corners are reset from outside (e.g. "auto detect" pressed again). */
    val resetToken: Int = 0,
    val error: String? = null,
)

data class ResultState(
    val busy: Boolean = false,
    val filter: ScanFilter = ScanFilter.MAGIC_COLOR,
    val image: Bitmap? = null,
    val ocrText: String = "",
    val ocrDone: Boolean = false,
    val ocrRunning: Boolean = false,
)

/**
 * Shared by all three screens (camera → crop → result): keeps the scan in progress.
 * All heavy work (decoding, OpenCV, OCR, exporting) runs off the main thread.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _crop = MutableStateFlow(CropState())
    val crop: StateFlow<CropState> = _crop.asStateFlow()

    private val _result = MutableStateFlow(ResultState())
    val result: StateFlow<ResultState> = _result.asStateFlow()

    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** The cropped + flattened page, before any filter. */
    private var flattened: Bitmap? = null
    private var extractor: TextExtractor? = null
    private var loadJob: Job? = null
    private var filterJob: Job? = null

    init {
        // Camera photos are temporary: remove the ones older than a day.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                File(application.cacheDir, "scans").listFiles()
                    ?.filter { it.lastModified() < limit }
                    ?.forEach { it.delete() }
            }
        }
    }

    // ───────────────────────── Step 1: load the photo + detect the document ─────────────────────────

    fun loadImage(uri: Uri) {
        loadJob?.cancel()
        resetScan()
        _crop.value = CropState(loading = true)
        loadJob = viewModelScope.launch {
            try {
                val (bitmap, detection) = withContext(Dispatchers.Default) {
                    val loaded = BitmapLoader.load(getApplication(), uri)
                    loaded to DocumentDetector.detect(loaded)
                }
                _crop.value = CropState(
                    bitmap = bitmap,
                    corners = detection.corners.toOffsets(),
                    autoDetected = detection.found,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _crop.value = CropState(error = "تعذر فتح الصورة: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    /** Runs the automatic detection again and resets the corners. */
    fun redetect() {
        val bitmap = _crop.value.bitmap ?: return
        viewModelScope.launch {
            try {
                val detection = withContext(Dispatchers.Default) { DocumentDetector.detect(bitmap) }
                _crop.update {
                    it.copy(
                        corners = detection.corners.toOffsets(),
                        autoDetected = detection.found,
                        resetToken = it.resetToken + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                eventChannel.trySend(UiEvent("فشل الاكتشاف التلقائي: ${t.localizedMessage ?: ""}"))
            }
        }
    }

    fun updateCorners(corners: List<Offset>) {
        _crop.update { it.copy(corners = corners) }
    }

    // ───────────────────────── Step 2: flatten (perspective warp) + default filter ─────────────────────────

    fun confirmCrop(onFinished: (success: Boolean) -> Unit) {
        val crop = _crop.value
        val bitmap = crop.bitmap
        if (bitmap == null || crop.corners.size != 4) {
            onFinished(false)
            return
        }
        viewModelScope.launch {
            try {
                val points = crop.corners.map { Point(it.x.toDouble(), it.y.toDouble()) }
                val flat = withContext(Dispatchers.Default) { ImageProcessor.warp(bitmap, points) }
                val filter = ScanFilter.MAGIC_COLOR
                val shown = withContext(Dispatchers.Default) { ImageProcessor.applyFilter(flat, filter) }
                flattened = flat
                _result.value = ResultState(filter = filter, image = shown)
                onFinished(true)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                eventChannel.trySend(UiEvent("فشلت معالجة الصورة: ${t.localizedMessage ?: t.javaClass.simpleName}"))
                onFinished(false)
            }
        }
    }

    // ───────────────────────── Step 3: filters ─────────────────────────

    fun setFilter(filter: ScanFilter) {
        val flat = flattened ?: return
        if (filter == _result.value.filter && _result.value.image != null) return
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            _result.update { it.copy(busy = true, filter = filter) }
            try {
                val image = withContext(Dispatchers.Default) { ImageProcessor.applyFilter(flat, filter) }
                _result.update { it.copy(image = image, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _result.update { it.copy(busy = false) }
                eventChannel.trySend(UiEvent("تعذر تطبيق الفلتر: ${t.localizedMessage ?: ""}"))
            }
        }
    }

    // ───────────────────────── Step 4: OCR ─────────────────────────

    fun runOcr(onFinished: () -> Unit = {}) {
        val flat = flattened ?: return
        if (_result.value.ocrRunning) return
        viewModelScope.launch {
            try {
                val text = recognize(flat)
                if (text.isBlank()) {
                    eventChannel.trySend(
                        UiEvent("لم يتم العثور على نص. تنبيه: القراءة الحالية تدعم الحروف اللاتينية (الإنجليزية وغيرها) ولا تدعم العربية.")
                    )
                }
                onFinished()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                eventChannel.trySend(UiEvent("فشل استخراج النص: ${t.localizedMessage ?: t.javaClass.simpleName}"))
            }
        }
    }

    fun onOcrTextChange(text: String) {
        _result.update { it.copy(ocrText = text) }
    }

    private suspend fun recognize(flat: Bitmap): String {
        _result.update { it.copy(ocrRunning = true) }
        try {
            val text = (extractor ?: TextExtractor().also { extractor = it }).extract(flat)
            _result.update { it.copy(ocrText = text, ocrDone = true, ocrRunning = false) }
            return text
        } catch (t: Throwable) {
            _result.update { it.copy(ocrRunning = false) }
            throw t
        }
    }

    // ───────────────────────── Step 5: export ─────────────────────────

    /** Exports the (possibly edited) OCR text to Word. Runs OCR first if it has not been run yet. */
    fun exportWord() {
        val flat = flattened ?: return
        val state = _result.value
        if (state.busy || state.ocrRunning) return
        viewModelScope.launch {
            _result.update { it.copy(busy = true) }
            try {
                val text = if (state.ocrDone) _result.value.ocrText else recognize(flat)
                if (text.isBlank()) {
                    eventChannel.trySend(
                        UiEvent("لا يوجد نص لتصديره. تنبيه: القراءة الحالية تدعم الحروف اللاتينية ولا تدعم العربية.")
                    )
                } else {
                    exportToWord(text, getApplication()).fold(
                        onSuccess = { uri ->
                            eventChannel.trySend(UiEvent("تم حفظ ملف Word في: Documents/DocScanner", uri, DOCX_MIME))
                        },
                        onFailure = { eventChannel.trySend(UiEvent("فشل تصدير Word: ${it.localizedMessage ?: ""}")) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                eventChannel.trySend(UiEvent("فشل تصدير Word: ${t.localizedMessage ?: t.javaClass.simpleName}"))
            } finally {
                _result.update { it.copy(busy = false) }
            }
        }
    }

    fun exportPdf() {
        val image = _result.value.image ?: return
        if (_result.value.busy) return
        viewModelScope.launch {
            _result.update { it.copy(busy = true) }
            try {
                exportToPdf(image, getApplication()).fold(
                    onSuccess = { uri ->
                        eventChannel.trySend(UiEvent("تم حفظ ملف PDF في: Documents/DocScanner", uri, PDF_MIME))
                    },
                    onFailure = { eventChannel.trySend(UiEvent("فشل تصدير PDF: ${it.localizedMessage ?: ""}")) },
                )
            } finally {
                _result.update { it.copy(busy = false) }
            }
        }
    }

    // ───────────────────────── new scan ─────────────────────────

    fun startNewScan() {
        loadJob?.cancel()
        resetScan()
        _crop.value = CropState()
    }

    private fun resetScan() {
        filterJob?.cancel()
        flattened = null
        _result.value = ResultState()
    }

    override fun onCleared() {
        extractor?.close()
        super.onCleared()
    }

    private fun List<Point>.toOffsets(): List<Offset> = map { Offset(it.x.toFloat(), it.y.toFloat()) }
}
