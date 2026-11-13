package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOptimizationTest {
    @Test
    fun choosesLowestQualityThatMeetsSsimTarget() {
        val visited = mutableListOf<Int>()
        val choice = PdfQualitySearch.choose(targetSsim = 0.72) { quality ->
            visited += quality
            byteArrayOf(quality.toByte()) to quality / 100.0
        }

        assertEquals(72, choice.quality)
        assertEquals(72, choice.bytes.single().toInt() and 0xFF)
        assertEquals(0.72, choice.ssim, 0.00001)
        assertTrue(choice.targetReached)
        assertTrue(visited.size <= 7)
    }

    @Test
    fun usesMaximumQualityAndReportsWhenTargetCannotBeReached() {
        val choice = PdfQualitySearch.choose(targetSsim = 0.99) { quality ->
            byteArrayOf(quality.toByte()) to 0.95
        }

        assertEquals(95, choice.quality)
        assertEquals(0.95, choice.ssim, 0.00001)
        assertFalse(choice.targetReached)
    }

    @Test
    fun mapsOnlyPdfCompatibleJpegCodecsDirectly() {
        assertEquals(PdfJpegCodec.ANDROID_JPEG, pdfJpegCodecFor(CompressionMethod.ANDROID))
        assertEquals(PdfJpegCodec.JPEGLI, pdfJpegCodecFor(CompressionMethod.JPEGLI))
        assertEquals(PdfJpegCodec.MOZJPEG, pdfJpegCodecFor(CompressionMethod.MOZJPEG))
        assertTrue(CompressionMethod.ANDROID.supportsDirectPdfJpeg())
        assertTrue(CompressionMethod.JPEGLI.supportsDirectPdfJpeg())
        assertTrue(CompressionMethod.MOZJPEG.supportsDirectPdfJpeg())
    }

    @Test
    fun unsupportedImageFormatsUsePdfCompatibleAndroidJpegFallback() {
        listOf(
            CompressionMethod.WEBP,
            CompressionMethod.JPEG_XL,
            CompressionMethod.HEIF,
            CompressionMethod.AVIF,
        ).forEach { method ->
            assertEquals(PdfJpegCodec.ANDROID_JPEG, pdfJpegCodecFor(method))
            assertFalse(method.supportsDirectPdfJpeg())
        }
    }
}
