package com.persistor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class CompressionMethod(
    val key: String,
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    ANDROID("android", "JPEG (Android)", "jpg", "image/jpeg"),
    JPEGLI("jpegli", "JPEGli (JPEG)", "jpg", "image/jpeg"),
    MOZJPEG("mozjpeg", "MozJPEG (JPEG)", "jpg", "image/jpeg"),
    WEBP("webp", "WebP lossy", "webp", "image/webp"),
    JPEG_XL("jpeg_xl", "JPEG XL", "jxl", "image/jxl"),
    HEIF("heif", "HEIF / HEIC", "heic", "image/heic"),
    AVIF("avif", "AVIF", "avif", "image/avif");

    companion object {
        fun fromKey(key: String?): CompressionMethod = entries.firstOrNull { it.key == key } ?: ANDROID
    }
}

data class ProcessSettings(
    val ssimTarget: Double = 0.95,
    val minQuality: Int = 40,              // нижняя граница Q: защита от нечитаемого текста
    val deleteTempFolder: Boolean = true,  // удалить папку со сжатыми файлами после архивации
    val deleteOriginals: Boolean = false,  // удалить исходные фото после успешной архивации
    val compressionMethod: CompressionMethod = CompressionMethod.ANDROID,
)

data class ProcessProgress(val running: Boolean = false, val message: String = "", val current: Int = 0, val total: Int = 0)

internal fun archiveZipName(folderName: String, includesPdf: Boolean): String =
    "$folderName${if (includesPdf) "_pdf" else ""}.zip"

sealed interface ProcessResult {
    data class Success(
        val zipName: String,
        val zipUri: Uri,
        val zipSize: Long,
        val files: Int,
        val originalBytes: Long,
        val deletedOriginals: Int,
        val warnings: List<String>,
        val shareUri: Uri? = null,
        val createdAt: Long = System.currentTimeMillis(),
    ) : ProcessResult

    data class Failure(val reason: String) : ProcessResult
}

class Processor(private val context: Context) {
    private val resolver get() = context.contentResolver

    suspend fun run(
        images: List<ImageItem>,
        outputTree: Uri,
        archiveTree: Uri,
        settings: ProcessSettings,
        onProgress: (String, Int, Int) -> Unit,
    ): ProcessResult = withContext(Dispatchers.Default) {
        try {
            doRun(images, outputTree, archiveTree, settings, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            ProcessResult.Failure("Не хватило памяти на обработку файла. Исходные файлы не тронуты.")
        } catch (e: Exception) {
            ProcessResult.Failure("Ошибка: ${e.message ?: e.javaClass.simpleName}. Исходные файлы не тронуты.")
        }
    }

    private suspend fun doRun(
        images: List<ImageItem>,
        outputTree: Uri,
        archiveTree: Uri,
        st: ProcessSettings,
        onProgress: (String, Int, Int) -> Unit,
    ): ProcessResult {
        if (images.isEmpty()) return ProcessResult.Failure("Не выбрано ни одного файла")

        val outRoot = DocumentFile.fromTreeUri(context, outputTree)
        val arcRoot = DocumentFile.fromTreeUri(context, archiveTree)
        if (outRoot == null || arcRoot == null || !outRoot.canWrite() || !arcRoot.canWrite()) {
            return ProcessResult.Failure("Нет доступа на запись в выбранные папки. Выберите их заново.")
        }

        // Имя папки/архива: текущая дата; при повторном запуске в тот же день — _2, _3...
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val includesPdf = images.any { it.isPdf }
        var n = 1
        fun nameFor(i: Int) = if (i == 1) date else "${date}_$i"
        while (outRoot.findFile(nameFor(n)) != null || arcRoot.findFile(archiveZipName(nameFor(n), includesPdf)) != null) n++
        val folderName = nameFor(n)

        val folder = outRoot.createDirectory(folderName)
            ?: return ProcessResult.Failure("Не удалось создать папку $folderName")

        // 1. Сжатие
        val written = mutableListOf<Pair<String, Long>>()
        val warnings = mutableListOf<String>()
        var originalBytes = 0L
        for ((index, img) in images.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress("${if (img.isPdf) "Добавление PDF" else "Сжатие"} ${index + 1}/${images.size}: ${img.name}", index, images.size)

            if (img.isPdf) {
                val (pdfName, copiedBytes) = copyPdfIntoOutput(img, folder)
                written += pdfName to copiedBytes
                originalBytes += img.size
                continue
            }

            val encoded = compressOne(img.uri, img.name, st, warnings)
            var bytes = encoded.bytes
            val isJpegInput = img.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            val jpegOutput = st.compressionMethod in setOf(
                CompressionMethod.ANDROID, CompressionMethod.JPEGLI, CompressionMethod.MOZJPEG
            )
            if (jpegOutput && isJpegInput && bytes.size.toLong() >= img.size && img.size > 0) {
                // Пережатие не помогло — оставляем оригинальные байты
                bytes = resolver.openInputStream(img.uri)?.use { it.readBytes() } ?: bytes
                warnings += "${img.name}: уже оптимален, скопирован без изменений"
            }

            val fileName = uniqueName(folder, img.name.substringBeforeLast('.') + ".${encoded.extension}")
            val doc = folder.createFile(encoded.mimeType, fileName)
                ?: return ProcessResult.Failure("Не удалось создать файл $fileName в папке $folderName")
            val out = resolver.openOutputStream(doc.uri)
                ?: return ProcessResult.Failure("Не удалось записать $fileName")
            out.use { it.write(bytes) }
            written += (doc.name ?: fileName) to bytes.size.toLong()
            originalBytes += img.size
        }

        // 2. ZIP
        onProgress("Создание архива…", images.size, images.size)
        val zipName = archiveZipName(folderName, includesPdf)
        val zipDoc = arcRoot.createFile("application/zip", zipName)
            ?: return ProcessResult.Failure("Не удалось создать архив $zipName")
        var archiveVerified = false
        try {
            val zipStream = resolver.openOutputStream(zipDoc.uri)
                ?: error("Не удалось записать архив $zipName")
            ZipOutputStream(BufferedOutputStream(zipStream)).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION) // JPEG всё равно не сжимается
                val files = folder.listFiles().filter { it.isFile }.sortedBy { it.name }
                for (f in files) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("$folderName/${f.name}"))
                    resolver.openInputStream(f.uri)?.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            // 3. Проверка архива через ZipFile: наличие, размер и CRC каждого файла.
            // Архив SAF-каталога временно копируется в cache, а содержимое читается потоково.
            onProgress("Проверка архива…", images.size, images.size)
            val activeContext = currentCoroutineContext()
            val verifyFile = File.createTempFile("persistor-verify-", ".zip", context.cacheDir)
            val verificationError = try {
                val zipIn = resolver.openInputStream(zipDoc.uri)
                    ?: error("Не удалось открыть архив для проверки")
                zipIn.use { input ->
                    verifyFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            activeContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                }

                val expected = written.associate { (name, size) -> "$folderName/$name" to size }
                ArchiveVerifier.verify(verifyFile, expected) { activeContext.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "ошибка чтения ZIP/CRC: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                verifyFile.delete()
            }
            if (verificationError != null) {
                return ProcessResult.Failure(
                    "Проверка архива не прошла: $verificationError. Исходные файлы не тронуты, " +
                        "сжатые файлы остались в папке $folderName."
                )
            }
            archiveVerified = true
        } finally {
            if (!archiveVerified) zipDoc.delete()
        }
        val zipSize = zipDoc.length()

        // 4. Очистка — только после успешной проверки
        if (st.deleteTempFolder) folder.delete()
        var deleted = 0
        if (st.deleteOriginals) {
            for (img in images) {
                val ok2 = DocumentFile.fromSingleUri(context, img.uri)?.delete() == true
                if (ok2) deleted++ else warnings += "Не удалось удалить ${img.name}"
            }
        }

        return ProcessResult.Success(zipName, zipDoc.uri, zipSize, written.size, originalBytes, deleted, warnings)
    }

    private fun copyPdfIntoOutput(item: ImageItem, outputFolder: DocumentFile): Pair<String, Long> {
        val requestedName = if (item.name.endsWith(".pdf", ignoreCase = true)) item.name else "${item.name}.pdf"
        val fileName = uniqueName(outputFolder, requestedName)
        val destination = outputFolder.createFile("application/pdf", fileName)
            ?: error("Не удалось создать файл $fileName")
        try {
            var copied = 0L
            val input = resolver.openInputStream(item.uri) ?: error("Не удалось открыть ${item.name}")
            input.use { source ->
                val output = resolver.openOutputStream(destination.uri) ?: error("Не удалось записать $fileName")
                output.use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            sink.write(buffer, 0, count)
                            copied += count
                        }
                    }
                }
            }
            check(copied > 0L) { "${item.name} пуст" }
            return (destination.name ?: fileName) to copied
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private data class EncodedImage(val bytes: ByteArray, val extension: String, val mimeType: String)

    /** Подбирает минимальное качество кодека, при котором изображение достигает заданной цели SSIM. */
    private fun compressOne(uri: Uri, name: String, st: ProcessSettings, warnings: MutableList<String>): EncodedImage {
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Не удалось прочитать $name")
        val bmp = decoded.applyOrientation(readOrientation(uri))
        try {
            var lo = st.minQuality
            var hi = 95
            var best: ByteArray? = null
            var iter = 0
            while (lo <= hi && iter < 8) {
                iter++
                val mid = (lo + hi) / 2
                val bytes = encode(bmp, mid, st.compressionMethod)
                if (ssimOf(bmp, bytes, st.compressionMethod) >= st.ssimTarget) {
                    best = bytes
                    hi = mid - 1   // качество достаточное — пробуем сжать сильнее
                } else {
                    lo = mid + 1
                }
            }
            if (best == null) {
                warnings += "$name: цель SSIM не достигнута, использовано качество 95"
                best = encode(bmp, 95, st.compressionMethod)
            }
            return EncodedImage(best, st.compressionMethod.extension, st.compressionMethod.mimeType)
        } finally {
            bmp.recycle()
        }
    }

    private fun encode(bmp: Bitmap, q: Int, method: CompressionMethod): ByteArray = when (method) {
        CompressionMethod.ANDROID -> {
            val out = ByteArrayOutputStream(512 * 1024)
            check(bmp.compress(Bitmap.CompressFormat.JPEG, q, out)) { "Android JPEG encoder failed" }
            out.toByteArray()
        }
        CompressionMethod.JPEGLI -> JpegliEncoder.encode(bmp, q)
        CompressionMethod.MOZJPEG -> NativeImageEncoder.encodeMozJpeg(bmp, q)
        CompressionMethod.WEBP -> PlatformImageEncoder.encodeWebp(bmp, q)
        CompressionMethod.JPEG_XL -> JxlEncoder.encode(bmp, q)
        CompressionMethod.HEIF -> PlatformImageEncoder.encodeHeif(bmp, q, context.cacheDir)
        CompressionMethod.AVIF -> PlatformImageEncoder.encodeAvif(bmp, q, context.cacheDir)
    }

    private fun ssimOf(orig: Bitmap, bytes: ByteArray, method: CompressionMethod): Double {
        val dec = if (method == CompressionMethod.JPEG_XL) {
            JxlEncoder.decode(bytes)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
            ?: error("Android could not decode the encoded image for SSIM validation; the device may not support this codec")
        try {
            return Ssim.compute(orig, dec)
        } finally {
            dec.recycle()
        }
    }

    private fun readOrientation(uri: Uri): Int =
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

    /** Физически поворачиваем пиксели по EXIF, потому что в результат EXIF не копируется. */
    private fun Bitmap.applyOrientation(o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> return this
        }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, m, true)
        if (rotated !== this) recycle()
        return rotated
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val c = if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
            if (dir.findFile(c) == null) return c
            i++
        }
    }
}
