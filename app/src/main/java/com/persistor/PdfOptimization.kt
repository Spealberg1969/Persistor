package com.persistor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PdfOptimizationReport(
    val pageCount: Int,
    val pagesBelowTarget: Int,
)

internal data class PdfQualityChoice(
    val quality: Int,
    val bytes: ByteArray,
    val ssim: Double,
    val targetReached: Boolean,
)

internal fun pdfJpegCodecFor(method: CompressionMethod): PdfJpegCodec = when (method) {
    CompressionMethod.JPEGLI -> PdfJpegCodec.JPEGLI
    CompressionMethod.MOZJPEG -> PdfJpegCodec.MOZJPEG
    else -> PdfJpegCodec.ANDROID_JPEG
}

internal fun CompressionMethod.supportsDirectPdfJpeg(): Boolean = this in setOf(
    CompressionMethod.ANDROID,
    CompressionMethod.JPEGLI,
    CompressionMethod.MOZJPEG,
)

/** Binary-searches the smallest JPEG quality that reaches the requested SSIM. */
internal object PdfQualitySearch {
    fun choose(
        targetSsim: Double,
        minQuality: Int = 40,
        maxQuality: Int = 95,
        encodeAndMeasure: (Int) -> Pair<ByteArray, Double>,
    ): PdfQualityChoice {
        require(targetSsim in 0.0..1.0)
        require(minQuality in 1..100 && maxQuality in minQuality..100)
        var low = minQuality
        var high = maxQuality
        var best: PdfQualityChoice? = null

        while (low <= high) {
            val quality = (low + high) / 2
            val (bytes, score) = encodeAndMeasure(quality)
            if (score >= targetSsim) {
                best = PdfQualityChoice(quality, bytes, score, targetReached = true)
                high = quality - 1
            } else {
                low = quality + 1
            }
        }

        if (best != null) return best
        val (bytes, score) = encodeAndMeasure(maxQuality)
        return PdfQualityChoice(maxQuality, bytes, score, targetReached = score >= targetSsim)
    }
}

/** Renders PDF pages one at a time, re-encodes them as PDF-compatible JPEG, and writes a new PDF. */
internal object PdfOptimizer {
    private const val TARGET_DPI = 200f
    private const val MAX_RENDER_SIDE = 3_000
    private const val MAX_RENDER_PIXELS = 4_000_000L
    private const val MAX_PAGES = 500

    suspend fun compress(
        context: Context,
        sourceUri: Uri,
        destination: File,
        targetSsim: Double,
        codec: PdfJpegCodec,
        onPageProgress: (page: Int, total: Int) -> Unit,
    ): PdfOptimizationReport {
        require(targetSsim in 0.90..0.99) { "Целевое SSIM должно быть от 0,90 до 0,99" }
        val activeContext = currentCoroutineContext()
        val tempDir = File(context.cacheDir, "pdf-opt-${System.nanoTime()}")
        check(tempDir.mkdirs()) { "Не удалось создать временную папку PDF" }
        val pages = mutableListOf<PdfJpegPage>()
        var outputComplete = false
        try {
            val descriptor = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: error("Не удалось открыть PDF ${sourceUri.lastPathSegment ?: ""}")
            try {
                val renderer = PdfRenderer(descriptor)
                try {
                    val pageCount = renderer.pageCount
                    require(pageCount in 1..MAX_PAGES) {
                        "PDF содержит $pageCount страниц; поддерживается не более $MAX_PAGES за одну обработку"
                    }
                    var pagesBelowTarget = 0
                    for (index in 0 until pageCount) {
                        activeContext.ensureActive()
                        onPageProgress(index + 1, pageCount)
                        val page = renderer.openPage(index)
                        try {
                            val (bitmapWidth, bitmapHeight) = renderSize(page.width, page.height)
                            val reference = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                            try {
                                reference.eraseColor(Color.WHITE)
                                page.render(reference, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val choice = PdfQualitySearch.choose(targetSsim) { quality ->
                                    activeContext.ensureActive()
                                    val bytes = PdfPageCompressor.encodeBitmap(reference, codec, quality)
                                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        ?: error("Не удалось проверить JPEG-страницу PDF")
                                    val score = try {
                                        Ssim.compute(reference, decoded)
                                    } finally {
                                        decoded.recycle()
                                    }
                                    bytes to score
                                }
                                if (!choice.targetReached) pagesBelowTarget++
                                val jpegFile = File(tempDir, "page-${index + 1}.jpg")
                                jpegFile.outputStream().buffered().use { it.write(choice.bytes) }
                                pages += PdfJpegPage(
                                    file = jpegFile,
                                    width = reference.width,
                                    height = reference.height,
                                    pageWidthPoints = page.width.toFloat(),
                                    pageHeightPoints = page.height.toFloat(),
                                    marginPoints = 0f,
                                )
                            } finally {
                                if (!reference.isRecycled) reference.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                    PdfDocumentWriter.write(pages, destination)
                    outputComplete = true
                    return PdfOptimizationReport(pageCount, pagesBelowTarget)
                } finally {
                    renderer.close()
                }
            } finally {
                descriptor.close()
            }
        } finally {
            pages.forEach { it.file.delete() }
            tempDir.deleteRecursively()
            if (!outputComplete) destination.delete()
        }
    }

    private fun renderSize(pageWidthPoints: Int, pageHeightPoints: Int): Pair<Int, Int> {
        require(pageWidthPoints > 0 && pageHeightPoints > 0) { "Некорректный размер страницы PDF" }
        val width = pageWidthPoints.toFloat()
        val height = pageHeightPoints.toFloat()
        val areaScale = sqrt(MAX_RENDER_PIXELS.toDouble() / (width * height)).toFloat()
        val sideScale = MAX_RENDER_SIDE / maxOf(width, height)
        val scale = min(TARGET_DPI / 72f, min(areaScale, sideScale))
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }
}
