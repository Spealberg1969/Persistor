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

    val sources: Flow<Set<Uri>> = context.dataStore.data
        .map { p -> p[SOURCES].orEmpty().map(Uri::parse).toSet() }
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

    val quickMode: Flow<Boolean> = context.dataStore.data.map { p -> p[QUICK] ?: false }

    val history: Flow<List<HistoryEntry>> = context.dataStore.data.map { p -> parseHistory(p[HISTORY].orEmpty()) }

    suspend fun addSource(uri: Uri) = context.dataStore.edit {
        it[SOURCES] = it[SOURCES].orEmpty() + uri.toString()
    }

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
