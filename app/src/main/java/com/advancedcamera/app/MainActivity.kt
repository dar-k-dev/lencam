package com.advancedcamera.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraScreen()
            }
        }
    }
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCamera by remember { mutableStateOf(false) }
    var hasAudio by remember { mutableStateOf(false) }

    val permissionsLauncher = remember {
        mutableStateOf<(() -> Unit)?>(null)
    }

    val launcher = remember {
        (context as ComponentActivity).registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            hasCamera = result[Manifest.permission.CAMERA] == true
            hasAudio = result[Manifest.permission.RECORD_AUDIO] == true
        }
    }

    LaunchedEffect(Unit) {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraGranted || !audioGranted) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            hasCamera = true
            hasAudio = true
        }
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(hasCamera) {
        if (hasCamera) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView?.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidViewPreview(onReady = { pv -> previewView = pv })
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val cap = imageCapture ?: return@Button
                    val output = ImageCapture.OutputFileOptions.Builder(
                        createOutputPhotoFile()
                    ).build()
                    cap.takePicture(
                        output,
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onError(exception: ImageCaptureException) {
                                // TODO: show error UI/log
                            }
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                // TODO: post-saved UI/scan
                            }
                        }
                    )
                },
                modifier = Modifier
                    .padding(bottom = 32.dp)
                    .size(width = 160.dp, height = 56.dp)
            ) { Text("Capture") }
        }
    }
}

@Composable
fun AndroidViewPreview(onReady: (PreviewView) -> Unit) {
    AndroidViewWrapper(onReady = onReady)
}

@Composable
private fun AndroidViewWrapper(onReady: (PreviewView) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { onReady(it) }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun createOutputPhotoFile(): java.io.File {
    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            "AdvancedCamera"
        )
    } else {
        java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "AdvancedCamera"
        )
    }
    if (!dir.exists()) dir.mkdirs()
    val name = "IMG_" + System.currentTimeMillis() + ".jpg"
    return java.io.File(dir, name)
}
