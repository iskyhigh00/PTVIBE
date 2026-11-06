package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import com.glidelive.audio.NativeEngine
import com.glidelive.theory.NoteNames
import com.glidelive.theory.SoloPitch
import com.glidelive.theory.WiggleVibrato
import kotlin.math.abs
import kotlin.math.max

/** Lo que las superficies necesitan para pintar y calcular la altura. */
data class SoloConfig(
    val allowed: Set<Int> = setOf(0, 2, 4, 5, 7, 9, 11),
    val tonic: Int = 0,
    val lock: Float = 0.8f,
    val loMidi: Int = 48,
    val octaves: Int = 3,
    val expression: Expression = Expression.FILTER,
    val bendRange: Float = 2f,
    val strings: List<Int> = Tuning.GUITAR.strings,
    val frets: Int = 15,
)

/**
 * Base multitouch: cada dedo es una voz independiente (tipo MPE). El toque se procesa en DOWN y el
 * movimiento se envía a C++ por atómicos, sin pasar por Compose.
 */
abstract class SoloTouchView(context: Context) : View(context) {
    protected class Touch(val idx: Int, density: Float) {
        val wiggle = WiggleVibrato(density)
        var row = 0
        var fx = 0f; var fy = 0f      // dedo
        var sx = 0f; var sy = 0f      // posición de la nota ajustada por el imán
        var pitch = 60f; var filter = 1f; var vib = 0f; var vol = 1f
    }

    var config = SoloConfig()
        set(v) { field = v; invalidate() }

    protected val touches = SparseArray<Touch>()
    private val touchPool = arrayOfNulls<Touch>(8)  // un Touch reutilizable por dedo (índice de voz)
    private var usedMask = 0
    protected val dp = resources.displayMetrics.density

    protected abstract fun begin(t: Touch, x: Float, y: Float)
    protected abstract fun solve(t: Touch, x: Float, y: Float)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { requestUnbufferedDispatch(e); down(e, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = touches.get(e.getPointerId(i)) ?: continue
                    solve(t, e.getX(i), e.getY(i))
                    NativeEngine.soloSet(t.idx, t.pitch, t.filter, t.vib, t.vol)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun down(e: MotionEvent, i: Int) {
        val x = e.getX(i); val y = e.getY(i)
        if (x < 0f || y < 0f || x > width || y > height) return  // fuera de la vista (p. ej. Mixto con varias vistas)
        var idx = 0
        while (idx < 8 && usedMask and (1 shl idx) != 0) idx++
        if (idx >= 8) return
        usedMask = usedMask or (1 shl idx)
        val t = touchPool[idx] ?: Touch(idx, dp).also { touchPool[idx] = it }
        t.row = 0; t.pitch = 60f; t.filter = 1f; t.vib = 0f; t.vol = 1f
        t.wiggle.reset(x)
        begin(t, x, y)
        solve(t, x, y)
        touches.put(e.getPointerId(i), t)
        NativeEngine.soloSet(idx, t.pitch, t.filter, t.vib, t.vol)
        NativeEngine.soloOn(idx, t.pitch)
        invalidate()
    }

    private fun up(pid: Int) {
        val t = touches.get(pid) ?: return
        NativeEngine.soloOff(t.idx)
        usedMask = usedMask and (1 shl t.idx).inv()
        touches.remove(pid)
        invalidate()
    }

    fun releaseAll() {
        for (i in 0 until touches.size()) NativeEngine.soloOff(touches.valueAt(i).idx)
        touches.clear()
        usedMask = 0
        invalidate()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    // --- pintura compartida ---
    protected val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA3B8.toInt(); textAlign = Paint.Align.CENTER; textSize = 12f * dp
    }
    protected val rect = RectF()
    protected fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private var glassShader: Shader? = null
    protected var tintShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glassShader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1E1E2A.toInt(), 0xFF12121A.toInt(), Shader.TileMode.CLAMP)
        tintShader = LinearGradient(0f, 0f, 0f, h.toFloat(), alpha(MINT, 0.10f), 0x00000000, Shader.TileMode.CLAMP)
    }

    protected fun drawGlass(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        glass.shader = glassShader
        c.drawRoundRect(rect, 28f, 28f, glass)
        line.shader = null
        line.strokeWidth = 2.5f
        line.color = alpha(VIOLET, 0.5f)
        c.drawRoundRect(rect, 28f, 28f, line)
    }

    protected fun drawFinger(c: Canvas, t: Touch) {
        for (k in 1..3) { dot.color = alpha(PINK, 0.22f / k); c.drawCircle(t.sx, t.sy, 20f * dp + k * 8f * dp, dot) }
        dot.color = alpha(PINK, 0.95f); c.drawCircle(t.sx, t.sy, 14f * dp, dot)
        if (abs(t.fx - t.sx) > 2f || abs(t.fy - t.sy) > 2f) {
            line.strokeWidth = 2f; line.color = alpha(0xFFFFFFFF.toInt(), 0.6f)
            c.drawCircle(t.fx, t.fy, 9f * dp, line)
        }
    }

    companion object {
        const val VIOLET = 0xFF6B4EFF.toInt()
        const val PINK = 0xFFFF6B9D.toInt()
        const val MINT = 0xFF00E5A0.toInt()
    }
}

/** Superficie continua: horizontal = altura (sin teclas), vertical = expresión. */
class SoloSurfaceView(context: Context) : SoloTouchView(context) {
    private val midiLabels = arrayOfNulls<String>(128)
    private fun midiLabel(pitch: Int): String {
        if (pitch !in 0..127) return NoteNames.midiName(pitch)
        return midiLabels[pitch] ?: NoteNames.midiName(pitch).also { midiLabels[pitch] = it }
    }

    override fun begin(t: Touch, x: Float, y: Float) {}

    override fun solve(t: Touch, x: Float, y: Float) {
        val cfg = config
        val total = 12f * cfg.octaves
        val free = SoloPitch.xToMidi(x / max(width, 1), cfg.loMidi, cfg.octaves)
        t.pitch = SoloPitch.applyLock(free, cfg.allowed, cfg.lock)
        t.fx = x; t.fy = y
        t.sx = (t.pitch - cfg.loMidi) / total * width
        t.sy = y
        val yn = 1f - (y / max(height, 1)).coerceIn(0f, 1f)
        t.filter = 1f; t.vib = 0f; t.vol = 1f
        when (cfg.expression) {
            Expression.FILTER -> t.filter = 0.1f + 0.9f * yn
            Expression.VIBRATO -> t.vib = yn
            Expression.VOLUME -> t.vol = 0.05f + 0.95f * yn
        }
        t.vib = max(t.vib, t.wiggle.update(x))
    }

    override fun onDraw(c: Canvas) {
        drawGlass(c)
        val cfg = config
        val total = 12 * cfg.octaves
        // Tinte vertical: arriba = más expresión.
        dot.shader = tintShader
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dot)
        dot.shader = null

        for (n in 0..total) {
            val pitch = cfg.loMidi + n
            val pc = Math.floorMod(pitch, 12)
            if (pc !in cfg.allowed) continue
            val x = n.toFloat() / total * width
            val tonic = pc == cfg.tonic
            line.strokeWidth = if (tonic) 4f else 1.5f
            line.color = if (tonic) alpha(MINT, 0.9f) else alpha(0xFFFFFFFF.toInt(), 0.16f)
            c.drawLine(x, 14f, x, height - 14f, line)
            if (tonic) {
                label.color = alpha(MINT, 0.95f)
                c.drawText(midiLabel(pitch), x, height - 20f * dp / 2, label)
            }
        }
        for (i in 0 until touches.size()) drawFinger(c, touches.valueAt(i))
    }
}

/** Diapasón: cuerdas horizontales, deslizar = altura continua, empujar arriba/abajo = bend. */
class FingerboardView(context: Context) : SoloTouchView(context) {
    private val fretLabels = Array(64) { it.toString() }
    private fun fretLabel(f: Int) = if (f in fretLabels.indices) fretLabels[f] else f.toString()

    private fun rowActive(row: Int): Boolean {
        for (i in 0 until touches.size()) if (touches.valueAt(i).row == row) return true
        return false
    }

    private val padL get() = 28f * dp
    private val padR get() = 12f * dp
    private fun rowH(): Float = height / max(config.strings.size, 1).toFloat()
    private fun rowCenter(row: Int) = (row + 0.5f) * rowH()
    private fun openOf(row: Int): Int = config.strings[config.strings.size - 1 - row]

    override fun begin(t: Touch, x: Float, y: Float) {
        t.row = (y / rowH()).toInt().coerceIn(0, config.strings.size - 1)
    }

    override fun solve(t: Touch, x: Float, y: Float) {
        val cfg = config
        val nw = max(width - padL - padR, 1f)
        val pos = ((x - padL) / nw).coerceIn(0f, 1f) * cfg.frets
        val free = openOf(t.row) + pos
        val snapped = SoloPitch.applyLock(free, cfg.allowed, cfg.lock)
        val dy = ((rowCenter(t.row) - y) / rowH()).coerceIn(-1f, 1f)
        val dead = 0.2f
        val bend = if (abs(dy) < dead) 0f
        else Math.signum(dy) * (abs(dy) - dead) / (1f - dead) * cfg.bendRange
        t.pitch = snapped + bend
        t.fx = x; t.fy = y
        t.sx = padL + (snapped - openOf(t.row)) / cfg.frets * nw
        t.sy = rowCenter(t.row) - (bend / max(cfg.bendRange, 0.1f)) * rowH() * 0.8f
        t.filter = 1f; t.vol = 1f
        t.vib = t.wiggle.update(x)
    }

    override fun onDraw(c: Canvas) {
        drawGlass(c)
        val cfg = config
        val n = cfg.strings.size
        val nw = width - padL - padR
        // Cejuela
        line.strokeWidth = 6f; line.color = alpha(0xFFFFFFFF.toInt(), 0.5f)
        c.drawLine(padL, 10f, padL, height - 10f, line)
        // Trastes y marcadores
        for (f in 1..cfg.frets) {
            val x = padL + f.toFloat() / cfg.frets * nw
            line.strokeWidth = 1.5f; line.color = alpha(0xFFFFFFFF.toInt(), 0.12f)
            c.drawLine(x, 10f, x, height - 10f, line)
            if (f % 12 == 0 || f == 3 || f == 5 || f == 7 || f == 9) {
                dot.color = alpha(0xFFFFFFFF.toInt(), 0.10f)
                c.drawCircle(x - nw / cfg.frets / 2, height / 2f, 6f * dp, dot)
            }
            label.color = alpha(0xFF9AA3B8.toInt(), 0.7f)
            if (f % 3 == 0) c.drawText(fretLabel(f), x, height - 4f * dp, label)
        }
        // Cuerdas y notas de la escala
        for (row in 0 until n) {
            val y = rowCenter(row)
            val open = openOf(row)
            val thick = 1.5f + (row.toFloat() / n) * 3f
            val active = rowActive(row)
            line.strokeWidth = thick * dp / 2
            line.color = if (active) alpha(PINK, 0.9f) else alpha(0xFFFFFFFF.toInt(), 0.35f)
            c.drawLine(padL, y, width - padR, y, line)
            for (f in 0..cfg.frets) {
                val pc = Math.floorMod(open + f, 12)
                if (pc !in cfg.allowed) continue
                val x = padL + f.toFloat() / cfg.frets * nw
                val tonic = pc == cfg.tonic
                dot.color = if (tonic) alpha(MINT, 0.95f) else alpha(VIOLET, 0.85f)
                c.drawCircle(x, y, (if (tonic) 7f else 4.5f) * dp, dot)
            }
            label.color = 0xFF9AA3B8.toInt()
            c.drawText(NoteNames.name(open), padL / 2, y + 4f * dp, label)
        }
        for (i in 0 until touches.size()) drawFinger(c, touches.valueAt(i))
    }
}
