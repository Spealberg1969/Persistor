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
        assertEquals("tif", CompressionMethod.TIFF_G4.extension)
        assertEquals("jb2", CompressionMethod.JBIG2.extension)
    }

    @Test
    fun onlyBilevelCodecsAreMarkedMonochrome() {
        assertTrue(CompressionMethod.TIFF_G4.monochrome)
        assertTrue(CompressionMethod.JBIG2.monochrome)
        assertFalse(CompressionMethod.WEBP.monochrome)
        assertFalse(CompressionMethod.JPEG_XL.monochrome)
    }
}
