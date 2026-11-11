package com.persistor

import android.graphics.Bitmap

/** Native MozJPEG encoder bundled with the Android app. */
internal object NativeImageEncoder {
    private val mozJpegLoadError: Throwable? = load("persistor_mozjpeg")

    private fun load(name: String): Throwable? = try {
        System.loadLibrary(name)
        null
    } catch (error: UnsatisfiedLinkError) {
        error
    } catch (error: SecurityException) {
        error
    }

    fun encodeMozJpeg(bitmap: Bitmap, quality: Int, progressive: Boolean = true): ByteArray {
        mozJpegLoadError?.let { throw IllegalStateException("MozJPEG native library is unavailable", it) }
        return nativeEncodeMozJpeg(bitmap, quality, progressive)
    }

    private external fun nativeEncodeMozJpeg(bitmap: Bitmap, quality: Int, progressive: Boolean): ByteArray
}
