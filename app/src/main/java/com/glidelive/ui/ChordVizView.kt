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
    private var px = FloatArray(64)
    private var py = FloatArray(64)
    private var center = 60f
    private val windowMs = 8000.0
    private val colX = 3f * dp

    // Instantánea por columna (se rellena bajo el lock del historial y se dibuja ya sin él)
    private var colN = IntArray(0)          // voces visibles en cada columna (de agudo a grave)
    private var colIdx = IntArray(0)        // índice de span por columna y rango
    private var colP = FloatArray(0)        // altura por columna y rango
    private var actIdx = IntArray(MAXV)     // voces activas en el playhead
    private var actP = FloatArray(MAXV)
    private var actN = 0
    private var markT = DoubleArray(64)
    private var markName = arrayOfNulls<String>(64)
    private var markCount = 0
    private var curName: String? = null
    private var curAge = 0.0
    private var ord = IntArray(64)
    private var pitchOf = FloatArray(64)
    private var lastVoice = IntArray(64)    // última columna en la que se dibujó cada voz
    private val midiLabels = arrayOfNulls<String>(128)
    private var lastSeq = -1L
    private var idleChecker: Runnable? = null

    private fun midiLabel(m: Int): String {
        if (m !in 0..127) return NoteNames.midiName(m)
        return midiLabels[m] ?: NoteNames.midiName(m).also { midiLabels[m] = it }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1B120C.toInt(), 0xFF0C0807.toInt(), Shader.TileMode.CLAMP)
        big.textSize = h * 0.34f
        small.textSize = 12f * dp
        smallC.textSize = 12f * dp
        val cols = (w / colX).toInt() + 3
        colN = IntArray(cols)
        colIdx = IntArray(cols * MAXV)
        colP = FloatArray(cols * MAXV)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastSeq = -1L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        idleChecker?.let { removeCallbacks(it) }
        idleChecker = null
        super.onDetachedFromWindow()
    }

    /** En reposo no se redibuja; este sondeo barato despierta la vista cuando llegan eventos nuevos. */
    private fun scheduleIdleCheck() {
        val r = idleChecker ?: Runnable {
            if (EventHub.chordSeq != lastSeq) postInvalidateOnAnimation() else scheduleIdleCheck()
        }.also { idleChecker = it }
        removeCallbacks(r)
        postDelayed(r, 100)
    }

    private fun ensureVoices(n: Int) {
        if (ord.size < n) {
            ord = IntArray(n * 2); pitchOf = FloatArray(n * 2); lastVoice = IntArray(n * 2)
            px = FloatArray(n * 2); py = FloatArray(n * 2)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (colN.isEmpty()) return
        val now = EventHub.nowMs()
        val playX = w * 0.37f
        val msPerPx = windowMs / w
        val semis = 30f
        val ghost = EventHub.ghostLoopMs
        val tl = EventHub.timeline
        var busy = ghost > 0
        var cols = 0
        var n = 0

        // Fase 1 (bajo el lock): copiar al mínimo lo necesario del historial.
        synchronized(tl) {
            lastSeq = EventHub.chordSeq
            val spans = tl.spans
            n = spans.size
            ensureVoices(n)
            // Centro vertical: sigue a las notas activas con suavidad.
            var sum = 0f; var cnt = 0
            var latest = -1.0e18
            for (i in 0 until n) {
                val sp = spans[i]
                if (sp.activeAt(now)) { sum += sp.pitchAt(now); cnt++ }
                if (sp.startMs > latest) latest = sp.startMs
                if (sp.endMs != Double.POSITIVE_INFINITY && sp.endMs > latest) latest = sp.endMs
            }
            if (cnt > 0) { center += (sum / cnt - center) * 0.08f; busy = true }
            val marks = tl.marks
            val nm = marks.size
            if (markT.size < nm) { markT = DoubleArray(nm * 2); markName = arrayOfNulls(nm * 2) }
            markCount = nm
            curName = null
            for (i in 0 until nm) {
                val m = marks[i]
                markT[i] = m.tMs; markName[i] = m.name
                if (m.tMs > latest) latest = m.tMs
                if (m.tMs <= now && m.name.isNotEmpty()) { curName = m.name; curAge = now - m.tMs }
            }
            if (now - latest < RECENT_MS) busy = true

            // Voces por columna de tiempo.
            var x = 0f
            while (x <= w && cols < colN.size) {
                val t = now + (x - playX) * msPerPx
                val future = t > now
                var k = 0
                for (i in 0 until n) {
                    val sp = spans[i]
                    val tt = if (future) (if (ghost > 0) t - ghost else Double.NaN) else t
                    if (tt.isNaN() || tt > now || !sp.activeAt(tt)) continue
                    val p = sp.pitchAt(tt)
                    // inserción estable, de agudo a grave
                    var j = k
                    while (j > 0 && pitchOf[ord[j - 1]] < p) { j-- }
                    pitchOf[i] = p
                    // desplazar a la derecha las voces más graves
                    var m = k
                    while (m > j) { ord[m] = ord[m - 1]; m-- }
                    ord[j] = i
                    k++
                }
                val base = cols * MAXV
                val kk = min(k, MAXV)
                for (r in 0 until kk) { colIdx[base + r] = ord[r]; colP[base + r] = pitchOf[ord[r]] }
                colN[cols] = kk
                cols++
                x += colX
            }
            // Voces activas en el playhead.
            var k = 0
            for (i in 0 until n) {
                val sp = spans[i]
                if (!sp.activeAt(now)) continue
                val p = sp.pitchAt(now)
                pitchOf[i] = p
                var j = k
                while (j > 0 && pitchOf[ord[j - 1]] < p) j--
                var m = k
                while (m > j) { ord[m] = ord[m - 1]; m-- }
                ord[j] = i
                k++
            }
            actN = min(k, MAXV)
            for (r in 0 until actN) { actIdx[r] = ord[r]; actP[r] = pitchOf[ord[r]] }
        }

        // Fase 2 (sin lock): dibujar desde la instantánea.
        frame.set(0f, 0f, w, h)
        c.drawRoundRect(frame, 28f, 28f, bg)
        fun yOf(p: Float) = h / 2f - (p - center) * (h / semis)

        // Cuadrícula: una línea por semitono.
        val lo = (center - semis / 2).toInt() - 1
        for (m in lo..lo + semis.toInt() + 2) {
            val y = yOf(m.toFloat())
            grid.color = if (Math.floorMod(m, 12) == 0) 0x40FFFFFF else 0x16FFFFFF
            c.drawLine(0f, y, w, y, grid)
        }

        // Nombre del acorde actual, muy grande y centrado.
        val cur = curName
        if (cur != null && curAge < 600_000) {
            big.color = 0x30FFFFFF
            var ts = h * 0.34f
            big.textSize = ts
            while (big.measureText(cur) > w * 0.8f && ts > 12f) { ts -= 2f; big.textSize = ts }
            c.drawText(cur, w / 2, h / 2 + ts * 0.35f, big)
        }

        // Líneas verticales de cambio de acorde con su nombre.
        for (i in 0 until markCount) {
            val x = playX + ((markT[i] - now) / msPerPx).toFloat()
            if (x < -50 || x > w) continue
            grid.color = if (markT[i] <= now) 0x66FFFFFF else 0x22FFFFFF
            c.drawLine(x, 0f, x, h, grid)
            small.color = if (markT[i] <= now) 0xCCFFFFFF.toInt() else 0x55FFFFFF
            c.drawText(markName[i] ?: "", x + 4 * dp, 14 * dp, small)
        }

        // Voces.
        java.util.Arrays.fill(lastVoice, 0, n, -2)
        for (col in 0 until cols) {
            val x = col * colX
            val a = if (x > playX) 0.30f else 1f
            var rank = 0
            var lastP = Float.NaN
            val base = col * MAXV
            for (r in 0 until colN[col]) {
                val idx = colIdx[base + r]
                val p = colP[base + r]
                // Voces con la misma nota se fusionan en una sola línea.
                if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue
                lastP = p
                val colr = colors[min(rank, colors.size - 1)]
                val y = yOf(p)
                if (lastVoice[idx] == col - 1) {
                    glow.color = withA(colr, 0.16f * a); c.drawLine(px[idx], py[idx], x, y, glow)
                    line.color = withA(colr, a); c.drawLine(px[idx], py[idx], x, y, line)
                }
                px[idx] = x; py[idx] = y; lastVoice[idx] = col
                rank++
            }
        }

        // Playhead fijo con puntos brillantes y la nota a su derecha.
        grid.color = 0x99FFFFFF.toInt()
        c.drawLine(playX, 0f, playX, h, grid)
        var rank = 0; var lastP = Float.NaN
        for (r in 0 until actN) {
            val p = actP[r]
            if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue
            lastP = p
            val col = colors[min(rank, colors.size - 1)]
            val y = yOf(p)
            dot.color = withA(col, 0.25f); c.drawCircle(playX, y, 11f * dp, dot)
            dot.color = col; c.drawCircle(playX, y, 5f * dp, dot)
            small.color = col
            c.drawText(midiLabel(p.roundToInt()), playX + 10f * dp, y - 6f * dp, small)
            rank++
        }
        if (busy) postInvalidateOnAnimation()  // 60 fps solo mientras hay voces o eventos recientes
        else scheduleIdleCheck()
    }

    private fun withA(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private companion object {
        const val MAXV = 16
        const val RECENT_MS = 4000.0
    }
}
