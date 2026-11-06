package com.glidelive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View

data class PadUi(val id: String, val title: String, val subtitle: String)

/** Cuadrícula de pads de acordes; el toque se procesa en DOWN, sin pasar por Compose. */
class ChordPadsView(context: Context) : View(context) {
    var onDown: (String) -> Unit = {}
    var onUp: (String) -> Unit = {}
    var onLongPress: (String) -> Unit = {}

    private var rows: List<List<PadUi>> = emptyList()
    private var activeId: String? = null
    private var edited: Set<String> = emptySet()
    private val rects = ArrayList<Pair<PadUi, RectF>>()
    private val pressed = SparseArray<String>()
    private val longPress = SparseArray<Runnable>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF6B5A4A.toInt()
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEFE6DC.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB9A593.toInt(); textAlign = Paint.Align.CENTER
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9A3C.toInt() }

    init { setBackgroundColor(0xFF140E0A.toInt()) }

    fun setData(rows: List<List<PadUi>>, activeId: String?, edited: Set<String>) {
        if (this.rows != rows || this.activeId != activeId || this.edited != edited) {
            this.rows = rows
            this.activeId = activeId
            this.edited = edited
            layoutPads()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutPads()

    private fun layoutPads() {
        rects.clear()
        if (rows.isEmpty() || width == 0) return
        val gap = 8f
        val rh = (height - gap * (rows.size + 1)) / rows.size
        rows.forEachIndexed { r, row ->
            val cw = (width - gap * (row.size + 1)) / row.size
            row.forEachIndexed { c, pad ->
                val l = gap + c * (cw + gap)
                val t = gap + r * (rh + gap)
                rects.add(pad to RectF(l, t, l + cw, t + rh))
            }
        }
        title.textSize = (minOf(width / 9f, height / rows.size.toFloat()) * 0.32f).coerceAtLeast(18f)
        sub.textSize = title.textSize * 0.55f
    }

    private fun hit(x: Float, y: Float): String? = rects.firstOrNull { it.second.contains(x, y) }?.first?.id

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { requestUnbufferedDispatch(e); down(e, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until pressed.size()) up(pressed.keyAt(i), notify = true, remove = false)
                pressed.clear()
                for (i in 0 until longPress.size()) removeCallbacks(longPress.valueAt(i))
                longPress.clear()
                invalidate()
            }
        }
        return true
    }

    private fun down(e: MotionEvent, idx: Int) {
        val id = hit(e.getX(idx), e.getY(idx)) ?: return
        val pid = e.getPointerId(idx)
        pressed.put(pid, id)
        onDown(id)
        val run = Runnable { if (pressed.get(pid) == id) onLongPress(id) }
        longPress.put(pid, run)
        postDelayed(run, LONG_PRESS_MS)
        invalidate()
    }

    private fun up(pid: Int, notify: Boolean = true, remove: Boolean = true) {
        longPress.get(pid)?.let { removeCallbacks(it) }
        longPress.remove(pid)
        val id = pressed.get(pid) ?: return
        if (remove) pressed.remove(pid)
        if (notify) onUp(id)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        for ((pad, r) in rects) {
            val held = (0 until pressed.size()).any { pressed.valueAt(it) == pad.id }
            val on = held || pad.id == activeId
            fill.color = if (on) 0xFFFF9A3C.toInt() else 0xFF2A1D14.toInt()
            c.drawRoundRect(r, 20f, 20f, fill)
            c.drawRoundRect(r, 20f, 20f, stroke)
            title.color = if (on) 0xFF140E0A.toInt() else 0xFFEFE6DC.toInt()
            sub.color = if (on) 0xFF3A2A1C.toInt() else 0xFFB9A593.toInt()
            c.drawText(pad.title, r.centerX(), r.centerY() + title.textSize * 0.1f, title)
            c.drawText(pad.subtitle, r.centerX(), r.centerY() + title.textSize * 0.1f + sub.textSize * 1.4f, sub)
            if (pad.id in edited) c.drawCircle(r.right - 18f, r.top + 18f, 6f, dot)
        }
    }

    private companion object { const val LONG_PRESS_MS = 550L }
}
