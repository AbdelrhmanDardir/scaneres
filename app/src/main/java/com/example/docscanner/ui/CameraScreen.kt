package com.example.docscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private enum class FlashSetting(val label: String) {
    OFF("⚡ الفلاش: إيقاف"),
    AUTO("⚡ الفلاش: تلقائي"),
    ON("⚡ الفلاش: تشغيل"),
    TORCH("🔦 مصباح مستمر");

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

/**
 * Full-screen camera (CameraX): live preview, tap-to-focus (with continuous auto-focus),
 * flash control, capture button, and import from the gallery.
 * The photo is saved in the cache folder and its Uri is handed to [onImageReady].
 */
@Composable
fun CameraScreen(onImageReady: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    fun cameraGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(cameraGranted()) }
    var permissionAsked by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var flash by remember { mutableStateOf(FlashSetting.OFF) }
    var capturing by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionAsked = true
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImageReady(uri)
    }

    // First launch: ask for the camera permission.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The user may grant the permission from the system settings and come back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = cameraGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER   // show exactly what will be captured
        }
    }

    // Bind the camera to this screen's lifecycle (it closes by itself when the screen is left).
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            // 4:3 for both use cases: the preview and the saved photo show the same area.
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(resolution)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setResolutionSelector(resolution)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            imageCapture = capture
            cameraError = null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            camera = null
            imageCapture = null
            cameraError = "تعذر تشغيل الكاميرا: ${t.localizedMessage ?: t.javaClass.simpleName}"
        }
    }

    // Apply the flash setting whenever it (or the camera) changes.
    LaunchedEffect(camera, imageCapture, flash) {
        val cam = camera ?: return@LaunchedEffect
        val capture = imageCapture ?: return@LaunchedEffect
        capture.flashMode = when (flash) {
            FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
            FlashSetting.OFF, FlashSetting.TORCH -> ImageCapture.FLASH_MODE_OFF
        }
        if (cam.cameraInfo.hasFlashUnit()) {
            runCatching { cam.cameraControl.enableTorch(flash == FlashSetting.TORCH) }
        }
    }

    // The focus ring disappears by itself.
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900)
            focusPoint = null
        }
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        val file = File(File(context.cacheDir, "scans").apply { mkdirs() }, "scan_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        onImageReady(uri)
                    } catch (t: Throwable) {
                        Toast.makeText(context, "تعذر حفظ الصورة: ${t.localizedMessage ?: ""}", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    file.delete()
                    Toast.makeText(context, "فشل التقاط الصورة: ${exception.localizedMessage ?: ""}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission && cameraError == null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Tap anywhere on the preview to focus there.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(camera) {
                        detectTapGestures { tap ->
                            val cam = camera ?: return@detectTapGestures
                            runCatching {
                                val point = previewView.meteringPointFactory.createPoint(tap.x, tap.y)
                                val action = FocusMeteringAction.Builder(
                                    point,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                                )
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                cam.cameraControl.startFocusAndMetering(action)
                            }
                            focusPoint = tap
                        }
                    },
            )

            focusPoint?.let { point ->
                val half = with(density) { 34.dp.roundToPx() }
                Box(
                    Modifier
                        .offset { IntOffset(point.x.roundToInt() - half, point.y.roundToInt() - half) }
                        .size(68.dp)
                        .border(2.dp, Color(0xFFFFD54F), CircleShape),
                )
            }

            // Top bar: flash.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    TextButton(onClick = { flash = flash.next() }) {
                        Text(flash.label, color = Color.White, fontSize = 15.sp)
                    }
                } else {
                    Text("ضع المستند على سطح مستوٍ وبإضاءة جيدة", color = Color.White, fontSize = 14.sp)
                }
            }

            // Bottom bar: gallery | capture | spacer.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp, top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("ضع المستند داخل الإطار ثم اضغط للتصوير", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.size(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.width(96.dp),
                    ) {
                        Text("🖼 المعرض", color = Color.White, fontSize = 14.sp)
                    }
                    CaptureButton(enabled = imageCapture != null && !capturing, onClick = ::takePhoto)
                    Spacer(Modifier.width(96.dp))
                }
            }
        } else {
            // No permission, or the camera could not start.
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = cameraError ?: "يحتاج التطبيق إلى إذن الكاميرا لتصوير المستندات.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (!hasPermission) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("السماح بالكاميرا")
                    }
                    if (permissionAsked) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            },
                        ) { Text("فتح إعدادات التطبيق") }
                    }
                }
                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                ) { Text("اختيار صورة من المعرض") }
            }
        }
    }
}

@Composable
private fun CaptureButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color(0xFF8A8A8A))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "التقاط صورة" },
    )
}
