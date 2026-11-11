package com.persistor

import android.graphics.Bitmap
import android.os.Build
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import java.io.File

/** Platform-backed image encoders; HEIF/AVIF are device-codec dependent. */
internal object PlatformImageEncoder {
    fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray {
        require(quality in 1..100)
        val output = java.io.ByteArrayOutputStream(256 * 1024)
        val format = if (Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        check(bitmap.compress(format, quality, output)) { "Android WebP encoder failed" }
        return output.toByteArray()
    }

    fun encodeHeif(bitmap: Bitmap, quality: Int, cacheDir: File): ByteArray {
        check(Build.VERSION.SDK_INT >= 29) {
            "HEIF encoding requires Android 10 (API 29) or newer"
        }
        val file = File.createTempFile("persistor-heif-", ".heic", cacheDir)
        var writer: HeifWriter? = null
        try {
            writer = HeifWriter.Builder(
                file.absolutePath,
                bitmap.width,
                bitmap.height,
                HeifWriter.INPUT_MODE_BITMAP,
            ).setGridEnabled(false)
                .setQuality(quality)
                .build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
            writer.close()
            writer = null
            check(file.length() > 0L) { "HEIF encoder returned an empty file" }
            return file.readBytes()
        } finally {
            try { writer?.close() } catch (_: Exception) { }
            file.delete()
        }
    }

    fun encodeAvif(bitmap: Bitmap, quality: Int, cacheDir: File): ByteArray {
        check(Build.VERSION.SDK_INT >= 34) {
            "System AVIF encoding requires Android 14 (API 34) or newer"
        }
        val file = File.createTempFile("persistor-avif-", ".avif", cacheDir)
        var writer: AvifWriter? = null
        try {
            writer = AvifWriter.Builder(
                file.absolutePath,
                bitmap.width,
                bitmap.height,
                AvifWriter.INPUT_MODE_BITMAP,
            ).setGridEnabled(false)
                .setQuality(quality)
                .build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
            writer.close()
            writer = null
            check(file.length() > 0L) { "AVIF encoder returned an empty file" }
            return file.readBytes()
        } finally {
            try { writer?.close() } catch (_: Exception) { }
            file.delete()
        }
    }
}
