package com.inspectorade.cardscanner

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import androidx.core.view.doOnLayout
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import com.inspectorade.cardscanner.capture.CaptureQuality
import com.inspectorade.cardscanner.capture.CardDetector
import com.inspectorade.cardscanner.databinding.ActivityCaptureBinding
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.surface.TiltScan
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.sqrt

class CaptureActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var rawCaptureSupported = false
    private val side: String by lazy { intent.getStringExtra(EXTRA_SIDE) ?: SIDE_FRONT }
    // A second photo of a side already graded, with the card turned 180° (see
    // CornersEdgesActivity): it goes straight back once reviewed.
    private val turned: Boolean by lazy { intent.getBooleanExtra(EXTRA_TURNED, false) }
    // A tilt scan of the surface instead of a photo (see TiltScan): frames of the
    // card at a spread of tilts, saved flattened to a folder that goes back as
    // EXTRA_SURFACE_DIR.
    private val surfaceScan: Boolean by lazy { intent.getBooleanExtra(EXTRA_SURFACE_SCAN, false) }
    @Volatile private var tiltScan: TiltScan? = null
    @Volatile private var scanDir: File? = null
    private var pendingScanStart = false
    private val scanFrameIndex = AtomicInteger(0)
    private val scanSavesInFlight = AtomicInteger(0)
    private val scanSaveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // The exposure auto-exposure last settled on, from the preview's capture
    // results: a scan swaps it for a shorter one of the same brightness.
    @Volatile private var lastExposureNs: Long? = null
    @Volatile private var lastSensitivity: Int? = null

    // Updated from the Camera2 session capture callback as frames stream in,
    // so we know what focus distance the lens converged on when we lock it
    // (CONTROL_AF_MODE_OFF requires an explicit LENS_FOCUS_DISTANCE).
    @Volatile private var lastFocusDistance: Float? = null
    private var is3ALocked = false
    private var focusMeteringInFlight = false
    private var meteringStatusText = "Tap card to focus & lock"

    private lateinit var sensorManager: SensorManager
    private var levelSensor: Sensor? = null
    @Volatile private var tiltXDegrees = 0f
    @Volatile private var tiltYDegrees = 0f
    private var overallTiltDegrees = 0f
    private var isLevel = false

    // Light, sharpness and steadiness checks (see CaptureQuality).
    private val quality = CaptureQuality()
    private var gyroSensor: Sensor? = null
    private var torchOn = false
    private var lastQualityLogMs = 0L

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
                setResult(RESULT_OK, result.data)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (surfaceScan) {
            binding.captureButton.setText(R.string.scan_start_button)
            binding.captureButton.setOnClickListener { startScanOrFocus() }
            binding.autoCaptureSwitch.visibility = View.GONE
            binding.overlayView.showLevel = false
        } else {
            binding.captureButton.setOnClickListener { takePhoto() }
        }
        binding.overlayView.setOnTouchListener { _, event ->
            // Focus and exposure stay locked for the whole scan.
            if (event.action == MotionEvent.ACTION_UP && tiltScan == null) {
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
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        binding.torchButton.setOnCheckedChangeListener { _, checked -> setTorch(checked) }

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
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        cancelPendingAutoActions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanSaveExecutor.shutdown()
    }

    /**
     * Tilt angle from "flat" (camera axis vertical) computed from the gravity vector in
     * device coordinates — this is orientation-agnostic to whether the phone is held
     * screen-up or screen-down above the card, since it only depends on how much gravity
     * leaks into the device's X/Y axes rather than being purely along Z.
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            quality.updateRotation(
                CaptureQuality.rotationSpeed(event.values[0], event.values[1], event.values[2]),
                SystemClock.elapsedRealtime(),
            )
            refreshQualityText()
            return
        }
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
        tiltScan?.let { scan ->
            val before = scan.pass
            if (scan.update(tiltXDegrees, tiltYDegrees, SystemClock.elapsedRealtime()) == TiltScan.Pass.DONE &&
                before != TiltScan.Pass.DONE
            ) {
                finishScan()
            }
        }
        refreshStatusText()
        maybeHandleAutoCapture()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        binding.statusText.text = ""
        // The shared ViewPort (see bindCamera) can only be taken once the preview
        // has a size. Binding before layout silently skipped it, so the preview,
        // live analysis, and saved photo each showed a different field of view.
        binding.previewView.doOnLayout { bindCamera() }
    }

    private fun bindCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val cameraInfo = cameraSelector.filter(cameraProvider.availableCameraInfos).firstOrNull()
            // A tilt scan brings the phone close and tilts it, and on phones whose
            // back camera is several working as one, that makes it hop between
            // them (main ↔ ultra-wide for close-ups) mid-scan: the frames change
            // lens, sharpness and color. So a scan streams from the main one only.
            val scanCameraId = if (surfaceScan) cameraInfo?.let { mainPhysicalCameraId(it) } else null

            val previewBuilder = Preview.Builder()
            scanCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
                        if (!is3ALocked) {
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastSensitivity = it }
                        }
                        quality.updateExposure(
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            result.get(CaptureResult.SENSOR_SENSITIVITY),
                        )
                    }
                }
            )
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // RAW/DNG output preserves sensor data untouched by the phone's noise
            // reduction, tone mapping, and sharpening, which surface-grading
            // depends on (see raw_capture_requirement project memory). Fall back
            // to JPEG-only on devices/cameras that don't support it.
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
            // A tilt scan keeps frames from this stream, so it asks for full color
            // at a resolution that puts the card near the standard flattened size.
            val analysisBuilder = ImageAnalysis.Builder()
            scanCameraId?.let { Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(it) }
            val imageAnalysis = analysisBuilder
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                if (surfaceScan) SCAN_FRAME_SIZE else Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .apply { if (surfaceScan) setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) }
                .build()
            if (openCvReady) {
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (surfaceScan) analyzeScanFrame(imageProxy) else analyzeFrameForCardOutline(imageProxy)
                }
            } else {
                Log.w(TAG, "OpenCV failed to initialize; live card-outline detection disabled")
            }

            try {
                cameraProvider.unbindAll()
                is3ALocked = false
                lastFocusDistance = null

                // A shared ViewPort keeps Preview, ImageAnalysis, and ImageCapture
                // showing the exact same field of view: a point found in an analysis
                // frame maps onto the preview with a straightforward crop-rect-based
                // transform (see analyzeFrameForCardOutline), and the saved photo is
                // what the user framed.
                val useCaseGroupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageAnalysis)
                if (!surfaceScan) useCaseGroupBuilder.addUseCase(capture)
                val viewPort = binding.previewView.viewPort
                if (viewPort != null) {
                    useCaseGroupBuilder.setViewPort(viewPort)
                } else {
                    Log.w(TAG, "PreviewView has no ViewPort; outline and photo may not match the preview")
                }

                camera = cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroupBuilder.build())
                // A lock set on an earlier capture screen (autofocus off at that
                // card's distance) stays on the camera; start from autofocus again.
                camera?.let { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() }
                binding.torchButton.visibility =
                    if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
                meteringStatusText = "Tap card to focus & lock"
                refreshStatusText()
            } catch (exc: Exception) {
                Log.e(TAG, "Camera binding failed", exc)
                binding.statusText.text = "Camera init failed: ${exc.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The main camera behind a back camera made of several (the one with the
     * largest sensor), or null if [cameraInfo] is a single camera.
     */
    private fun mainPhysicalCameraId(cameraInfo: androidx.camera.core.CameraInfo): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
        val manager = getSystemService(CameraManager::class.java)
        val physicalIds = manager.getCameraCharacteristics(Camera2CameraInfo.from(cameraInfo).cameraId).physicalCameraIds
        if (physicalIds.size < 2) return null
        val sizes = physicalIds.associateWith { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let { it.width * it.height } ?: 0f
        }
        Log.i(TAG, "Physical cameras (sensor mm²): $sizes")
        return sizes.maxByOrNull { it.value }?.key
    }

    private fun refreshStatusText() {
        if (surfaceScan) {
            binding.statusText.text = scanStatusText()
            return
        }
        val sideLabel = (if (side == SIDE_BACK) "BACK" else "FRONT") + if (turned) " · TURNED 180°" else ""
        val rawStatus = if (rawCaptureSupported) "RAW: on" else "RAW: unsupported, using JPEG"
        val tiltStatus = if (isLevel) "Level ✓" else String.format(Locale.US, "Tilt %.0f°", overallTiltDegrees)
        binding.statusText.text = "$sideLabel · $rawStatus · $tiltStatus · $meteringStatusText"
    }

    /**
     * The light / sharpness / steadiness line: each check's ✓ or ⚠, then the fix
     * for the most important problem. Warns rather than blocks — manual capture
     * still works; auto-capture waits for a sharp, steady view (see
     * maybeHandleAutoCapture).
     */
    private fun refreshQualityText() {
        val now = SystemClock.elapsedRealtime()
        val issues = quality.issues(now)
        fun mark(ok: Boolean) = if (ok) "✓" else "⚠"
        val light = mark(CaptureQuality.Issue.DARK !in issues)
        val steady = mark(CaptureQuality.Issue.MOVING !in issues)
        val even = mark(CaptureQuality.Issue.UNEVEN_LIGHT !in issues && CaptureQuality.Issue.GLARE !in issues)
        val checks = when {
            !quality.hasSharpnessReading(now) -> getString(R.string.quality_no_card, light, steady)
            issues.isEmpty() -> getString(R.string.quality_all_good)
            else -> getString(R.string.quality_checks, light, mark(CaptureQuality.Issue.BLURRY !in issues), steady, even)
        }
        val advice = when (issues.firstOrNull()) {
            CaptureQuality.Issue.DARK ->
                if (camera?.cameraInfo?.hasFlashUnit() == true && !torchOn) getString(R.string.quality_dark)
                else getString(R.string.quality_dark_no_torch)
            CaptureQuality.Issue.BLURRY -> getString(R.string.quality_blurry)
            CaptureQuality.Issue.MOVING -> getString(R.string.quality_moving)
            CaptureQuality.Issue.UNEVEN_LIGHT -> getString(R.string.quality_uneven_light)
            CaptureQuality.Issue.GLARE -> getString(R.string.quality_glare)
            null -> if (torchOn) getString(R.string.quality_torch_glare) else null
        }
        binding.qualityText.text = listOfNotNull(checks, advice).joinToString("\n")
        binding.qualityText.visibility = View.VISIBLE

        if (now - lastQualityLogMs > QUALITY_LOG_INTERVAL_MS) {
            lastQualityLogMs = now
            Log.d(TAG, "Quality: exposureEffort=%s edgeWidth=%s steady=%b unevenness=%s glare=%s torch=%b issues=%s".format(
                quality.exposureEffort?.let { "%.3f".format(it) }, quality.currentEdgeWidth?.let { "%.2f".format(it) },
                quality.isSteady, quality.currentUnevenness?.let { "%.2f".format(it) },
                quality.currentGlare?.let { "%.3f".format(it) }, torchOn, issues,
            ))
        }
    }

    /**
     * The flashlight helps in a dim room but puts a reflection on holos and shiny
     * cards, so it's the user's choice. Light changes what focus and exposure
     * should be, so any lock is released to be redone under the new light.
     */
    private fun setTorch(on: Boolean) {
        val cam = camera ?: return
        torchOn = on
        cam.cameraControl.enableTorch(on)
        if (is3ALocked) unlock3A(cam)
        refreshQualityText()
    }

    /**
     * Start pressed: the scan needs focus and exposure locked first (every frame
     * must share them to be compared), so if they aren't, lock on the middle of
     * the guide and start once locked. Pressed again (it reads Stop while
     * scanning), ends the scan with the frames so far.
     */
    private fun startScanOrFocus() {
        tiltScan?.let { scan ->
            if (scan.pass != TiltScan.Pass.DONE) {
                scan.stop()
                finishScan()
            }
            return
        }
        if (is3ALocked) {
            startScan()
        } else {
            pendingScanStart = true
            focusAndLockOn(binding.overlayView.width / 2f, binding.overlayView.height / 2f)
        }
    }

    private fun startScan() {
        pendingScanStart = false
        if (tiltScan != null) return
        val sideDir = File(cacheDir, "surface/$side").apply { deleteRecursively() }
        scanDir = File(sideDir, SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())).apply { mkdirs() }
        scanFrameIndex.set(0)
        tiltScan = TiltScan(tiltXDegrees, tiltYDegrees, SystemClock.elapsedRealtime())
        binding.captureButton.setText(R.string.scan_stop_button)
        refreshStatusText()
    }

    /**
     * Which pass the scan is on and which ways it has reached. With the phone
     * screen-up over the card, gravity's x component is positive when the left
     * edge is down, and y positive when the top edge is up.
     */
    private fun scanStatusText(): String {
        val scan = tiltScan
            ?: return getString(R.string.scan_ready, if (side == SIDE_BACK) "BACK" else "FRONT") + "\n" + meteringStatusText
        fun mark(done: Boolean) = if (done) "✓" else "…"
        return when (scan.pass) {
            TiltScan.Pass.SIDE_TO_SIDE ->
                getString(R.string.scan_pass_side, mark(scan.reachedPositive), mark(scan.reachedNegative), scan.framesKept)
            TiltScan.Pass.TOP_TO_BOTTOM ->
                getString(R.string.scan_pass_top, mark(scan.reachedPositive), mark(scan.reachedNegative), scan.framesKept)
            TiltScan.Pass.DONE -> getString(R.string.scan_finishing, scan.framesKept)
        }
    }

    /**
     * The tilt-scan counterpart of [analyzeFrameForCardOutline], on full-color
     * frames: detection and the quality checks run on a copy scaled to the usual
     * analysis size (their thresholds are tuned there), and while scanning, a
     * sharp frame at a new tilt is kept — flattened and saved on
     * scanSaveExecutor so the preview keeps up. Runs on cameraExecutor; must
     * always close imageProxy.
     */
    private fun analyzeScanFrame(imageProxy: ImageProxy) {
        try {
            val rgba = rgbaToMat(imageProxy)
            val scale = SCAN_DETECTION_SIZE / maxOf(rgba.cols(), rgba.rows())
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, gray, org.opencv.core.Size(), scale, scale, Imgproc.INTER_AREA)
            val now = SystemClock.elapsedRealtime()
            val found = CardDetector.detect(gray)
            var sharp = false
            if (found != null) {
                val width = CaptureQuality.edgeWidth(gray, found)
                quality.updateEdgeWidth(width, now)
                quality.updateLighting(CaptureQuality.surfaceUnevenness(gray, found), CaptureQuality.glareFraction(gray, found), now)
                sharp = width != null && width <= CaptureQuality.MAX_EDGE_WIDTH
            } else {
                quality.updateEdgeWidth(null, now)
            }
            gray.release()

            val scan = tiltScan
            val dir = scanDir
            val tiltX = tiltXDegrees
            val tiltY = tiltYDegrees
            if (scan != null && dir != null && sharp &&
                scanSavesInFlight.get() < MAX_SCAN_SAVES_IN_FLIGHT && scan.wants(tiltX, tiltY)
            ) {
                scan.keep(tiltX, tiltY)
                val upright = Mat()
                when (imageProxy.imageInfo.rotationDegrees) {
                    90 -> Core.rotate(rgba, upright, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(rgba, upright, Core.ROTATE_180)
                    270 -> Core.rotate(rgba, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> rgba.copyTo(upright)
                }
                val index = scanFrameIndex.getAndIncrement()
                scanSavesInFlight.incrementAndGet()
                scanSaveExecutor.execute {
                    try {
                        saveScanFrame(upright, File(dir, TiltScan.frameName(index, tiltX, tiltY)))
                    } catch (exc: Exception) {
                        Log.e(TAG, "Saving scan frame failed", exc)
                    } finally {
                        upright.release()
                        scanSavesInFlight.decrementAndGet()
                    }
                }
            }
            rgba.release()

            val quad = found?.let { corners ->
                mapToViewQuad(Array(corners.size) { Point(corners[it].x / scale, corners[it].y / scale) }, imageProxy)
            }
            handler.post {
                binding.overlayView.updateDetectedQuad(quad)
                refreshQualityText()
                refreshStatusText()
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Scan frame analysis failed", exc)
        } finally {
            imageProxy.close()
        }
    }

    /** An RGBA_8888 frame as an RGBA Mat, row padding cropped off (see [yPlaneToMat]). */
    private fun rgbaToMat(imageProxy: ImageProxy): Mat {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = Mat(imageProxy.height, rowPixels, CvType.CV_8UC4)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        padded.put(0, 0, bytes)
        if (rowPixels == imageProxy.width) return padded
        val cropped = Mat(padded, org.opencv.core.Rect(0, 0, imageProxy.width, imageProxy.height)).clone()
        padded.release()
        return cropped
    }

    /** Flattens the card in [upright] (RGBA) the same way as a photo and saves it alone as [file]. */
    private fun saveScanFrame(upright: Mat, file: File) {
        val flattened = CardFlattener.flatten(upright)
        flattened.bitmap.recycle()
        val corners = flattened.cornersInPhoto ?: return
        val card = CardFlattener.cardOnly(upright, corners, 1)
        file.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, SCAN_JPEG_QUALITY, it) }
        card.recycle()
    }

    /** Both passes done, or Stop: once the last frames are saved, return them, or ask for another go if too few. */
    private fun finishScan() {
        binding.captureButton.isEnabled = false
        handler.post(object : Runnable {
            override fun run() {
                if (scanSavesInFlight.get() > 0) {
                    handler.postDelayed(this, 100)
                    return
                }
                val dir = scanDir ?: return
                val frames = dir.listFiles { f -> f.extension == "jpg" }?.size ?: 0
                Log.i(TAG, "Tilt scan done: $frames frames")
                if (frames < TiltScan.MIN_FRAMES) {
                    Toast.makeText(this@CaptureActivity, getString(R.string.scan_too_few, frames), Toast.LENGTH_LONG).show()
                    dir.deleteRecursively()
                    tiltScan = null
                    scanDir = null
                    binding.captureButton.setText(R.string.scan_start_button)
                    binding.captureButton.isEnabled = true
                    refreshStatusText()
                    return
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SURFACE_DIR, dir.path))
                finish()
            }
        })
    }

    /** Runs on cameraExecutor (background thread) — must always close imageProxy. */
    private fun analyzeFrameForCardOutline(imageProxy: ImageProxy) {
        try {
            val gray = yPlaneToMat(imageProxy)
            val corners = try {
                CardDetector.detect(gray)?.also { found ->
                    val now = SystemClock.elapsedRealtime()
                    quality.updateEdgeWidth(CaptureQuality.edgeWidth(gray, found), now)
                    quality.updateLighting(
                        CaptureQuality.surfaceUnevenness(gray, found), CaptureQuality.glareFraction(gray, found), now,
                    )
                } ?: null.also { quality.updateEdgeWidth(null, SystemClock.elapsedRealtime()) }
            } finally {
                gray.release()
            }

            val quad = corners?.let { mapToViewQuad(it, imageProxy) }
            handler.post {
                binding.overlayView.updateDetectedQuad(quad)
                refreshQualityText()
            }
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
                    pendingScanStart = false
                    meteringStatusText = "Focus failed — tap again"
                    refreshStatusText()
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Focus/metering failed", exc)
                pendingScanStart = false
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
        // Not for a tilt scan: the phone is in hand and its distance drifts as it
        // tilts, so focus keeps following (exposure and white balance stay locked,
        // which is what keeps the frames comparable).
        if (surfaceScan) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            scanExposure(cam)?.let { (exposureNs, sensitivity) ->
                optionsBuilder
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, SCAN_FRAME_DURATION_NS)
            }
        } else lastFocusDistance?.let { distance ->
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
        if (pendingScanStart) startScan()
    }

    /**
     * A tilt scan's exposure: no longer than SCAN_MAX_EXPOSURE_NS, so the print
     * stays sharp while the phone moves (in a dim room auto-exposure picks 1/30 s
     * or so, which smears it), with the sensitivity raised to keep the
     * brightness auto-exposure settled on — up to the sensor's limit, past which
     * the frames come out darker. Null if auto-exposure hasn't reported yet.
     */
    private fun scanExposure(cam: Camera): Pair<Long, Int>? {
        val exposureNs = lastExposureNs ?: return null
        val sensitivity = lastSensitivity ?: return null
        val maxSensitivity = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: sensitivity
        val shorter = minOf(exposureNs, SCAN_MAX_EXPOSURE_NS)
        val raised = (sensitivity.toDouble() * exposureNs / shorter).toInt().coerceIn(sensitivity, maxOf(sensitivity, maxSensitivity))
        Log.i(TAG, "Scan exposure: ${exposureNs / 1000} µs at ISO $sensitivity → ${shorter / 1000} µs at ISO $raised (max $maxSensitivity)")
        return shorter to raised
    }

    private fun unlock3A(cam: Camera) {
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
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
            // Auto-capture also waits for the card to be sharp and the phone still;
            // dim light only warns, since it may be all there is.
            val blockers = quality.issues(SystemClock.elapsedRealtime()) - CaptureQuality.Issue.DARK
            if (autoCaptureEnabled && autoCaptureArmed && isLevel && is3ALocked && blockers.isEmpty()) {
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
                putExtra(ReviewActivity.EXTRA_TURNED, turned)
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
        private const val QUALITY_LOG_INTERVAL_MS = 2000L
        // Tilt-scan frames: about the standard flattened card's resolution once
        // the card fills the guide; checks run on a copy scaled to this.
        private val SCAN_FRAME_SIZE = Size(1920, 1440)
        private const val SCAN_DETECTION_SIZE = 640.0
        private const val MAX_SCAN_SAVES_IN_FLIGHT = 2
        private const val SCAN_JPEG_QUALITY = 95
        // 1/250 s: tilting at a steady pace smears the card about a pixel.
        private const val SCAN_MAX_EXPOSURE_NS = 4_000_000L
        private const val SCAN_FRAME_DURATION_NS = 33_333_333L

        const val EXTRA_SIDE = "extra_side"
        /** Boolean: a second photo with the card turned 180°; the result carries its [ReviewActivity.EXTRA_JPEG_URI]. */
        const val EXTRA_TURNED = "extra_turned"
        /** Boolean: a tilt scan of the surface; the result carries [EXTRA_SURFACE_DIR]. */
        const val EXTRA_SURFACE_SCAN = "extra_surface_scan"
        /** String: folder of a tilt scan's flattened frames (see [TiltScan.frameName]). */
        const val EXTRA_SURFACE_DIR = "extra_surface_dir"
        const val SIDE_FRONT = "FRONT"
        const val SIDE_BACK = "BACK"
    }
}
