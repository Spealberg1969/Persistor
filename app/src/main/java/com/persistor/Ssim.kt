package com.persistor

import android.graphics.Bitmap

/**
 * Упрощённый SSIM по яркости: окна 8x8 без перекрытия, читаем битмапы полосами по 8 строк
 * (чтобы не держать в памяти массив пикселей всего снимка).
 * Значения близки к «настоящему» SSIM, но не идентичны тем, что показывает Image Toolbox.
 */
object Ssim {
    private const val C1 = (0.01 * 255) * (0.01 * 255)
    private const val C2 = (0.03 * 255) * (0.03 * 255)

    fun compute(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 0.0
        val w = a.width
        val h = a.height
        val pa = IntArray(w * 8)
        val pb = IntArray(w * 8)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y + 8 <= h) {
            a.getPixels(pa, 0, w, 0, y, w, 8)
            b.getPixels(pb, 0, w, 0, y, w, 8)
            var x = 0
            while (x + 8 <= w) {
                var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
                for (r in 0 until 8) {
                    val base = r * w + x
                    for (c in 0 until 8) {
                        val la = luma(pa[base + c])
                        val lb = luma(pb[base + c])
                        sa += la; sb += lb
                        saa += la * la; sbb += lb * lb; sab += la * lb
                    }
                }
                val ma = sa / 64.0
                val mb = sb / 64.0
                val va = saa / 64.0 - ma * ma
                val vb = sbb / 64.0 - mb * mb
                val cov = sab / 64.0 - ma * mb
                sum += ((2 * ma * mb + C1) * (2 * cov + C2)) / ((ma * ma + mb * mb + C1) * (va + vb + C2))
                count++
                x += 8
            }
            y += 8
        }
        return if (count == 0) 1.0 else sum / count
    }

    private fun luma(p: Int): Double =
        0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
}
