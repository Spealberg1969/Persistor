package com.persistor

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Потоковая проверка структуры ZIP и CRC содержимого всех ожидаемых файлов. */
internal object ArchiveVerifier {
    /** Возвращает null при успехе или описание первой найденной ошибки. */
    fun verify(
        zipFile: File,
        expectedEntries: Map<String, Long>,
        checkCancelled: () -> Unit = {},
    ): String? {
        val seen = mutableSetOf<String>()
        var issue: String? = null
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        issue = issue ?: "в архиве обнаружена лишняя папка ${entry.name}"
                        continue
                    }

                    val expectedSize = expectedEntries[entry.name]
                    if (expectedSize == null) issue = issue ?: "лишний или неизвестный файл ${entry.name}"
                    if (!seen.add(entry.name)) issue = issue ?: "повторяющийся файл ${entry.name}"

                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { stream ->
                        while (true) {
                            checkCancelled()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            crc.update(buffer, 0, count)
                            size += count
                        }
                    }
                    if (expectedSize != null && size != expectedSize) {
                        issue = issue ?: "размер ${entry.name}: ожидалось $expectedSize, получено $size"
                    }
                    if (entry.size != size) issue = issue ?: "размер в заголовке ZIP не совпадает для ${entry.name}"
                    if (crc.value != entry.crc) issue = issue ?: "CRC не совпадает для ${entry.name}"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "ошибка чтения ZIP/CRC: ${e.message ?: e.javaClass.simpleName}"
        }

        val missing = expectedEntries.keys - seen
        if (missing.isNotEmpty()) issue = issue ?: "в архиве отсутствуют: ${missing.joinToString()}"
        return issue
    }
}
