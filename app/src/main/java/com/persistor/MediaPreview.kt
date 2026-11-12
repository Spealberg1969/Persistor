package com.persistor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PdfPreviewState(
    val bitmap: Bitmap? = null,
    val pageCount: Int = 0,
    val error: String? = null,
)

@Composable
fun FileThumbnail(
    item: ImageItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (item.isPdf) {
        Box(modifier.background(androidx.compose.ui.graphics.Color(0xFF182B46))) {
            PdfPagePreview(
                uri = item.uri,
                pageIndex = 0,
                maxDimension = 480,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "PDF",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(0xCC10243C)).padding(horizontal = 5.dp, vertical = 2.dp),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 10.sp,
            )
        }
    } else {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

@Composable
fun FullScreenMediaViewer(
    items: List<ImageItem>,
    index: Int,
    onIndex: (Int) -> Unit,
    onClose: () -> Unit,
    onDelete: ((ImageItem) -> Unit)? = null,
) {
    if (items.isEmpty() || index !in items.indices) return
    val item = items[index]
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var pdfPageIndex by remember(item.uri) { mutableIntStateOf(0) }
    var pdfPageCount by remember(item.uri) { mutableIntStateOf(1) }
    var confirmDelete by remember(item.uri) { mutableStateOf(false) }
    val currentScale = rememberUpdatedState(scale)
    val density = LocalDensity.current
    val previewWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx().coerceIn(640, 2048) }
    val previewHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx().coerceIn(960, 3072) }
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).systemBarsPadding()) {
        val previewModifier = Modifier.fillMaxSize()
            .pointerInput(item.uri) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 3f
                    offset = androidx.compose.ui.geometry.Offset.Zero
                })
            }
            .pointerInput(item.uri, index, items.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    var movement = androidx.compose.ui.geometry.Offset.Zero
                    var horizontalSwipeAllowed = currentScale.value <= 1.01f
                    var pageChanged = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size != 1) {
                            horizontalSwipeAllowed = false
                            continue
                        }
                        val change = pressed.firstOrNull { it.id == down.id }
                        if (change == null) {
                            horizontalSwipeAllowed = false
                            continue
                        }
                        movement += change.position - change.previousPosition
                        if (abs(movement.y) > touchSlop && abs(movement.y) > abs(movement.x)) {
                            horizontalSwipeAllowed = false
                        }
                        if (!pageChanged && horizontalSwipeAllowed && currentScale.value <= 1.01f &&
                            abs(movement.x) >= swipeThresholdPx && abs(movement.x) > abs(movement.y) * 1.2f
                        ) {
                            val next = if (movement.x < 0f) index + 1 else index - 1
                            if (next in items.indices) onIndex(next)
                            pageChanged = true
                        }
                    }
                }
            }
            .pointerInput(item.uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)

        if (item.isPdf) {
            PdfPagePreview(
                uri = item.uri,
                pageIndex = pdfPageIndex,
                maxDimension = 2400,
                onPageCount = { pdfPageCount = it.coerceAtLeast(1) },
                modifier = previewModifier,
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).size(previewWidthPx, previewHeightPx).allowHardware(false).build(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = previewModifier,
            )
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.58f)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PButton(onClick = onClose) { Text("← Назад", color = androidx.compose.ui.graphics.Color.White) }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${index + 1}/${items.size} · ${item.size / 1024} КБ", color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            if (onDelete != null) PButton(onClick = { confirmDelete = true }) {
                Text("Удалить", color = androidx.compose.ui.graphics.Color(0xFFFF8A80))
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (item.isPdf) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PButton(onClick = { pdfPageIndex = (pdfPageIndex - 1).coerceAtLeast(0) }, enabled = pdfPageIndex > 0) {
                        Text("Стр. ◀", color = androidx.compose.ui.graphics.Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Страница ${pdfPageIndex + 1} / $pdfPageCount", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    PButton(onClick = { pdfPageIndex = (pdfPageIndex + 1).coerceAtMost(pdfPageCount - 1) }, enabled = pdfPageIndex < pdfPageCount - 1) {
                        Text("▶ Стр.", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PButton(onClick = { onIndex(index - 1) }, enabled = index > 0) {
                    Text("◀ Файл", color = androidx.compose.ui.graphics.Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text("Свайп: листать файлы · двойное касание: увеличить", color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                PButton(onClick = { onIndex(index + 1) }, enabled = index < items.lastIndex) {
                    Text("Файл ▶", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить файл?") },
            text = { Text("${item.name} будет удалён из папки.") },
            confirmButton = { PButton(onClick = { confirmDelete = false; onDelete(item) }) { Text("Удалить") } },
            dismissButton = { PButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PdfPagePreview(
    uri: android.net.Uri,
    pageIndex: Int,
    maxDimension: Int,
    modifier: Modifier = Modifier,
    onPageCount: (Int) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val latestPageCount = rememberUpdatedState(onPageCount)
    val bitmapOwner = remember(uri, pageIndex, maxDimension) { AtomicReference<Bitmap?>(null) }
    val state by produceState<PdfPreviewState?>(null, uri, pageIndex, maxDimension) {
        value = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                renderPdfPage(context, uri, pageIndex, maxDimension) { bitmapOwner.set(it) }
            }
            currentCoroutineContext().ensureActive()
            value = rendered
            bitmapOwner.compareAndSet(rendered.bitmap, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (memory: OutOfMemoryError) {
            value = PdfPreviewState(error = "Недостаточно памяти для предпросмотра этой страницы")
        } catch (failure: Exception) {
            value = PdfPreviewState(error = failure.message ?: "Не удалось открыть PDF")
        } finally {
            bitmapOwner.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    DisposableEffect(state?.bitmap) {
        val bitmap = state?.bitmap
        onDispose { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }
    LaunchedEffect(state?.pageCount) {
        state?.pageCount?.takeIf { it > 0 }?.let { latestPageCount.value(it) }
    }

    Box(modifier.background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
        val bitmap = state?.bitmap
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = "Страница PDF", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            state?.error != null -> Text("PDF\n${state?.error}", color = androidx.compose.ui.graphics.Color.DarkGray, modifier = Modifier.padding(12.dp))
            else -> CircularProgressIndicator()
        }
    }
}

private fun renderPdfPage(
    context: Context,
    uri: android.net.Uri,
    pageIndex: Int,
    maxDimension: Int,
    onBitmapCreated: (Bitmap) -> Unit,
): PdfPreviewState {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Не удалось открыть PDF")
    descriptor.use { pfd ->
        val renderer = PdfRenderer(pfd)
        try {
            val count = renderer.pageCount
            require(count > 0) { "PDF не содержит страниц" }
            val safeIndex = pageIndex.coerceIn(0, count - 1)
            val page = renderer.openPage(safeIndex)
            try {
                val maxSide = maxOf(page.width, page.height).coerceAtLeast(1)
                val scale = (maxDimension.toFloat() / maxSide).coerceAtMost(1f)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                onBitmapCreated(bitmap)
                try {
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return PdfPreviewState(bitmap = bitmap, pageCount = count)
                } catch (failure: Throwable) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    throw failure
                }
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
        }
    }
}
