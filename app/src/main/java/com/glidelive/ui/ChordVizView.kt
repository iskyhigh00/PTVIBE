package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.glidelive.theory.NoteNames
import com.glidelive.viz.EventHub
import com.glidelive.viz.VizPrefs
import com.glidelive.viz.VizTheme
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
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var appliedTheme: VizTheme? = null
    private val grid = Paint().apply { strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.7f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4.5f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var spanColor = IntArray(0)   // color de cada voz en cada columna (0 = no se dibuja)
    private var spanY = FloatArray(0)
    private var semisCur = 22f            // rango vertical visible (semitonos); se ajusta a las notas
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
    private val colX = 2f * dp

    // Instantánea por columna (se rellena bajo el lock del historial y se dibuja ya sin él)
    private var colN = IntArray(0)          // voces visibles en cada columna (de agudo a grave)
    private var colIdx = IntArray(0)        // índice de span por columna y rango
    private var colP = FloatArray(0)        // altura por columna y rango
    private var colA = FloatArray(0)        // opacidad (desvanecimiento al apagarse)
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
    private var fadeOf = FloatArray(64)
    private var lastVoice = IntArray(64)    // última columna en la que se dibujó cada voz
    private val midiLabels = arrayOfNulls<String>(128)
    private var lastSeq = -1L
    private var idleChecker: Runnable? = null

    private fun midiLabel(m: Int): String {
        if (m !in 0..127) return NoteNames.midiName(m)
        return midiLabels[m] ?: NoteNames.midiName(m).also { midiLabels[m] = it }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        applyTheme(w, h)
        small.textSize = 12f * dp
        smallC.textSize = 11f * dp
        val cols = (w / colX).toInt() + 3
        colN = IntArray(cols)
        colIdx = IntArray(cols * MAXV)
        colP = FloatArray(cols * MAXV)
        colA = FloatArray(cols * MAXV)
    }

    private fun applyTheme(w: Int, h: Int) {
        val t = VizPrefs.current
        appliedTheme = t
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), t.bgTop, t.bgBottom, Shader.TileMode.CLAMP)
        haloPaint.shader = if (t.halo != 0) {
            RadialGradient(w / 2f, h * 0.14f, w * 0.55f, t.halo, t.halo and 0x00FFFFFF, Shader.TileMode.CLAMP)
        } else null
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
            if (EventHub.chordSeq != lastSeq || appliedTheme != VizPrefs.current) postInvalidateOnAnimation() else scheduleIdleCheck()
        }.also { idleChecker = it }
        removeCallbacks(r)
        postDelayed(r, 100)
    }

    private fun ensureVoices(n: Int) {
        if (ord.size < n) {
            ord = IntArray(n * 2); pitchOf = FloatArray(n * 2); fadeOf = FloatArray(n * 2); lastVoice = IntArray(n * 2)
            px = FloatArray(n * 2); py = FloatArray(n * 2)
        }
    }

    private fun yOf(p: Float) = chartTop + chartH / 2f - (p - center) * (chartH / semisCur)

    /** Color según el estilo elegido y el número de voces distintas (agudo claro, bajo distinto). */
    private fun colorFor(rank: Int, n: Int): Int = VizPrefs.current.color(rank, n)

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
            var cnt = 0
            var pMin = Float.MAX_VALUE; var pMax = -Float.MAX_VALUE
            var latest = -1.0e18
            for (i in 0 until n) {
                val sp = spans[i]
                if (sp.activeAt(now)) {
                    val pp = sp.pitchAt(now)
                    if (pp < pMin) pMin = pp
                    if (pp > pMax) pMax = pp
                    cnt++
                }
                if (sp.startMs > latest) latest = sp.startMs
                if (sp.endMs != Double.POSITIVE_INFINITY && sp.endMs > latest) latest = sp.endMs
            }
            if (cnt > 0) {
                // Centro y zoom siguen al conjunto de notas: así las líneas se separan y se leen bien.
                center += ((pMin + pMax) / 2f - center) * 0.08f
                val target = (pMax - pMin + 12f).coerceIn(16f, 36f)
                semisCur += (target - semisCur) * 0.06f
                busy = true
            }
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
                    fadeOf[i] = sp.fadeAt(tt)
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
                for (r in 0 until kk) { colIdx[base + r] = ord[r]; colP[base + r] = pitchOf[ord[r]]; colA[base + r] = fadeOf[ord[r]] }
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
        if (appliedTheme != VizPrefs.current) applyTheme(width, height)
        frame.set(0f, 0f, w, h)
        c.drawRoundRect(frame, 28f, 28f, bg)
        if (haloPaint.shader != null) c.drawRoundRect(frame, 28f, 28f, haloPaint)

        // Cabecera: acorde actual (fundamental muy grande, calidad a su lado).
        val root = curRoot
        if (root != null && (actN > 0 || curAge < RECENT_MS)) drawHeader(c, w, root)

        c.save()
        c.clipRect(0f, chartTop, w, h)

        // Cuadrícula horizontal: una línea por semitono si caben limpias; si no, solo las de cada otro semitono.
        val lo = (center - semisCur / 2).toInt() - 1
        val step = if (chartH / semisCur >= 5f * dp) 1 else 2
        grid.strokeWidth = 1f
        var m = lo - Math.floorMod(lo, step)
        while (m <= lo + semisCur.toInt() + 3) {
            val y = Math.round(yOf(m.toFloat())).toFloat() + 0.5f   // alineada al píxel: línea nítida
            grid.color = if (Math.floorMod(m, 12) == 0) 0x38FFFFFF else 0x10FFFFFF
            c.drawLine(0f, y, w, y, grid)
            m += step
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

        // Voces: una curva continua por voz (sin segmentos sueltos ni cuentas); brillo suave solo en lo ya tocado.
        val cap = min(n, SPAN_CAP)
        val off = n - cap
        if (spanColor.size < cap * cols) { spanColor = IntArray(cap * cols); spanY = FloatArray(cap * cols) }
        java.util.Arrays.fill(spanColor, 0, cap * cols, 0)
        for (col in 0 until cols) {
            val base = col * MAXV
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
                val li = colIdx[base + r] - off
                val p = colP[base + r]
                if (!lastP.isNaN() && abs(lastP - p) < 0.05f) continue   // voces que coinciden: una sola línea
                lastP = p
                if (li >= 0) {
                    // La opacidad va en el color (en pasos de 1/16) para que el trazo se corte solo al cambiar.
                    val fa = (Math.round(colA[base + r] * 16f) / 16f).coerceIn(0.0625f, 1f)
                    spanColor[li * cols + col] = withA(colorFor(rank, dn), fa)
                    spanY[li * cols + col] = yOf(p)
                }
                rank++
            }
        }
        for (li in 0 until cap) {
            val rowBase = li * cols
            var runColor = 0
            var runFuture = false
            for (col in 0 until cols) {
                val colr = spanColor[rowBase + col]
                if (colr == 0) {
                    if (runColor != 0) { flushRun(c, runColor, runFuture); runColor = 0 }
                    continue
                }
                val x = col * colX
                val y = spanY[rowBase + col]
                val fut = x > playX
                if (runColor == 0) {
                    runColor = colr; runFuture = fut
                    path.moveTo(x, y)
                } else if (colr != runColor || fut != runFuture) {
                    path.lineTo(x, y)                 // el tramo anterior termina aquí...
                    flushRun(c, runColor, runFuture)
                    runColor = colr; runFuture = fut  // ...y el siguiente empieza exactamente en el mismo punto
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            if (runColor != 0) flushRun(c, runColor, runFuture)
        }

        // Salida proyectada (solo si no hay un bucle de acordes sonando).
        if (ghost <= 0.0 && actN > 0) drawProjection(c, playX, w)

        // Playhead fijo con puntos brillantes y la nota a su derecha.
        grid.color = 0x99FFFFFF.toInt()
        grid.strokeWidth = 1f
        c.drawLine(Math.round(playX).toFloat() + 0.5f, chartTop, Math.round(playX).toFloat() + 0.5f, h, grid)
        val lineH = small.textSize
        var prevY = -1.0e9f
        var far = false
        for (r in 0 until actN) {
            val p = actP[r]
            val col = colorFor(r, actN)
            val y = yOf(p)
            dot.color = withA(col, if (r == 0) 0.22f else 0.16f)
            c.drawCircle(playX, y, (if (r == 0) 10f else 8f) * dp, dot)
            dot.color = col; c.drawCircle(playX, y, 3.6f * dp, dot)
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
            path.rewind()
            path.moveTo(playX, y0)
            if (converge) {
                val segs = 28
                for (sgi in 1..segs) {
                    val t = sgi / segs.toFloat()
                    val sm = t * t * (3 - 2 * t)
                    path.lineTo(playX + (endX - playX) * t, y0 + (yMid - y0) * sm)
                }
            } else {
                path.lineTo(w, y0)
            }
            // Trazo único con transparencia uniforme (sin solapes): se ve limpio y se atenúa hacia el borde.
            line.color = withA(colr, if (converge) 0.28f else 0.24f)
            c.drawPath(path, line)
        }
        // Etiquetas tenues de la mediana y el bajo donde terminan las curvas.
        small.color = withA(colorFor(midR, n), 0.45f)
        c.drawText(midiLabel(actP[midR].roundToInt()), endX + 6f * dp, yMid - 4f * dp, small)
        if (bassR != midR) {
            small.color = withA(colorFor(bassR, n), 0.45f)
            c.drawText(midiLabel(actP[bassR].roundToInt()), endX + 6f * dp, yOf(actP[bassR]) - 4f * dp, small)
        }
    }

    /** Dibuja el tramo acumulado en [path]: brillo suave (solo pasado) y línea fina y nítida. */
    private fun flushRun(c: Canvas, color: Int, future: Boolean) {
        val a = (color ushr 24) / 255f
        if (!future) { glow.color = withA(color, 0.10f * a); c.drawPath(path, glow) }
        line.color = withA(color, a * (if (future) 0.30f else 1f))
        c.drawPath(path, line)
        path.rewind()
    }

    private fun withA(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private companion object {
        const val MAXV = 16
        const val RECENT_MS = 4000.0
        const val SPAN_CAP = 48
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
