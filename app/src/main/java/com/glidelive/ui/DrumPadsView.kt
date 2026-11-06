package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import com.glidelive.audio.NativeEngine
import kotlin.math.abs

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
        "BOMBO", "CAJA", "RIM", "HH CERR.", "HH ABIER.", "HH PEDAL",
        "TOM 1", "TOM 2", "FLOOR", "CRASH", "RIDE", "CAMPANA", "SPLASH",
    )

    // x, y, w, h normalizados: distribución tipo batería.
    val layout = arrayOf(
        floatArrayOf(0.36f, 0.70f, 0.32f, 0.28f),  // bombo
        floatArrayOf(0.36f, 0.37f, 0.22f, 0.31f),  // caja
        floatArrayOf(0.58f, 0.37f, 0.10f, 0.31f),  // rim
        floatArrayOf(0.01f, 0.37f, 0.16f, 0.31f),  // hh cerrado
        floatArrayOf(0.17f, 0.37f, 0.16f, 0.31f),  // hh abierto
        floatArrayOf(0.01f, 0.70f, 0.32f, 0.28f),  // hh pedal
        floatArrayOf(0.36f, 0.02f, 0.16f, 0.33f),  // tom 1
        floatArrayOf(0.52f, 0.02f, 0.16f, 0.33f),  // tom 2
        floatArrayOf(0.70f, 0.37f, 0.29f, 0.61f),  // floor
        floatArrayOf(0.15f, 0.02f, 0.19f, 0.33f),  // crash
        floatArrayOf(0.70f, 0.02f, 0.18f, 0.33f),  // ride
        floatArrayOf(0.88f, 0.02f, 0.11f, 0.33f),  // campana
        floatArrayOf(0.01f, 0.02f, 0.13f, 0.33f),  // splash
    )

    fun isCymbal(pad: Int) = pad in CRASH..SPLASH
    fun isHat(pad: Int) = pad in HH_CLOSED..HH_PEDAL
}

/**
 * Superficie de pads en una View clásica: el toque va directo a C++ en
 * ACTION_DOWN / ACTION_POINTER_DOWN, sin pasar por Compose.
 */
class DrumPadsView(context: Context) : View(context) {
    /** Sensibilidad por presión/tamaño del toque (si el dispositivo la reporta). */
    var pressureSensitivity = false

    private val rects = Array(Pads.COUNT) { RectF() }
    private val pressed = SparseIntArray()  // pointerId -> pad
    private val flash = FloatArray(Pads.COUNT)
    private val chokeRunnables = SparseArray<Runnable>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF6B5A4A.toInt()
    }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x55FFFFFF
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEFE6DC.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    init {
        setBackgroundColor(0xFF140E0A.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 5f
        for (i in rects.indices) {
            val l = Pads.layout[i]
            rects[i].set(l[0] * w + gap, l[1] * h + gap, (l[0] + l[2]) * w - gap, (l[1] + l[3]) * h - gap)
        }
        text.textSize = (minOf(w, h) * 0.04f).coerceAtLeast(18f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                down(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until chokeRunnables.size()) removeCallbacks(chokeRunnables.valueAt(i))
                chokeRunnables.clear()
                pressed.clear(); invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                release(id)
                pressed.delete(id); invalidate()
            }
        }
        return true
    }

    private fun release(id: Int) {
        chokeRunnables.get(id)?.let { removeCallbacks(it) }
        chokeRunnables.remove(id)
    }

    private fun down(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        for (i in rects.indices) {
            val r = rects[i]
            if (!r.contains(x, y)) continue
            val dx = abs(x - r.centerX()) / (r.width() / 2)
            val dy = abs(y - r.centerY()) / (r.height() / 2)
            val d = maxOf(dx, dy).coerceIn(0f, 1f)
            // Centro = fuerte, borde = suave.
            var vel = if (d < 0.5f) 1f - 0.3f * d else 0.85f - 1.1f * (d - 0.5f)
            // Zona de ghost notes: franja inferior de la caja.
            if (i == Pads.SNARE && (y - r.top) / r.height() > 0.78f) vel = 0.18f
            if (pressureSensitivity) vel *= 0.4f + 0.6f * e.getPressure(idx).coerceIn(0f, 1f)
            NativeEngine.trigger(i, vel.coerceIn(0.05f, 1f), e.eventTime)
            val id = e.getPointerId(idx)
            pressed.put(id, i)
            flash[i] = 1f
            if (Pads.isCymbal(i)) {  // toque largo = choke
                val run = Runnable {
                    if (pressed.get(id, -1) == i) {
                        NativeEngine.choke(i, android.os.SystemClock.uptimeMillis())
                        flash[i] = 0.3f
                        invalidate()
                    }
                }
                chokeRunnables.put(id, run)
                postDelayed(run, CHOKE_MS)
            }
            invalidate()
            return
        }
    }

    override fun onDraw(c: Canvas) {
        var animating = false
        for (i in rects.indices) {
            val active = (0 until pressed.size()).any { pressed.valueAt(it) == i }
            val f = if (active) 1f else flash[i]
            val base = when {
                Pads.isCymbal(i) -> 0xFF2B2415.toInt()
                Pads.isHat(i) -> 0xFF2A2218.toInt()
                else -> 0xFF2A1D14.toInt()
            }
            fill.color = blend(base, 0xFFFF9A3C.toInt(), f)
            val r = rects[i]
            if (Pads.isCymbal(i)) c.drawOval(r, fill) else c.drawRoundRect(r, 24f, 24f, fill)
            if (Pads.isCymbal(i)) c.drawOval(r, stroke) else c.drawRoundRect(r, 24f, 24f, stroke)
            if (i == Pads.SNARE) {
                val gy = r.top + r.height() * 0.78f
                c.drawLine(r.left + 12f, gy, r.right - 12f, gy, ghost)
            }
            c.drawText(Pads.names[i], r.centerX(), r.centerY() + text.textSize / 3, text)
            if (!active && flash[i] > 0f) {
                flash[i] = (flash[i] - 0.12f).coerceAtLeast(0f)
                animating = true
            }
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        const val CHOKE_MS = 350L
    }
}
