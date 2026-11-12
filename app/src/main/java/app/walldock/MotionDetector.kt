package app.walldock

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import kotlin.math.abs

/**
 * Détection de mouvement avec la caméra frontale (comme Fully Kiosk) : de petites images
 * (environ 320 × 240), quelques fois par seconde, comparées à la précédente sur une grille
 * de 32 × 24 cases. Les images ne quittent jamais la tablette et ne sont pas enregistrées.
 *
 * onMotion(true) au premier mouvement, onMotion(false) après [HOLD_MS] sans mouvement.
 */
class MotionDetector(
    private val context: Context,
    private val onMotion: (Boolean) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    var running = false
        private set

    /** 0 faible, 1 moyenne, 2 élevée. */
    @Volatile
    var sensitivity = 1

    /** Dernière part de l'image qui a bougé (0–100 %), pour le diagnostic. */
    @Volatile
    var lastChangePercent = 0f
        private set

    /** Dernière erreur (caméra occupée, absente…), null si tout va bien. */
    @Volatile
    var lastError: String? = null
        private set

    private var previous: FloatArray? = null
    private var lastFrameAt = 0L
    private var lastMotionAt = 0L
    @Volatile
    private var motion = false
    private var hits = 0

    private val release = Runnable {
        if (motion && SystemClock.elapsedRealtime() - lastMotionAt >= HOLD_MS) {
            motion = false
            onMotion(false)
        }
    }

    private val retry = Runnable { if (running) open() }

    fun start() {
        if (running) return
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = tr(R.string.motion_no_permission)
            return
        }
        running = true
        val t = HandlerThread("walldock-motion").also { it.start() }
        thread = t
        worker = Handler(t.looper)
        open()
    }

    fun stop() {
        running = false
        main.removeCallbacks(retry)
        main.removeCallbacks(release)
        closeCamera()
        thread?.quitSafely()
        thread = null
        worker = null
        previous = null
        if (motion) {
            motion = false
            onMotion(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val handler = worker ?: return
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull()
            if (id == null) {
                lastError = tr(R.string.motion_no_camera)
                return
            }
            val characteristics = manager.getCameraCharacteristics(id)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val size = sizes.filter { it.width >= 240 && it.height >= 180 }.minByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height } ?: Size(320, 240)
            val fpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.minByOrNull { it.upper }
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            imageReader.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = imageReader
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) {
                        device.close()
                        return
                    }
                    camera = device
                    startSession(device, imageReader, fpsRange, handler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    // Une autre appli (visio…) a pris la caméra : on réessaiera plus tard
                    lastError = tr(R.string.motion_camera_busy)
                    closeCamera()
                    scheduleRetry()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    lastError = tr(R.string.motion_camera_error, error)
                    closeCamera()
                    scheduleRetry()
                }
            }, handler)
        } catch (e: Exception) {
            lastError = tr(R.string.motion_camera_error_text, e.javaClass.simpleName)
            closeCamera()
            scheduleRetry()
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice, imageReader: ImageReader, fps: Range<Int>?, handler: Handler) {
        try {
            device.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        request.addTarget(imageReader.surface)
                        if (fps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        s.setRepeatingRequest(request.build(), null, handler)
                        lastError = null
                    } catch (e: Exception) {
                        lastError = tr(R.string.motion_camera_error_text, e.javaClass.simpleName)
                        closeCamera()
                        scheduleRetry()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    lastError = tr(R.string.motion_camera_error_text, "session")
                    closeCamera()
                    scheduleRetry()
                }
            }, handler)
        } catch (e: Exception) {
            lastError = tr(R.string.motion_camera_error_text, e.javaClass.simpleName)
            closeCamera()
            scheduleRetry()
        }
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            // Luminosité moyenne de chaque case (un pixel sur deux suffit)
            val cells = FloatArray(GRID_W * GRID_H)
            val counts = IntArray(GRID_W * GRID_H)
            var y = 0
            while (y < height) {
                val cy = y * GRID_H / height
                var x = 0
                while (x < width) {
                    val cx = x * GRID_W / width
                    val index = y * rowStride + x * pixelStride
                    if (index < buffer.limit()) {
                        cells[cy * GRID_W + cx] += (buffer.get(index).toInt() and 0xFF).toFloat()
                        counts[cy * GRID_W + cx]++
                    }
                    x += 2
                }
                y += 2
            }
            for (i in cells.indices) if (counts[i] > 0) cells[i] /= counts[i]
            analyse(cells, now)
        } finally {
            image.close()
        }
    }

    private fun analyse(cells: FloatArray, now: Long) {
        val before = previous
        previous = cells
        if (before == null) return
        // On retire le changement global de luminosité (exposition automatique, lumière allumée)
        var meanDelta = 0f
        for (i in cells.indices) meanDelta += cells[i] - before[i]
        meanDelta /= cells.size
        var changed = 0
        for (i in cells.indices) {
            if (abs(cells[i] - before[i] - meanDelta) > CELL_THRESHOLD) changed++
        }
        val percent = changed * 100f / cells.size
        lastChangePercent = percent
        val limit = THRESHOLDS[sensitivity.coerceIn(0, 2)]
        // Deux images de suite au-dessus du seuil : évite les déclenchements sur un reflet
        hits = if (percent >= limit) hits + 1 else 0
        if (hits >= 2) {
            lastMotionAt = now
            if (!motion) {
                motion = true
                main.post { onMotion(true) }
            }
            main.removeCallbacks(release)
            main.postDelayed(release, HOLD_MS)
        }
    }

    private fun scheduleRetry() {
        if (!running) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, RETRY_MS)
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (e: Exception) {
            // déjà fermée
        }
        session = null
        try {
            camera?.close()
        } catch (e: Exception) {
            // déjà fermée
        }
        camera = null
        try {
            reader?.close()
        } catch (e: Exception) {
            // déjà fermé
        }
        reader = null
        previous = null
    }

    companion object {
        private const val GRID_W = 32
        private const val GRID_H = 24
        private const val FRAME_INTERVAL_MS = 250L
        private const val CELL_THRESHOLD = 14f
        /** Part de l'image qui doit bouger (%) selon la sensibilité : faible, moyenne, élevée. */
        private val THRESHOLDS = floatArrayOf(6f, 3f, 1.2f)
        /** Le capteur « Mouvement » reste allumé 15 s après le dernier mouvement. */
        const val HOLD_MS = 15_000L
        private const val RETRY_MS = 60_000L
    }
}
