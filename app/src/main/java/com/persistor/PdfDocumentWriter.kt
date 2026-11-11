package com.persistor

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

internal data class PdfJpegPage(val file: File, val width: Int, val height: Int)

/** Minimal standards-based PDF writer; JPEG page bytes remain JPEG image XObjects. */
internal object PdfDocumentWriter {
    private const val A4_PORTRAIT_WIDTH = 595.276f
    private const val A4_PORTRAIT_HEIGHT = 841.890f
    private const val PAGE_MARGIN = 18f

    fun write(pages: List<PdfJpegPage>, destination: File) {
        require(pages.isNotEmpty()) { "PDF must contain at least one page" }
        pages.forEach { page ->
            require(page.width > 0 && page.height > 0) { "Invalid image page dimensions" }
            require(page.file.isFile && page.file.length() >= 4L) { "JPEG page file is missing or empty" }
            RandomAccessFile(page.file, "r").use { jpeg ->
                require(jpeg.readUnsignedByte() == 0xFF && jpeg.readUnsignedByte() == 0xD8) {
                    "Page data is not a JPEG image"
                }
            }
        }

        destination.parentFile?.mkdirs()
        RandomAccessFile(destination, "rw").use { output ->
            output.setLength(0L)
            writeBytes(output, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(StandardCharsets.ISO_8859_1))
            val objectCount = 2 + pages.size * 3
            val offsets = LongArray(objectCount + 1)

            fun beginObject(id: Int) {
                offsets[id] = output.filePointer
                writeAscii(output, "$id 0 obj\n")
            }
            fun endObject() = writeAscii(output, "endobj\n")

            beginObject(1)
            writeAscii(output, "<< /Type /Catalog /Pages 2 0 R >>\n")
            endObject()

            val pageRefs = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
            beginObject(2)
            writeAscii(output, "<< /Type /Pages /Kids [$pageRefs] /Count ${pages.size} >>\n")
            endObject()

            pages.forEachIndexed { index, page ->
                val pageObject = 3 + index * 3
                val contentObject = pageObject + 1
                val imageObject = pageObject + 2
                val portrait = page.height >= page.width
                val pageWidth = if (portrait) A4_PORTRAIT_WIDTH else A4_PORTRAIT_HEIGHT
                val pageHeight = if (portrait) A4_PORTRAIT_HEIGHT else A4_PORTRAIT_WIDTH
                val scale = min(
                    (pageWidth - PAGE_MARGIN * 2f) / page.width,
                    (pageHeight - PAGE_MARGIN * 2f) / page.height,
                )
                val drawWidth = page.width * scale
                val drawHeight = page.height * scale
                val drawX = (pageWidth - drawWidth) / 2f
                val drawY = (pageHeight - drawHeight) / 2f
                val content = String.format(
                    Locale.US,
                    "q\n%.4f 0 0 %.4f %.4f %.4f cm\n/Im0 Do\nQ\n",
                    drawWidth,
                    drawHeight,
                    drawX,
                    drawY,
                ).toByteArray(StandardCharsets.US_ASCII)

                beginObject(pageObject)
                writeAscii(
                    output,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${number(pageWidth)} ${number(pageHeight)}] " +
                        "/Resources << /XObject << /Im0 $imageObject 0 R >> >> /Contents $contentObject 0 R >>\n",
                )
                endObject()

                beginObject(contentObject)
                writeAscii(output, "<< /Length ${content.size} >>\nstream\n")
                writeBytes(output, content)
                writeAscii(output, "endstream\n")
                endObject()

                beginObject(imageObject)
                writeAscii(
                    output,
                    "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.file.length()} >>\nstream\n",
                )
                page.file.inputStream().buffered().use { input -> input.copyTo(RandomAccessFileOutputStream(output)) }
                writeAscii(output, "\nendstream\n")
                endObject()
            }

            val xrefOffset = output.filePointer
            writeAscii(output, "xref\n0 ${objectCount + 1}\n0000000000 65535 f \n")
            for (id in 1..objectCount) {
                writeAscii(output, String.format(Locale.US, "%010d 00000 n \n", offsets[id]))
            }
            writeAscii(
                output,
                "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n",
            )
        }
        require(destination.length() > 0L) { "PDF output is empty" }
    }

    private fun number(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun writeAscii(file: RandomAccessFile, text: String) =
        writeBytes(file, text.toByteArray(StandardCharsets.US_ASCII))

    private fun writeBytes(file: RandomAccessFile, bytes: ByteArray) = file.write(bytes)

    /** Lets InputStream.copyTo write to RandomAccessFile without buffering the whole JPEG in memory. */
    private class RandomAccessFileOutputStream(private val file: RandomAccessFile) : java.io.OutputStream() {
        override fun write(value: Int) = file.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = file.write(buffer, offset, length)
    }
}
