package com.persistor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val sources: Set<Uri> = emptySet(),
    val cameraJpgFolder: Uri? = null,
    val cameraPdfFolder: Uri? = null,
    val output: Uri? = null,
    val archive: Uri? = null,
    val images: List<ImageItem> = emptyList(),
    val selected: Set<Uri> = emptySet(),
    val settings: ProcessSettings = ProcessSettings(),
    val mail: MailSettings = MailSettings(),
    val quickMode: Boolean = false,
    val progress: ProcessProgress = ProcessProgress(),
    val result: ProcessResult? = null,
    val history: List<HistoryEntry> = emptyList(),
    val theme: ThemeMode = ThemeMode.LIGHT,
)

private data class Prefs(
    val settings: ProcessSettings,
    val mail: MailSettings,
    val quick: Boolean,
    val history: List<HistoryEntry>,
    val theme: ThemeMode,
)

private data class FolderPrefs(
    val sources: Set<Uri>,
    val cameraJpgFolder: Uri?,
    val cameraPdfFolder: Uri?,
    val output: Uri?,
    val archive: Uri?,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FolderStore(app)
    val repo = ImageRepository(app)

    private val images = MutableStateFlow<List<ImageItem>>(emptyList())
    private val selected = MutableStateFlow<Set<Uri>>(emptySet())
    private var autoSentFor: ProcessResult? = null

    private val folders = combine(
        store.sources, store.cameraJpgFolder, store.cameraPdfFolder, store.output, store.archive,
    ) { sources, jpg, pdf, output, archive -> FolderPrefs(sources, jpg, pdf, output, archive) }
    private val inputFolders = combine(store.sources, store.cameraJpgFolder, store.cameraPdfFolder) { sources, jpg, pdf ->
        sources + listOfNotNull(jpg, pdf)
    }
    private val items = combine(images, selected) { i, s -> i to s }
    private val prefs = combine(store.settings, store.mail, store.quickMode, store.history, store.theme) { a, b, c, d, e ->
        Prefs(a, b, c, d, e)
    }

    val state: StateFlow<UiState> = combine(
        folders, items, prefs, ProcessingState.progress, ProcessingState.result
    ) { f, im, p, pr, r ->
        UiState(
            sources = f.sources,
            cameraJpgFolder = f.cameraJpgFolder,
            cameraPdfFolder = f.cameraPdfFolder,
            output = f.output,
            archive = f.archive,
            images = im.first,
            selected = im.second,
            settings = p.settings,
            mail = p.mail,
            quickMode = p.quick,
            progress = pr,
            result = r,
            history = p.history,
            theme = p.theme,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch { inputFolders.collect { reload(it) } }
        // После окончания обработки обновляем список (исходники могли быть удалены)
        viewModelScope.launch {
            ProcessingState.result.filterNotNull().collect {
                val current = state.value
                reload(current.sources + listOfNotNull(current.cameraJpgFolder, current.cameraPdfFolder))
            }
        }
    }

    private suspend fun reload(src: Set<Uri>) {
        val list = repo.list(src)
        images.value = list
        selected.value = selected.value intersect list.map { it.uri }.toSet()
    }

    private fun persist(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun addSource(uri: Uri) { persist(uri); viewModelScope.launch { store.addSource(uri) } }
    fun removeSource(uri: Uri) = viewModelScope.launch { store.removeSource(uri) }
    fun setCameraJpgFolder(uri: Uri) { persist(uri); viewModelScope.launch { store.setCameraJpgFolder(uri) } }
    fun setCameraPdfFolder(uri: Uri) { persist(uri); viewModelScope.launch { store.setCameraPdfFolder(uri) } }
    fun setOutput(uri: Uri) { persist(uri); viewModelScope.launch { store.setOutput(uri) } }
    fun setArchive(uri: Uri) { persist(uri); viewModelScope.launch { store.setArchive(uri) } }

    fun setSsim(v: Double) = viewModelScope.launch { store.setSsim(v) }
    fun setCompressPdf(v: Boolean) = viewModelScope.launch { store.setCompressPdf(v) }
    fun setDeleteTemp(v: Boolean) = viewModelScope.launch { store.setDeleteTemp(v) }
    fun setDeleteOriginals(v: Boolean) = viewModelScope.launch { store.setDeleteOriginals(v) }
    fun setCompressionMethod(v: CompressionMethod) = viewModelScope.launch { store.setCompressionMethod(v) }
    fun setQuickMode(v: Boolean) = viewModelScope.launch { store.setQuickMode(v) }

    fun addRecipient(email: String) = viewModelScope.launch { store.addRecipient(email) }
    fun removeRecipient(email: String) = viewModelScope.launch { store.removeRecipient(email) }
    fun setRecipientSelected(email: String, on: Boolean) = viewModelScope.launch { store.setRecipientSelected(email, on) }
    fun setSubject(v: String) = viewModelScope.launch { store.setSubject(v) }
    fun setAutoMail(v: Boolean) = viewModelScope.launch { store.setAutoMail(v) }
    fun clearHistory() = viewModelScope.launch { store.clearHistory() }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { store.setTheme(v) }

    fun toggle(uri: Uri) {
        selected.value = if (uri in selected.value) selected.value - uri else selected.value + uri
    }

    fun selectAll() { selected.value = images.value.map { it.uri }.toSet() }
    fun selectCategory(pdf: Boolean) {
        val current = images.value
        val indices = categorySelectionIndices(current.map { it.isJpeg }, current.map { it.isPdf }, pdf)
        selected.value = indices.mapTo(linkedSetOf()) { current[it].uri }
    }
    fun clearSelection() { selected.value = emptySet() }
    fun dismissResult() { ProcessingState.result.value = null }
    fun refreshImages() {
        viewModelScope.launch {
            val current = state.value
            reload(current.sources + listOfNotNull(current.cameraJpgFolder, current.cameraPdfFolder))
        }
    }

    /** true один раз для каждого результата: чтобы Gmail не открывался повторно при повороте экрана. */
    fun consumeAutoSend(r: ProcessResult): Boolean {
        if (autoSentFor === r) return false
        autoSentFor = r
        return true
    }

    /**
     * Открывает Gmail с последним архивом и записывает это в статистику.
     * Единая точка для кнопки в окне результата, автооткрытия и кнопки в уведомлении.
     */
    fun sendLast(context: Context): Boolean {
        val r = ProcessingState.lastSuccess ?: return false
        val uri = r.shareUri ?: return false
        val plan = ProcessingState.lastPlan
        val s = state.value
        val to = plan?.to ?: s.mail.selected.sorted()
        val subject = subjectFor(plan?.subject ?: s.mail.subject, r.zipName)
        Mailer.launch(context, uri, to, subject)
        viewModelScope.launch { store.markGmailOpened(r.createdAt, System.currentTimeMillis(), to) }
        return true
    }

    /** all = true: обработать все изображения (быстрый режим), иначе только выбранные. */
    fun process(all: Boolean = false) {
        val s = state.value
        val out = s.output ?: return
        val arc = s.archive ?: return
        if (ProcessingState.progress.value.running) return
        val chosen = if (all) images.value else images.value.filter { it.uri in selected.value }
        if (chosen.isEmpty()) return

        val plan = s.mail.selected.takeIf { it.isNotEmpty() }?.let { MailPlan(it.sorted(), s.mail.subject) }
        ProcessingState.job = ProcessJob(chosen, out, arc, s.settings, plan)
        ProcessingState.result.value = null
        ProcessingState.progress.value = ProcessProgress(true, "Запуск…", 0, chosen.size)

        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java))
        } catch (e: Exception) {
            ProcessingState.job = null
            ProcessingState.progress.value = ProcessProgress()
            ProcessingState.result.value = ProcessResult.Failure("Не удалось запустить обработку: ${e.message}")
        }
    }
}
