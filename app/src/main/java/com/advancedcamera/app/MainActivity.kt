package com.advancedcamera.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.hardware.camera2.CaptureRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraScreen(activity = this)
            }
        }
    }
}

@Composable
fun CameraScreen(activity: ComponentActivity) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCamera by remember { mutableStateOf(false) }
    var hasAudio by remember { mutableStateOf(false) }

    val launcher = remember {
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            hasCamera = result[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            hasAudio = result[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
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
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var isHdrEnabled by remember { mutableStateOf(true) }
    var isRecording by remember { mutableStateOf(false) }
    var zebraOn by remember { mutableStateOf(true) }
    var peakingOn by remember { mutableStateOf(true) }

    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    // Histogram state (256 bins)
    val histogram = remember { IntArray(256) }

    // Downsampled overlays
    val gridW = 64
    val gridH = 36
    val zebraGrid = remember { BooleanArray(gridW * gridH) }
    val peakingGrid = remember { FloatArray(gridW * gridH) }

    // Controls
    var zoomRatio by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(5f) }
    var evIndex by remember { mutableStateOf(0) }
    var evLower by remember { mutableStateOf(0) }
    var evUpper by remember { mutableStateOf(0) }

    LaunchedEffect(hasCamera) {
        if (hasCamera) {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also { p ->
                p.setSurfaceProvider(previewView?.surfaceProvider)
            }
            val cap = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(100)
                .build()

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { a ->
                    a.setAnalyzer(executor) { img ->
                        computeHistogram(img, histogram)
                        computeZebraAndPeaking(img, zebraGrid, peakingGrid, gridW, gridH)
                        img.close()
                    }
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD),
                        FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                    )
                )
                .build()
            val vcap = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    cap,
                    vcap,
                    analyzer
                )
                imageCapture = cap
                videoCapture = vcap

                // Enable video stabilization via Camera2 interop when available
                camera?.let { cam ->
                    val c2 = Camera2CameraControl.from(cam.cameraControl)
                    val opts = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        .build()
                    runCatching { c2.setCaptureRequestOptions(opts) }
                }

                // Initialize controls
                camera?.cameraInfo?.zoomState?.value?.let {
                    zoomRatio = it.zoomRatio
                    maxZoom = it.maxZoomRatio
                }
                camera?.cameraInfo?.exposureState?.let {
                    evLower = it.exposureCompensationRange.lower
                    evUpper = it.exposureCompensationRange.upper
                    evIndex = it.exposureCompensationIndex
                }
            } catch (_: Exception) { }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidViewPreview(onReady = { pv -> previewView = pv })

        // Overlays
        Box(modifier = Modifier.fillMaxSize()) {
            HistogramView(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(width = 200.dp, height = 100.dp),
                histogram = histogram
            )

            if (zebraOn) ZebraOverlay(gridW = gridW, gridH = gridH, zebraGrid = zebraGrid)
            if (peakingOn) PeakingOverlay(gridW = gridW, gridH = gridH, peakingGrid = peakingGrid)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = isHdrEnabled, onClick = { isHdrEnabled = !isHdrEnabled }, label = { Text("HDR") })
                FilterChip(selected = zebraOn, onClick = { zebraOn = !zebraOn }, label = { Text("Zebra") })
                FilterChip(selected = peakingOn, onClick = { peakingOn = !peakingOn }, label = { Text("Peaking") })
                FilterChip(selected = isRecording, onClick = {}, label = { Text(if (isRecording) "REC" else "IDLE") })
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Zoom control
                Text("Zoom: ${"%.2f".format(zoomRatio)}x", color = Color.White)
                Slider(
                    value = zoomRatio,
                    onValueChange = {
                        zoomRatio = it
                        camera?.cameraControl?.setZoomRatio(it)
                    },
                    valueRange = 1f..max(1f, maxZoom),
                    steps = 10,
                    modifier = Modifier.fillMaxWidth(0.9f)
                )

                // Exposure compensation control
                Text("EV: $evIndex (range $evLower..$evUpper)", color = Color.White)
                Slider(
                    value = evIndex.toFloat(),
                    onValueChange = { v ->
                        evIndex = v.toInt()
                        camera?.cameraControl?.setExposureCompensationIndex(evIndex)
                    },
                    valueRange = evLower.toFloat()..evUpper.toFloat(),
                    steps = max(0, (evUpper - evLower) - 1),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch { handleVideoToggle(context, hasAudio, videoCapture, isRecordingSetter = { isRecording = it }) }
                        }, modifier = Modifier.size(width = 140.dp, height = 56.dp)
                    ) { Text(if (isRecording) "Stop" else "Record") }

                    Button(
                        onClick = {
                            scope.launch {
                                val cap = imageCapture ?: return@launch
                                val cam = camera
                                if (isHdrEnabled && cam != null) {
                                    captureHdrBurstAndSave(context, cam, cap, Executors.newSingleThreadExecutor())
                                } else {
                                    captureSingleAndSave(context, cap, Executors.newSingleThreadExecutor())
                                }
                            }
                        }, modifier = Modifier.size(width = 140.dp, height = 56.dp)
                    ) { Text("Capture") }
                }
            }
        }
    }
}

private fun computeHistogram(image: androidx.camera.core.ImageProxy, bins: IntArray) {
    java.util.Arrays.fill(bins, 0)
    val planes = image.planes
    if (planes.isEmpty()) return
    val yBuf = planes[0].buffer
    val rowStride = planes[0].rowStride
    val pixelStride = planes[0].pixelStride
    val width = image.width
    val height = image.height

    val row = ByteArray(rowStride)
    var y = 0
    while (y < height) {
        yBuf.position(y * rowStride)
        yBuf.get(row, 0, rowStride)
        var x = 0
        var idx = 0
        while (x < width) {
            val v = row[idx].toInt() and 0xFF
            bins[v] = bins[v] + 1
            x += pixelStride
            idx += pixelStride
        }
        y++
    }
}

private fun computeZebraAndPeaking(
    image: androidx.camera.core.ImageProxy,
    zebraGrid: BooleanArray,
    peakingGrid: FloatArray,
    gridW: Int,
    gridH: Int,
    zebraThreshold: Int = 250,
    peakingThreshold: Float = 20f
) {
    java.util.Arrays.fill(zebraGrid, false)
    java.util.Arrays.fill(peakingGrid, 0f)

    val planes = image.planes
    if (planes.isEmpty()) return
    val yPlane = planes[0]
    val buf = yPlane.buffer
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val width = image.width
    val height = image.height

    val cellW = max(1, width / gridW)
    val cellH = max(1, height / gridH)

    // Simple gradient magnitude for peaking
    fun yAt(px: Int, py: Int): Int {
        val clampedX = min(width - 1, max(0, px))
        val clampedY = min(height - 1, max(0, py))
        val pos = clampedY * rowStride + clampedX * pixelStride
        return (buf.get(pos).toInt() and 0xFF)
    }

    var gy = 0
    while (gy < gridH) {
        var gx = 0
        while (gx < gridW) {
            val startX = gx * cellW
            val startY = gy * cellH
            val endX = min(width, startX + cellW)
            val endY = min(height, startY + cellH)

            var sum = 0L
            var count = 0
            var gradAccum = 0f

            var y = startY
            while (y < endY) {
                var x = startX
                val base = y * rowStride
                while (x < endX) {
                    val pos = base + x * pixelStride
                    val v = buf.get(pos).toInt() and 0xFF
                    sum += v

                    // Sobel 3x3 approximation
                    val gxv = (-1 * yAt(x - 1, y - 1) + 1 * yAt(x + 1, y - 1)
                            -2 * yAt(x - 1, y) + 2 * yAt(x + 1, y)
                            -1 * yAt(x - 1, y + 1) + 1 * yAt(x + 1, y + 1))
                    val gyv = (1 * yAt(x - 1, y - 1) + 2 * yAt(x, y - 1) + 1 * yAt(x + 1, y - 1)
                            -1 * yAt(x - 1, y + 1) - 2 * yAt(x, y + 1) - 1 * yAt(x + 1, y + 1))
                    val mag = kotlin.math.sqrt((gxv * gxv + gyv * gyv).toFloat())
                    gradAccum += mag

                    count++
                    x += 2 // subsample within cell
                }
                y += 2
            }

            val avg = if (count > 0) (sum / count).toInt() else 0
            zebraGrid[gy * gridW + gx] = avg >= zebraThreshold
            peakingGrid[gy * gridW + gx] = gradAccum / max(1, count)

            gx++
        }
        gy++
    }
}

@Composable
private fun HistogramView(modifier: Modifier = Modifier, histogram: IntArray) {
    val maxVal = (histogram.maxOrNull() ?: 1).toFloat()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w / histogram.size
        for (i in histogram.indices) {
            val v = histogram[i] / maxVal
            val barH = h * v
            drawRect(
                color = Color(0xFFFFCC00),
                topLeft = androidx.compose.ui.geometry.Offset(x = i * barW, y = h - barH),
                size = androidx.compose.ui.geometry.Size(width = barW, height = barH)
            )
        }
        drawRect(color = Color.White, style = Stroke(width = 1f), size = androidx.compose.ui.geometry.Size(w, h))
    }
}

@Composable
private fun ZebraOverlay(gridW: Int, gridH: Int, zebraGrid: BooleanArray) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellW = size.width / gridW
        val cellH = size.height / gridH
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                if (zebraGrid[gy * gridW + gx]) {
                    val left = gx * cellW
                    val top = gy * cellH
                    drawRect(
                        color = Color(1f, 1f, 1f, 0.08f),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(cellW, cellH)
                    )
                    // Diagonal lines
                    var x = left
                    while (x < left + cellW) {
                        drawLine(
                            color = Color(1f, 1f, 1f, 0.4f),
                            start = androidx.compose.ui.geometry.Offset(x, top),
                            end = androidx.compose.ui.geometry.Offset(x - cellH, top + cellH),
                            strokeWidth = 1.2f
                        )
                        x += 8f
                    }
                }
            }
        }
    }
}

@Composable
private fun PeakingOverlay(gridW: Int, gridH: Int, peakingGrid: FloatArray, threshold: Float = 24f) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellW = size.width / gridW
        val cellH = size.height / gridH
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val mag = peakingGrid[gy * gridW + gx]
                if (mag >= threshold) {
                    val left = gx * cellW
                    val top = gy * cellH
                    drawRect(
                        color = Color(0f, 1f, 0f, 0.20f),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(cellW, cellH)
                    )
                }
            }
        }
    }
}

private suspend fun handleVideoToggle(
    context: android.content.Context,
    hasAudio: Boolean,
    videoCapture: VideoCapture<Recorder>?,
    isRecordingSetter: (Boolean) -> Unit
) {
    if (videoCapture == null) return

    if (currentRecording == null) {
        val name = timestamp("VID") + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AdvancedCamera")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
        currentRecording = (videoCapture.output).prepareRecording(context, options).apply {
            if (hasAudio) withAudioEnabled()
        }.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> isRecordingSetter(true)
                is VideoRecordEvent.Finalize -> {
                    isRecordingSetter(false)
                    currentRecording = null
                }
                else -> {}
            }
        }
    } else {
        currentRecording?.stop()
        currentRecording = null
    }
}

private var currentRecording: Recording? = null

private suspend fun captureSingleAndSave(
    context: android.content.Context,
    imageCapture: ImageCapture,
    executor: ExecutorService
) {
    val name = timestamp("IMG") + ".jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AdvancedCamera")
        }
    }
    val output = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    suspendCancellableCoroutine<Unit> { cont ->
        imageCapture.takePicture(
            output,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.cancel(exception)
                }
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(Unit, onCancellation = null)
                }
            }
        )
    }
}

private suspend fun captureHdrBurstAndSave(
    context: android.content.Context,
    camera: Camera,
    imageCapture: ImageCapture,
    executor: ExecutorService
) {
    val range: Range<Int> = camera.cameraInfo.exposureState.exposureCompensationRange
    val stepNeg2 = clampExposure(range, -2)
    val step0 = clampExposure(range, 0)
    val stepPos2 = clampExposure(range, +2)

    val tempFiles = mutableListOf<File>()
    for (ev in listOf(stepNeg2, step0, stepPos2)) {
        camera.cameraControl.setExposureCompensationIndex(ev)
        val f = File.createTempFile("cap_", ".jpg", context.cacheDir)
        tempFiles += f
        val options = ImageCapture.OutputFileOptions.Builder(f).build()
        suspendCancellableCoroutine<Unit> { cont ->
            imageCapture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.cancel(exception)
                    }
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(Unit, onCancellation = null)
                    }
                }
            )
        }
    }

    camera.cameraControl.setExposureCompensationIndex(step0)

    val bitmaps = withContext(Dispatchers.IO) {
        tempFiles.mapNotNull { f ->
            runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        }
    }

    if (bitmaps.isNotEmpty()) {
        val merged = simpleExposureMerge(bitmaps)
        val name = timestamp("HDR") + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AdvancedCamera")
            }
        }
        val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {
                    merged.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
                }
            }
        }
    }

    tempFiles.forEach { runCatching { it.delete() } }
}

private fun simpleExposureMerge(bitmaps: List<android.graphics.Bitmap>): android.graphics.Bitmap {
    val base = bitmaps[0]
    val merged = android.graphics.Bitmap.createBitmap(base.width, base.height, android.graphics.Bitmap.Config.ARGB_8888)
    val pixels = IntArray(base.width * base.height)
    val accumR = FloatArray(pixels.size)
    val accumG = FloatArray(pixels.size)
    val accumB = FloatArray(pixels.size)

    for (bmp in bitmaps) {
        if (bmp.width != base.width || bmp.height != base.height) continue
        bmp.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            accumR[i] += ((c shr 16) and 0xFF)
            accumG[i] += ((c shr 8) and 0xFF)
            accumB[i] += (c and 0xFF)
            i++
        }
    }
    val n = bitmaps.size.toFloat()
    var i = 0
    while (i < pixels.size) {
        val r = (accumR[i] / n).toInt().coerceIn(0, 255)
        val g = (accumG[i] / n).toInt().coerceIn(0, 255)
        val b = (accumB[i] / n).toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        i++
    }
    merged.setPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
    toneMapInPlace(merged)
    return merged
}

private fun clampExposure(range: Range<Int>?, steps: Int): Int {
    if (range == null) return 0
    return max(range.lower, min(range.upper, steps))
}

private fun toneMapInPlace(bmp: android.graphics.Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    var i = 0
    fun curve(x: Int): Int {
        val xf = x / 255f
        val y = (1f / (1f + kotlin.math.exp(-6f * (xf - 0.5f))))
        return (y * 255f).toInt().coerceIn(0, 255)
    }
    while (i < pixels.size) {
        val c = pixels[i]
        val r = curve((c shr 16) and 0xFF)
        val g = curve((c shr 8) and 0xFF)
        val b = curve(c and 0xFF)
        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        i++
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
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

private fun timestamp(prefix: String): String {
    return prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
