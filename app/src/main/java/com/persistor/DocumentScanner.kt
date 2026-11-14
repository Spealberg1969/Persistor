package com.persistor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Координаты углов документа нормализованы относительно кадра: 0..1. */
data class DocumentPoint(val x: Float, val y: Float)

data class DocumentQuad(
    val topLeft: DocumentPoint,
    val topRight: DocumentPoint,
    val bottomRight: DocumentPoint,
    val bottomLeft: DocumentPoint,
    val confidence: Float,
) {
    fun points(): List<DocumentPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

internal const val MIN_AUTO_DOCUMENT_CONFIDENCE = 0.50f

internal fun DocumentQuad.isReliableAutomaticCandidate(): Boolean =
    confidence >= MIN_AUTO_DOCUMENT_CONFIDENCE

data class DocumentQuadTrackingState(
    val stable: DocumentQuad?,
    val candidate: DocumentQuad?,
    val consecutiveFrames: Int,
    val requiredFrames: Int,
)

/** Suppresses frame-to-frame Hough jitter and rejects isolated alternate rectangles. */
class DocumentQuadStabilizer(
    private val requiredFrames: Int = 3,
    private val matchDistance: Float = 0.04f,
    private val stableDistance: Float = 0.04f,
    private val smoothing: Float = 0.25f,
    private val missingFramesToReset: Int = 3,
) {
    private var accepted: DocumentQuad? = null
    private var candidate: DocumentQuad? = null
    private var candidateFrames = 0
    private var missedFrames = 0

    init {
        require(requiredFrames >= 2)
        require(matchDistance > 0f && stableDistance > 0f)
        require(smoothing in 0f..1f && missingFramesToReset >= 1)
    }

    fun reset() {
        accepted = null
        candidate = null
        candidateFrames = 0
        missedFrames = 0
    }

    fun observe(detected: DocumentQuad?): DocumentQuadTrackingState {
        if (detected == null) {
            missedFrames++
            candidate = null
            candidateFrames = 0
            if (missedFrames >= missingFramesToReset) accepted = null
            return DocumentQuadTrackingState(accepted, null, 0, requiredFrames)
        }

        missedFrames = 0
        val current = accepted
        if (current != null && maximumCornerDistance(current, detected) <= stableDistance) {
            accepted = blend(current, detected, smoothing)
            candidate = null
            candidateFrames = 0
            return DocumentQuadTrackingState(accepted, null, requiredFrames, requiredFrames)
        }

        val previousCandidate = candidate
        if (previousCandidate != null && maximumCornerDistance(previousCandidate, detected) <= matchDistance) {
            candidate = blend(previousCandidate, detected, smoothing)
            candidateFrames++
        } else {
            candidate = detected
            candidateFrames = 1
        }

        if (candidateFrames >= requiredFrames) {
            accepted = candidate
            candidate = null
            candidateFrames = 0
            return DocumentQuadTrackingState(accepted, null, requiredFrames, requiredFrames)
        }
        // Hide an old accepted frame while a contradictory rectangle is being verified.
        return DocumentQuadTrackingState(null, candidate, candidateFrames, requiredFrames)
    }

    private fun maximumCornerDistance(a: DocumentQuad, b: DocumentQuad): Float =
        a.points().zip(b.points()).maxOf { (first, second) ->
            hypot(first.x - second.x, first.y - second.y)
        }

    private fun blend(a: DocumentQuad, b: DocumentQuad, amount: Float): DocumentQuad {
        fun point(first: DocumentPoint, second: DocumentPoint) = DocumentPoint(
            first.x + (second.x - first.x) * amount,
            first.y + (second.y - first.y) * amount,
        )
        return DocumentQuad(
            topLeft = point(a.topLeft, b.topLeft),
            topRight = point(a.topRight, b.topRight),
            bottomRight = point(a.bottomRight, b.bottomRight),
            bottomLeft = point(a.bottomLeft, b.bottomLeft),
            confidence = a.confidence + (b.confidence - a.confidence) * amount,
        )
    }
}

enum class DocumentCaptureProfile(
    val key: String,
    val title: String,
    val maxLongEdge: Int,
    val maxBytes: Int,
) {
    SCREEN("screen", "Экран · 1000 px / 200 КБ", 1000, 200 * 1024),
    STANDARD("standard", "Обычный · 1600 px / 450 КБ", 1600, 450 * 1024),
    PRINT("print", "Печать · 2400 px / 1,2 МБ", 2400, 1200 * 1024);

    companion object {
        fun fromKey(key: String?): DocumentCaptureProfile = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

data class DocumentCaptureSettings(
    val enabled: Boolean = true,
    val profile: DocumentCaptureProfile = DocumentCaptureProfile.STANDARD,
)

data class DocumentCaptureSize(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
    val area: Long get() = width.toLong() * height
    val landscapeAspect: Float get() = max(width, height).toFloat() / min(width, height).coerceAtLeast(1)
}

internal fun DocumentCaptureProfile.selectCaptureSize(sizes: List<DocumentCaptureSize>): DocumentCaptureSize? {
    val valid = sizes.filter { it.width > 0 && it.height > 0 }
    val minimumCaptureEdge = (maxLongEdge * 1.5f).roundToInt()
    val adequate = valid.filter { it.longEdge >= minimumCaptureEdge }
    if (adequate.isEmpty()) return valid.maxByOrNull { it.area }
    val nearFourByThree = adequate.filter { it.landscapeAspect in 1.18f..1.55f }
    val candidates = nearFourByThree.ifEmpty { adequate }
    return candidates.minWithOrNull(compareBy<DocumentCaptureSize> { it.longEdge }.thenBy { it.area })
}

/** Детектор прямоугольных границ документа на preview/кадре, без внешнего CV runtime. */
object DocumentQuadDetector {
    private const val MAX_ANALYSIS_EDGE = 480
    private const val THETA_COUNT = 90 // шаг 2°

    fun detect(bitmap: Bitmap): DocumentQuad? {
        if (bitmap.width < 64 || bitmap.height < 64) return null
        val scale = min(1f, MAX_ANALYSIS_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        val analysis = if (w == bitmap.width && h == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)
        return try {
            val pixels = IntArray(w * h)
            analysis.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = ByteArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c ushr 16) and 0xff
                val g = (c ushr 8) and 0xff
                val b = c and 0xff
                gray[i] = ((77 * r + 150 * g + 29 * b) ushr 8).toByte()
            }
            detectGray(w, h, gray)
        } finally {
            if (analysis !== bitmap) analysis.recycle()
        }
    }

    /** Pure-array entry point used by regression tests and preview processing. */
    internal fun detectGray(width: Int, height: Int, gray: ByteArray): DocumentQuad? {
        if (width < 64 || height < 64 || gray.size != width * height) return null
        val magnitude = IntArray(gray.size)
        val gradientX = IntArray(gray.size)
        val gradientY = IntArray(gray.size)
        val histogram = IntArray(2041)
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                fun p(k: Int) = gray[k].toInt() and 0xff
                val gx = -p(i - width - 1) + p(i - width + 1) - 2 * p(i - 1) + 2 * p(i + 1) - p(i + width - 1) + p(i + width + 1)
                val gy = -p(i - width - 1) - 2 * p(i - width) - p(i - width + 1) + p(i + width - 1) + 2 * p(i + width) + p(i + width + 1)
                val m = (abs(gx) + abs(gy)).coerceIn(0, 2040)
                magnitude[i] = m
                gradientX[i] = gx
                gradientY[i] = gy
                if (m > 0) {
                    histogram[m]++
                    count++
                }
            }
        }
        if (count < min(width, height)) return null
        val percentileRank = (count * 0.82).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        var threshold = 0
        for (v in histogram.indices) {
            cumulative += histogram[v]
            if (cumulative >= percentileRank) { threshold = v; break }
        }
        threshold = threshold.coerceIn(45, 520)

        val edgeIndices = ArrayList<Int>()
        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val i = row + x
                val m = magnitude[i]
                if (m < threshold) continue
                val gx = gradientX[i]
                val gy = gradientY[i]
                val (dx, dy) = when {
                    abs(gx) >= abs(gy) * 2 -> 1 to 0
                    abs(gy) >= abs(gx) * 2 -> 0 to 1
                    gx * gy >= 0 -> 1 to 1
                    else -> 1 to -1
                }
                if (m >= magnitude[i - dy * width - dx] && m >= magnitude[i + dy * width + dx]) {
                    edgeIndices += i
                }
            }
        }
        if (edgeIndices.size < min(width, height)) return null

        val rhoLimit = ceil(hypot(width.toDouble(), height.toDouble())).toInt()
        val rhoBins = rhoLimit * 2 + 1
        val accumulator = IntArray(THETA_COUNT * rhoBins)
        val cosTable = DoubleArray(THETA_COUNT) { cos(Math.toRadians(it * 2.0)) }
        val sinTable = DoubleArray(THETA_COUNT) { sin(Math.toRadians(it * 2.0)) }
        val stride = max(1, edgeIndices.size / 14_000)
        for (pointIndex in edgeIndices.indices step stride) {
            val index = edgeIndices[pointIndex]
            val x = index % width
            val y = index / width
            for (t in 0 until THETA_COUNT) {
                val rho = (x * cosTable[t] + y * sinTable[t]).roundToInt() + rhoLimit
                if (rho in 0 until rhoBins) accumulator[t * rhoBins + rho]++
            }
        }

        val minVotes = max(24, (min(width, height) * 0.16f).roundToInt())
        val peaks = ArrayList<HoughLine>()
        for (t in 0 until THETA_COUNT) {
            for (r in 3 until rhoBins - 3) {
                val votes = accumulator[t * rhoBins + r]
                if (votes < minVotes) continue
                var maximum = true
                loop@ for (dt in -1..1) for (dr in -3..3) {
                    if (dt == 0 && dr == 0) continue
                    val nt = t + dt
                    val nr = r + dr
                    if (nt in 0 until THETA_COUNT && nr in 0 until rhoBins &&
                        accumulator[nt * rhoBins + nr] > votes
                    ) { maximum = false; break@loop }
                }
                if (maximum) peaks += HoughLine(t * 2.0, (r - rhoLimit).toDouble(), votes)
            }
        }
        if (peaks.isEmpty()) return null

        val lines = ArrayList<HoughLine>()
        for (line in peaks.sortedByDescending { it.votes }) {
            if (lines.none { sameHoughLine(it, line) }) lines += line
            if (lines.size == 24) break
        }
        val minSide = min(width, height) * 0.16
        val maxSeparation = hypot(width.toDouble(), height.toDouble()) * 0.95
        val pairs = ArrayList<LinePair>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val pair = parallelPair(lines[i], lines[j]) ?: continue
            if (pair.separation < minSide || pair.separation > maxSeparation) continue
            pairs += pair
        }
        if (pairs.size < 2) return null

        fun edgeCoverage(a: PointD, b: PointD): Double {
            val steps = max(12, (distance(a, b) / 6.0).roundToInt())
            var supported = 0
            for (step in 0..steps) {
                val fraction = step.toDouble() / steps
                val x = (a.x + (b.x - a.x) * fraction).roundToInt()
                val y = (a.y + (b.y - a.y) * fraction).roundToInt()
                var hasEdge = false
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    val px = x + dx
                    val py = y + dy
                    if (px in 1 until width - 1 && py in 1 until height - 1 &&
                        magnitude[py * width + px] >= threshold
                    ) {
                        hasEdge = true
                        break@loop
                    }
                }
                if (hasEdge) supported++
            }
            return supported.toDouble() / (steps + 1)
        }

        var best: Candidate? = null
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val a = pairs[i]
            val b = pairs[j]
            if (a.a === b.a || a.a === b.b || a.b === b.a || a.b === b.b) continue
            val angle = angleDifference(a.angle, b.angle)
            if (angle !in 68.0..112.0) continue
            val raw = listOf(
                intersect(a.a, b.a) ?: continue,
                intersect(a.a, b.b) ?: continue,
                intersect(a.b, b.a) ?: continue,
                intersect(a.b, b.b) ?: continue,
            )
            if (raw.any { it.x < 0.0 || it.y < 0.0 || it.x > width - 1.0 || it.y > height - 1.0 }) continue
            val ordered = orderCorners(raw) ?: continue
            val area = polygonArea(ordered)
            val areaRatio = area / (width.toDouble() * height)
            if (areaRatio !in 0.12..0.94 || !isConvex(ordered)) continue
            val sides = ordered.indices.map { k -> distance(ordered[k], ordered[(k + 1) % 4]) }
            if (sides.minOrNull()!! < minSide) continue
            val continuity = ordered.indices.map { k -> edgeCoverage(ordered[k], ordered[(k + 1) % 4]) }
            if (continuity.any { it < 0.40 }) continue
            val aspect = max(sides[0], sides[2]) / min(sides[1], sides[3]).coerceAtLeast(1.0)
            if (aspect !in 0.24..4.2) continue
            val support = minOf(a.a.votes, a.b.votes, b.a.votes, b.b.votes)
            val confidence = (support / (min(width, height) * 0.38f)).coerceIn(0f, 1f)
            if (confidence < 0.25f) continue
            val score = support + (a.support + b.support) * 0.12 +
                areaRatio * min(width, height) * 0.42 + continuity.minOrNull()!! * min(width, height) * 0.15
            if (best == null || score > best!!.score) best = Candidate(ordered, confidence, score)
        }
        val result = best ?: return null
        return DocumentQuad(
            topLeft = result.points[0].normalized(width, height),
            topRight = result.points[1].normalized(width, height),
            bottomRight = result.points[2].normalized(width, height),
            bottomLeft = result.points[3].normalized(width, height),
            confidence = result.confidence,
        )
    }

    private data class HoughLine(val angle: Double, val rho: Double, val votes: Int)
    private data class LinePair(val a: HoughLine, val b: HoughLine, val angle: Double, val separation: Double) {
        val support: Int get() = a.votes + b.votes
    }
    private data class PointD(val x: Double, val y: Double) {
        fun normalized(w: Int, h: Int) = DocumentPoint((x / w).toFloat(), (y / h).toFloat())
    }
    private data class Candidate(val points: List<PointD>, val confidence: Float, val score: Double)

    private fun sameHoughLine(a: HoughLine, b: HoughLine): Boolean {
        val (ba, br) = align(b, a.angle)
        return angleDifference(a.angle, ba) < 5.0 && abs(a.rho - br) < 18.0
    }

    private fun align(line: HoughLine, referenceAngle: Double): Pair<Double, Double> {
        var angle = line.angle
        var rho = line.rho
        while (angle - referenceAngle > 90.0) { angle -= 180.0; rho = -rho }
        while (angle - referenceAngle < -90.0) { angle += 180.0; rho = -rho }
        return angle to rho
    }

    private fun parallelPair(first: HoughLine, second: HoughLine): LinePair? {
        val (angleB, rhoB) = align(second, first.angle)
        if (abs(angleB - first.angle) > 12.0) return null
        val mean = ((first.angle + angleB) / 2.0).let { ((it % 180.0) + 180.0) % 180.0 }
        return LinePair(first, second, mean, abs(first.rho - rhoB))
    }

    private fun angleDifference(a: Double, b: Double): Double {
        val raw = abs(a - b) % 180.0
        return min(raw, 180.0 - raw)
    }

    private fun intersect(a: HoughLine, b: HoughLine): PointD? {
        val a1 = Math.toRadians(a.angle)
        val b1 = Math.toRadians(b.angle)
        val ca = cos(a1); val sa = sin(a1)
        val cb = cos(b1); val sb = sin(b1)
        val det = ca * sb - sa * cb
        if (abs(det) < 1e-5) return null
        return PointD((a.rho * sb - b.rho * sa) / det, (ca * b.rho - cb * a.rho) / det)
    }

    private fun orderCorners(points: List<PointD>): List<PointD>? {
        val cx = points.sumOf { it.x } / 4.0
        val cy = points.sumOf { it.y } / 4.0
        val around = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = around.indices.minByOrNull { around[it].x + around[it].y } ?: return null
        val ordered = List(4) { around[(start + it) % 4] }
        if (ordered.indices.any { i -> distance(ordered[i], ordered[(i + 1) % 4]) < 2.0 }) return null
        return ordered
    }

    private fun isConvex(points: List<PointD>): Boolean {
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1.0) return false
            val current = if (cross > 0) 1 else -1
            if (sign != 0 && sign != current) return false
            sign = current
        }
        return true
    }

    private fun polygonArea(points: List<PointD>): Double = abs(points.indices.sumOf { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        a.x * b.y - b.x * a.y
    }) / 2.0

    private fun distance(a: PointD, b: PointD): Double = hypot(a.x - b.x, a.y - b.y)
}

internal data class JpegByteFit(
    val bytes: ByteArray,
    val quality: Int,
    val targetReached: Boolean,
    val minQualityBytes: ByteArray,
)

/** Максимизирует JPEG quality под byte cap, не предполагая точную линейность размера файла. */
internal object JpegByteTargetSearch {
    fun choose(
        targetBytes: Int,
        minQuality: Int = 45,
        maxQuality: Int = 95,
        iterations: Int = 7,
        encode: (quality: Int) -> ByteArray,
    ): JpegByteFit {
        require(targetBytes > 0 && minQuality in 1..100 && maxQuality in minQuality..100)
        var low = minQuality
        val atMaximum = encode(maxQuality)
        if (atMaximum.size <= targetBytes) {
            val atMinimum = if (minQuality == maxQuality) atMaximum else encode(minQuality)
            return JpegByteFit(atMaximum, maxQuality, true, atMinimum)
        }
        var high = maxQuality - 1
        var best: Pair<Int, ByteArray>? = null
        var minBytes: ByteArray? = null
        for (iteration in 0 until iterations.coerceIn(1, 12)) {
            if (low > high) break
            val quality = (low + high) / 2
            val bytes = encode(quality)
            if (quality == minQuality) minBytes = bytes
            if (bytes.size <= targetBytes) {
                if (best == null || quality > best!!.first) best = quality to bytes
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        if (minBytes == null) minBytes = encode(minQuality)
        val fitting = best
        return if (fitting != null) JpegByteFit(fitting.second, fitting.first, true, minBytes!!)
        else JpegByteFit(minBytes!!, minQuality, false, minBytes!!)
    }
}

data class DocumentImageResult(
    val bytes: ByteArray,
    val detected: Boolean,
    val targetReached: Boolean?,
    val quality: Int,
    val width: Int,
    val height: Int,
)

/** Перспективное выравнивание, ресайз и bounded-size JPEG encode для камеры. */
object DocumentImageProcessor {
    private const val MIN_LONG_EDGE = 720

    fun processJpeg(
        jpeg: ByteArray,
        profile: DocumentCaptureProfile,
        stamp: CameraOverlaySettings,
        timestampMillis: Long,
        fitByteTarget: Boolean,
        quadOverride: DocumentQuad? = null,
        manualViewportAspect: Float? = null,
    ): DocumentImageResult {
        val exifOrientation = ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("Не удалось декодировать снимок камеры")
        val oriented = decoded.applyExifOrientation(exifOrientation)
        try {
            val quad = quadOverride?.let {
                mapViewportQuadToFrame(it, oriented.width.toFloat() / oriented.height, manualViewportAspect)
            } ?: DocumentQuadDetector.detect(oriented)?.takeIf { it.isReliableAutomaticCandidate() }
            val warped = quad?.let { perspectiveCrop(oriented, it, profile.maxLongEdge) }
            val source = warped ?: oriented
            try {
                var edge = min(profile.maxLongEdge, max(source.width, source.height))
                var final: EncodedAtEdge? = null
                var attemptNumber = 0
                while (attemptNumber++ < 5) {
                    val attempt = encodeAtEdge(source, edge, profile, stamp, timestampMillis, fitByteTarget)
                    final = attempt
                    if (attempt.targetReached != false || edge <= MIN_LONG_EDGE) break
                    val minSize = attempt.minQualitySize.coerceAtLeast(1)
                    val ratio = (sqrt(profile.maxBytes.toDouble() / minSize) * 0.91).coerceIn(0.55, 0.88)
                    val next = max(MIN_LONG_EDGE, (edge * ratio).roundToInt())
                    if (next >= edge) break
                    edge = next
                }
                val result = final ?: error("Не удалось сформировать JPEG")
                return DocumentImageResult(
                    bytes = result.bytes,
                    detected = warped != null,
                    targetReached = if (fitByteTarget) result.targetReached else null,
                    quality = result.quality,
                    width = result.width,
                    height = result.height,
                )
            } finally {
                if (source !== oriented) source.recycle()
            }
        } finally {
            oriented.recycle()
        }
    }

    private data class EncodedAtEdge(
        val bytes: ByteArray,
        val quality: Int,
        val targetReached: Boolean?,
        val minQualitySize: Int,
        val width: Int,
        val height: Int,
    )

    internal fun mapViewportQuadToFrame(
        quad: DocumentQuad,
        sourceAspect: Float,
        viewportAspect: Float?,
    ): DocumentQuad {
        if (viewportAspect == null || sourceAspect <= 0f || viewportAspect <= 0f ||
            kotlin.math.abs(sourceAspect - viewportAspect) < 0.001f
        ) return quad
        val points = quad.points().map { point ->
            if (sourceAspect > viewportAspect) {
                val visibleFraction = (viewportAspect / sourceAspect).coerceIn(0f, 1f)
                DocumentPoint((point.x - 0.5f) * visibleFraction + 0.5f, point.y)
            } else {
                val visibleFraction = (sourceAspect / viewportAspect).coerceIn(0f, 1f)
                DocumentPoint(point.x, (point.y - 0.5f) * visibleFraction + 0.5f)
            }
        }
        return quad.copy(
            topLeft = points[0],
            topRight = points[1],
            bottomRight = points[2],
            bottomLeft = points[3],
        )
    }

    private fun encodeAtEdge(
        source: Bitmap,
        edge: Int,
        profile: DocumentCaptureProfile,
        stamp: CameraOverlaySettings,
        timestampMillis: Long,
        fitByteTarget: Boolean,
    ): EncodedAtEdge {
        val scaled = scaledCopy(source, edge)
        try {
            CameraOverlayRenderer.drawOnBitmap(scaled, stamp, timestampMillis)
            if (!fitByteTarget) {
                val bytes = encodeJpeg(scaled, 95)
                return EncodedAtEdge(bytes, 95, null, bytes.size, scaled.width, scaled.height)
            }
            val fit = JpegByteTargetSearch.choose(profile.maxBytes) { quality -> encodeJpeg(scaled, quality) }
            return EncodedAtEdge(
                fit.bytes,
                fit.quality,
                fit.targetReached,
                fit.minQualityBytes.size,
                scaled.width,
                scaled.height,
            )
        } finally {
            scaled.recycle()
        }
    }

    private fun scaledCopy(source: Bitmap, maxEdge: Int): Bitmap {
        val scale = min(1f, maxEdge.toFloat() / max(source.width, source.height))
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled === source) return source.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled.isMutable) return scaled
        val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
        scaled.recycle()
        return mutable ?: error("Не удалось подготовить bitmap для кодирования")
    }

    private fun perspectiveCrop(source: Bitmap, quad: DocumentQuad, maxEdge: Int): Bitmap? {
        if (!isValidQuad(quad)) return null
        val pts = quad.points().map { p ->
            DocumentPoint(
                (p.x * source.width).coerceIn(0f, (source.width - 1).toFloat()),
                (p.y * source.height).coerceIn(0f, (source.height - 1).toFloat()),
            )
        }
        fun side(a: Int, b: Int): Double = hypot(
            (pts[a].x - pts[b].x).toDouble(),
            (pts[a].y - pts[b].y).toDouble(),
        )
        val outW = ((side(0, 1) + side(3, 2)) / 2.0).roundToInt().coerceAtLeast(1)
        val outH = ((side(0, 3) + side(1, 2)) / 2.0).roundToInt().coerceAtLeast(1)
        if (outW < 80 || outH < 80) return null
        val scale = min(1.0, maxEdge.toDouble() / max(outW, outH))
        val w = max(1, (outW * scale).roundToInt())
        val h = max(1, (outH * scale).roundToInt())
        val src = floatArrayOf(
            pts[0].x, pts[0].y, pts[1].x, pts[1].y,
            pts[2].x, pts[2].y, pts[3].x, pts[3].y,
        )
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return output
    }

    private fun isValidQuad(quad: DocumentQuad): Boolean {
        val points = quad.points()
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val c = points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (kotlin.math.abs(cross) < 0.0005f) return false
            val current = if (cross > 0f) 1 else -1
            if (sign != 0 && sign != current) return false
            sign = current
        }
        val area = kotlin.math.abs(points.indices.sumOf { i ->
            val a = points[i]
            val b = points[(i + 1) % 4]
            (a.x * b.y - b.x * a.y).toDouble()
        }) / 2.0
        return area in 0.04..0.96
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
            "Не удалось сжать снимок в JPEG"
        }
        return out.toByteArray()
    }
}
