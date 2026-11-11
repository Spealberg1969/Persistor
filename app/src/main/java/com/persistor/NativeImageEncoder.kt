package com.persistor

import android.graphics.Bitmap

/** Native codecs bundled with the Android app for the experimental format matrix. */
internal object NativeImageEncoder {
    private val mozJpegLoadError: Throwable? = load("persistor_mozjpeg")
    private val documentCodecsLoadError: Throwable? = load("persistor_document_codecs")

    private fun load(name: String): Throwable? = try {
        System.loadLibrary(name)
        null
    } catch (error: UnsatisfiedLinkError) {
        error
    } catch (error: SecurityException) {
        error
    }

    fun encodeMozJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        mozJpegLoadError?.let { throw IllegalStateException("MozJPEG native library is unavailable", it) }
        return nativeEncodeMozJpeg(bitmap, quality)
    }

    fun encodeTiffG4(bitmap: Bitmap, threshold: Int = 160): ByteArray {
        documentCodecsLoadError?.let { throw IllegalStateException("TIFF/JBIG2 native library is unavailable", it) }
        return nativeEncodeTiffG4(bitmap, threshold)
    }

    fun encodeJbig2(bitmap: Bitmap, threshold: Int = 160): ByteArray {
        documentCodecsLoadError?.let { throw IllegalStateException("TIFF/JBIG2 native library is unavailable", it) }
        return nativeEncodeJbig2(bitmap, threshold)
    }

    private external fun nativeEncodeMozJpeg(bitmap: Bitmap, quality: Int): ByteArray
    private external fun nativeEncodeTiffG4(bitmap: Bitmap, threshold: Int): ByteArray
    private external fun nativeEncodeJbig2(bitmap: Bitmap, threshold: Int): ByteArray
}
