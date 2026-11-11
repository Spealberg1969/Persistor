package com.persistor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CameraOutputMode(val title: String) {
    PHOTO("Фото"),
    PDF_SINGLE_PAGE("PDF · 1 страница"),
    PDF_MULTI_PAGE("PDF · много страниц");

    val isPdf: Boolean get() = this != PHOTO
}

internal enum class PdfJpegCodec(val title: String) {
    ANDROID_JPEG("Android JPEG"),
    JPEGLI("JPEGli"),
    MOZJPEG("MozJPEG"),
}

/** Produces upright, ordinary JPEG streams for PDF DCTDecode image objects. */
internal object PdfPageCompressor {
    fun encode(
        sourceJpeg: ByteArray,
        outputFile: File,
        codec: PdfJpegCodec,
        quality: Int,
    ): PdfJpegPage {
        require(quality in 60..100) { "PDF JPEG quality must be between 60 and 100" }
        val orientation = ExifInterface(ByteArrayInputStream(sourceJpeg)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val decoded = BitmapFactory.decodeByteArray(
            sourceJpeg,
            0,
            sourceJpeg.size,
            BitmapFactory.Options().apply { inMutable = true },
        ) ?: error("Не удалось декодировать снимок для PDF")
        var oriented: Bitmap? = null
        try {
            oriented = decoded.applyExifOrientation(orientation)
            val bitmap = requireNotNull(oriented)
            val encoded = when (codec) {
                PdfJpegCodec.ANDROID_JPEG -> ByteArrayOutputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        "Android JPEG не смог сжать страницу PDF"
                    }
                    stream.toByteArray()
                }
                PdfJpegCodec.JPEGLI -> JpegliEncoder.encode(bitmap, quality)
                PdfJpegCodec.MOZJPEG -> NativeImageEncoder.encodeMozJpeg(bitmap, quality, progressive = false)
            }
            require(encoded.size >= 4 && encoded[0] == 0xFF.toByte() && encoded[1] == 0xD8.toByte()) {
                "Выбранный кодек не вернул корректный JPEG для PDF"
            }
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(encoded)
            return PdfJpegPage(outputFile, bitmap.width, bitmap.height)
        } finally {
            oriented?.takeIf { it !== decoded && !it.isRecycled }?.recycle()
            if (!decoded.isRecycled) decoded.recycle()
        }
    }
}

/** Writes a generated PDF to the user-selected SAF tree and returns its display name and URI. */
internal fun savePdfToTree(context: Context, treeUri: Uri, sourcePdf: File): Pair<String, Uri> {
    val folder = DocumentFile.fromTreeUri(context, treeUri) ?: error("Не удалось открыть папку для PDF")
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    var name = "Persistor_$stamp.pdf"
    var suffix = 2
    while (folder.findFile(name) != null) {
        name = "Persistor_${stamp}_$suffix.pdf"
        suffix++
    }
    val destination = folder.createFile("application/pdf", name) ?: error("Не удалось создать PDF в выбранной папке")
    try {
        val output = context.contentResolver.openOutputStream(destination.uri, "w")
            ?: error("Не удалось открыть файл PDF для записи")
        sourcePdf.inputStream().buffered().use { input ->
            output.buffered().use { stream -> input.copyTo(stream) }
        }
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
    return (destination.name ?: name) to destination.uri
}
