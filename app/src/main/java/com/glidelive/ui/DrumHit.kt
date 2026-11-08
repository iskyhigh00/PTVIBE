package com.glidelive.ui

import kotlin.math.abs
import kotlin.math.max

/**
 * Geometría y detección de toques de la batería (sin dependencias de Android, probada en tests).
 * Cada [PadLayout] es una cuadrícula que cubre toda la superficie: cada punto de la vista pertenece a un pad
 * (o a ninguno solo si una distribución oculta pads), así que ningún toque cae en un hueco.
 */
object DrumHit {
    /** Rectángulo visible de cada pad para una vista de [w] x [h], con [gap] entre pads. Los ocultos quedan en 0. */
    fun geometry(layout: PadLayout, w: Int, h: Int, gap: Float, l: FloatArray, t: FloatArray, r: FloatArray, b: FloatArray) {
        val cw = w.toFloat() / layout.cols
        val ch = h.toFloat() / layout.rows
        for (i in 0 until Pads.TOTAL) {
            val c = layout.cells[i]
            if (c == null) { l[i] = 0f; t[i] = 0f; r[i] = 0f; b[i] = 0f; continue }
            l[i] = c[0] * cw + gap / 2
            t[i] = c[1] * ch + gap / 2
            r[i] = (c[0] + c[2]) * cw - gap / 2
            b[i] = (c[1] + c[3]) * ch - gap / 2
        }
    }

    /** Pad tocado en (x, y) para una vista de [w] x [h]; los bordes se agrupan en el pad más cercano. */
    fun hitTest(layout: PadLayout, x: Float, y: Float, w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return -1
        val col = (x / w * layout.cols).toInt().coerceIn(0, layout.cols - 1)
        val row = (y / h * layout.rows).toInt().coerceIn(0, layout.rows - 1)
        return layout.owner[row * layout.cols + col]
    }

    /** Posición del toque dentro del pad, de -1 (izquierda/arriba) a 1 (derecha/abajo). */
    fun norm(v: Float, lo: Float, hi: Float): Float = ((v - lo) / max(hi - lo, 1f) * 2f - 1f).coerceIn(-1f, 1f)

    /** Fuerza de un toque: siempre la misma, caiga donde caiga dentro del pad (los bordes no suenan distinto). */
    const val VELOCITY = 0.9f

    @Suppress("UNUSED_PARAMETER")
    fun velocity(pad: Int, nx: Float, ny: Float): Float = VELOCITY
}
