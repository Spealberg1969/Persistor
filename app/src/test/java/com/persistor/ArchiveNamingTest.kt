package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveNamingTest {
    @Test
    fun pdfArchiveReceivesPdfSuffixBeforeZipExtension() {
        assertEquals("2026-11-12_pdf.zip", archiveZipName("2026-11-12", includesPdf = true))
    }

    @Test
    fun imageOnlyArchiveKeepsExistingName() {
        assertEquals("2026-11-12.zip", archiveZipName("2026-11-12", includesPdf = false))
    }
}
