package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import com.glidelive.audio.NativeEngine
import kotlin.math.abs

/**
 * Superficie de pads en una View clásica: el toque va directo a C++ en
 * ACTION_DOWN / ACTION_POINTER_DOWN, sin pasar por Compose.
 */
class DrumPadsView(context: Context) : View(context) {
    private val names = arrayOf("BOMBO", "CAJA", "HI-HAT", "TOM")
    private val rects = Array(names.size) { RectF() }
    private val pressed = SparseIntArray()  // pointerId -> pad
    private val flash = FloatArray(names.size)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF6B5A4A.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEFE6DC.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    init {
        setBackgroundColor(0xFF140E0A.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 16f
        val cols = 2
        val rows = 2
        val cw = (w - gap * (cols + 1)) / cols
        val ch = (h - gap * (rows + 1)) / rows
        for (i in rects.indices) {
            val c = i % cols
            val r = i / cols
            val l = gap + c * (cw + gap)
            val t = gap + r * (ch + gap)
            rects[i].set(l, t, l + cw, t + ch)
        }
        text.textSize = minOf(cw, ch) * 0.14f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                down(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed.clear(); invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pressed.delete(e.getPointerId(e.actionIndex)); invalidate()
            }
        }
        return true
    }

    private fun down(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        for (i in rects.indices) {
            val r = rects[i]
            if (r.contains(x, y)) {
                // Centro = fuerte, borde = suave.
                val dx = abs(x - r.centerX()) / (r.width() / 2)
                val dy = abs(y - r.centerY()) / (r.height() / 2)
                val d = maxOf(dx, dy).coerceIn(0f, 1f)
                val vel = 1f - 0.6f * d
                NativeEngine.trigger(i, vel, e.eventTime)
                pressed.put(e.getPointerId(idx), i)
                flash[i] = 1f
                invalidate()
                return
            }
        }
    }

    override fun onDraw(c: Canvas) {
        var animating = false
        for (i in rects.indices) {
            val active = (0 until pressed.size()).any { pressed.valueAt(it) == i }
            val f = if (active) 1f else flash[i]
            fill.color = blend(0xFF2A1D14.toInt(), 0xFFFF9A3C.toInt(), f)
            c.drawRoundRect(rects[i], 28f, 28f, fill)
            c.drawRoundRect(rects[i], 28f, 28f, stroke)
            c.drawText(names[i], rects[i].centerX(), rects[i].centerY() + text.textSize / 3, text)
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
}
