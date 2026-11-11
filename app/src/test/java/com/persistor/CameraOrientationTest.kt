package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun rearCameraJpegOrientationUsesCamera2ClockwiseFormula() {
        assertEquals(90, jpegOrientationDegrees(90, 0))
        assertEquals(180, jpegOrientationDegrees(90, 90))
        assertEquals(270, jpegOrientationDegrees(90, 180))
        assertEquals(0, jpegOrientationDegrees(90, 270))
        assertEquals(0, jpegOrientationDegrees(270, 90))
    }

    @Test
    fun customDimensionsFollowUprightCaptureOrientation() {
        // A typical 4:3 camera buffer is landscape; sensor + device orientation makes
        // the phone's portrait capture upright, so a 1600x1200 custom size must swap.
        assertEquals(1200 to 1600, orientedOutputDimensions(1600, 1200, 4000, 3000, 90))
        assertEquals(1600 to 1200, orientedOutputDimensions(1600, 1200, 4000, 3000, 180))
        assertEquals(1600 to 1200, orientedOutputDimensions(1600, 1200, 4000, 3000, 0))
    }

    @Test
    fun customPortraitDimensionsSwapForLandscapeCaptureAndSquaresRemainSquare() {
        assertEquals(1600 to 1200, orientedOutputDimensions(1200, 1600, 4000, 3000, 180))
        assertEquals(900 to 900, orientedOutputDimensions(900, 900, 4000, 3000, 90))
        assertEquals(1200 to 1600, orientedOutputDimensions(1600, 1200, 3000, 4000, 0))
    }
}
