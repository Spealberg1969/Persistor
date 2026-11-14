package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentScannerTest {
    @Test
    fun qualitySearchChoosesHighestQualityWithinByteTarget() {
        val visited = mutableListOf<Int>()
        val result = JpegByteTargetSearch.choose(targetBytes = 750, iterations = 7) { quality ->
            visited += quality
            ByteArray(quality * 10)
        }

        assertTrue(result.targetReached)
        assertEquals(75, result.quality)
        assertEquals(750, result.bytes.size)
        assertTrue(visited.size <= 7)
    }

    @Test
    fun qualitySearchReturnsImmediatelyWhenMaximumQualityFits() {
        val visited = mutableListOf<Int>()
        val result = JpegByteTargetSearch.choose(targetBytes = 1000) { quality ->
            visited += quality
            ByteArray(quality * 10)
        }

        assertEquals(95, result.quality)
        assertTrue(result.targetReached)
        assertEquals(listOf(95, 45), visited)
        assertEquals(450, result.minQualityBytes.size)
    }

    @Test
    fun qualitySearchReportsWhenEvenMinimumQualityExceedsTarget() {
        val result = JpegByteTargetSearch.choose(targetBytes = 100, iterations = 7) { quality ->
            ByteArray(quality * 10)
        }

        assertFalse(result.targetReached)
        assertEquals(45, result.quality)
        assertEquals(450, result.bytes.size)
        assertEquals(450, result.minQualityBytes.size)
    }

    @Test
    fun captureProfileSelectsSmallestAdequateFourByThreeCameraSize() {
        val selected = DocumentCaptureProfile.STANDARD.selectCaptureSize(
            listOf(
                DocumentCaptureSize(1600, 1200),
                DocumentCaptureSize(2560, 1440),
                DocumentCaptureSize(2688, 2016),
                DocumentCaptureSize(4000, 3000),
            ),
        )

        assertEquals(DocumentCaptureSize(2688, 2016), selected)
    }

    @Test
    fun captureProfileFallsBackToLargestAvailableCameraSize() {
        val selected = DocumentCaptureProfile.PRINT.selectCaptureSize(
            listOf(DocumentCaptureSize(1280, 720), DocumentCaptureSize(1920, 1080)),
        )

        assertEquals(DocumentCaptureSize(1920, 1080), selected)
    }

    @Test
    fun detectorRejectsBlankFrame() {
        val gray = ByteArray(320 * 240) { 220.toByte() }
        assertNull(DocumentQuadDetector.detectGray(320, 240, gray))
    }

    @Test
    fun mapsManualPointsFromNarrowerViewportIntoWiderSource() {
        val viewport = sampleQuad()
        val mapped = DocumentImageProcessor.mapViewportQuadToFrame(viewport, sourceAspect = 2f, viewportAspect = 1f)

        assertEquals(0.30f, mapped.topLeft.x, 0.001f)
        assertEquals(0.70f, mapped.topRight.x, 0.001f)
        assertEquals(viewport.topLeft.y, mapped.topLeft.y, 0.001f)
    }

    @Test
    fun mapsManualPointsFromShorterViewportIntoTallerSource() {
        val viewport = sampleQuad()
        val mapped = DocumentImageProcessor.mapViewportQuadToFrame(viewport, sourceAspect = 1f, viewportAspect = 2f)

        assertEquals(0.30f, mapped.topLeft.y, 0.001f)
        assertEquals(0.70f, mapped.bottomLeft.y, 0.001f)
        assertEquals(viewport.topLeft.x, mapped.topLeft.x, 0.001f)
    }

    @Test
    fun detectorFindsPerspectiveDocumentInSyntheticFrame() {
        val width = 320
        val height = 240
        val gray = ByteArray(width * height) { 225.toByte() }
        val corners = listOf(
            46 to 28,
            274 to 42,
            260 to 210,
            54 to 198,
        )
        corners.indices.forEach { i ->
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            drawLine(gray, width, a.first, a.second, b.first, b.second, 20)
        }

        val quad = DocumentQuadDetector.detectGray(width, height, gray)

        assertNotNull("Expected the high-contrast document outline to be detected", quad)
        val found = quad!!
        assertEquals(0.14f, found.topLeft.x, 0.08f)
        assertEquals(0.12f, found.topLeft.y, 0.08f)
        assertEquals(0.85f, found.topRight.x, 0.08f)
        assertEquals(0.18f, found.topRight.y, 0.08f)
        assertEquals(0.81f, found.bottomRight.x, 0.08f)
        assertEquals(0.87f, found.bottomRight.y, 0.08f)
        assertEquals(0.17f, found.bottomLeft.x, 0.08f)
        assertEquals(0.82f, found.bottomLeft.y, 0.08f)
        assertTrue(found.confidence >= 0.25f)
    }

    private fun drawLine(gray: ByteArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy
        while (true) {
            for (oy in -1..1) for (ox in -1..1) {
                val px = x + ox
                val py = y + oy
                if (px in 0 until width && py >= 0 && py * width + px < gray.size) {
                    gray[py * width + px] = value.toByte()
                }
            }
            if (x == x1 && y == y1) break
            val twice = 2 * error
            if (twice >= dy) { error += dy; x += sx }
            if (twice <= dx) { error += dx; y += sy }
        }
    }

    private fun sampleQuad() = DocumentQuad(
        topLeft = DocumentPoint(0.1f, 0.1f),
        topRight = DocumentPoint(0.9f, 0.1f),
        bottomRight = DocumentPoint(0.9f, 0.9f),
        bottomLeft = DocumentPoint(0.1f, 0.9f),
        confidence = 1f,
    )
}
