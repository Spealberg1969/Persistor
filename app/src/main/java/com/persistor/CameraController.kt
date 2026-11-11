package com.persistor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Размер снимка: Native — размер, который отдаёт сама камера; Custom — любой размер с обрезкой по центру. */
sealed interface Resolution {
    val width: Int
    val height: Int

    data class Native(override val width: Int, override val height: Int) : Resolution
    data class Custom(override val width: Int, override val height: Int) : Resolution

    fun encode(): String = (if (this is Native) "N" else "C") + "${width}x${height}"

    companion object {
        fun decode(s: String?): Resolution? {
            if (s == null || s.length < 4) return null
            val parts = s.substring(1).split("x")
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return when (s[0]) {
                'N' -> Native(w, h)
                'C' -> Custom(w, h)
                else -> null
            }
        }
    }
}

/** Объектив: openId — какую камеру открываем, physicalId — физический объектив внутри логической камеры. */
data class Lens(val label: String, val openId: String, val physicalId: String?, val eq: Double?)

data class CameraPlan(
    val previewSize: Size,
    val captureSize: Size,
    val outputSize: Size,
    val cropAspect: Float?,
    val resolution: Resolution,
    val sensorOrientation: Int,
    val flashAvailable: Boolean,
) {
    /** Соотношение сторон превью в вертикальном положении телефона (ширина / высота). */
    val uprightAspect: Float
        get() = if (sensorOrientation % 180 == 0)
            previewSize.width.toFloat() / previewSize.height
        else
            previewSize.height.toFloat() / previewSize.width
}

/**
 * Съёмка через Camera2: без расширений производителя (ночной режим, HDR, «улучшайзеры» не включаются).
 * Вспышка: выключена или постоянно включена (режим фонарика).
 */
class CameraController(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onSaved: (String, Uri) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("persistor-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile var saveDir: Uri? = null
    var busy by mutableStateOf(false)
        private set
    var manualFocus by mutableStateOf(false)
        private set

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var chars: CameraCharacteristics? = null
    private var planInUse: CameraPlan? = null
    private var lensInUse: Lens? = null
    private var textureInUse: SurfaceTexture? = null
    private var active = false
    private var gen = 0

    @Volatile private var torch = false
    @Volatile private var clean = true
    @Volatile private var pendingAngle = 0
    @Volatile private var waitingLock = false
    @Volatile private var focusRect: MeteringRectangle? = null

    // ---------- Список объективов ----------

    private data class Cand(val lens: Lens, val sensor: String)

    /**
     * Задние объективы: перечисленные системой и «скрытые» ID 2..9, которые Motorola не показывает в списке,
     * но которые открываются. Дубликаты (тот же объектив под другим ID) отбрасываются.
     */
    fun lenses(): List<Lens> {
        val cands = mutableListOf<Cand>()
        val listed = manager.cameraIdList.toSet()

        fun add(id: String, physical: String?, c: CameraCharacteristics, suffix: String = "") {
            val eq = eqMm(c)
            val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val label = "ID ${physical ?: id} · ${mmText(eq)}$suffix"
            cands += Cand(Lens(label, id, physical, eq), "${eq?.roundToInt()}|${sz?.width}|${sz?.height}")
        }

        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val phys = c.physicalCameraIds
            add(id, null, c, if (phys.isNotEmpty()) " (логическая)" else "")
            for (p in phys.sorted()) {
                val pc = runCatching { manager.getCameraCharacteristics(p) }.getOrNull() ?: continue
                add(id, p, pc)
            }
        }
        for (n in 2..9) {
            val id = n.toString()
            if (id in listed) continue
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            add(id, null, c)
        }

        val seen = HashSet<String>()
        val unique = cands.filter { seen.add(it.sensor) }.map { it.lens }
        return unique.sortedBy { it.eq ?: 999.0 }
    }

    private fun eqMm(c: CameraCharacteristics): Double? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return f.toDouble() * 43.27 / hypot(sz.width.toDouble(), sz.height.toDouble())
    }

    private fun mmText(eq: Double?): String {
        if (eq == null) return "?"
        val hint = when {
            eq < 20 -> "ультраширокий"
            eq < 40 -> "основной"
            else -> "теле"
        }
        return "≈${eq.roundToInt()} мм, $hint"
    }

    // ---------- Размеры и план съёмки ----------

    /** Все размеры JPEG, которые камера отдаёт напрямую (как список в TimeStamp Camera). */
    fun nativeSizes(lens: Lens): List<Size> {
        val c = manager.getCameraCharacteristics(lens.physicalId ?: lens.openId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            .sortedWith(compareByDescending<Size> { it.width }.thenByDescending { it.height })
    }

    fun plan(lens: Lens, res: Resolution): CameraPlan {
        val sizeChars = manager.getCameraCharacteristics(lens.physicalId ?: lens.openId)
        val openChars = manager.getCameraCharacteristics(lens.openId)
        val map = sizeChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Камера не сообщила список размеров")
        fun area(s: Size) = s.width.toLong() * s.height

        val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        require(jpeg.isNotEmpty()) { "Нет доступных размеров JPEG" }
        val base43 = jpeg.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            .maxByOrNull(::area) ?: jpeg.maxByOrNull(::area)!!

        val capture: Size
        val output: Size
        val crop: Float?
        when (res) {
            is Resolution.Native -> {
                val reqAspect = res.width.toFloat() / res.height
                val reqArea = res.width.toLong() * res.height
                capture = jpeg.firstOrNull { it.width == res.width && it.height == res.height }
                    ?: jpeg.filter { abs(it.width.toFloat() / it.height - reqAspect) < 0.02f }
                        .minByOrNull { abs(area(it) - reqArea) }
                    ?: base43
                output = capture
                crop = null
            }
            is Resolution.Custom -> {
                capture = base43
                output = Size(res.width, res.height)
                crop = res.width.toFloat() / res.height
            }
        }

        // Превью подбираем под пропорции снимка, чтобы кадр на экране совпадал с кадром на фото
        val ratio = capture.width.toFloat() / capture.height
        val previews = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        require(previews.isNotEmpty()) { "Нет доступных размеров превью" }
        val preview = previews.filter { it.width <= 1920 }
            .sortedWith(
                compareBy<Size> { (abs(it.width.toFloat() / it.height - ratio) * 100).roundToInt() }
                    .thenByDescending { area(it) }
            ).firstOrNull() ?: previews.first()

        return CameraPlan(
            previewSize = preview,
            captureSize = capture,
            outputSize = output,
            cropAspect = crop,
            resolution = res,
            sensorOrientation = sizeChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            flashAvailable = openChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        )
    }

    // ---------- Открытие и закрытие ----------

    @SuppressLint("MissingPermission")
    fun open(lens: Lens, plan: CameraPlan, texture: SurfaceTexture) {
        if (active && textureInUse === texture && lensInUse == lens && planInUse == plan) return
        close()
        active = true
        val g = ++gen
        planInUse = plan
        lensInUse = lens
        textureInUse = texture
        try {
            chars = manager.getCameraCharacteristics(lens.openId)
            manager.openCamera(lens.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (g != gen) { device.close(); return }
                    camera = device
                    startSession(device, lens, plan, texture, g)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (g == gen) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (g == gen) {
                        camera = null
                        onError("Ошибка камеры (код $error)")
                    }
                }
            }, handler)
        } catch (e: Exception) {
            onError("Не удалось открыть камеру: ${e.message}")
        }
    }

    private fun startSession(device: CameraDevice, lens: Lens, plan: CameraPlan, texture: SurfaceTexture, g: Int) {
        try {
            texture.setDefaultBufferSize(plan.previewSize.width, plan.previewSize.height)
            val surface = Surface(texture)
            previewSurface = surface
            val r = ImageReader.newInstance(plan.captureSize.width, plan.captureSize.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = r

            val outs = listOf(OutputConfiguration(surface), OutputConfiguration(r.surface))
            if (lens.physicalId != null) outs.forEach { it.setPhysicalCameraId(lens.physicalId) }

            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (g != gen) { s.close(); return }
                        session = s
                        try {
                            startPreview(device, surface)
                        } catch (e: Exception) {
                            onError("Не удалось запустить превью: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (g == gen) {
                            onError(
                                "Не удалось настроить камеру: эта связка объектива и размера, возможно, " +
                                    "не поддерживается. Выберите другой размер."
                            )
                        }
                    }
                }
            )
            device.createCaptureSession(cfg)
        } catch (e: Exception) {
            onError("Не удалось создать сессию камеры: ${e.message}")
        }
    }

    private fun startPreview(device: CameraDevice, surface: Surface) {
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        applyCommon(b)
        previewBuilder = b
        session?.setRepeatingRequest(b.build(), previewCallback, handler)
    }

    fun close() {
        active = false
        gen++
        handler.removeCallbacks(afTimeout)
        waitingLock = false
        busy = false
        manualFocus = false
        focusRect = null
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { reader?.close() }
        reader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        previewBuilder = null
        textureInUse = null
    }

    /** Закрывает камеру, только если уничтожена именно та текстура, на которую она сейчас выводит превью. */
    fun closeIfTexture(t: SurfaceTexture) {
        if (textureInUse === t) close()
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    // ---------- Настройки ----------

    fun setTorch(on: Boolean) { torch = on; updateRepeating() }
    fun setClean(on: Boolean) { clean = on; updateRepeating() }

    private fun updateRepeating() {
        val b = previewBuilder ?: return
        val s = session ?: return
        try {
            applyCommon(b)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            // сессия могла закрыться — игнорируем
        }
    }

    private fun CameraCharacteristics.has(key: CameraCharacteristics.Key<IntArray>, v: Int): Boolean =
        get(key)?.contains(v) == true

    /** Общие параметры для превью и снимка. «Чистая» обработка просит у камеры минимум шумодава и резкости. */
    private fun applyCommon(b: CaptureRequest.Builder) {
        val c = chars ?: return
        val afModes = CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        val rect = focusRect
        if (manualFocus && rect != null && c.has(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
            }
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            }
        } else if (c.has(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        b.set(
            CaptureRequest.FLASH_MODE,
            if (torch && planInUse?.flashAvailable == true) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
        )
        if (c.has(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (clean) {
            val nr = CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
            when {
                c.has(nr, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) ->
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                c.has(nr, CameraMetadata.NOISE_REDUCTION_MODE_OFF) ->
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            }
            if (c.has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF)) {
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            }
            if (c.has(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)) {
                b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            }
        }
    }

    // ---------- Фокус по касанию ----------

    /**
     * u, v: точка касания в видимом превью (0..1, от левого верхнего угла).
     * displayRotation: Surface.ROTATION_* текущего экрана. Касание пересчитывается в координаты сенсора.
     */
    fun focusAt(u: Float, v: Float, displayRotation: Int) {
        val b = previewBuilder ?: return
        val s = session ?: return
        val c = chars ?: return
        val plan = planInUse ?: return
        val act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val rotDeg = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Поворот, который нужно применить к кадру сенсора, чтобы получить картинку на экране
        val t = ((plan.sensorOrientation - rotDeg) % 360 + 360) % 360
        val (sx, sy) = when (t) {
            0 -> u to v
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            else -> (1f - v) to u
        }
        // Видимая часть сенсора: поток может быть обрезан по центру под другие пропорции
        val arAct = act.width().toFloat() / act.height()
        val arStream = plan.previewSize.width.toFloat() / plan.previewSize.height
        var fw = 1f
        var fh = 1f
        if (arStream > arAct) fh = arAct / arStream else if (arStream < arAct) fw = arStream / arAct
        val ax = (1f - fw) / 2f + sx.coerceIn(0f, 1f) * fw
        val ay = (1f - fh) / 2f + sy.coerceIn(0f, 1f) * fh

        val half = (minOf(act.width(), act.height()) * 0.08f).toInt().coerceAtLeast(8)
        val cx = (ax * act.width()).toInt()
        val cy = (ay * act.height()).toInt()
        val x = (cx - half).coerceIn(0, (act.width() - 2 * half).coerceAtLeast(0))
        val y = (cy - half).coerceIn(0, (act.height() - 2 * half).coerceAtLeast(0))
        focusRect = MeteringRectangle(x, y, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        manualFocus = true

        try {
            applyCommon(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            onError("Не удалось навести фокус: ${e.message}")
        }
    }

    /** Возврат к непрерывному автофокусу. */
    fun resetFocus() {
        manualFocus = false
        focusRect = null
        try {
            val dev = camera
            val sf = previewSurface
            if (dev != null && sf != null) startPreview(dev, sf)
        } catch (e: Exception) {
            // игнорируем
        }
    }

    // ---------- Съёмка ----------

    private val afTimeout = Runnable {
        if (waitingLock) {
            waitingLock = false
            captureStill()
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!waitingLock) return
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (af == null ||
                af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                waitingLock = false
                handler.removeCallbacks(afTimeout)
                captureStill()
            }
        }
    }

    /** deviceAngle: положение телефона по датчику (0/90/180/270), не зависит от автоповорота экрана. */
    fun takePicture(deviceAngle: Int) {
        val s = session ?: return
        val b = previewBuilder ?: return
        if (busy) return
        busy = true
        pendingAngle = deviceAngle
        waitingLock = true
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            handler.postDelayed(afTimeout, 2000)
        } catch (e: Exception) {
            busy = false
            waitingLock = false
            onError("Не удалось сфокусироваться: ${e.message}")
        }
    }

    private fun captureStill() {
        val dev = camera
        val s = session
        val r = reader
        if (dev == null || s == null || r == null) { busy = false; return }
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            applyCommon(b)
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, ((planInUse?.sensorOrientation ?: 90) + pendingAngle + 360) % 360)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    unlockFocus()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    busy = false
                    onError("Снимок не удался (код ${failure.reason})")
                    unlockFocus()
                }
            }, handler)
        } catch (e: Exception) {
            busy = false
            onError("Не удалось сделать снимок: ${e.message}")
        }
    }

    private fun unlockFocus() {
        val b = previewBuilder ?: return
        val s = session ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), previewCallback, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: Exception) {
            // сессия могла закрыться — игнорируем
        }
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireNextImage() } catch (e: Exception) { null }
        if (image == null) { busy = false; return }
        val bytes = try {
            val buf = image.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            image.close()
        }
        try {
            val plan = planInUse
            val out = if (plan != null && plan.resolution is Resolution.Custom) {
                try {
                    PhotoProcessor.cropResize(bytes, plan.outputSize.width, plan.outputSize.height)
                } catch (e: Throwable) {
                    onError("Не удалось обработать снимок: ${e.message}")
                    bytes
                }
            } else bytes
            save(out)
        } finally {
            busy = false
        }
    }

    private fun save(bytes: ByteArray) {
        val dirUri = saveDir ?: return onError("Папка для снимков не выбрана")
        val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return onError("Не удалось открыть папку")
        val base = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var name = "$base.jpg"
        var i = 2
        while (dir.findFile(name) != null) { name = "${base}_$i.jpg"; i++ }
        val file = dir.createFile("image/jpeg", name) ?: return onError("Не удалось создать файл в папке")
        val out = context.contentResolver.openOutputStream(file.uri) ?: return onError("Не удалось записать файл")
        out.use { it.write(bytes) }
        onSaved(file.name ?: name, file.uri)
    }
}
