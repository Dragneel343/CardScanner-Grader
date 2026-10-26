package com.inspectorade.cardscanner

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import com.inspectorade.cardscanner.capture.CardDetector
import com.inspectorade.cardscanner.databinding.ActivityCaptureBinding
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.sqrt

class CaptureActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var rawCaptureSupported = false
    private val side: String by lazy { intent.getStringExtra(EXTRA_SIDE) ?: SIDE_FRONT }

    // Updated from the Camera2 session capture callback as frames stream in,
    // so we know what focus distance the lens converged on when we lock it
    // (CONTROL_AF_MODE_OFF requires an explicit LENS_FOCUS_DISTANCE).
    @Volatile private var lastFocusDistance: Float? = null
    private var is3ALocked = false
    private var focusMeteringInFlight = false
    private var meteringStatusText = "Tap card to focus & lock"

    private lateinit var sensorManager: SensorManager
    private var levelSensor: Sensor? = null
    private var tiltXDegrees = 0f
    private var tiltYDegrees = 0f
    private var overallTiltDegrees = 0f
    private var isLevel = false

    private val handler = Handler(Looper.getMainLooper())
    private var autoCaptureEnabled = false
    private var autoCaptureArmed = true
    private var autoFocusRunnable: Runnable? = null
    private var autoCaptureRunnable: Runnable? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val openCvReady: Boolean by lazy { OpenCVLoader.initLocal() }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.statusText.text = getString(R.string.camera_permission_rationale)
            }
        }

    // Retake just returns here (non-OK result) so the camera stays live for another
    // attempt; Confirm returns RESULT_OK, which we forward up to whoever launched us
    // (e.g. CardSidesActivity marking this side done) by finishing with the same result.
    private val reviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.overlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAndLockOn(event.x, event.y)
            }
            true
        }
        binding.autoCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            autoCaptureEnabled = checked
            if (!checked) {
                cancelPendingAutoActions()
            } else {
                maybeHandleAutoCapture()
            }
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        levelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Edge-to-edge is enforced on modern Android, so the preview draws full-bleed
        // behind the system bars; push the controls in from under the gesture bar
        // and status bar so they stay tappable/visible.
        val captureButtonBaseBottomMargin = binding.captureButton.marginBottom
        val backButtonBaseTopMargin = binding.backButton.marginTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.captureButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = captureButtonBaseBottomMargin + systemBars.bottom
            }
            binding.backButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = backButtonBaseTopMargin + systemBars.top
            }
            insets
        }

        // Always jump straight to Home, regardless of how many capture/review
        // screens deep the front/back flow currently is (plain finish() would
        // otherwise just pop back one step, e.g. onto the front capture screen
        // while capturing the back).
        binding.backButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        levelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        cancelPendingAutoActions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    /**
     * Tilt angle from "flat" (camera axis vertical) computed from the gravity vector in
     * device coordinates — this is orientation-agnostic to whether the phone is held
     * screen-up or screen-down above the card, since it only depends on how much gravity
     * leaks into the device's X/Y axes rather than being purely along Z.
     */
    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        tiltXDegrees = Math.toDegrees(atan2(gx.toDouble(), kotlin.math.abs(gz).toDouble())).toFloat()
        tiltYDegrees = Math.toDegrees(atan2(gy.toDouble(), kotlin.math.abs(gz).toDouble())).toFloat()
        overallTiltDegrees = Math.toDegrees(
            atan2(sqrt((gx * gx + gy * gy).toDouble()), kotlin.math.abs(gz).toDouble())
        ).toFloat()
        isLevel = overallTiltDegrees <= LEVEL_TOLERANCE_DEGREES

        binding.overlayView.updateTilt(tiltXDegrees, tiltYDegrees, isLevel)
        refreshStatusText()
        maybeHandleAutoCapture()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        binding.statusText.text = ""
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
                    }
                }
            )
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // RAW/DNG output preserves sensor data untouched by the phone's noise
            // reduction, tone mapping, and sharpening, which surface-grading
            // depends on (see raw_capture_requirement project memory). Fall back
            // to JPEG-only on devices/cameras that don't support it.
            val cameraInfo = cameraSelector.filter(cameraProvider.availableCameraInfos).firstOrNull()
            rawCaptureSupported = cameraInfo?.let {
                ImageCapture.getImageCaptureCapabilities(it).supportedOutputFormats
                    .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            } ?: false

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .apply {
                    if (rawCaptureSupported) {
                        setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                    }
                }
                .build()
            imageCapture = capture

            // Live card-edge detection (CardDetector) drives the dashed outline on
            // CardOutlineOverlayView. Capped to a modest analysis resolution since
            // it runs OpenCV contour detection on every frame.
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            if (openCvReady) {
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrameForCardOutline(imageProxy) }
            } else {
                Log.w(TAG, "OpenCV failed to initialize; live card-outline detection disabled")
            }

            try {
                cameraProvider.unbindAll()
                is3ALocked = false
                lastFocusDistance = null

                // A shared ViewPort keeps Preview and ImageAnalysis showing the exact
                // same field of view, so a point found in an analysis frame maps onto
                // the preview with a straightforward crop-rect-based transform (see
                // analyzeFrameForCardOutline) instead of having to reconcile two
                // independently-cropped streams.
                val useCaseGroupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageAnalysis)
                    .addUseCase(capture)
                binding.previewView.viewPort?.let { useCaseGroupBuilder.setViewPort(it) }

                camera = cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroupBuilder.build())
                meteringStatusText = "Tap card to focus & lock"
                refreshStatusText()
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
                binding.statusText.text = "Camera init failed: ${exc.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshStatusText() {
        val sideLabel = if (side == SIDE_BACK) "BACK" else "FRONT"
        val rawStatus = if (rawCaptureSupported) "RAW: on" else "RAW: unsupported, using JPEG"
        val tiltStatus = if (isLevel) "Level ✓" else String.format(Locale.US, "Tilt %.0f°", overallTiltDegrees)
        binding.statusText.text = "$sideLabel · $rawStatus · $tiltStatus · $meteringStatusText"
    }

    /** Runs on cameraExecutor (background thread) — must always close imageProxy. */
    private fun analyzeFrameForCardOutline(imageProxy: ImageProxy) {
        try {
            val gray = yPlaneToMat(imageProxy)
            val corners = try {
                CardDetector.detect(gray)
            } finally {
                gray.release()
            }

            val quad = corners?.let { mapToViewQuad(it, imageProxy) }
            handler.post { binding.overlayView.updateDetectedQuad(quad) }
        } catch (exc: Exception) {
            Log.e(TAG, "Card-outline analysis failed", exc)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * The Y (luminance) plane alone is enough for edge/contour detection, avoiding
     * a full YUV->RGB conversion. Row stride can exceed the true image width
     * (alignment padding), so crop that off to keep pixel coordinates consistent
     * with imageProxy.cropRect, which mapToViewQuad relies on.
     */
    private fun yPlaneToMat(imageProxy: ImageProxy): Mat {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height

        val paddedMat = Mat(height, rowStride, CvType.CV_8UC1)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        paddedMat.put(0, 0, bytes)

        if (rowStride == width) return paddedMat

        val cropped = Mat(paddedMat, org.opencv.core.Rect(0, 0, width, height)).clone()
        paddedMat.release()
        return cropped
    }

    /**
     * Maps detected corner points (in the analysis frame's pixel coordinates) onto
     * CardOutlineOverlayView's coordinate space (same as PreviewView's, since the
     * overlay is laid out to exactly match it). Uses the crop-rect + rotation
     * technique from CameraX's "Transform output" guide: since a shared ViewPort
     * keeps Preview and ImageAnalysis showing the same field of view, cropRect's
     * four corners map directly onto the PreviewView's displayed bounds.
     */
    private fun mapToViewQuad(points: Array<Point>, imageProxy: ImageProxy): Array<Pair<Float, Float>> {
        val cropRect = imageProxy.cropRect
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val previewWidth = binding.previewView.width.toFloat()
        val previewHeight = binding.previewView.height.toFloat()

        val source = floatArrayOf(
            cropRect.left.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.top.toFloat(),
            cropRect.right.toFloat(), cropRect.bottom.toFloat(),
            cropRect.left.toFloat(), cropRect.bottom.toFloat()
        )
        val destination = floatArrayOf(
            0f, 0f,
            previewWidth, 0f,
            previewWidth, previewHeight,
            0f, previewHeight
        )

        // Destination vertices need to be shifted based on the rotation CameraX
        // says is needed to correct the image for display.
        val vertexSize = 2
        val shiftOffset = (rotationDegrees / 90) * vertexSize
        val rotated = destination.clone()
        for (toIndex in source.indices) {
            val fromIndex = (toIndex + shiftOffset) % source.size
            destination[toIndex] = rotated[fromIndex]
        }

        val matrix = Matrix()
        matrix.setPolyToPoly(source, 0, destination, 0, 4)

        val pts = FloatArray(8)
        for (i in points.indices) {
            pts[i * 2] = points[i].x.toFloat()
            pts[i * 2 + 1] = points[i].y.toFloat()
        }
        matrix.mapPoints(pts)

        return Array(4) { i -> Pair(pts[i * 2], pts[i * 2 + 1]) }
    }

    /**
     * Tapping the card runs one-shot AF/AE/AWB convergence at that point, then freezes
     * all three so every frame in a capture (and across repeated captures) shares
     * identical focus/exposure/white-balance — required for the multi-frame alignment
     * and glare-fusion techniques in the surface-grading pipeline (see
     * image_alignment_pipeline / surface_grading_approach project memory). A second tap
     * unlocks and re-targets wherever the user taps next.
     */
    private fun focusAndLockOn(x: Float, y: Float) {
        val cam = camera ?: return
        if (focusMeteringInFlight) return

        if (is3ALocked) {
            unlock3A(cam)
        }

        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).disableAutoCancel().build()

        focusMeteringInFlight = true
        meteringStatusText = "Focusing…"
        refreshStatusText()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            focusMeteringInFlight = false
            try {
                val result = future.get()
                if (result.isFocusSuccessful) {
                    lock3A(cam)
                } else {
                    meteringStatusText = "Focus failed — tap again"
                    refreshStatusText()
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Focus/metering failed", exc)
                meteringStatusText = "Focus error — tap again"
                refreshStatusText()
            }
            maybeHandleAutoCapture()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun lock3A(cam: Camera) {
        val optionsBuilder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)

        // Pin the lens at its converged distance; without this, CONTROL_AF_MODE_OFF
        // would freeze focus wherever it happened to be, not where we just focused.
        lastFocusDistance?.let { distance ->
            optionsBuilder
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        }

        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(optionsBuilder.build())
        is3ALocked = true
        binding.overlayView.setLocked(true)
        meteringStatusText = "Locked (AE/AF/AWB) — tap to refocus"
        refreshStatusText()
        maybeHandleAutoCapture()
    }

    private fun unlock3A(cam: Camera) {
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            .build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options)
        is3ALocked = false
        binding.overlayView.setLocked(false)
        meteringStatusText = "Tap card to focus & lock"
        refreshStatusText()
    }

    /**
     * Auto-capture readiness is (for now) level + 3A-locked — once live edge/corner
     * detection exists, "card fully framed" becomes a third condition here. When level
     * but not yet locked, auto-focus is triggered at the guide center rather than
     * requiring a manual tap, since the card is expected to be framed there.
     *
     * Re-arming after a shot is edge-triggered on the ready state breaking (phone
     * tilted away or lock lost), not a timer — otherwise, since nothing else changes
     * while the phone sits still over the same card, a fixed cooldown just fires
     * again and again indefinitely instead of once per placement.
     */
    private fun maybeHandleAutoCapture() {
        // If the phone tilts away while still locked, it almost certainly means it's
        // being moved to a new card — drop the stale lock so leveling out again
        // re-focuses fresh instead of auto-capturing the new card with the old
        // card's focus/exposure.
        if (autoCaptureEnabled && !isLevel && is3ALocked) {
            camera?.let { unlock3A(it) }
        }

        if (!isLevel || !is3ALocked) {
            autoCaptureArmed = true
        }

        if (!autoCaptureEnabled || !autoCaptureArmed || !isLevel) {
            cancelPendingAutoActions()
            return
        }

        if (!is3ALocked) {
            cancelAutoCapture()
            if (!focusMeteringInFlight) {
                scheduleAutoFocus()
            }
        } else {
            cancelAutoFocus()
            scheduleAutoCapture()
        }
    }

    private fun scheduleAutoFocus() {
        if (autoFocusRunnable != null) return
        val runnable = Runnable {
            autoFocusRunnable = null
            if (autoCaptureEnabled && autoCaptureArmed && isLevel && !is3ALocked && !focusMeteringInFlight) {
                focusAndLockOn(binding.overlayView.width / 2f, binding.overlayView.height / 2f)
            }
        }
        autoFocusRunnable = runnable
        handler.postDelayed(runnable, AUTO_FOCUS_STABILITY_MS)
    }

    private fun scheduleAutoCapture() {
        if (autoCaptureRunnable != null) return
        val runnable = Runnable {
            autoCaptureRunnable = null
            if (autoCaptureEnabled && autoCaptureArmed && isLevel && is3ALocked) {
                autoCaptureArmed = false
                takePhoto()
            }
        }
        autoCaptureRunnable = runnable
        handler.postDelayed(runnable, AUTO_CAPTURE_STABILITY_MS)
    }

    private fun cancelAutoFocus() {
        autoFocusRunnable?.let { handler.removeCallbacks(it) }
        autoFocusRunnable = null
    }

    private fun cancelAutoCapture() {
        autoCaptureRunnable?.let { handler.removeCallbacks(it) }
        autoCaptureRunnable = null
    }

    private fun cancelPendingAutoActions() {
        cancelAutoFocus()
        cancelAutoCapture()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        if (rawCaptureSupported) {
            takeRawAndJpegPhoto(capture, name)
        } else {
            takeJpegOnlyPhoto(capture, name)
        }
    }

    private fun outputOptionsFor(name: String, mimeType: String): ImageCapture.OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CardScanner")
        }
        return ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    private fun takeRawAndJpegPhoto(capture: ImageCapture, name: String) {
        val rawOutputOptions = outputOptionsFor(name, "image/x-adobe-dng")
        val jpegOutputOptions = outputOptionsFor(name, "image/jpeg")

        // onImageSaved fires once per format, order not guaranteed; identify which
        // is which by the saved file's extension and wait for both before continuing.
        var savedCount = 0
        var jpegUri: android.net.Uri? = null
        var rawUri: android.net.Uri? = null

        capture.takePicture(
            rawOutputOptions,
            jpegOutputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "RAW+JPEG capture failed", exc)
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    savedCount++
                    // MediaStore content URIs (content://media/external/images/media/12345)
                    // don't carry the extension in the URI itself, so distinguish by the
                    // MIME type we set at insert time rather than string-matching the URI.
                    val uri = output.savedUri
                    if (uri != null && contentResolver.getType(uri) == "image/jpeg") {
                        jpegUri = uri
                    } else {
                        rawUri = uri
                    }
                    if (savedCount == 2) {
                        openReview(jpegUri, rawUri)
                    }
                }
            }
        )
    }

    private fun takeJpegOnlyPhoto(capture: ImageCapture, name: String) {
        capture.takePicture(
            outputOptionsFor(name, "image/jpeg"),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exc)
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    openReview(output.savedUri, null)
                }
            }
        )
    }

    private fun openReview(jpegUri: android.net.Uri?, rawUri: android.net.Uri?) {
        jpegUri ?: return
        reviewLauncher.launch(
            Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_JPEG_URI, jpegUri.toString())
                putExtra(ReviewActivity.EXTRA_SIDE, side)
                rawUri?.let { putExtra(ReviewActivity.EXTRA_RAW_URI, it.toString()) }
            }
        )
    }

    companion object {
        private const val TAG = "CaptureActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val LEVEL_TOLERANCE_DEGREES = 3.0f
        private const val AUTO_FOCUS_STABILITY_MS = 400L
        private const val AUTO_CAPTURE_STABILITY_MS = 500L

        const val EXTRA_SIDE = "extra_side"
        const val SIDE_FRONT = "FRONT"
        const val SIDE_BACK = "BACK"
    }
}
