package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveNamingTest {
    @Test
    fun jpegOnlyOutputUsesJpegSuffix() {
        assertEquals("12.11.2026_jpeg", processedFolderName("12.11.2026", includesJpeg = true, includesPdf = false))
        assertEquals("12.11.2026_jpeg.zip", archiveZipName("12.11.2026_jpeg"))
    }

    @Test
    fun pdfOnlyOutputUsesPdfSuffix() {
        assertEquals("12.11.2026_pdf", processedFolderName("12.11.2026", includesJpeg = false, includesPdf = true))
        assertEquals("12.11.2026_pdf.zip", archiveZipName("12.11.2026_pdf"))
    }

    @Test
    fun mixedOutputUsesBothSuffixesWithoutDuplicatingPdfTag() {
        val folder = processedFolderName("12.11.2026", includesJpeg = true, includesPdf = true)
        assertEquals("12.11.2026_jpeg_pdf", folder)
        assertEquals("12.11.2026_jpeg_pdf.zip", archiveZipName(folder))
    }

    @Test
    fun duplicateRunsGetSequenceAfterTypeSuffix() {
        assertEquals("12.11.2026_pdf_2", processedFolderName("12.11.2026", false, true, sequence = 2))
    }
}
