package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCategorySelectionTest {
    @Test
    fun selectsJpegFilesWithoutSelectingOtherImageFormatsOrPdfs() {
        assertEquals(
            setOf(0, 3),
            categorySelectionIndices(
                jpegFlags = listOf(true, false, false, true),
                pdfFlags = listOf(false, false, true, false),
                pdf = false,
            ),
        )
    }

    @Test
    fun selectsOnlyPdfIndices() {
        assertEquals(
            setOf(1, 3),
            categorySelectionIndices(
                jpegFlags = listOf(true, false, false, true),
                pdfFlags = listOf(false, true, false, true),
                pdf = true,
            ),
        )
    }

    @Test
    fun emptyCategoryProducesEmptySelection() {
        assertEquals(
            emptySet<Int>(),
            categorySelectionIndices(jpegFlags = listOf(false, false), pdfFlags = listOf(false, false), pdf = true),
        )
    }
}
