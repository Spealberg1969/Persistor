package com.persistor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Поворот пикселей по EXIF (в результат EXIF не копируется). */
fun Bitmap.applyExifOrientation(o: Int): Bitmap {
    val m = Matrix()
    when (o) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
        else -> return this
    }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    if (rotated !== this) recycle()
    return rotated
}

object PhotoProcessor {

    /**
     * Свой размер снимка: кадр поворачивается как надо, обрезается по центру до нужных пропорций
     * и уменьшается до targetW×targetH (больше, чем есть в кадре, не получится, увеличения нет).
     */
    fun cropResize(bytes: ByteArray, targetW: Int, targetH: Int, quality: Int = 95): ByteArray {
        val orientation = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Не удалось прочитать снимок")
        bmp = bmp.applyExifOrientation(orientation)

        val aspect = targetW.toFloat() / targetH
        var cw = bmp.width
        var ch = (cw / aspect).roundToInt()
        if (ch > bmp.height) {
            ch = bmp.height
            cw = (ch * aspect).roundToInt()
        }
        cw = cw.coerceIn(1, bmp.width)
        ch = ch.coerceIn(1, bmp.height)
        val x = (bmp.width - cw) / 2
        val y = (bmp.height - ch) / 2
        var cur = Bitmap.createBitmap(bmp, x, y, cw, ch)
        if (cur !== bmp) bmp.recycle()

        var tw = targetW
        var th = targetH
        if (tw > cw || th > ch) {
            val k = minOf(cw.toFloat() / tw, ch.toFloat() / th)
            tw = (tw * k).roundToInt().coerceAtLeast(1)
            th = (th * k).roundToInt().coerceAtLeast(1)
        }

        // Уменьшаем ступенями вдвое, чтобы не терять качество на больших коэффициентах
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            val n = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (n !== cur) cur.recycle()
            cur = n
        }
        if (cur.width != tw || cur.height != th) {
            val n = Bitmap.createScaledBitmap(cur, tw, th, true)
            if (n !== cur) cur.recycle()
            cur = n
        }
        val out = ByteArrayOutputStream(512 * 1024)
        cur.compress(Bitmap.CompressFormat.JPEG, quality, out)
        cur.recycle()
        return out.toByteArray()
    }
}
