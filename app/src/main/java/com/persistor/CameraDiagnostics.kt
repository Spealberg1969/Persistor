package com.persistor

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.hypot

/** Текстовый отчёт о том, какие камеры и режимы телефон отдаёт сторонним приложениям. */
object CameraDiagnostics {

    fun report(context: Context): String {
        val m = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val ids = runCatching { m.cameraIdList.toList() }.getOrDefault(emptyList())
        sb.appendLine("Камеры (cameraIdList): ${ids.joinToString()}")
        for (id in ids) {
            sb.appendLine()
            describe(m, id, "ID $id", sb)
            val c = runCatching { m.getCameraCharacteristics(id) }.getOrNull()
            c?.physicalCameraIds?.sorted()?.forEach { p ->
                sb.appendLine()
                describe(m, p, "  └ физическая камера $p (внутри ID $id)", sb)
            }
        }
        sb.appendLine()
        sb.appendLine("--- Проверка скрытых ID 2..9 ---")
        var hidden = 0
        for (n in 2..9) {
            val id = n.toString()
            if (id in ids) continue
            val c = (try { m.getCameraCharacteristics(id) } catch (e: Exception) { null }) ?: continue
            hidden++
            sb.appendLine()
            describe(m, id, "СКРЫТЫЙ ID $id", sb)
        }
        if (hidden == 0) sb.appendLine("Скрытых камер не найдено.")
        return sb.toString()
    }

    private val capNames = mapOf(
        1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW", 6 to "BURST_CAPTURE",
        11 to "LOGICAL_MULTI_CAMERA", 16 to "ULTRA_HIGH_RES_SENSOR",
    )
    private val nrNames = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG")
    private val edgeNames = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "ZERO_SHUTTER_LAG")
    private val afNames = mapOf(0 to "OFF", 1 to "AUTO", 2 to "MACRO", 3 to "CONT_VIDEO", 4 to "CONT_PICTURE", 5 to "EDOF")
    private val oisNames = mapOf(0 to "OFF", 1 to "ON")
    private val aberNames = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")

    private fun modes(c: CameraCharacteristics, key: CameraCharacteristics.Key<IntArray>, names: Map<Int, String>): String =
        c.get(key)?.joinToString { names[it] ?: it.toString() } ?: "нет данных"

    private fun describe(m: CameraManager, id: String, title: String, sb: StringBuilder) {
        val c = try {
            m.getCameraCharacteristics(id)
        } catch (e: Exception) {
            sb.appendLine("$title: недоступна (${e.message})")
            return
        }
        sb.appendLine("=== $title ===")
        sb.appendLine(
            "Сторона: " + when (c.get(CameraCharacteristics.LENS_FACING)) {
                0 -> "передняя"; 1 -> "задняя"; 2 -> "внешняя"; else -> "?"
            }
        )
        sb.appendLine(
            "Уровень HW: " + when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; 4 -> "EXTERNAL"; else -> "?"
            }
        )
        sb.appendLine(
            "Возможности: " + (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.joinToString { capNames[it] ?: it.toString() } ?: "?")
        )
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        sb.appendLine("Фокусное, мм: " + (focal?.joinToString { "%.2f".format(it) } ?: "?"))
        if (size != null) {
            sb.append("Сенсор, мм: %.2f×%.2f".format(size.width, size.height))
            val f = focal?.firstOrNull()
            if (f != null) {
                val eq = f.toDouble() * 43.27 / hypot(size.width.toDouble(), size.height.toDouble())
                sb.append(" (экв. ≈%.0f мм)".format(eq))
            }
            sb.appendLine()
        }
        c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            sb.appendLine("Активная область: ${it.width()}×${it.height()}")
        }
        c.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let { sb.appendLine("Ориентация сенсора: $it°") }
        sb.appendLine("Вспышка: " + (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: "?"))

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpeg = map?.getOutputSizes(ImageFormat.JPEG)
            ?.sortedByDescending { it.width.toLong() * it.height }?.take(5)
            ?.joinToString { "${it.width}×${it.height}" }
        sb.appendLine("JPEG, крупнейшие: " + (jpeg ?: "нет данных"))
        if (Build.VERSION.SDK_INT >= 31) {
            val mm = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            val big = mm?.getOutputSizes(ImageFormat.JPEG)
                ?.sortedByDescending { it.width.toLong() * it.height }?.take(3)
                ?.joinToString { "${it.width}×${it.height}" }
            sb.appendLine("JPEG, режим максимального разрешения: " + (big ?: "нет"))
        }

        sb.appendLine("Шумоподавление: " + modes(c, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, nrNames))
        sb.appendLine("Резкость (edge): " + modes(c, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, edgeNames))
        sb.appendLine("Хром. аберрации: " + modes(c, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, aberNames))
        sb.appendLine("Автофокус: " + modes(c, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, afNames))
        sb.appendLine("Стабилизация (OIS): " + modes(c, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, oisNames))
    }
}
