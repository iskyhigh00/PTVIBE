package com.glidelive.ui

import kotlin.math.abs
import kotlin.math.max

/**
 * Geometría y detección de toques de la batería (sin dependencias de Android, probada en tests).
 * Los 13 pads forman una cuadrícula de [GRID_COLS] x [GRID_ROWS] celdas que cubre toda la superficie: cada punto
 * de la vista pertenece a exactamente un pad, así que ningún toque se pierde y todo el pad es zona sensible.
 */
object DrumHit {
    const val GRID_COLS = 12
    const val GRID_ROWS = 6

    /** Dueño de cada celda de la cuadrícula (fila * GRID_COLS + columna), calculado una sola vez. */
    private val owner = IntArray(GRID_COLS * GRID_ROWS) { -1 }.also { o ->
        for (p in 0 until Pads.COUNT) {
            val l = Pads.layout[p]
            for (r in l[1] until l[1] + l[3]) for (c in l[0] until l[0] + l[2]) o[r * GRID_COLS + c] = p
        }
    }

    /** Pad que ocupa la celda (col, fila), o -1. */
    fun cellOwner(col: Int, row: Int): Int = owner[row * GRID_COLS + col]

    /** Rectángulo visible de cada pad para una vista de [w] x [h], con [gap] de separación entre pads. */
    fun geometry(w: Int, h: Int, gap: Float, l: FloatArray, t: FloatArray, r: FloatArray, b: FloatArray) {
        val cw = w.toFloat() / GRID_COLS
        val ch = h.toFloat() / GRID_ROWS
        for (i in 0 until Pads.COUNT) {
            val c = Pads.layout[i]
            l[i] = c[0] * cw + gap / 2
            t[i] = c[1] * ch + gap / 2
            r[i] = (c[0] + c[2]) * cw - gap / 2
            b[i] = (c[1] + c[3]) * ch - gap / 2
        }
    }

    /** Pad tocado en (x, y) para una vista de [w] x [h]; los bordes se agrupan en el pad más cercano. */
    fun hitTest(x: Float, y: Float, w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return -1
        val col = (x / w * GRID_COLS).toInt().coerceIn(0, GRID_COLS - 1)
        val row = (y / h * GRID_ROWS).toInt().coerceIn(0, GRID_ROWS - 1)
        return owner[row * GRID_COLS + col]
    }

    /** Posición del toque dentro del pad, de -1 (izquierda/arriba) a 1 (derecha/abajo). */
    fun norm(v: Float, lo: Float, hi: Float): Float = ((v - lo) / max(hi - lo, 1f) * 2f - 1f).coerceIn(-1f, 1f)

    /** Velocidad según dónde se toca: centro fuerte, borde suave; franja inferior de la caja = ghost note. */
    fun velocity(pad: Int, nx: Float, ny: Float): Float {
        if (pad == Pads.SNARE && ny > GHOST_FROM) return GHOST_VELOCITY
        val d = max(abs(nx), abs(ny))
        return if (d < 0.5f) 1f - 0.3f * d else 0.85f - 0.9f * (d - 0.5f)
    }

    /** Desde esta altura relativa (0 = centro, 1 = borde inferior) la caja toca ghost notes (franja del ~22 %). */
    const val GHOST_FROM = 0.56f
    const val GHOST_VELOCITY = 0.28f
}
