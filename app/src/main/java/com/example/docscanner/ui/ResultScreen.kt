package com.example.docscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.docscanner.ScanViewModel
import com.example.docscanner.cv.ScanFilter

/** Screen 3: the scanned page with 3 filters, OCR text, and export to PDF / Word. */
@Composable
fun ResultScreen(vm: ScanViewModel, onBack: () -> Unit, onNewScan: () -> Unit) {
    val state by vm.result.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .systemBarsPadding(),
    ) {
        if (state.image == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("لا توجد صفحة ممسوحة", textAlign = TextAlign.Center)
                Button(onClick = onNewScan, modifier = Modifier.padding(top = 12.dp)) { Text("العودة للكاميرا") }
            }
            return@Column
        }

        // Tabs: image | text.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabButton("الصورة", selected = tab == 0, modifier = Modifier.weight(1f)) { tab = 0 }
            TabButton("النص (OCR)", selected = tab == 1, modifier = Modifier.weight(1f)) { tab = 1 }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                ImagePane(state.image, Modifier.fillMaxSize())
            } else {
                TextPane(
                    text = state.ocrText,
                    done = state.ocrDone,
                    running = state.ocrRunning,
                    onTextChange = vm::onOcrTextChange,
                    onExtract = { vm.runOcr() },
                    onCopy = { copyToClipboard(context, state.ocrText) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.busy) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (tab == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScanFilter.entries.forEach { filter ->
                    val selected = state.filter == filter
                    if (selected) {
                        Button(onClick = { vm.setFilter(filter) }, modifier = Modifier.weight(1f)) {
                            Text(filter.label, fontSize = 13.sp, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { vm.setFilter(filter) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(filter.label, fontSize = 13.sp, maxLines = 1) }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onNewScan, modifier = Modifier.weight(1f)) {
                Text("مسح جديد", fontSize = 13.sp, maxLines = 1)
            }
            FilledTonalButton(
                onClick = vm::exportPdf,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("حفظ PDF", fontSize = 13.sp, maxLines = 1) }
            FilledTonalButton(
                onClick = vm::exportWord,
                enabled = !state.busy && !state.ocrRunning,
                modifier = Modifier.weight(1f),
            ) { Text("حفظ Word", fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/** The scanned page. Pinch to zoom, drag to move. */
@Composable
private fun ImagePane(bitmap: android.graphics.Bitmap?, modifier: Modifier) {
    if (bitmap == null) return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                    pan = if (zoom > 1f) pan + panChange else Offset.Zero
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = "الصفحة الممسوحة",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        )
    }
}

@Composable
private fun TextPane(
    text: String,
    done: Boolean,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onExtract: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier,
) {
    if (!done) {
        Column(
            modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "استخرج النص من الصفحة لتعديله أو حفظه كملف Word.",
                textAlign = TextAlign.Center,
            )
            Text(
                "ملاحظة: قارئ النصوص الحالي يدعم الحروف اللاتينية (الإنجليزية والفرنسية وغيرها) ولا يدعم العربية.",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = Color(0xFFB8C2CA),
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onExtract, enabled = !running, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (running) "جارٍ الاستخراج…" else "استخراج النص (OCR)")
            }
        }
    } else {
        Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = { Text("النص المستخرج (يمكنك تعديله)") },
                textStyle = TextStyle(textDirection = TextDirection.Content, fontSize = 15.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopy, enabled = text.isNotBlank()) { Text("نسخ النص") }
                OutlinedButton(onClick = onExtract, enabled = !running) {
                    Text(if (running) "جارٍ الاستخراج…" else "إعادة الاستخراج")
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("scan", text))
    Toast.makeText(context, "تم نسخ النص", Toast.LENGTH_SHORT).show()
}
