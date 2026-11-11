package com.persistor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
            drawStamp(output, lines, settings)
            val stream = ByteArrayOutputStream(bytes.size)
            check(output.compress(Bitmap.CompressFormat.JPEG, 100, stream)) {
                "Не удалось сохранить снимок с надписью"
            }
            return stream.toByteArray()
        } finally {
            output.recycle()
        }
    }

    private fun drawStamp(bitmap: Bitmap, sourceLines: List<String>, settings: CameraOverlaySettings) {
        val shortSide = minOf(bitmap.width, bitmap.height).toFloat()
        val textSize = (shortSide * settings.fontSizeSp.coerceIn(12, 36) / 360f).coerceAtLeast(12f)
        val margin = (shortSide * 0.025f).coerceAtLeast(8f)
        val padding = (textSize * 0.42f).coerceAtLeast(6f)
        val gap = textSize * 0.16f
        val maxTextWidth = (bitmap.width - 2f * margin - 2f * padding).coerceAtLeast(textSize)

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
        val contentWidth = wrapped.maxOf { textPaint.measureText(it) }
        val boxWidth = (contentWidth + 2f * padding).coerceAtMost(bitmap.width - 2f * margin)
        val boxHeight = 2f * padding + wrapped.size * lineHeight + (wrapped.size - 1).coerceAtLeast(0) * gap
        val left = when (settings.position.x) {
            0f -> margin
            1f -> bitmap.width - margin - boxWidth
            else -> (bitmap.width - boxWidth) / 2f
        }.coerceIn(0f, (bitmap.width - boxWidth).coerceAtLeast(0f))
        val top = when (settings.position.y) {
            0f -> margin
            1f -> bitmap.height - margin - boxHeight
            else -> (bitmap.height - boxHeight) / 2f
        }.coerceIn(0f, (bitmap.height - boxHeight).coerceAtLeast(0f))
        val box = RectF(left, top, left + boxWidth, top + boxHeight)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(
            box,
            textSize * 0.24f,
            textSize * 0.24f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                alpha = 155
            },
        )

        val anchorX = when (settings.position.x) {
            0f -> box.left + padding
            1f -> box.right - padding
            else -> box.centerX()
        }
        var baseline = box.top + padding - metrics.ascent
        wrapped.forEach { line ->
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
