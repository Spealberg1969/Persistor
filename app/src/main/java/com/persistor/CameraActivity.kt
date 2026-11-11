package com.persistor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Камера Persistor. Экран вращается вместе с телефоном по датчику положения,
 * независимо от системной настройки автоповорота (screenOrientation = fullSensor).
 * Угол по датчику используется ещё и для ориентации самого снимка.
 */
class CameraActivity : ComponentActivity() {
    private val angle = mutableIntStateOf(0)
    private var listener: OrientationEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                angle.intValue = ((orientation + 45) / 90 * 90) % 360
            }
        }
        val store = FolderStore(applicationContext)
        setContent {
            val mode by store.theme.collectAsState(initial = ThemeMode.LIGHT)
            PersistorTheme(mode) { CameraScreen(angle) }
        }
    }

    override fun onResume() {
        super.onResume()
        listener?.enable()
    }

    override fun onPause() {
        listener?.disable()
        super.onPause()
    }
}

private fun displayRotation(activity: ComponentActivity): Int =
    if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: Surface.ROTATION_0
    else legacyRotation(activity)

@Suppress("DEPRECATION")
private fun legacyRotation(activity: ComponentActivity): Int =
    activity.windowManager.defaultDisplay.rotation

/**
 * Поворот и масштаб превью под текущее положение экрана (схема из примера Camera2Basic).
 * В портрете поправок не нужно, в альбомном положении изображение поворачивается матрицей.
 */
private fun applyPreviewTransform(tv: TextureView, plan: CameraPlan, rotation: Int) {
    val vw = tv.width.toFloat()
    val vh = tv.height.toFloat()
    if (vw == 0f || vh == 0f) return
    val m = Matrix()
    val viewRect = RectF(0f, 0f, vw, vh)
    val cx = viewRect.centerX()
    val cy = viewRect.centerY()
    when (rotation) {
        Surface.ROTATION_90, Surface.ROTATION_270 -> {
            val pw = plan.previewSize.width.toFloat()
            val ph = plan.previewSize.height.toFloat()
            val bufferRect = RectF(0f, 0f, ph, pw)
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(vh / ph, vw / pw)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rotation - 2), cx, cy)
        }
        Surface.ROTATION_180 -> m.postRotate(180f, cx, cy)
    }
    tv.setTransform(m)
}

private fun ratioLabel(w: Int, h: Int): String {
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val g = gcd(w, h)
    val rw = w / g
    val rh = h / g
    return if (rw <= 20 && rh <= 20) "$rw:$rh" else "%.2f:1".format(w.toFloat() / h)
}

@Composable
fun CameraScreen(angleState: MutableIntState) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()

    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val rotation = displayRotation(activity)

    val store = remember { FolderStore(appCtx) }
    val repo = remember { ImageRepository(appCtx) }
    val sources by store.sources.collectAsState(initial = emptySet())
    val overlaySettings by store.cameraOverlay.collectAsState(initial = CameraOverlaySettings())
    val targets = remember(sources) { sources.sortedBy { it.toString() } }
    var targetIdx by remember { mutableIntStateOf(0) }
    val target: Uri? = if (targets.isEmpty()) null else targets[targetIdx % targets.size]

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    var errorText by remember { mutableStateOf<String?>(null) }
    var savedCount by remember { mutableIntStateOf(0) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    val shots = remember { mutableStateListOf<Uri>() }
    var reviewIdx by remember { mutableStateOf<Int?>(null) }
    var torch by remember { mutableStateOf(false) }
    var clean by remember { mutableStateOf(true) }
    var showDiag by remember { mutableStateOf(false) }
    var showResDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showOverlayDialog by remember { mutableStateOf(false) }
    var overlayPreviewTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lensIdx by remember { mutableIntStateOf(-1) }
    var lensMenu by remember { mutableStateOf(false) }
    var focusMark by remember { mutableStateOf<Offset?>(null) }

    val controller = remember {
        CameraController(
            appCtx,
            onError = { msg -> activity.runOnUiThread { errorText = msg } },
            onSaved = { name, uri ->
                activity.runOnUiThread {
                    savedCount++
                    lastSaved = name
                    errorText = null
                    shots.add(uri)
                }
            },
        )
    }
    SideEffect {
        controller.saveDir = target
        controller.overlaySettings = overlaySettings
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    // Объективы: по умолчанию основной (около 23 мм)
    val lenses = remember { runCatching { controller.lenses() }.getOrDefault(emptyList()) }
    val mainIdx = remember(lenses) {
        lenses.indexOfFirst { (it.eq ?: 0.0) in 20.0..40.0 }.let { if (it < 0) 0 else it }
    }
    val lens = lenses.getOrNull(if (lensIdx < 0) mainIdx else lensIdx)

    // Разрешение: по умолчанию 1600×1200, если камера отдаёт такой размер напрямую
    val nativeSizes = remember(lens) {
        lens?.let { runCatching { controller.nativeSizes(it) }.getOrDefault(emptyList()) }.orEmpty()
    }
    val resRaw by store.cameraRes.collectAsState(initial = null)
    val resolution: Resolution = remember(resRaw, nativeSizes) {
        Resolution.decode(resRaw)
            ?: if (nativeSizes.any { it.width == 1600 && it.height == 1200 }) Resolution.Native(1600, 1200)
            else Resolution.Custom(1600, 1200)
    }

    val planResult = remember(lens, resolution) { lens?.let { runCatching { controller.plan(it, resolution) } } }
    val plan = planResult?.getOrNull()
    val planError = planResult?.exceptionOrNull()?.message

    LaunchedEffect(torch) { controller.setTorch(torch) }
    LaunchedEffect(clean) { controller.setClean(clean) }
    LaunchedEffect(overlaySettings.enabled, overlaySettings.showDateTime) {
        if (overlaySettings.enabled && overlaySettings.showDateTime) {
            while (true) {
                overlayPreviewTime = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    LaunchedEffect(focusMark) {
        if (focusMark != null) {
            delay(1200)
            focusMark = null
        }
    }

    // Превью и жизненный цикл камеры
    val tvRef = remember { mutableStateOf<TextureView?>(null) }
    val planHolder = rememberUpdatedState(plan)
    val rotationHolder = rememberUpdatedState(rotation)
    val openCamera = rememberUpdatedState<() -> Unit>({
        val tv = tvRef.value
        val st = tv?.surfaceTexture
        if (hasPermission && lens != null && plan != null && tv != null && tv.isAvailable && st != null) {
            controller.open(lens, plan, st)
        }
    })
    LaunchedEffect(lens, plan, hasPermission) {
        controller.close()
        openCamera.value()
    }
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) controller.close()
            if (e == Lifecycle.Event.ON_RESUME) openCamera.value()
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    BackHandler(enabled = showDiag) { showDiag = false }
    BackHandler(enabled = reviewIdx != null) { reviewIdx = null }

    if (showDiag) {
        DiagScreen(onBack = { showDiag = false })
        return
    }

    val ready = target != null && plan != null && hasPermission && !controller.busy

    // ---- Элементы управления ----
    val closeBtn: @Composable () -> Unit = {
        PButton(onClick = { activity.finish() }) { Text("✕ Закрыть", color = Color.White) }
    }
    val diagBtn: @Composable () -> Unit = {
        PButton(onClick = { showDiag = true }) { Text("Диагностика", color = Color.White) }
    }
    val overlayBtn: @Composable () -> Unit = {
        PButton(onClick = { showOverlayDialog = true }) {
            Text(if (overlaySettings.enabled) "Надпись: вкл" else "Надпись: выкл", color = Color.White)
        }
    }
    val lensBtn: @Composable () -> Unit = {
        Box {
            PButton(onClick = { lensMenu = true }) {
                Text(lens?.label ?: "нет камер", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = lensMenu, onDismissRequest = { lensMenu = false }) {
                lenses.forEachIndexed { i, l ->
                    DropdownMenuItem(text = { Text(l.label) }, onClick = { lensIdx = i; lensMenu = false })
                }
            }
        }
    }
    val flashBtn: @Composable () -> Unit = {
        if (plan?.flashAvailable == true) {
            PButton(onClick = { torch = !torch }) {
                Text(
                    if (torch) "Вспышка: вкл" else "Вспышка: выкл",
                    color = if (torch) Color(0xFFFFD54F) else Color.White,
                )
            }
        }
    }
    val focusBtn: @Composable () -> Unit = {
        if (controller.manualFocus) {
            PButton(onClick = { controller.resetFocus() }) { Text("Фокус: ручной ✕", color = Color(0xFFFFD54F)) }
        }
    }
    val cleanSw: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = clean, onCheckedChange = { clean = it })
            Spacer(Modifier.width(6.dp))
            PText("Чистая", color = Color.White)
        }
    }
    val infoTexts: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (plan != null) {
                val o = plan.outputSize
                val mp = o.width.toLong() * o.height / 1_000_000.0
                val crop = if (plan.cropAspect != null) ", кроп" else ""
                // Нажатие на размер открывает список разрешений
                PText(
                    "Снимок: ${o.width}×${o.height} (${"%.1f".format(mp)} Мп$crop) ▾",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { showResDialog = true },
                )
            }
            PText(
                if (target != null) {
                    "Папка: ${repo.folderName(target)}" + if (targets.size > 1) " (нажмите, чтобы сменить)" else ""
                } else "Исходная папка не выбрана: выберите её в Persistor",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(enabled = targets.size > 1) { targetIdx++ },
            )
            lastSaved?.let {
                PText("Сохранено: $it · всего: $savedCount", color = Color(0xFF9CCC65), style = MaterialTheme.typography.bodySmall)
            }
            errorText?.let { PText(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
        }
    }
    val shutter: @Composable () -> Unit = {
        Box(
            Modifier.size(104.dp).clip(CircleShape)
                .background(if (ready) Color.White else Color.Gray)
                .framedCircle()
                .clickable(enabled = ready) { controller.takePicture(angleState.intValue) }
        )
    }
    val thumb: @Composable () -> Unit = {
        val last = shots.lastOrNull()
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)
                .framed(RoundedCornerShape(8.dp))
                .clickable(enabled = last != null) { reviewIdx = shots.lastIndex }
        ) {
            if (last != null) {
                AsyncImage(
                    model = last,
                    contentDescription = "Последний снимок",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---- Превью: всегда в одном месте дерева, поэтому при повороте не пересоздаётся ----
    val preview: @Composable () -> Unit = {
        when {
            !hasPermission -> Text("Нужно разрешение на камеру", color = Color.White)
            plan == null -> Text(planError ?: "Камеры не найдены", color = Color.White)
            else -> {
                val aspect = if (landscape) 1f / plan.uprightAspect else plan.uprightAspect
                BoxWithConstraints(Modifier.aspectRatio(aspect)) {
                    AndroidView(
                        factory = { c ->
                            TextureView(c).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        planHolder.value?.let { applyPreviewTransform(this@apply, it, rotationHolder.value) }
                                        openCamera.value()
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                        planHolder.value?.let { applyPreviewTransform(this@apply, it, rotationHolder.value) }
                                    }

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        controller.closeIfTexture(st)
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                    planHolder.value?.let { applyPreviewTransform(v as TextureView, it, rotationHolder.value) }
                                }
                                tvRef.value = this
                            }
                        },
                        update = { tv -> applyPreviewTransform(tv, plan, rotation) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Поверх: рамка обрезки (для своего размера), маркер фокуса, нажатие = фокус
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { off ->
                                focusMark = off
                                controller.focusAt(off.x / size.width, off.y / size.height, rotationHolder.value)
                            }
                        }
                    ) {
                        val ca = planHolder.value?.cropAspect
                        if (ca != null) {
                            val w = size.width
                            val h = size.height
                            val rw: Float
                            val rh: Float
                            if (ca > w / h) { rw = w; rh = w / ca } else { rh = h; rw = h * ca }
                            val left = (w - rw) / 2f
                            val top = (h - rh) / 2f
                            val dim = Color.Black.copy(alpha = 0.55f)
                            drawRect(dim, Offset(0f, 0f), Size(w, top))
                            drawRect(dim, Offset(0f, top + rh), Size(w, h - top - rh))
                            drawRect(dim, Offset(0f, top), Size(left, rh))
                            drawRect(dim, Offset(left + rw, top), Size(w - left - rw, rh))
                            drawRect(Color.White.copy(alpha = 0.8f), Offset(left, top), Size(rw, rh), style = Stroke(2.dp.toPx()))
                        }
                        focusMark?.let {
                            drawCircle(Color(0xFFFFD54F), radius = 36.dp.toPx(), center = it, style = Stroke(3.dp.toPx()))
                        }
                    }
                    if (overlaySettings.enabled) {
                        val stampLines = CameraOverlayRenderer.lines(overlaySettings, overlayPreviewTime)
                        if (stampLines.isNotEmpty()) {
                            val ca = plan.cropAspect
                            val viewAspect = maxWidth.value / maxHeight.value
                            val cropWidth = if (ca == null || ca > viewAspect) maxWidth else maxHeight * ca
                            val cropHeight = if (ca == null || ca <= viewAspect) maxHeight else maxWidth / ca
                            Box(
                                Modifier.offset((maxWidth - cropWidth) / 2, (maxHeight - cropHeight) / 2)
                                    .size(cropWidth, cropHeight),
                                contentAlignment = overlaySettings.position.previewAlignment(),
                            ) {
                                Column(
                                    Modifier.padding(8.dp).widthIn(max = (cropWidth - 16.dp).coerceAtLeast(24.dp))
                                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                ) {
                                    stampLines.forEach { line ->
                                        Text(
                                            line,
                                            color = Color.White,
                                            fontSize = overlaySettings.fontSizeSp.sp,
                                            lineHeight = (overlaySettings.fontSizeSp * 1.2f).sp,
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = if (landscape) Alignment.CenterStart else Alignment.Center,
            ) { preview() }

            if (landscape) {
                // Альбомное положение: панель справа, кнопка спуска закреплена внизу панели
                Column(
                    Modifier.align(Alignment.CenterEnd).width(230.dp).fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        closeBtn()
                        diagBtn()
                        lensBtn()
                        flashBtn()
                        focusBtn()
                        overlayBtn()
                        cleanSw()
                        Spacer(Modifier.height(8.dp))
                        infoTexts()
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        thumb()
                        Spacer(Modifier.width(14.dp))
                        shutter()
                    }
                }
            } else {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        closeBtn()
                        Spacer(Modifier.weight(1f))
                        diagBtn()
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { lensBtn() }
                        flashBtn()
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        focusBtn()
                        Spacer(Modifier.width(8.dp))
                        overlayBtn()
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    infoTexts()
                    Spacer(Modifier.height(8.dp))
                    // Кнопка спуска справа внизу
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { cleanSw() }
                        thumb()
                        Spacer(Modifier.width(14.dp))
                        shutter()
                    }
                }
            }
        }

        val ri = reviewIdx
        if (ri != null && ri in shots.indices) {
            ReviewScreen(
                shots = shots,
                index = ri,
                onIndex = { reviewIdx = it },
                onClose = { reviewIdx = null },
                onDelete = { u ->
                    runCatching { DocumentFile.fromSingleUri(ctx, u)?.delete() }
                    val i = shots.indexOf(u)
                    shots.remove(u)
                    reviewIdx = if (shots.isEmpty()) null else i.coerceIn(0, shots.lastIndex)
                },
            )
        }
    }

    if (showResDialog) {
        ResolutionDialog(
            sizes = nativeSizes,
            current = resolution,
            onPick = { r ->
                scope.launch { store.setCameraRes(r.encode()) }
                showResDialog = false
            },
            onCustom = { showResDialog = false; showCustomDialog = true },
            onDismiss = { showResDialog = false },
        )
    }
    if (showCustomDialog) {
        CustomResolutionDialog(
            initialW = resolution.width,
            initialH = resolution.height,
            onApply = { w, h ->
                scope.launch { store.setCameraRes(Resolution.Custom(w, h).encode()) }
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }
    if (showOverlayDialog) {
        CameraOverlayDialog(
            settings = overlaySettings,
            onEnabled = { scope.launch { store.setCameraOverlayEnabled(it) } },
            onDateTime = { scope.launch { store.setCameraOverlayDateTime(it) } },
            onNameEnabled = { scope.launch { store.setCameraOverlayNameEnabled(it) } },
            onName = { scope.launch { store.setCameraOverlayName(it) } },
            onPosition = { scope.launch { store.setCameraOverlayPosition(it) } },
            onFontSize = { scope.launch { store.setCameraOverlayFontSize(it) } },
            onDismiss = { showOverlayDialog = false },
        )
    }
}

private fun CameraOverlayPosition.previewAlignment(): Alignment = when (this) {
    CameraOverlayPosition.TOP_LEFT -> Alignment.TopStart
    CameraOverlayPosition.TOP_CENTER -> Alignment.TopCenter
    CameraOverlayPosition.TOP_RIGHT -> Alignment.TopEnd
    CameraOverlayPosition.CENTER_LEFT -> Alignment.CenterStart
    CameraOverlayPosition.CENTER -> Alignment.Center
    CameraOverlayPosition.CENTER_RIGHT -> Alignment.CenterEnd
    CameraOverlayPosition.BOTTOM_LEFT -> Alignment.BottomStart
    CameraOverlayPosition.BOTTOM_CENTER -> Alignment.BottomCenter
    CameraOverlayPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
}

@Composable
private fun CameraOverlayDialog(
    settings: CameraOverlaySettings,
    onEnabled: (Boolean) -> Unit,
    onDateTime: (Boolean) -> Unit,
    onNameEnabled: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onPosition: (CameraOverlayPosition) -> Unit,
    onFontSize: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var positionMenu by remember { mutableStateOf(false) }
    var fontSize by remember(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Надпись на снимке") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Впечатывать в фото", modifier = Modifier.weight(1f))
                    Switch(checked = settings.enabled, onCheckedChange = onEnabled)
                }
                Text(
                    "При включении текст становится частью изображения и останется на снимке после сжатия или отправки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Дата и время", modifier = Modifier.weight(1f))
                    Checkbox(checked = settings.showDateTime, onCheckedChange = onDateTime)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Добавить имя и фамилию", modifier = Modifier.weight(1f))
                    Checkbox(checked = settings.showName, onCheckedChange = onNameEnabled)
                }
                if (settings.showName) {
                    OutlinedTextField(
                        value = settings.name,
                        onValueChange = onName,
                        label = { Text("Имя и фамилия") },
                        singleLine = true,
                        supportingText = { Text("Имя хранится локально; при включённой надписи оно будет видно на снимках.") },
                        colors = themedFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Расположение", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { positionMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(settings.position.title)
                    }
                    DropdownMenu(expanded = positionMenu, onDismissRequest = { positionMenu = false }) {
                        CameraOverlayPosition.entries.forEach { position ->
                            DropdownMenuItem(
                                text = { Text(position.title) },
                                onClick = { onPosition(position); positionMenu = false },
                            )
                        }
                    }
                }
                Text("Размер шрифта: ${fontSize.toInt()} sp", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = { onFontSize(fontSize.toInt().coerceIn(12, 36)) },
                    valueRange = 12f..36f,
                    steps = 23,
                )
                Text("Эти настройки применяются к новым снимкам встроенной камеры.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { PButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun ResolutionDialog(
    sizes: List<android.util.Size>,
    current: Resolution,
    onPick: (Resolution) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разрешение снимка") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onCustom).padding(vertical = 14.dp)) {
                        Text(
                            "Свой размер… (любые пропорции)" + if (current is Resolution.Custom) "  ✓ ${current.width}×${current.height}" else "",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
                items(sizes) { s ->
                    val selected = current is Resolution.Native && current.width == s.width && current.height == s.height
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(Resolution.Native(s.width, s.height)) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${s.width} × ${s.height}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(ratioLabel(s.width, s.height), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        RadioButton(selected = selected, onClick = null)
                    }
                }
            }
        },
        confirmButton = { PButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun CustomResolutionDialog(
    initialW: Int,
    initialH: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var w by remember { mutableStateOf(initialW.toString()) }
    var h by remember { mutableStateOf(initialH.toString()) }
    val ratios = listOf("1:1" to (1 to 1), "3:2" to (3 to 2), "2:3" to (2 to 3), "4:3" to (4 to 3),
        "16:9" to (16 to 9), "9:16" to (9 to 16), "9:26" to (9 to 26))
    val wi = w.toIntOrNull()
    val hi = h.toIntOrNull()
    val valid = wi != null && hi != null && wi in 16..4096 && hi in 16..4096

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой размер") },
        text = {
            Column {
                Text(
                    "Кадр обрезается по центру до нужных пропорций и уменьшается до размера. Больше, чем есть в кадре, не получится.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = w, onValueChange = { w = it.filter(Char::isDigit).take(4) },
                        label = { Text("Ширина") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = themedFieldColors(), modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = h, onValueChange = { h = it.filter(Char::isDigit).take(4) },
                        label = { Text("Высота") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = themedFieldColors(), modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Пропорции (длинная сторона сохраняется):", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ratios.forEach { (label, r) ->
                        POutlinedButton(
                            onClick = {
                                val longSide = maxOf(wi ?: initialW, hi ?: initialH).coerceIn(16, 3072)
                                if (r.first >= r.second) {
                                    w = longSide.toString()
                                    h = (longSide * r.second / r.first).coerceAtLeast(16).toString()
                                } else {
                                    h = longSide.toString()
                                    w = (longSide * r.first / r.second).coerceAtLeast(16).toString()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {
            PButton(onClick = { onApply(wi!!, hi!!) }, enabled = valid) { Text("Применить") }
        },
        dismissButton = { PButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ReviewScreen(
    shots: List<Uri>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onDelete: (Uri) -> Unit,
) {
    val ctx = LocalContext.current
    val uri = shots[index]
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var confirm by remember { mutableStateOf(false) }
    val sizeKb = remember(uri) { (DocumentFile.fromSingleUri(ctx, uri)?.length() ?: 0L) / 1024 }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(uri).size(coil.size.Size.ORIGINAL).allowHardware(false).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(uri) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else 3f
                        offset = Offset.Zero
                    })
                }
                .pointerInput(uri) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PButton(onClick = onClose) { Text("← Назад", color = Color.White) }
            Spacer(Modifier.weight(1f))
            PText("${index + 1}/${shots.size} · $sizeKb КБ", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            PButton(onClick = { confirm = true }) { Text("Удалить", color = Color(0xFFFF8A80)) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PButton(onClick = { onIndex(index - 1) }, enabled = index > 0) { Text("◀", color = Color.White) }
            Spacer(Modifier.weight(1f))
            PText("Двойное касание: увеличить", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            PButton(onClick = { onIndex(index + 1) }, enabled = index < shots.lastIndex) { Text("▶", color = Color.White) }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить снимок?") },
            text = { Text("Файл будет удалён из папки.") },
            confirmButton = { PButton(onClick = { confirm = false; onDelete(uri) }) { Text("Удалить") } },
            dismissButton = { PButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DiagScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val report = remember { CameraDiagnostics.report(ctx) }
    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PButton(onClick = onBack) { Text("← Назад", color = Color.White) }
            Spacer(Modifier.weight(1f))
            PButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("camera report", report))
                Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
            }) { Text("Копировать", color = Color.White) }
        }
        PText("Диагностика камер", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            report,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
    }
}
