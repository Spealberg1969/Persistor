package com.persistor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val s by vm.state.collectAsState()
            PersistorTheme(s.theme) {
                Surface(Modifier.fillMaxSize()) { MainScreen(vm) }
            }
        }
        if (savedInstanceState == null) handleSend(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSend(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshImages()   // подхватываем снимки, сделанные камерой
    }

    /** Нажатие «Отправить в Gmail» в уведомлении. */
    private fun handleSend(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_SEND, false) == true) {
            i.removeExtra(EXTRA_SEND)
            if (!vm.sendLast(this)) {
                Toast.makeText(
                    this,
                    "Архив уже недоступен для отправки из уведомления. Возьмите его из папки ZIP.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        const val EXTRA_SEND = "persistor.send_last"
    }
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val s by vm.state.collectAsState()
    val running = s.progress.running
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addSource) }
    val pickCameraJpg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setCameraJpgFolder) }
    val pickCameraPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setCameraPdfFolder) }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setOutput) }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setArchive) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var expanded by remember { mutableStateOf(true) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newEmail by remember { mutableStateOf("") }
    var subjectEdit by remember { mutableStateOf<String?>(null) }
    var formatMenuExpanded by remember { mutableStateOf(false) }
    var previewIndex by rememberSaveable { mutableIntStateOf(-1) }

    BackHandler(enabled = showStats) { showStats = false }
    BackHandler(enabled = previewIndex >= 0 && !showStats) { previewIndex = -1 }

    // Разрешение на уведомления (Android 13+): нужно, чтобы видеть ход обработки
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Быстрый режим: отсчёт 5 секунд, затем обработка всех файлов
    var quickHandled by rememberSaveable { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    val ready = s.output != null && s.archive != null && s.images.isNotEmpty() && !running
    LaunchedEffect(s.images.size) {
        if (previewIndex >= s.images.size) previewIndex = -1
    }
    LaunchedEffect(s.quickMode, ready, quickHandled) {
        if (s.quickMode && ready && !quickHandled) {
            try {
                for (i in 5 downTo 1) { countdown = i; delay(1000) }
                quickHandled = true
                vm.process(all = true)
            } finally {
                countdown = null
            }
        }
    }

    // Автоматически открыть Gmail, когда обработка закончилась и приложение на экране
    LaunchedEffect(s.result) {
        val r = s.result
        if (r is ProcessResult.Success && r.shareUri != null && s.mail.autoOpen && s.mail.selected.isNotEmpty() &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && vm.consumeAutoSend(r)
        ) {
            vm.sendLast(ctx)
        }
    }

    if (showStats) {
        StatsScreen(
            history = s.history,
            onBack = { showStats = false },
            onClear = vm::clearHistory,
        )
    } else {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                PText("Persistor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val pad = PaddingValues(horizontal = 8.dp)
                PButton(onClick = { showStats = true }, contentPadding = pad) { Text("Статистика") }
                Spacer(Modifier.width(4.dp))
                PButton(onClick = { expanded = !expanded }, contentPadding = pad) { Text(if (expanded) "Настройки ▴" else "Настройки ▾") }
            }

            if (expanded) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    PText("Исходные папки", style = MaterialTheme.typography.titleSmall)
                    s.sources.forEach { uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PText(vm.repo.folderName(uri), Modifier.weight(1f))
                            PButton(onClick = { vm.removeSource(uri) }) { Text("Убрать") }
                        }
                    }
                    POutlinedButton(onClick = { pickSource.launch(null) }) { Text("+ Добавить папку") }

                    Spacer(Modifier.height(8.dp))
                    PText("Папки съёмки", style = MaterialTheme.typography.titleSmall)
                    FolderRow("Снимки JPG камеры", s.cameraJpgFolder?.let(vm.repo::folderName)) { pickCameraJpg.launch(null) }
                    FolderRow("Снимки PDF камеры", s.cameraPdfFolder?.let(vm.repo::folderName)) { pickCameraPdf.launch(null) }
                    Text(
                        "Эти папки автоматически добавляются в список файлов для просмотра и обработки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    FolderRow("Сжатые файлы (папка с датой создаётся внутри)", s.output?.let(vm.repo::folderName)) { pickOutput.launch(null) }
                    Spacer(Modifier.height(4.dp))
                    FolderRow("Папка для ZIP", s.archive?.let(vm.repo::folderName)) { pickArchive.launch(null) }

                    Spacer(Modifier.height(8.dp))
                    PText("Формат изображения", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { formatMenuExpanded = true }, enabled = !running) {
                            Text(s.settings.compressionMethod.label)
                        }
                        DropdownMenu(
                            expanded = formatMenuExpanded,
                            onDismissRequest = { formatMenuExpanded = false },
                        ) {
                            CompressionMethod.entries.forEach { method ->
                                DropdownMenuItem(
                                    text = { Text(method.label) },
                                    onClick = {
                                        vm.setCompressionMethod(method)
                                        formatMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    val formatHint = when (s.settings.compressionMethod) {
                        CompressionMethod.HEIF -> "Требуется Android 10+ и доступный HEVC-кодек телефона."
                        CompressionMethod.AVIF -> "Требуется Android 14+ и доступный AV1-кодек телефона."
                        CompressionMethod.JPEG_XL -> "Выход .jxl; приложение проверяет его собственной библиотекой, но галереи и старые просмотрщики могут не открыть файл."
                        else -> "Для цветных форматов качество подбирается по SSIM; результат зависит от выбранного кодека."
                    }
                    Text(
                        formatHint + if (s.images.any { it.isPdf }) " Готовые PDF копируются без повторного сжатия страниц." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    var sl by remember(s.settings.ssimTarget) { mutableFloatStateOf(s.settings.ssimTarget.toFloat()) }
                    PText("Целевое качество SSIM: ${"%.2f".format(sl)}", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = sl,
                        onValueChange = { sl = it },
                        onValueChangeFinished = { vm.setSsim((sl * 100).roundToInt() / 100.0) },
                        valueRange = 0.90f..0.99f,
                        steps = 8,
                    )
                    SwitchRow("Удалять папку со сжатыми файлами после архивации", s.settings.deleteTempFolder, vm::setDeleteTemp)
                    SwitchRow("Удалять исходные фото и PDF после успешной архивации", s.settings.deleteOriginals, vm::setDeleteOriginals)
                    SwitchRow("Быстрый режим: при запуске обработать все файлы (отсчёт 5 с)", s.quickMode, vm::setQuickMode)

                    Spacer(Modifier.height(8.dp))
                    PText("Тема", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                Modifier.clickable { vm.setTheme(mode) }.padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.theme == mode, onClick = { vm.setTheme(mode) })
                                PText(mode.title)
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    PText("Отправка по почте (Gmail)", style = MaterialTheme.typography.titleSmall)
                    s.mail.recipients.forEach { email ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = email in s.mail.selected,
                                onCheckedChange = { vm.setRecipientSelected(email, it) },
                            )
                            PText(email, Modifier.weight(1f))
                            PButton(onClick = { vm.removeRecipient(email) }) { Text("Убрать") }
                        }
                    }
                    val validEmail = Patterns.EMAIL_ADDRESS.matcher(newEmail.trim()).matches()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newEmail,
                            onValueChange = { newEmail = it },
                            label = { Text("Email получателя") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = themedFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        PFilledButton(
                            onClick = { vm.addRecipient(newEmail.trim()); newEmail = "" },
                            enabled = validEmail,
                        ) { Text("+") }
                    }
                    OutlinedTextField(
                        value = subjectEdit ?: s.mail.subject,
                        onValueChange = { subjectEdit = it; vm.setSubject(it) },
                        label = { Text("Тема письма ({date} = дата архива)") },
                        singleLine = true,
                        colors = themedFieldColors(),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    SwitchRow("Открывать Gmail после архивации", s.mail.autoOpen, vm::setAutoMail)
                }
            }

            Spacer(Modifier.height(8.dp))
            PText("Файлов (изображения и PDF): ${s.images.size}, выбрано: ${s.selected.size}")
            Spacer(Modifier.height(4.dp))

            // Сетка миниатюр, поверх неё в правом нижнем углу круглая кнопка камеры
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(s.images, key = { _, item -> item.uri.toString() }) { index, img ->
                        Box(Modifier.aspectRatio(1f).clickable { previewIndex = index }) {
                            FileThumbnail(
                                item = img,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Checkbox(
                                checked = img.uri in s.selected,
                                onCheckedChange = { vm.toggle(img.uri) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { ctx.startActivity(Intent(ctx, CameraActivity::class.java)) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).framedCircle(),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) { Text("📷") }
            }

            if (running) {
                Spacer(Modifier.height(8.dp))
                PText(s.progress.message, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { if (s.progress.total == 0) 0f else s.progress.current.toFloat() / s.progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                PText("Можно свернуть приложение: обработка идёт в фоне", style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allSelected = s.images.isNotEmpty() && s.selected.size == s.images.size
                POutlinedButton(onClick = { if (allSelected) vm.clearSelection() else vm.selectAll() }, enabled = !running) {
                    Text(if (allSelected) "Снять всё" else "Выбрать все")
                }
                PFilledButton(
                    onClick = { if (s.settings.deleteOriginals) confirmDelete = true else vm.process() },
                    enabled = !running && s.selected.isNotEmpty() && s.output != null && s.archive != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Обработать (${s.selected.size})") }
            }
        }
    }

    if (!showStats && previewIndex in s.images.indices) {
        FullScreenMediaViewer(
            items = s.images,
            index = previewIndex,
            onIndex = { previewIndex = it.coerceIn(0, s.images.lastIndex) },
            onClose = { previewIndex = -1 },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить исходные файлы?") },
            text = { Text("После создания и проверки архива исходные изображения и PDF будут удалены безвозвратно.") },
            confirmButton = { PButton(onClick = { confirmDelete = false; vm.process() }) { Text("Продолжить") } },
            dismissButton = { PButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    countdown?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Быстрый режим") },
            text = { Text("Обработка всех ${s.images.size} файлов начнётся через $c с") },
            confirmButton = { PButton(onClick = { quickHandled = true }) { Text("Отмена") } },
        )
    }

    s.result?.let { r ->
        val canSend = r is ProcessResult.Success && r.shareUri != null
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(if (r is ProcessResult.Success) "Готово" else "Ошибка") },
            text = { Text(resultText(r)) },
            confirmButton = { PButton(onClick = vm::dismissResult) { Text("OK") } },
            dismissButton = if (canSend) {
                { PButton(onClick = { vm.sendLast(ctx) }) { Text("Отправить в Gmail") } }
            } else null,
        )
    }
}

@Composable
private fun StatsScreen(history: List<HistoryEntry>, onBack: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            PButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty()) { Text("Очистить") }
        }
        PText("Статистика", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        val total = history.sumOf { it.sizeBytes }
        val opened = history.count { it.gmailOpenedAt != null }
        PText("Архивов: ${history.size} · общий размер: ${mb(total)} · передано в Gmail: $opened")
        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            PText("Пока пусто. Записи появятся после первой обработки.")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(history, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).framed().padding(8.dp)) {
                        Text("${fmt.format(Date(e.id))} · ${e.zipName}", style = MaterialTheme.typography.titleSmall)
                        Text("Размер: ${mb(e.sizeBytes)} · фото: ${e.files}")
                        Text("Кому: ${e.to.joinToString().ifEmpty { "не указано" }}")
                        val sent = e.gmailOpenedAt
                        Text(
                            if (sent != null) "Gmail открыт: ${fmt.format(Date(sent))}" else "В Gmail не передавался",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить статистику?") },
            text = { Text("Все записи будут удалены. Сами архивы это не затронет.") },
            confirmButton = { PButton(onClick = { confirmClear = false; onClear() }) { Text("Очистить") } },
            dismissButton = { PButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

private fun resultText(r: ProcessResult): String = when (r) {
    is ProcessResult.Success -> buildString {
        appendLine("Архив: ${r.zipName}")
        appendLine("Файлов: ${r.files}")
        appendLine("Было: ${mb(r.originalBytes)} → архив: ${mb(r.zipSize)}")
        if (r.deletedOriginals > 0) appendLine("Удалено исходных файлов: ${r.deletedOriginals}")
        if (r.zipSize > 17L * 1048576) appendLine("\n⚠ Архив больше 17 МБ, Gmail может его не принять.")
        r.warnings.forEach { appendLine("• $it") }
    }.trim()
    is ProcessResult.Failure -> r.reason
}

@Composable
private fun FolderRow(label: String, name: String?, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PText(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            PText(name ?: "не выбрана", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        POutlinedButton(onClick = onPick) { Text("Выбрать") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        PText(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
