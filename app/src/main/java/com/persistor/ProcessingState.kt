package com.persistor

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

data class MailSettings(
    val recipients: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val subject: String = "Документы {date}",
    val autoOpen: Boolean = true,
)

data class MailPlan(val to: List<String>, val subject: String)

data class ProcessJob(
    val images: List<ImageItem>,
    val outputTree: Uri,
    val archiveTree: Uri,
    val settings: ProcessSettings,
    val mail: MailPlan?,
)

/**
 * Запись статистики об одном архиве.
 * id = время создания архива (мс). gmailOpenedAt = когда архив был передан в Gmail
 * (сам факт отправки Gmail приложению не сообщает).
 */
data class HistoryEntry(
    val id: Long,
    val zipName: String,
    val sizeBytes: Long,
    val files: Int,
    val to: List<String>,
    val gmailOpenedAt: Long?,
)

/** Общее состояние между сервисом обработки и интерфейсом. */
object ProcessingState {
    val progress = MutableStateFlow(ProcessProgress())
    val result = MutableStateFlow<ProcessResult?>(null)
    @Volatile var job: ProcessJob? = null
    @Volatile var lastSuccess: ProcessResult.Success? = null
    @Volatile var lastPlan: MailPlan? = null
}
