package com.persistor

import android.graphics.Bitmap
import com.awxkee.jxlcoder.JxlCoder

/** JPEG XL encoder and decoder used by the quality/SSIM loop. */
internal object JxlEncoder {
    fun encode(bitmap: Bitmap, quality: Int): ByteArray = JxlCoder.encode(bitmap, quality = quality)
    fun decode(bytes: ByteArray): Bitmap = JxlCoder.decode(bytes)
}
