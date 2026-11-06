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
 * Líneas de las voces de los acordes: una línea por voz (agudo siempre blanco, bajo siempre rojo; el resto según
 * cuántas voces haya), playhead fijo al 37 %, pasado brillante y futuro tenue, transiciones en S.
 * Arriba va la cabecera con el acorde actual (fundamental muy grande + calidad); el gráfico ocupa el resto.
 * Sin bucle de acordes, a la derecha del playhead se proyecta la salida: las voces superiores convergen en la voz
 * mediana y esta y el bajo siguen rectas.
 * Se redibuja a 60 fps leyendo un historial: nunca interactúa con el hilo de audio.
 */
class ChordVizView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val bg = Paint()
    private val grid = Paint().apply { strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.6f * dp; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 8f * dp; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; isFakeBoldText = true }
    private val accP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; isFakeBoldText = true }
    private val qualP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; isFakeBoldText = true }
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
    private var curRoot: String? = null
    private var curAcc: String = ""
    private var curQual: String = ""
    private var chartTop = 0f
    private var chartH = 1f
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
        small.textSize = 12f * dp
        smallC.textSize = 11f * dp
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

    private fun yOf(p: Float) = chartTop + chartH / 2f - (p - center) * (chartH / SEMIS)

    /** Color según el número de voces distintas: agudo blanco, bajo rojo, el resto según la tabla. */
    private fun colorFor(rank: Int, n: Int): Int {
        if (n <= 1 || rank == 0) return WHITE
        if (rank >= n - 1) return RED
        val pal = INTERIOR[min(n, 6) - 3]
        return pal[min(rank - 1, pal.size - 1)]
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (colN.isEmpty()) return
        val now = EventHub.nowMs()
        val playX = w * 0.37f
        val msPerPx = windowMs / w
        val ghost = EventHub.ghostLoopMs
        val tl = EventHub.timeline
        chartTop = h * 0.25f
        chartH = h - chartTop
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
            curRoot = null
            for (i in 0 until nm) {
                val m = marks[i]
                markT[i] = m.tMs; markName[i] = m.name
                if (m.tMs > latest) latest = m.tMs
                if (m.tMs <= now && m.name.isNotEmpty()) { curRoot = m.root; curAcc = m.acc; curQual = m.qual; curAge = now - m.tMs }
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
            // Voces activas en el playhead (notas distintas, de agudo a grave).
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
            var d = 0
            for (r in 0 until min(k, MAXV)) {
                val p = pitchOf[ord[r]]
                if (d > 0 && abs(actP[d - 1] - p) < 0.05f) continue
                actP[d++] = p
            }
            actN = d
        }

        // Fase 2 (sin lock): dibujar desde la instantánea.
        frame.set(0f, 0f, w, h)
        c.drawRoundRect(frame, 28f, 28f, bg)

        // Cabecera: acorde actual (fundamental muy grande, calidad a su lado).
        val root = curRoot
        if (root != null && (actN > 0 || curAge < RECENT_MS)) drawHeader(c, w, root)

        c.save()
        c.clipRect(0f, chartTop, w, h)

        // Cuadrícula: una línea por semitono.
        val lo = (center - SEMIS / 2).toInt() - 1
        for (m in lo..lo + SEMIS.toInt() + 2) {
            val y = yOf(m.toFloat())
            grid.color = if (Math.floorMod(m, 12) == 0) 0x40FFFFFF else 0x16FFFFFF
            c.drawLine(0f, y, w, y, grid)
        }
        // Cuadrícula vertical muy tenue cada segundo: se desplaza con el tiempo.
        grid.color = 0x0DFFFFFF
        var k0 = Math.floor((now - playX * msPerPx) / 1000.0)
        while (true) {
            val x = playX + ((k0 * 1000.0 - now) / msPerPx).toFloat()
            if (x > w) break
            if (x >= 0f) c.drawLine(x, chartTop, x, h, grid)
            k0 += 1.0
        }

        // Líneas verticales de cambio de acorde.
        for (i in 0 until markCount) {
            val x = playX + ((markT[i] - now) / msPerPx).toFloat()
            if (x < -50 || x > w) continue
            grid.color = if (markT[i] <= now) 0x66FFFFFF else 0x22FFFFFF
            c.drawLine(x, chartTop, x, h, grid)
        }

        // Voces.
        java.util.Arrays.fill(lastVoice, 0, n, -2)
        for (col in 0 until cols) {
            val x = col * colX
            val a = if (x > playX) 0.30f else 1f
            val base = col * MAXV
            // Notas distintas de la columna (las que coinciden se funden en una sola línea).
            var dn = 0
            var lastP = Float.NaN
            for (r in 0 until colN[col]) {
                val p = colP[base + r]
                if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue
                lastP = p; dn++
            }
            var rank = 0
            lastP = Float.NaN
            for (r in 0 until colN[col]) {
                val idx = colIdx[base + r]
                val p = colP[base + r]
                if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue
                lastP = p
                val colr = colorFor(rank, dn)
                val y = yOf(p)
                if (lastVoice[idx] == col - 1) {
                    glow.color = withA(colr, 0.16f * a); c.drawLine(px[idx], py[idx], x, y, glow)
                    line.color = withA(colr, a); c.drawLine(px[idx], py[idx], x, y, line)
                }
                px[idx] = x; py[idx] = y; lastVoice[idx] = col
                rank++
            }
        }

        // Salida proyectada (solo si no hay un bucle de acordes sonando).
        if (ghost <= 0.0 && actN > 0) drawProjection(c, playX, w)

        // Playhead fijo con puntos brillantes y la nota a su derecha.
        grid.color = 0x99FFFFFF.toInt()
        c.drawLine(playX, chartTop, playX, h, grid)
        val lineH = small.textSize
        var prevY = -1.0e9f
        var far = false
        for (r in 0 until actN) {
            val p = actP[r]
            val col = colorFor(r, actN)
            val y = yOf(p)
            dot.color = withA(col, if (r == 0) 0.30f else 0.25f)
            c.drawCircle(playX, y, (if (r == 0) 17f else 11f) * dp, dot)
            dot.color = col; c.drawCircle(playX, y, 5f * dp, dot)
            // Etiquetas demasiado juntas: se alterna entre un desplazamiento cercano y otro lejano.
            far = if (abs(y - prevY) < 1.2f * lineH) !far else false
            prevY = y
            small.color = col
            c.drawText(midiLabel(p.roundToInt()), playX + (if (far) 44f else 12f) * dp, y + lineH * 0.35f, small)
        }
        c.restore()

        // Nombres de los acordes sobre las líneas de cambio, arriba del área del gráfico.
        smallC.textSize = 11f * dp
        small.textSize = 12f * dp
        for (i in 0 until markCount) {
            val x = playX + ((markT[i] - now) / msPerPx).toFloat()
            if (x < -50 || x > w) continue
            qualP.textSize = 11f * dp
            qualP.color = if (markT[i] <= now) 0xCCFFFFFF.toInt() else 0x66FFFFFF
            c.drawText(markName[i] ?: "", x + 3 * dp, chartTop - 4 * dp, qualP)
        }

        if (busy) postInvalidateOnAnimation()  // 60 fps solo mientras hay voces o eventos recientes
        else scheduleIdleCheck()
    }

    /** Cabecera centrada: letra de la fundamental, accidental en superíndice y calidad a su lado. */
    private fun drawHeader(c: Canvas, w: Float, root: String) {
        val baseline = chartTop - 20f * dp
        var rs = (baseline - 3f * dp) / 0.72f
        val acc = curAcc
        val qual = curQual
        var rw = 0f; var aw = 0f; var qw = 0f
        for (pass in 0..1) {
            big.textSize = rs; accP.textSize = rs * 0.45f; qualP.textSize = rs * 0.42f
            rw = big.measureText(root)
            aw = if (acc.isEmpty()) 0f else accP.measureText(acc)
            qw = if (qual.isEmpty()) 0f else qualP.measureText(qual)
            val total = rw + aw + qw + (if (qw > 0f) 4f * dp else 0f)
            if (total <= w * 0.92f) break
            rs *= w * 0.92f / total
        }
        val total = rw + aw + qw + (if (qw > 0f) 4f * dp else 0f)
        var x = (w - total) / 2f
        big.color = 0xFFFFFFFF.toInt()
        c.drawText(root, x, baseline, big)
        x += rw
        if (acc.isNotEmpty()) {
            accP.color = 0xFFFFFFFF.toInt()
            c.drawText(acc, x, baseline - rs * 0.40f, accP)
            x += aw
        }
        if (qual.isNotEmpty()) {
            qualP.color = 0xE6FFFFFF.toInt()
            c.drawText(qual, x + 4f * dp, baseline, qualP)
        }
    }

    /**
     * Proyección de la salida: las voces superiores convergen en la mediana (índice n/2 de grave a agudo) en
     * ~0,33 del ancho; la mediana y el bajo siguen rectos. Todo atenuado (alpha 0,35 que se desvanece).
     */
    private fun drawProjection(c: Canvas, playX: Float, w: Float) {
        val n = actN
        val midR = n - 1 - n / 2          // posición de la mediana en el orden agudo -> grave
        val bassR = n - 1
        val endX = playX + w * 0.33f
        val yMid = yOf(actP[midR])
        for (r in 0 until n) {
            val colr = colorFor(r, n)
            val y0 = yOf(actP[r])
            val converge = r != midR && r != bassR
            val segs = if (converge) 14 else 8
            var x0 = playX; var yy0 = y0
            for (sgi in 1..segs) {
                val t = sgi / segs.toFloat()
                val x1: Float; val y1: Float; val al: Float
                if (converge) {
                    x1 = playX + (endX - playX) * t
                    val sm = t * t * (3 - 2 * t)
                    y1 = y0 + (yMid - y0) * sm
                    al = 0.35f * (1f - t)
                } else {
                    x1 = playX + (w - playX) * t
                    y1 = y0
                    al = 0.30f - 0.20f * t
                }
                glow.color = withA(colr, 0.12f * al / 0.35f); c.drawLine(x0, yy0, x1, y1, glow)
                line.color = withA(colr, al); c.drawLine(x0, yy0, x1, y1, line)
                x0 = x1; yy0 = y1
            }
        }
        // Etiquetas tenues de la mediana y el bajo donde terminan las curvas.
        small.color = withA(colorFor(midR, n), 0.45f)
        c.drawText(midiLabel(actP[midR].roundToInt()), endX + 6f * dp, yMid - 4f * dp, small)
        if (bassR != midR) {
            small.color = withA(colorFor(bassR, n), 0.45f)
            c.drawText(midiLabel(actP[bassR].roundToInt()), endX + 6f * dp, yOf(actP[bassR]) - 4f * dp, small)
        }
    }

    private fun withA(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private companion object {
        const val MAXV = 16
        const val RECENT_MS = 4000.0
        const val SEMIS = 28f
        val WHITE = 0xFFFFFFFF.toInt()
        val RED = 0xFFFF5A4D.toInt()
        val LILA = 0xFFB48CFF.toInt()
        val BLUE = 0xFF5AA0FF.toInt()
        val GREEN = 0xFF48E08C.toInt()
        val ORANGE = 0xFFFFA040.toInt()
        // Colores intermedios (entre el agudo blanco y el bajo rojo) para 3, 4, 5 y 6 voces.
        val INTERIOR = arrayOf(
            intArrayOf(BLUE),
            intArrayOf(LILA, GREEN),
            intArrayOf(LILA, BLUE, GREEN),
            intArrayOf(LILA, BLUE, GREEN, ORANGE),
        )
    }
}
