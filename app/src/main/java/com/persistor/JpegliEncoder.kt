package com.persistor

import android.graphics.Bitmap

/** JPEGli-backed JPEG encoder exposed through the app's JNI library. */
internal object JpegliEncoder {
    private val loadError: Throwable? = try {
        System.loadLibrary("persistor_jpegli")
        null
    } catch (error: UnsatisfiedLinkError) {
        error
    } catch (error: SecurityException) {
        error
    }

    fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        loadError?.let { error ->
            throw IllegalStateException("JPEGli native library is unavailable", error)
        }
        return nativeEncode(bitmap, quality)
    }

    private external fun nativeEncode(bitmap: Bitmap, quality: Int): ByteArray
}
