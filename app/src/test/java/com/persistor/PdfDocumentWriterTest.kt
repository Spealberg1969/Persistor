package com.persistor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class PdfDocumentWriterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun writesSinglePagePdfWithJpegImageXObject() {
        val image = fixture("pdf-page-portrait.jpg")
        val output = File(temporaryFolder.root, "one-page.pdf")

        PdfDocumentWriter.write(listOf(PdfJpegPage(image, 40, 60)), output)

        val pdf = output.readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.contains("/Count 1"))
        assertTrue(pdf.contains("/Subtype /Image /Width 40 /Height 60"))
        assertTrue(pdf.contains("/Filter /DCTDecode"))
        assertTrue(pdf.endsWith("%%EOF\n"))
        validateWithPdfInfoIfAvailable(output, 1)
    }

    @Test
    fun writesMultipagePdfAndUsesLandscapePageForLandscapeImage() {
        val portrait = fixture("pdf-page-portrait.jpg")
        val landscape = fixture("pdf-page-landscape.jpg")
        val output = File(temporaryFolder.root, "two-page.pdf")

        PdfDocumentWriter.write(
            listOf(PdfJpegPage(portrait, 40, 60), PdfJpegPage(landscape, 60, 40)),
            output,
        )

        val pdf = output.readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(pdf.contains("/Count 2"))
        assertEquals(2, Regex("/Subtype /Image").findAll(pdf).count())
        assertTrue(pdf.contains("/MediaBox [0 0 595.276 841.890]"))
        assertTrue(pdf.contains("/MediaBox [0 0 841.890 595.276]"))
        assertTrue(pdf.contains("xref\n0 9\n"))
        validateWithPdfInfoIfAvailable(output, 2)
    }

    @Test
    fun retainsOriginalPageSizeAndUsesFullPageForOptimizedPdfPages() {
        val image = fixture("pdf-page-portrait.jpg")
        val output = File(temporaryFolder.root, "preserved-page-size.pdf")

        PdfDocumentWriter.write(
            listOf(
                PdfJpegPage(
                    file = image,
                    width = 40,
                    height = 60,
                    pageWidthPoints = 400f,
                    pageHeightPoints = 600f,
                    marginPoints = 0f,
                ),
            ),
            output,
        )

        val pdf = output.readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(pdf.contains("/MediaBox [0 0 400.000 600.000]"))
        assertTrue(pdf.contains("400.0000 0 0 600.0000 0.0000 0.0000 cm"))
        validateWithPdfInfoIfAvailable(output, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyPageList() {
        PdfDocumentWriter.write(emptyList(), File(temporaryFolder.root, "empty.pdf"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJpegPageData() {
        val invalid = File(temporaryFolder.root, "not-a-jpeg.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        PdfDocumentWriter.write(listOf(PdfJpegPage(invalid, 10, 10)), File(temporaryFolder.root, "invalid.pdf"))
    }

    private fun fixture(name: String): File {
        val target = File(temporaryFolder.root, name)
        javaClass.getResourceAsStream("/$name")!!.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun validateWithPdfInfoIfAvailable(pdf: File, expectedPages: Int) {
        val process = try {
            ProcessBuilder("pdfinfo", pdf.absolutePath).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        assertTrue(output, Regex("(?m)^Pages:\\s+$expectedPages$").containsMatchIn(output))
        val prefix = File(pdf.parentFile, "rendered-${pdf.nameWithoutExtension}")
        val renderer = try {
            ProcessBuilder("pdftoppm", "-png", pdf.absolutePath, prefix.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return
        }
        val renderOutput = renderer.inputStream.bufferedReader().use { it.readText() }
        assertEquals(renderOutput, 0, renderer.waitFor())
        val renderedPages = pdf.parentFile?.listFiles().orEmpty().count {
            it.name.startsWith("${prefix.name}-") && it.extension == "png"
        }
        assertEquals(renderOutput, expectedPages, renderedPages)
    }
}
