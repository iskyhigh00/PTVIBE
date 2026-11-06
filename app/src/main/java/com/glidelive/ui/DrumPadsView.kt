package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
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
    const val COUNT = 13

    val names = arrayOf(
        "BOMBO", "CAJA", "AROS\n(rim)", "HI-HAT\nCERRADO", "HI-HAT\nABIERTO", "PEDAL\nHI-HAT",
        "TOM 1", "TOM 2", "TOM DE\nPISO", "CRASH", "RIDE", "CAMPANA\nRIDE", "SPLASH\n/ CHINA",
    )

    /** Líneas de cada nombre, calculadas una sola vez (onDraw no debe asignar memoria). */
    val lines: Array<Array<String>> = Array(COUNT) { names[it].split("\n").toTypedArray() }

    // x, y, w, h normalizados: vista superior de una batería.
    val layout = arrayOf(
        floatArrayOf(0.34f, 0.66f, 0.30f, 0.33f),  // bombo
        floatArrayOf(0.36f, 0.34f, 0.20f, 0.30f),  // caja
        floatArrayOf(0.56f, 0.40f, 0.09f, 0.22f),  // aros
        floatArrayOf(0.02f, 0.38f, 0.15f, 0.26f),  // hh cerrado
        floatArrayOf(0.17f, 0.38f, 0.15f, 0.26f),  // hh abierto
        floatArrayOf(0.06f, 0.68f, 0.22f, 0.30f),  // hh pedal
        floatArrayOf(0.34f, 0.01f, 0.15f, 0.31f),  // tom 1
        floatArrayOf(0.50f, 0.01f, 0.15f, 0.31f),  // tom 2
        floatArrayOf(0.68f, 0.38f, 0.30f, 0.60f),  // floor
        floatArrayOf(0.14f, 0.01f, 0.19f, 0.34f),  // crash
        floatArrayOf(0.68f, 0.01f, 0.20f, 0.34f),  // ride
        floatArrayOf(0.88f, 0.05f, 0.10f, 0.26f),  // campana
        floatArrayOf(0.01f, 0.03f, 0.12f, 0.30f),  // splash
    )

    fun isCymbal(pad: Int) = pad in CRASH..SPLASH
    fun isHat(pad: Int) = pad in HH_CLOSED..HH_PEDAL
}

/**
 * Batería en vista superior: parches redondos con aros, platos metálicos y hi-hat.
 * El toque va directo a C++ en ACTION_DOWN / ACTION_POINTER_DOWN, sin pasar por Compose.
 */
class DrumPadsView(context: Context) : View(context) {
    /** Sensibilidad por presión/tamaño del toque (si el dispositivo la reporta). */
    var pressureSensitivity = false

    private val n = Pads.COUNT
    private val cx = FloatArray(n)
    private val cy = FloatArray(n)
    private val rx = FloatArray(n)
    private val ry = FloatArray(n)
    private val pressed = SparseIntArray()  // pointerId -> pad
    private val flash = FloatArray(n)
    private val chokeRunnables = SparseArray<Runnable>()
    private val dp = resources.displayMetrics.density

    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F2FF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x66FFFFFF
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val tmp = RectF()
    private val padText = FloatArray(Pads.COUNT)
    private var baseText = 12f
    private val order = IntArray(n)  // pads de menor a mayor área (orden de prueba del toque)
    private val headShaders = arrayOfNulls<Shader>(n)
    private val rimShaders = arrayOfNulls<Shader>(n)

    private fun accent(i: Int) = when {
        Pads.isCymbal(i) -> GOLD
        Pads.isHat(i) -> MINT
        i == Pads.KICK -> PINK
        else -> VIOLET
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun elliptical(i: Int) = Pads.isCymbal(i) || Pads.isHat(i)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        DrumHit.geometry(w, h, 6f * dp, cx, cy, rx, ry)
        DrumHit.orderByArea(rx, ry, order)
        for (i in 0 until n) {
            val acc = accent(i)
            val base = 0xFF14141E.toInt()
            headShaders[i] = RadialGradient(
                cx[i] - rx[i] * 0.25f, cy[i] - ry[i] * 0.3f, maxOf(rx[i], ry[i]) * 1.25f,
                intArrayOf(blend(base, acc, 0.30f), blend(base, acc, 0.10f), base),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            rimShaders[i] = LinearGradient(
                cx[i] - rx[i], cy[i] - ry[i], cx[i] + rx[i], cy[i] + ry[i],
                withAlpha(acc, 0.95f), withAlpha(0xFFFFFFFF.toInt(), 0.25f), Shader.TileMode.CLAMP,
            )
        }
        baseText = (min(w, h) * 0.036f).coerceAtLeast(12f * dp)
        text.textSize = baseText
        // Cada pad usa el mayor tamaño de texto que cabe dentro de su parche (sin asignaciones al dibujar).
        for (i in 0 until n) {
            val maxW = rx[i] * 1.55f
            var ts = baseText
            val minTs = 7f * dp
            while (ts > minTs) {
                text.textSize = ts
                var widest = 0f
                for (ln in Pads.lines[i]) widest = max(widest, text.measureText(ln))
                if (widest <= maxW) break
                ts -= 0.5f * dp
            }
            padText[i] = ts
        }
        text.textSize = baseText
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
        val i = DrumHit.hitTest(x, y, cx, cy, rx, ry, order)
        if (i < 0) return
        val dy = (y - cy[i]) / ry[i]
        val d = DrumHit.normDist(x, y, cx, cy, rx, ry, i)
        val dd = d.coerceIn(0f, 1f)
        // Centro = fuerte, borde = suave.
        var vel = if (dd < 0.5f) 1f - 0.3f * dd else 0.85f - 1.1f * (dd - 0.5f)
        // Zona de ghost notes: franja inferior de la caja.
        if (i == Pads.SNARE && dy > 0.62f && d <= DrumHit.INSIDE) vel = 0.28f
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
            val active = isPressed(i)
            val f = if (active) 1f else flash[i]
            val acc = accent(i)
            val pulse = f * 4f * dp  // micro pulso al sonar
            tmp.set(cx[i] - rx[i] - pulse, cy[i] - ry[i] - pulse, cx[i] + rx[i] + pulse, cy[i] + ry[i] + pulse)

            if (f > 0.01f) {
                for (k in 1..3) {
                    glow.color = withAlpha(acc, 0.30f * f / k)
                    glow.strokeWidth = 7f * k
                    c.drawOval(tmp, glow)
                }
            }
            // Parche / plato
            head.shader = headShaders[i]
            c.drawOval(tmp, head)
            if (f > 0.01f) { shine.color = withAlpha(acc, 0.55f * f); c.drawOval(tmp, shine) }
            // Aro exterior
            rim.shader = rimShaders[i]
            rim.strokeWidth = (if (Pads.isCymbal(i)) 3f else 5f) * dp
            c.drawOval(tmp, rim)
            ring.color = withAlpha(0xFFFFFFFF.toInt(), 0.16f)
            ring.strokeWidth = 1.5f * dp
            // Detalles según instrumento
            when {
                Pads.isCymbal(i) -> {  // surcos concéntricos y cúpula
                    for (q in CYMBAL_RINGS.indices) {
                        val k = CYMBAL_RINGS[q]
                        tmp.set(cx[i] - rx[i] * k / 100f, cy[i] - ry[i] * k / 100f, cx[i] + rx[i] * k / 100f, cy[i] + ry[i] * k / 100f)
                        c.drawOval(tmp, ring)
                    }
                    shine.color = withAlpha(0xFFFFFFFF.toInt(), 0.28f)
                    c.drawCircle(cx[i], cy[i], min(rx[i], ry[i]) * 0.16f, shine)
                }
                Pads.isHat(i) -> {  // láminas del hi-hat
                    for (q in HAT_RINGS.indices) {
                        val k = HAT_RINGS[q]
                        tmp.set(cx[i] - rx[i] * k / 100f, cy[i] - ry[i] * k / 100f, cx[i] + rx[i] * k / 100f, cy[i] + ry[i] * k / 100f)
                        c.drawOval(tmp, ring)
                    }
                }
                else -> {  // anillo interior del parche
                    c.drawCircle(cx[i], cy[i], rx[i] * 0.72f, ring)
                }
            }
            if (i == Pads.SNARE) {
                val gy = cy[i] + ry[i] * 0.62f
                val half = rx[i] * 0.78f
                c.drawLine(cx[i] - half, gy, cx[i] + half, gy, ghost)
            }
            val lines = Pads.lines[i]
            val size = padText[i]
            text.textSize = size
            val lh = size * 1.15f
            var y = cy[i] + size / 3 - lh * (lines.size - 1) / 2
            for (q in lines.indices) { c.drawText(lines[q], cx[i], y, text); y += lh }
            text.textSize = baseText
            if (!active && flash[i] > 0f) {
                flash[i] = (flash[i] - 0.09f).coerceAtLeast(0f)
                animating = true
            }
        }
        if (animating) postInvalidateOnAnimation()
    }

    private companion object {
        const val CHOKE_MS = 350L
        val CYMBAL_RINGS = intArrayOf(70, 45)
        val HAT_RINGS = intArrayOf(75, 50)
        const val VIOLET = 0xFF6B4EFF.toInt()
        const val PINK = 0xFFFF6B9D.toInt()
        const val MINT = 0xFF00E5A0.toInt()
        const val GOLD = 0xFFFFC857.toInt()
    }
}
