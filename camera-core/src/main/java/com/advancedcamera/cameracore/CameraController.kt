package com.advancedcamera.cameracore

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner

class CameraController(private val context: Context) {
    private var imageCapture: ImageCapture? = null

    fun bind(lifecycleOwner: LifecycleOwner, preview: Preview, selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA): ImageCapture? {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        imageCapture = capture
        return capture
    }

    fun currentImageCapture(): ImageCapture? = imageCapture
}
