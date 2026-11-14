package com.persistor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Координаты расположения штампа в кадре: от 0 (лево/верх) до 1 (право/низ). */
enum class CameraOverlayPosition(
    val key: String,
    val title: String,
    val x: Float,
    val y: Float,
) {
    TOP_LEFT("top_left", "Слева сверху", 0f, 0f),
    TOP_CENTER("top_center", "По центру сверху", 0.5f, 0f),
    TOP_RIGHT("top_right", "Справа сверху", 1f, 0f),
    CENTER_LEFT("center_left", "Слева по центру", 0f, 0.5f),
    CENTER("center", "По центру", 0.5f, 0.5f),
    CENTER_RIGHT("center_right", "Справа по центру", 1f, 0.5f),
    BOTTOM_LEFT("bottom_left", "Слева снизу", 0f, 1f),
    BOTTOM_CENTER("bottom_center", "По центру снизу", 0.5f, 1f),
    BOTTOM_RIGHT("bottom_right", "Справа снизу", 1f, 1f);

    companion object {
        fun fromKey(key: String?): CameraOverlayPosition = entries.firstOrNull { it.key == key } ?: BOTTOM_RIGHT
    }
}

data class CameraOverlaySettings(
    val enabled: Boolean = false,
    val showDateTime: Boolean = true,
    val showName: Boolean = false,
    val name: String = "",
    val position: CameraOverlayPosition = CameraOverlayPosition.BOTTOM_RIGHT,
    /** Размер Compose-текста для preview; на фото масштабируется относительно короткой стороны. */
    val fontSizeSp: Int = 18,
)

object CameraOverlayRenderer {
    fun lines(
        settings: CameraOverlaySettings,
        timestampMillis: Long,
        locale: Locale = Locale.getDefault(),
    ): List<String> = buildList {
        if (settings.showDateTime) {
            add(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", locale).format(Date(timestampMillis)))
        }
        settings.name.trim().takeIf { settings.showName && it.isNotEmpty() }?.let(::add)
    }

    /** Тонкий контур масштабируется с текстом, но не становится чрезмерно толстым на больших фото. */
    internal fun outlineStrokeWidth(textSize: Float): Float = (textSize * 0.045f).coerceIn(1.25f, 2.5f)

    /** Впечатывает штамп в пиксели и нормализует EXIF orientation, чтобы надпись не повернулась вместе с EXIF. */
    fun applyToJpeg(bytes: ByteArray, settings: CameraOverlaySettings, timestampMillis: Long): ByteArray {
        if (!settings.enabled) return bytes
        val lines = lines(settings, timestampMillis)
        if (lines.isEmpty()) return bytes

        val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inMutable = true },
        )
            ?: error("Не удалось декодировать снимок для нанесения надписи")
        var output = decoded.applyExifOrientation(orientation)
        if (!output.isMutable) {
            val mutable = output.copy(Bitmap.Config.ARGB_8888, true)
            output.recycle()
            output = mutable ?: error("Не удалось подготовить снимок для нанесения надписи")
        }

        try {
            drawOnBitmap(output, settings, timestampMillis)
            val stream = ByteArrayOutputStream(bytes.size)
            check(output.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                "Не удалось сохранить снимок с надписью"
            }
            return stream.toByteArray()
        } finally {
            output.recycle()
        }
    }

    /** Наносит тот же штамп непосредственно на bitmap перед финальным JPEG-кодированием. */
    internal fun drawOnBitmap(bitmap: Bitmap, settings: CameraOverlaySettings, timestampMillis: Long) {
        if (!settings.enabled) return
        val stampLines = lines(settings, timestampMillis)
        if (stampLines.isNotEmpty()) drawStamp(bitmap, stampLines, settings)
    }

    private fun drawStamp(bitmap: Bitmap, sourceLines: List<String>, settings: CameraOverlaySettings) {
        val shortSide = minOf(bitmap.width, bitmap.height).toFloat()
        val textSize = (shortSide * settings.fontSizeSp.coerceIn(12, 36) / 360f).coerceAtLeast(12f)
        val margin = (shortSide * 0.025f).coerceAtLeast(8f)
        val gap = textSize * 0.16f
        val maxTextWidth = (bitmap.width - 2f * margin).coerceAtLeast(textSize)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = when (settings.position.x) {
                0f -> Paint.Align.LEFT
                1f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
        }
        val wrapped = sourceLines.flatMap { wrap(it, maxTextWidth, textPaint) }
        if (wrapped.isEmpty()) return

        val metrics = textPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val contentHeight = wrapped.size * lineHeight + (wrapped.size - 1).coerceAtLeast(0) * gap
        val anchorX = when (settings.position.x) {
            0f -> margin
            1f -> bitmap.width - margin
            else -> bitmap.width / 2f
        }
        val top = when (settings.position.y) {
            0f -> margin
            1f -> bitmap.height - margin - contentHeight
            else -> (bitmap.height - contentHeight) / 2f
        }.coerceIn(0f, (bitmap.height - contentHeight).coerceAtLeast(0f))
        val canvas = Canvas(bitmap)
        val outlinePaint = Paint(textPaint).apply {
            color = android.graphics.Color.BLACK
            alpha = 230
            style = Paint.Style.STROKE
            strokeWidth = outlineStrokeWidth(textSize)
            strokeJoin = Paint.Join.ROUND
        }
        var baseline = top - metrics.ascent
        wrapped.forEach { line ->
            canvas.drawText(line, anchorX, baseline, outlinePaint)
            canvas.drawText(line, anchorX, baseline, textPaint)
            baseline += lineHeight + gap
        }
    }

    private fun wrap(text: String, maxWidth: Float, paint: Paint): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val result = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) result += current
            if (paint.measureText(word) <= maxWidth) {
                current = word
            } else {
                val part = StringBuilder()
                for (char in word) {
                    if (part.isNotEmpty() && paint.measureText(part.toString() + char) > maxWidth) {
                        result += part.toString()
                        part.clear()
                    }
                    part.append(char)
                }
                current = part.toString()
            }
        }
        if (current.isNotEmpty()) result += current
        return result.ifEmpty { listOf(text.take(1)) }
    }
}
