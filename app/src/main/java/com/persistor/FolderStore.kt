package com.persistor

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("settings")

private fun parseHistory(json: String): List<HistoryEntry> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val t = o.getJSONArray("to")
        HistoryEntry(
            id = o.getLong("id"),
            zipName = o.getString("zip"),
            sizeBytes = o.getLong("size"),
            files = o.getInt("files"),
            to = (0 until t.length()).map { t.getString(it) },
            gmailOpenedAt = if (o.isNull("sent")) null else o.getLong("sent"),
        )
    }
}.getOrDefault(emptyList())

private fun serializeHistory(list: List<HistoryEntry>): String {
    val a = JSONArray()
    list.forEach { e ->
        a.put(
            JSONObject()
                .put("id", e.id)
                .put("zip", e.zipName)
                .put("size", e.sizeBytes)
                .put("files", e.files)
                .put("to", JSONArray(e.to))
                .put("sent", e.gmailOpenedAt ?: JSONObject.NULL)
        )
    }
    return a.toString()
}

/** Хранит папки (SAF tree URI), настройки, почту, быстрый режим и статистику. */
class FolderStore(private val context: Context) {
    private val SOURCES = stringSetPreferencesKey("sources")
    private val CAMERA_JPG_FOLDER = stringPreferencesKey("camera_jpg_folder")
    private val CAMERA_PDF_FOLDER = stringPreferencesKey("camera_pdf_folder")
    private val OUTPUT = stringPreferencesKey("output")
    private val ARCHIVE = stringPreferencesKey("archive")
    private val SSIM = doublePreferencesKey("ssim")
    private val DEL_TEMP = booleanPreferencesKey("del_temp")
    private val DEL_ORIG = booleanPreferencesKey("del_orig")
    private val COMPRESSION_METHOD = stringPreferencesKey("compression_method")
    private val RECIPIENTS = stringSetPreferencesKey("recipients")
    private val SELECTED = stringSetPreferencesKey("recipients_selected")
    private val SUBJECT = stringPreferencesKey("mail_subject")
    private val AUTO_MAIL = booleanPreferencesKey("mail_auto")
    private val QUICK = booleanPreferencesKey("quick_mode")
    private val HISTORY = stringPreferencesKey("history")
    private val THEME = stringPreferencesKey("theme")
    private val CAM_RES = stringPreferencesKey("camera_res")
    private val CAM_OVERLAY_ENABLED = booleanPreferencesKey("camera_overlay_enabled")
    private val CAM_OVERLAY_DATE_TIME = booleanPreferencesKey("camera_overlay_date_time")
    private val CAM_OVERLAY_NAME_ENABLED = booleanPreferencesKey("camera_overlay_name_enabled")
    private val CAM_OVERLAY_NAME = stringPreferencesKey("camera_overlay_name")
    private val CAM_OVERLAY_POSITION = stringPreferencesKey("camera_overlay_position")
    private val CAM_OVERLAY_FONT_SIZE = androidx.datastore.preferences.core.intPreferencesKey("camera_overlay_font_size")

    val sources: Flow<Set<Uri>> = context.dataStore.data
        .map { p -> p[SOURCES].orEmpty().map(Uri::parse).toSet() }
    // Старым установкам сохраняем прежнюю папку камеры, пока пользователь не выберет новые назначения.
    val cameraJpgFolder: Flow<Uri?> = context.dataStore.data.map { p ->
        p[CAMERA_JPG_FOLDER]?.let(Uri::parse)
            ?: p[SOURCES].orEmpty().sorted().firstOrNull()?.let(Uri::parse)
    }
    val cameraPdfFolder: Flow<Uri?> = context.dataStore.data.map { p ->
        p[CAMERA_PDF_FOLDER]?.let(Uri::parse)
            ?: p[SOURCES].orEmpty().sorted().firstOrNull()?.let(Uri::parse)
    }
    val output: Flow<Uri?> = context.dataStore.data.map { p -> p[OUTPUT]?.let(Uri::parse) }
    val archive: Flow<Uri?> = context.dataStore.data.map { p -> p[ARCHIVE]?.let(Uri::parse) }

    val settings: Flow<ProcessSettings> = context.dataStore.data.map { p ->
        ProcessSettings(
            ssimTarget = p[SSIM] ?: 0.95,
            deleteTempFolder = p[DEL_TEMP] ?: true,
            deleteOriginals = p[DEL_ORIG] ?: false,
            compressionMethod = CompressionMethod.fromKey(p[COMPRESSION_METHOD]),
        )
    }

    val mail: Flow<MailSettings> = context.dataStore.data.map { p ->
        val rec = p[RECIPIENTS].orEmpty().sorted()
        MailSettings(
            recipients = rec,
            selected = p[SELECTED].orEmpty().intersect(rec.toSet()),
            subject = p[SUBJECT] ?: "Документы {date}",
            autoOpen = p[AUTO_MAIL] ?: true,
        )
    }

    val theme: Flow<ThemeMode> = context.dataStore.data.map { p -> ThemeMode.fromKey(p[THEME]) }
    val cameraRes: Flow<String?> = context.dataStore.data.map { p -> p[CAM_RES] }
    val cameraOverlay: Flow<CameraOverlaySettings> = context.dataStore.data.map { p ->
        CameraOverlaySettings(
            enabled = p[CAM_OVERLAY_ENABLED] ?: false,
            showDateTime = p[CAM_OVERLAY_DATE_TIME] ?: true,
            showName = p[CAM_OVERLAY_NAME_ENABLED] ?: false,
            name = p[CAM_OVERLAY_NAME].orEmpty(),
            position = CameraOverlayPosition.fromKey(p[CAM_OVERLAY_POSITION]),
            fontSizeSp = (p[CAM_OVERLAY_FONT_SIZE] ?: 18).coerceIn(12, 36),
        )
    }

    val quickMode: Flow<Boolean> = context.dataStore.data.map { p -> p[QUICK] ?: false }

    val history: Flow<List<HistoryEntry>> = context.dataStore.data.map { p -> parseHistory(p[HISTORY].orEmpty()) }

    suspend fun addSource(uri: Uri) = context.dataStore.edit {
        it[SOURCES] = it[SOURCES].orEmpty() + uri.toString()
    }

    suspend fun setCameraJpgFolder(uri: Uri) = context.dataStore.edit { it[CAMERA_JPG_FOLDER] = uri.toString() }
    suspend fun setCameraPdfFolder(uri: Uri) = context.dataStore.edit { it[CAMERA_PDF_FOLDER] = uri.toString() }

    suspend fun removeSource(uri: Uri) = context.dataStore.edit {
        it[SOURCES] = it[SOURCES].orEmpty() - uri.toString()
    }

    suspend fun setOutput(uri: Uri) = context.dataStore.edit { it[OUTPUT] = uri.toString() }
    suspend fun setArchive(uri: Uri) = context.dataStore.edit { it[ARCHIVE] = uri.toString() }
    suspend fun setSsim(v: Double) = context.dataStore.edit { it[SSIM] = v }
    suspend fun setDeleteTemp(v: Boolean) = context.dataStore.edit { it[DEL_TEMP] = v }
    suspend fun setDeleteOriginals(v: Boolean) = context.dataStore.edit { it[DEL_ORIG] = v }
    suspend fun setCompressionMethod(v: CompressionMethod) =
        context.dataStore.edit { it[COMPRESSION_METHOD] = v.key }

    suspend fun addRecipient(email: String) = context.dataStore.edit {
        it[RECIPIENTS] = it[RECIPIENTS].orEmpty() + email
        it[SELECTED] = it[SELECTED].orEmpty() + email   // новый адресат сразу отмечен
    }

    suspend fun removeRecipient(email: String) = context.dataStore.edit {
        it[RECIPIENTS] = it[RECIPIENTS].orEmpty() - email
        it[SELECTED] = it[SELECTED].orEmpty() - email
    }

    suspend fun setRecipientSelected(email: String, on: Boolean) = context.dataStore.edit {
        val cur = it[SELECTED].orEmpty()
        it[SELECTED] = if (on) cur + email else cur - email
    }

    suspend fun setSubject(v: String) = context.dataStore.edit { it[SUBJECT] = v }
    suspend fun setAutoMail(v: Boolean) = context.dataStore.edit { it[AUTO_MAIL] = v }
    suspend fun setQuickMode(v: Boolean) = context.dataStore.edit { it[QUICK] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[THEME] = v.key }
    suspend fun setCameraRes(v: String) = context.dataStore.edit { it[CAM_RES] = v }
    suspend fun setCameraOverlayEnabled(v: Boolean) = context.dataStore.edit { it[CAM_OVERLAY_ENABLED] = v }
    suspend fun setCameraOverlayDateTime(v: Boolean) = context.dataStore.edit { it[CAM_OVERLAY_DATE_TIME] = v }
    suspend fun setCameraOverlayNameEnabled(v: Boolean) = context.dataStore.edit { it[CAM_OVERLAY_NAME_ENABLED] = v }
    suspend fun setCameraOverlayName(v: String) = context.dataStore.edit { it[CAM_OVERLAY_NAME] = v.take(80) }
    suspend fun setCameraOverlayPosition(v: CameraOverlayPosition) =
        context.dataStore.edit { it[CAM_OVERLAY_POSITION] = v.key }
    suspend fun setCameraOverlayFontSize(v: Int) =
        context.dataStore.edit { it[CAM_OVERLAY_FONT_SIZE] = v.coerceIn(12, 36) }

    // --- Статистика ---
    suspend fun addHistory(entry: HistoryEntry) = context.dataStore.edit { prefs ->
        val list = parseHistory(prefs[HISTORY].orEmpty())
        prefs[HISTORY] = serializeHistory((listOf(entry) + list).take(300))
    }

    suspend fun markGmailOpened(id: Long, at: Long, to: List<String>) = context.dataStore.edit { prefs ->
        val list = parseHistory(prefs[HISTORY].orEmpty()).map { e ->
            if (e.id == id) e.copy(gmailOpenedAt = at, to = to) else e
        }
        prefs[HISTORY] = serializeHistory(list)
    }

    suspend fun clearHistory() = context.dataStore.edit { prefs -> prefs.remove(HISTORY) }
}
