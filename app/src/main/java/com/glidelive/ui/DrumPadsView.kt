package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import com.glidelive.audio.NativeEngine
import kotlin.math.max
import kotlin.math.min

object Pads {
    const val KICK = 0
    const val SNARE = 1
    const val RIM = 2
    const val HH_CLOSED = 3
    const val HH_OPEN = 4
    const val HH_PEDAL = 5
    const val TOM1 = 6
    const val TOM2 = 7
    const val FLOOR = 8
    const val CRASH = 9
    const val RIDE = 10
    const val BELL = 11
    const val SPLASH = 12
    const val GUIRO_SHORT = 13
    const val GUIRO_LONG = 14
    const val MARACAS = 15
    const val CONGA = 16
    const val QUINTO = 17
    const val TUMBA = 18
    const val BONGO = 19
    const val COWBELL = 20
    const val COUNT = 21

    val names = arrayOf(
        "BOMBO", "CAJA", "AROS\n(rim)", "HI-HAT\nCERRADO", "HI-HAT\nABIERTO", "PEDAL\nHI-HAT",
        "TOM 1", "TOM 2", "TOM DE\nPISO", "CRASH", "RIDE", "CAMPANA\nRIDE", "SPLASH\n/ CHINA",
        "GÜIRO\ncorto", "GÜIRO\nlargo", "MARACAS", "CONGA", "QUINTO\n(alegre)", "TUMBA", "BONGÓ", "CAMPANA\n(cencerro)",
    )

    /** Líneas de cada nombre, calculadas una sola vez (onDraw no debe asignar memoria). */
    val lines: Array<Array<String>> = Array(COUNT) { names[it].split("\n").toTypedArray() }

    fun isCymbal(pad: Int) = pad in CRASH..SPLASH
    fun isHat(pad: Int) = pad in HH_CLOSED..HH_PEDAL
    fun isPercussion(pad: Int) = pad in GUIRO_SHORT..COWBELL
}

/**
 * Batería para los dedos: 13 pads grandes en una cuadrícula que cubre toda la superficie. Todo el pad es zona
 * sensible; el toque va directo a C++ en ACTION_DOWN / ACTION_POINTER_DOWN, sin pasar por Compose.
 */
class DrumPadsView(context: Context) : View(context) {
    /** Sensibilidad por presión/tamaño del toque (si el dispositivo la reporta). */
    var pressureSensitivity = false

    /** Distribución de los pads; al cambiarla se recalcula todo sin recrear la vista. */
    var padLayout: PadLayout = PadLayout.KIT
        set(v) {
            if (field == v) return
            field = v
            pressed.clear(); flash.fill(0f)
            if (width > 0) relayout(width, height)
            invalidate()
        }

    private val n = Pads.COUNT
    private val left = FloatArray(n)
    private val top = FloatArray(n)
    private val right = FloatArray(n)
    private val bottom = FloatArray(n)
    private val pressed = SparseIntArray()  // pointerId -> pad
    private val flash = FloatArray(n)
    private val chokeRunnables = SparseArray<Runnable>()
    private val dp = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F2FF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x66FFFFFF
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val rect = RectF()
    private val padText = FloatArray(n)
    private val fillShaders = arrayOfNulls<Shader>(n)
    private val borderShaders = arrayOfNulls<Shader>(n)
    private var corner = 18f

    private fun accent(i: Int) = when {
        Pads.isPercussion(i) -> ORANGE
        Pads.isCymbal(i) -> GOLD
        Pads.isHat(i) -> MINT
        i == Pads.KICK -> PINK
        else -> VIOLET
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout(w, h)

    private fun relayout(w: Int, h: Int) {
        DrumHit.geometry(padLayout, w, h, 6f * dp, left, top, right, bottom)
        corner = 16f * dp
        val base = 0xFF15151F.toInt()
        for (i in 0 until n) {
            if (!padLayout.visible(i)) continue
            val acc = accent(i)
            fillShaders[i] = LinearGradient(
                0f, top[i], 0f, bottom[i],
                intArrayOf(blend(base, acc, 0.28f), blend(base, acc, 0.10f), base),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            borderShaders[i] = LinearGradient(
                left[i], top[i], right[i], bottom[i],
                withAlpha(acc, 0.95f), withAlpha(0xFFFFFFFF.toInt(), 0.18f), Shader.TileMode.CLAMP,
            )
        }
        // Cada pad usa el mayor texto que cabe en su ancho (y no más de un 26 % de su alto).
        for (i in 0 until n) {
            if (!padLayout.visible(i)) continue
            val maxW = (right[i] - left[i]) * 0.86f
            var ts = (bottom[i] - top[i]) * 0.26f
            val minTs = 9f * dp
            while (ts > minTs) {
                text.textSize = ts
                var widest = 0f
                for (ln in Pads.lines[i]) widest = max(widest, text.measureText(ln))
                if (widest <= maxW) break
                ts -= 0.5f * dp
            }
            padText[i] = ts
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { requestUnbufferedDispatch(e); down(e, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until chokeRunnables.size()) removeCallbacks(chokeRunnables.valueAt(i))
                chokeRunnables.clear()
                pressed.clear(); invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                chokeRunnables.get(id)?.let { removeCallbacks(it) }
                chokeRunnables.remove(id)
                pressed.delete(id); invalidate()
            }
        }
        return true
    }

    private fun isPressed(pad: Int): Boolean {
        for (k in 0 until pressed.size()) if (pressed.valueAt(k) == pad) return true
        return false
    }

    private fun down(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (x < 0f || y < 0f || x > width || y > height) return  // fuera de la vista (p. ej. Mixto con varias vistas)
        val i = DrumHit.hitTest(padLayout, x, y, width, height)
        if (i < 0) return
        val nx = DrumHit.norm(x, left[i], right[i])
        val ny = DrumHit.norm(y, top[i], bottom[i])
        var vel = DrumHit.velocity(i, nx, ny)
        if (pressureSensitivity) vel *= 0.4f + 0.6f * e.getPressure(idx).coerceIn(0f, 1f)
        NativeEngine.trigger(i, vel.coerceIn(0.05f, 1f), e.eventTime)
        val id = e.getPointerId(idx)
        pressed.put(id, i)
        flash[i] = 1f
        if (Pads.isCymbal(i)) {  // toque largo = choke
            val run = Runnable {
                if (pressed.get(id, -1) == i) {
                    NativeEngine.choke(i, SystemClock.uptimeMillis())
                    flash[i] = 0.3f
                    invalidate()
                }
            }
            chokeRunnables.put(id, run)
            postDelayed(run, CHOKE_MS)
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        var animating = false
        for (i in 0 until n) {
            if (!padLayout.visible(i)) continue
            val active = isPressed(i)
            val f = if (active) 1f else flash[i]
            val acc = accent(i)
            val pulse = f * 3f * dp  // micro pulso al sonar
            rect.set(left[i] - pulse, top[i] - pulse, right[i] + pulse, bottom[i] + pulse)

            if (f > 0.01f) {
                for (k in 1..3) {
                    glow.color = withAlpha(acc, 0.30f * f / k)
                    glow.strokeWidth = 7f * k
                    c.drawRoundRect(rect, corner, corner, glow)
                }
            }
            fill.shader = fillShaders[i]
            c.drawRoundRect(rect, corner, corner, fill)
            if (f > 0.01f) { tint.color = withAlpha(acc, 0.60f * f); c.drawRoundRect(rect, corner, corner, tint) }
            border.shader = borderShaders[i]
            border.strokeWidth = 2.5f * dp
            c.drawRoundRect(rect, corner, corner, border)

            // Detalle interior suave según instrumento
            inner.color = withAlpha(0xFFFFFFFF.toInt(), 0.14f)
            inner.strokeWidth = 1.5f * dp
            val m = 7f * dp
            rect.set(left[i] + m, top[i] + m, right[i] - m, bottom[i] - m)
            c.drawRoundRect(rect, max(corner - m, 4f), max(corner - m, 4f), inner)
            if (i == Pads.SNARE) {  // zona de ghost notes (franja inferior)
                val gy = top[i] + (bottom[i] - top[i]) * (1f + DrumHit.GHOST_FROM) / 2f
                c.drawLine(left[i] + m * 2, gy, right[i] - m * 2, gy, ghost)
            }

            val lines = Pads.lines[i]
            val size = padText[i]
            text.textSize = size
            val lh = size * 1.15f
            var y = (top[i] + bottom[i]) / 2 + size / 3 - lh * (lines.size - 1) / 2
            val x = (left[i] + right[i]) / 2
            for (q in lines.indices) { c.drawText(lines[q], x, y, text); y += lh }
            if (!active && flash[i] > 0f) {
                flash[i] = (flash[i] - 0.09f).coerceAtLeast(0f)
                animating = true
            }
        }
        if (animating) postInvalidateOnAnimation()
    }

    private companion object {
        const val CHOKE_MS = 350L
        const val VIOLET = 0xFF6B4EFF.toInt()
        const val PINK = 0xFFFF6B9D.toInt()
        const val MINT = 0xFF00E5A0.toInt()
        const val GOLD = 0xFFFFC857.toInt()
        const val ORANGE = 0xFFFF8A3D.toInt()
    }
}
