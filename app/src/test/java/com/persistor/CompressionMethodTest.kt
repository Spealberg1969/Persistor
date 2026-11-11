package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionMethodTest {
    @Test
    fun persistedKeysAreUniqueAndUnknownKeysRemainBackwardSafe() {
        assertEquals(CompressionMethod.entries.size, CompressionMethod.entries.map { it.key }.toSet().size)
        assertEquals(CompressionMethod.ANDROID, CompressionMethod.fromKey(null))
        assertEquals(CompressionMethod.ANDROID, CompressionMethod.fromKey("android"))
        assertEquals(CompressionMethod.JPEGLI, CompressionMethod.fromKey("jpegli"))
        assertEquals(CompressionMethod.ANDROID, CompressionMethod.fromKey("future-unknown-codec"))
        assertEquals(CompressionMethod.ANDROID, CompressionMethod.fromKey("tiff_g4"))
        assertEquals(CompressionMethod.ANDROID, CompressionMethod.fromKey("jbig2"))
    }

    @Test
    fun everyCodecHasAnOutputExtensionAndMimeType() {
        CompressionMethod.entries.forEach { method ->
            assertTrue("Missing extension for ${method.name}", method.extension.isNotBlank())
            assertTrue("Missing MIME type for ${method.name}", method.mimeType.contains('/'))
        }
        assertEquals("jpg", CompressionMethod.MOZJPEG.extension)
        assertEquals("image/jpeg", CompressionMethod.JPEGLI.mimeType)
        assertEquals("webp", CompressionMethod.WEBP.extension)
        assertEquals("heic", CompressionMethod.HEIF.extension)
        assertEquals("avif", CompressionMethod.AVIF.extension)
        assertEquals("jxl", CompressionMethod.JPEG_XL.extension)
        assertFalse(CompressionMethod.entries.any { it.key == "tiff_g4" || it.key == "jbig2" })
    }
}
