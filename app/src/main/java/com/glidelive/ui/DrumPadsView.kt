package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x55FFFFFF
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F0FA.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val tmp = RectF()
    private val fillShaders = arrayOfNulls<Shader>(Pads.COUNT)
    private val strokeShaders = arrayOfNulls<Shader>(Pads.COUNT)

    private fun accent(i: Int) = when {
        Pads.isCymbal(i) -> PINK
        Pads.isHat(i) -> MINT
        else -> VIOLET
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 5f
        for (i in rects.indices) {
            val l = Pads.layout[i]
            val r = rects[i]
            r.set(l[0] * w + gap, l[1] * h + gap, (l[0] + l[2]) * w - gap, (l[1] + l[3]) * h - gap)
            fillShaders[i] = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF1E1E2A.toInt(), 0xFF12121A.toInt(), Shader.TileMode.CLAMP)
            strokeShaders[i] = LinearGradient(
                r.left, r.top, r.right, r.bottom, withAlpha(accent(i), 0.7f), 0x24FFFFFF, Shader.TileMode.CLAMP,
            )
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
            val acc = accent(i)
            val r = rects[i]
            val pulse = f * 5f  // micro pulso al sonar
            tmp.set(r.left - pulse, r.top - pulse, r.right + pulse, r.bottom + pulse)
            val cym = Pads.isCymbal(i)

            if (f > 0.01f) {
                for (k in 1..3) {
                    glow.color = withAlpha(acc, 0.28f * f / k)
                    glow.strokeWidth = 7f * k
                    if (cym) c.drawOval(tmp, glow) else c.drawRoundRect(tmp, 24f, 24f, glow)
                }
            }
            fill.shader = fillShaders[i]
            tint.color = withAlpha(acc, 0.10f + 0.55f * f)
            stroke.shader = strokeShaders[i]
            if (cym) {
                c.drawOval(tmp, fill); c.drawOval(tmp, tint); c.drawOval(tmp, stroke)
            } else {
                c.drawRoundRect(tmp, 24f, 24f, fill)
                c.drawRoundRect(tmp, 24f, 24f, tint)
                c.drawRoundRect(tmp, 24f, 24f, stroke)
            }
            if (i == Pads.SNARE) {
                val gy = r.top + r.height() * 0.78f
                c.drawLine(r.left + 14f, gy, r.right - 14f, gy, ghost)
            }
            c.drawText(Pads.names[i], r.centerX(), r.centerY() + text.textSize / 3, text)
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
    }
}
