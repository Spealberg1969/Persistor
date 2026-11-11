package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CameraOverlayTest {
    @Test
    fun dateTimeAndNameAreIncludedOnlyWhenSelected() {
        val settings = CameraOverlaySettings(
            enabled = true,
            showDateTime = true,
            showName = true,
            name = "  Oleg Spivakov  ",
        )

        val lines = CameraOverlayRenderer.lines(settings, 1_762_848_000_000L, Locale.US)

        assertEquals(2, lines.size)
        assertTrue(lines.first().matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2}")))
        assertEquals("Oleg Spivakov", lines.last())
    }

    @Test
    fun blankOrDisabledNameIsNotRendered() {
        val blankName = CameraOverlaySettings(showDateTime = false, showName = true, name = "  ")
        val disabledName = CameraOverlaySettings(showDateTime = false, showName = false, name = "Oleg")

        assertTrue(CameraOverlayRenderer.lines(blankName, 0L).isEmpty())
        assertTrue(CameraOverlayRenderer.lines(disabledName, 0L).isEmpty())
    }

    @Test
    fun everyOverlayPositionHasStableKeyAndFallback() {
        CameraOverlayPosition.entries.forEach { position ->
            assertEquals(position, CameraOverlayPosition.fromKey(position.key))
        }
        assertEquals(CameraOverlayPosition.BOTTOM_RIGHT, CameraOverlayPosition.fromKey("unknown"))
    }

    @Test
    fun defaultOverlayDoesNotChangeExistingCameraPhotos() {
        val defaults = CameraOverlaySettings()

        assertFalse(defaults.enabled)
        assertTrue(defaults.showDateTime)
        assertFalse(defaults.showName)
        assertEquals(CameraOverlayPosition.BOTTOM_RIGHT, defaults.position)
        assertEquals(18, defaults.fontSizeSp)
    }

    @Test
    fun disabledOverlayLeavesJpegBytesUntouched() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

        assertSame(jpeg, CameraOverlayRenderer.applyToJpeg(jpeg, CameraOverlaySettings(), 0L))
    }
}
