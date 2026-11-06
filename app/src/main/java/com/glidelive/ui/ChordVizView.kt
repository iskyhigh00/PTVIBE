package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.glidelive.theory.NoteNames
import com.glidelive.viz.EventHub
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Líneas de las voces de los acordes: una línea por voz (blanco = agudo … rojo = bajo), playhead fijo al 37 %,
 * pasado brillante y futuro tenue, nombre del acorde actual muy grande, transiciones en S.
 * Se redibuja a 60 fps leyendo un historial: nunca interactúa con el hilo de audio.
 */
class ChordVizView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val colors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFB48CFF.toInt(), 0xFF5AA0FF.toInt(),
        0xFF48E08C.toInt(), 0xFFFFA040.toInt(), 0xFFFF5A4D.toInt(),
    )
    private val bg = Paint()
    private val grid = Paint().apply { strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.6f * dp; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 8f * dp; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val smallC = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val frame = RectF()
    private var center = 60f
    private val windowMs = 8000.0
    private val colX = 3f * dp

    // Pendientes de la última columna por voz
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pa = BooleanArray(0)
    private var ca = BooleanArray(0)

    private val order = ArrayList<Int>()
    private val pitchOf = FloatArray(256)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1B120C.toInt(), 0xFF0C0807.toInt(), Shader.TileMode.CLAMP)
        big.textSize = h * 0.34f
        small.textSize = 12f * dp
        smallC.textSize = 12f * dp
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        frame.set(0f, 0f, w, h)
        c.drawRoundRect(frame, 28f, 28f, bg)
        val now = EventHub.nowMs()
        val playX = w * 0.37f
        val msPerPx = windowMs / w
        val semis = 30f

        val tl = EventHub.timeline
        synchronized(tl) {
            // Centro vertical: sigue a las notas activas con suavidad.
            var sum = 0f; var cnt = 0
            for (sp in tl.spans) if (sp.activeAt(now)) { sum += sp.pitchAt(now); cnt++ }
            if (cnt > 0) center += (sum / cnt - center) * 0.08f
            fun yOf(p: Float) = h / 2f - (p - center) * (h / semis)

            // Cuadrícula: una línea por semitono.
            val lo = (center - semis / 2).toInt() - 1
            for (m in lo..lo + semis.toInt() + 2) {
                val y = yOf(m.toFloat())
                grid.color = if (Math.floorMod(m, 12) == 0) 0x40FFFFFF else 0x16FFFFFF
                c.drawLine(0f, y, w, y, grid)
            }

            // Nombre del acorde actual, muy grande y centrado.
            val cur = tl.marks.lastOrNull { it.tMs <= now }
            if (cur != null && cur.name.isNotEmpty() && now - cur.tMs < 600_000) {
                big.color = 0x30FFFFFF
                var ts = h * 0.34f
                big.textSize = ts
                while (big.measureText(cur.name) > w * 0.8f && ts > 12f) { ts -= 2f; big.textSize = ts }
                c.drawText(cur.name, w / 2, h / 2 + ts * 0.35f, big)
            }

            // Líneas verticales de cambio de acorde con su nombre.
            for (m in tl.marks) {
                val x = playX + ((m.tMs - now) / msPerPx).toFloat()
                if (x < -50 || x > w) continue
                grid.color = if (m.tMs <= now) 0x66FFFFFF else 0x22FFFFFF
                c.drawLine(x, 0f, x, h, grid)
                small.color = if (m.tMs <= now) 0xCCFFFFFF.toInt() else 0x55FFFFFF
                c.drawText(m.name, x + 4 * dp, 14 * dp, small)
            }

            // Voces.
            val n = tl.spans.size
            if (px.size < n) { px = FloatArray(n * 2); py = FloatArray(n * 2); pa = BooleanArray(n * 2); ca = BooleanArray(n * 2) }
            java.util.Arrays.fill(pa, false)
            val ghost = EventHub.ghostLoopMs
            var x = 0f
            while (x <= w) {
                val t = now + (x - playX) * msPerPx
                val future = t > now
                order.clear()
                java.util.Arrays.fill(ca, false)
                for (i in 0 until n) {
                    val sp = tl.spans[i]
                    val tt = if (future) (if (ghost > 0) t - ghost else Double.NaN) else t
                    if (tt.isNaN() || tt > now || !sp.activeAt(tt)) continue
                    pitchOf[i.coerceAtMost(255)] = sp.pitchAt(tt)
                    order.add(i)
                }
                order.sortByDescending { pitchOf[it.coerceAtMost(255)] }
                var rank = 0
                var lastP = Float.NaN
                val a = if (future) 0.30f else 1f
                for (idx in order) {
                    val p = pitchOf[idx.coerceAtMost(255)]
                    // Voces con la misma nota se fusionan en una sola línea.
                    if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue
                    lastP = p
                    val col = colors[min(rank, colors.size - 1)]
                    val y = yOf(p)
                    if (pa[idx]) {
                        glow.color = withA(col, 0.16f * a); c.drawLine(px[idx], py[idx], x, y, glow)
                        line.color = withA(col, a); c.drawLine(px[idx], py[idx], x, y, line)
                    }
                    px[idx] = x; py[idx] = y; ca[idx] = true
                    rank++
                }
                val tmp = pa; pa = ca; ca = tmp
                x += colX
            }

            // Playhead fijo con puntos brillantes y la nota a su derecha.
            grid.color = 0x99FFFFFF.toInt()
            c.drawLine(playX, 0f, playX, h, grid)
            val active = tl.spans.filter { it.activeAt(now) }.sortedByDescending { it.pitchAt(now) }
            var rank = 0; var lastP = Float.NaN
            for (sp in active) {
                val p = sp.pitchAt(now)
                if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue
                lastP = p
                val col = colors[min(rank, colors.size - 1)]
                val y = yOf(p)
                dot.color = withA(col, 0.25f); c.drawCircle(playX, y, 11f * dp, dot)
                dot.color = col; c.drawCircle(playX, y, 5f * dp, dot)
                small.color = col
                c.drawText(NoteNames.midiName(p.roundToInt()), playX + 10f * dp, y - 6f * dp, small)
                rank++
            }
        }
        postInvalidateOnAnimation()  // 60 fps
    }

    private fun withA(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
