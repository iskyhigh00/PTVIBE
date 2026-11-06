package com.glidelive.ui

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometría y detección de toques de la batería (sin dependencias de Android, probada en tests).
 * Cada pad es un círculo o una elipse; el toque se resuelve contra el pad que lo contiene y, si cae en un hueco,
 * contra el más cercano (Voronoi aproximado en distancia normalizada), para que casi toda la superficie suene.
 */
object DrumHit {
    /** Un toque dentro de la elipse (con un 6 % de margen) siempre dispara ese pad. */
    const val INSIDE = 1.06f

    /** Fuera de todas las elipses, dispara el pad más cercano si su distancia normalizada no supera esto. */
    const val NEAR = 2.0f

    private fun elliptical(i: Int) = Pads.isCymbal(i) || Pads.isHat(i)

    /** Calcula centros y radios de cada pad para una vista de [w] x [h] con un margen [gap]. */
    fun geometry(w: Int, h: Int, gap: Float, cx: FloatArray, cy: FloatArray, rx: FloatArray, ry: FloatArray) {
        for (i in 0 until Pads.COUNT) {
            val l = Pads.layout[i]
            val x0 = l[0] * w + gap
            val y0 = l[1] * h + gap
            val x1 = (l[0] + l[2]) * w - gap
            val y1 = (l[1] + l[3]) * h - gap
            cx[i] = (x0 + x1) / 2
            cy[i] = (y0 + y1) / 2
            if (elliptical(i)) {
                rx[i] = (x1 - x0) / 2
                ry[i] = (y1 - y0) / 2
            } else {
                val r = min(x1 - x0, y1 - y0) / 2
                rx[i] = r
                ry[i] = r
            }
        }
    }

    /** Orden de prueba: los pads pequeños primero para que no los tape uno grande. Se calcula una vez por tamaño. */
    fun orderByArea(rx: FloatArray, ry: FloatArray, out: IntArray) {
        for (i in out.indices) {
            var j = i
            val a = rx[i] * ry[i]
            while (j > 0 && rx[out[j - 1]] * ry[out[j - 1]] > a) { out[j] = out[j - 1]; j-- }
            out[j] = i
        }
    }

    fun normDist(x: Float, y: Float, cx: FloatArray, cy: FloatArray, rx: FloatArray, ry: FloatArray, i: Int): Float {
        val dx = (x - cx[i]) / rx[i]
        val dy = (y - cy[i]) / ry[i]
        return sqrt(dx * dx + dy * dy)
    }

    /** Índice del pad que suena para el toque (x, y), o -1 si queda demasiado lejos de todos. */
    fun hitTest(
        x: Float, y: Float,
        cx: FloatArray, cy: FloatArray, rx: FloatArray, ry: FloatArray,
        order: IntArray,
    ): Int {
        var nearest = -1
        var nearestD = Float.MAX_VALUE
        for (k in order.indices) {
            val i = order[k]
            val d = normDist(x, y, cx, cy, rx, ry, i)
            if (d <= INSIDE) return i
            if (d < nearestD) { nearestD = d; nearest = i }
        }
        return if (nearestD <= NEAR) nearest else -1
    }
}
