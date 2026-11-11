package com.example.docscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.docscanner.ScanViewModel
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the photo is drawn inside the canvas, so image coordinates can be mapped to the screen. */
private class ImageFit(val scale: Float, val left: Float, val top: Float) {
    fun toScreen(p: Offset) = Offset(left + p.x * scale, top + p.y * scale)
}

private fun computeFit(canvas: Size, imageWidth: Int, imageHeight: Int, inset: Float): ImageFit {
    val availableW = (canvas.width - 2 * inset).coerceAtLeast(1f)
    val availableH = (canvas.height - 2 * inset).coerceAtLeast(1f)
    val scale = min(availableW / imageWidth, availableH / imageHeight)
    val left = (canvas.width - imageWidth * scale) / 2f
    val top = (canvas.height - imageHeight * scale) / 2f
    return ImageFit(scale, left, top)
}

/** Screen 2: shows the photo with the detected document outline; the 4 corners can be dragged. */
@Composable
fun CropScreen(vm: ScanViewModel, onBack: () -> Unit, onConfirmed: () -> Unit) {
    val state by vm.crop.collectAsStateWithLifecycle()
    var processing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .systemBarsPadding(),
    ) {
        Text(
            text = when {
                state.bitmap == null -> "تحليل الصورة"
                state.autoDetected -> "تم اكتشاف المستند — اسحب النقاط لضبط الحدود"
                else -> "لم يتم اكتشاف الحدود تلقائيًا — اسحب النقاط الأربع إلى أركان المستند"
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val bitmap = state.bitmap
            when {
                state.loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("جارٍ تحليل الصورة…")
                }

                state.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error ?: "", textAlign = TextAlign.Center)
                    Button(onClick = onBack) { Text("رجوع") }
                }

                bitmap != null && state.corners.size == 4 -> CropCanvas(
                    bitmap = bitmap,
                    initialCorners = state.corners,
                    resetToken = state.resetToken,
                    onCornersChange = vm::updateCorners,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("لا توجد صورة لمعالجتها", textAlign = TextAlign.Center)
                    Button(onClick = onBack) { Text("رجوع للكاميرا") }
                }
            }

            if (processing) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xAA000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("جارٍ قص وتسوية المستند…")
                    }
                }
            }
        }

        val canContinue = state.bitmap != null && state.corners.size == 4 && !processing
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack, enabled = !processing, modifier = Modifier.weight(1f)) {
                Text("إعادة التصوير", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = vm::redetect,
                enabled = state.bitmap != null && !processing,
                modifier = Modifier.weight(1f),
            ) { Text("اكتشاف تلقائي", fontSize = 13.sp) }
            Button(
                onClick = {
                    processing = true
                    vm.confirmCrop { success ->
                        processing = false
                        if (success) onConfirmed()
                    }
                },
                enabled = canContinue,
                modifier = Modifier.weight(1f),
            ) { Text("متابعة ✓", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    initialCorners: List<Offset>,
    resetToken: Int,
    onCornersChange: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val insetPx = with(density) { 22.dp.toPx() }
    val handleRadius = with(density) { 11.dp.toPx() }
    val touchRadius = with(density) { 46.dp.toPx() }
    val loupeRadius = with(density) { 58.dp.toPx() }
    val strokeWidth = with(density) { 3.dp.toPx() }

    // The corners live here while dragging (updated immediately on every touch event) and are
    // reported to the ViewModel; they are re-initialised when "auto detect" is pressed again.
    var corners by remember(bitmap, resetToken) { mutableStateOf(initialCorners) }
    var dragIndex by remember { mutableIntStateOf(-1) }

    val imageW = bitmap.width
    val imageH = bitmap.height

    Canvas(
        modifier = modifier.pointerInput(bitmap, resetToken) {
            detectDragGestures(
                onDragStart = { start ->
                    val fit = computeFit(Size(size.width.toFloat(), size.height.toFloat()), imageW, imageH, insetPx)
                    var best = -1
                    var bestDistance = touchRadius
                    corners.forEachIndexed { index, corner ->
                        val distance = (fit.toScreen(corner) - start).getDistance()
                        if (distance <= bestDistance) {
                            best = index
                            bestDistance = distance
                        }
                    }
                    dragIndex = best
                },
                onDrag = { change, dragAmount ->
                    val index = dragIndex
                    if (index >= 0) {
                        change.consume()
                        val fit = computeFit(Size(size.width.toFloat(), size.height.toFloat()), imageW, imageH, insetPx)
                        val moved = corners[index] + dragAmount / fit.scale
                        val updated = corners.toMutableList()
                        updated[index] = Offset(
                            moved.x.coerceIn(0f, imageW.toFloat()),
                            moved.y.coerceIn(0f, imageH.toFloat()),
                        )
                        corners = updated
                        onCornersChange(updated)
                    }
                },
                onDragEnd = { dragIndex = -1 },
                onDragCancel = { dragIndex = -1 },
            )
        },
    ) {
        val fit = computeFit(size, imageW, imageH, insetPx)

        // The photo.
        drawImage(
            image = image,
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize((imageW * fit.scale).roundToInt(), (imageH * fit.scale).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )

        // The 4-point polygon.
        val points = corners.map { fit.toScreen(it) }
        val outline = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }
        drawPath(outline, Color(0x3300E5FF))
        drawPath(outline, Color(0xFF00E5FF), style = Stroke(width = strokeWidth, join = StrokeJoin.Round))

        // The 4 drag handles.
        points.forEachIndexed { index, point ->
            drawCircle(Color.White, handleRadius + strokeWidth, point)
            drawCircle(if (index == dragIndex) Color(0xFFFFC400) else Color(0xFF00B8D4), handleRadius, point)
        }

        // Magnifier: while dragging, shows an enlarged view above the finger for precise placement.
        val active = dragIndex
        if (active in points.indices) {
            val finger = points[active]
            val x = finger.x.coerceIn(loupeRadius, size.width - loupeRadius)
            val above = finger.y - loupeRadius * 2.3f
            val y = if (above - loupeRadius >= 0f) above else finger.y + loupeRadius * 2.3f
            val center = Offset(x, y)
            val zoomedScale = fit.scale * 2.5f
            val target = corners[active]

            val circle = Path().apply { addOval(Rect(center, loupeRadius)) }
            clipPath(circle) {
                drawRect(Color.Black, topLeft = Offset(center.x - loupeRadius, center.y - loupeRadius), size = Size(loupeRadius * 2, loupeRadius * 2))
                withTransform({
                    translate(left = center.x - target.x * zoomedScale, top = center.y - target.y * zoomedScale)
                    scale(zoomedScale, zoomedScale, pivot = Offset.Zero)
                }) {
                    drawImage(image, filterQuality = FilterQuality.Medium)
                }
            }
            drawCircle(Color.White, loupeRadius, center, style = Stroke(width = strokeWidth))
            val arm = loupeRadius * 0.28f
            drawLine(Color(0xFFFF5252), Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = strokeWidth / 1.5f)
            drawLine(Color(0xFFFF5252), Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = strokeWidth / 1.5f)
        }
    }
}
