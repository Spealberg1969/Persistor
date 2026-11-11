package com.persistor

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveVerifierTest {
    @Test
    fun validArchivePassesSizeAndCrcChecks() {
        withTempZip { zip ->
            val payload = "invoice: 12345\namount: 42.00\n".toByteArray()
            writeStoredZip(zip, mapOf("2026-11-10/invoice.jpg" to payload))

            assertNull(ArchiveVerifier.verify(zip, mapOf("2026-11-10/invoice.jpg" to payload.size.toLong())))
        }
    }

    @Test
    fun sameSizePayloadWithChangedByteFailsCrcCheck() {
        withTempZip { zip ->
            val payload = "document with important numbers 9876543210".toByteArray()
            val entryName = "2026-11-10/document.jpg"
            writeStoredZip(zip, mapOf(entryName to payload))
            corruptStoredEntryByte(zip)

            val error = ArchiveVerifier.verify(zip, mapOf(entryName to payload.size.toLong()))
            assertNotNull("Same-size corruption must not pass CRC verification", error)
        }
    }

    @Test
    fun missingExpectedEntryFailsVerification() {
        withTempZip { zip ->
            val payload = "one document".toByteArray()
            writeStoredZip(zip, mapOf("2026-11-10/one.jpg" to payload))

            val error = ArchiveVerifier.verify(
                zip,
                mapOf(
                    "2026-11-10/one.jpg" to payload.size.toLong(),
                    "2026-11-10/two.jpg" to 10L,
                ),
            )
            assertNotNull("Missing expected ZIP entries must be reported", error)
        }
    }

    @Test
    fun unexpectedEntryFailsVerification() {
        withTempZip { zip ->
            val expected = "expected".toByteArray()
            val extra = "extra".toByteArray()
            writeStoredZip(
                zip,
                mapOf(
                    "2026-11-10/expected.jpg" to expected,
                    "2026-11-10/extra.jpg" to extra,
                ),
            )

            val error = ArchiveVerifier.verify(
                zip,
                mapOf("2026-11-10/expected.jpg" to expected.size.toLong()),
            )
            assertNotNull("Unexpected ZIP entries must be reported", error)
        }
    }

    private fun writeStoredZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                val crc = CRC32().apply { update(bytes) }.value
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Flips a payload bit in a STORED entry while leaving ZIP metadata and byte count intact. */
    private fun corruptStoredEntryByte(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteArray(30)
            raf.readFully(header)
            require(header[0] == 0x50.toByte() && header[1] == 0x4b.toByte())
            require(header[2] == 0x03.toByte() && header[3] == 0x04.toByte())
            val nameLength = littleEndianU16(header, 26)
            val extraLength = littleEndianU16(header, 28)
            val payloadOffset = 30L + nameLength + extraLength
            val compressedSize = littleEndianU32(header, 18)
            require(compressedSize > 0)
            val offset = payloadOffset + compressedSize / 2
            raf.seek(offset)
            val original = raf.readUnsignedByte()
            raf.seek(offset)
            raf.writeByte(original xor 0x01)
        }
    }

    private fun littleEndianU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private inline fun withTempZip(block: (File) -> Unit) {
        val zip = File.createTempFile("archive-verifier-test-", ".zip")
        try {
            block(zip)
        } finally {
            zip.delete()
        }
    }
}
