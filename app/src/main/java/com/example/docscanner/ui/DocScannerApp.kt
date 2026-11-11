package com.example.docscanner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.docscanner.ScanViewModel

private object Routes {
    const val CAMERA = "camera"
    const val CROP = "crop"
    const val RESULT = "result"
}

/** Root of the app: navigation (camera → crop → result) + the snackbar for messages. */
@Composable
fun DocScannerApp(vm: ScanViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Messages coming from the ViewModel (errors, "file saved" with an Open button).
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val outcome = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = if (event.openUri != null) "فتح" else null,
                duration = SnackbarDuration.Long,
            )
            if (outcome == SnackbarResult.ActionPerformed && event.openUri != null) {
                openFile(context, event.openUri, event.mimeType)
            }
        }
    }

    // The interface is in Arabic, so it is always laid out right-to-left.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize()) {
                NavHost(navController = navController, startDestination = Routes.CAMERA) {
                    composable(Routes.CAMERA) {
                        CameraScreen(
                            onImageReady = { uri ->
                                vm.loadImage(uri)
                                navController.navigate(Routes.CROP) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Routes.CROP) {
                        CropScreen(
                            vm = vm,
                            onBack = { navController.popBackStack() },
                            onConfirmed = { navController.navigate(Routes.RESULT) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.RESULT) {
                        ResultScreen(
                            vm = vm,
                            onBack = { navController.popBackStack() },
                            onNewScan = {
                                navController.popBackStack(Routes.CAMERA, inclusive = false)
                                vm.startNewScan()
                            },
                        )
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 88.dp),
                )
            }
        }
    }
}

private fun openFile(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "لا يوجد تطبيق على هاتفك لفتح هذا الملف. الملف محفوظ في مجلد Documents/DocScanner.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
