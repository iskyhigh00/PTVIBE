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

data class PadUi(val id: String, val title: String, val subtitle: String)

/** Cuadrícula de pads de acordes; el toque se procesa en DOWN, sin pasar por Compose. */
class ChordPadsView(context: Context) : View(context) {
    var onDown: (String) -> Unit = {}
    var onUp: (String) -> Unit = {}
    var onEdit: (String) -> Unit = {}
    /** En modo edición, tocar un pad abre el editor en vez de sonar. */
    var editMode = false

    /** Una sola fila en escalera: cada botón queda más arriba que el anterior, para ver cuánto falta y qué viene. */
    var stairs = false
        set(v) { if (field != v) { field = v; layoutPads(); invalidate() } }

    private var rows: List<List<PadUi>> = emptyList()
    private var activeId: String? = null
    private var edited: Set<String> = emptySet()
    private val rects = ArrayList<Pair<PadUi, RectF>>()
    private val pressed = SparseArray<String>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F0FA.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA3B8.toInt(); textAlign = Paint.Align.CENTER
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5A0.toInt() }
    private val fillShaders = ArrayList<Shader>()
    private val strokeShaders = ArrayList<Shader>()
    private val activeShaders = ArrayList<Shader>()
    private val tmp = RectF()
    private var baseTitle = 18f

    fun setData(rows: List<List<PadUi>>, activeId: String?, edited: Set<String>) {
        val rowsChanged = this.rows != rows
        if (!rowsChanged && this.activeId == activeId && this.edited == edited) return
        this.activeId = activeId
        this.edited = edited
        if (rowsChanged) {
            val sameShape = this.rows.size == rows.size && rows.indices.all { this.rows[it].size == rows[it].size }
            this.rows = rows
            if (sameShape && rects.isNotEmpty() && width > 0) {
                // Misma cuadrícula: solo cambian los textos; no se recrean rectángulos ni shaders.
                var k = 0
                for (row in rows) for (pad in row) { rects[k] = pad to rects[k].second; k++ }
            } else {
                layoutPads()
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutPads()

    private fun layoutPads() {
        rects.clear()
        fillShaders.clear(); strokeShaders.clear(); activeShaders.clear()
        if (rows.isEmpty() || width == 0) return
        val gap = 8f
        if (stairs && rows.size == 1) {
            val row = rows[0]
            val n = row.size
            val cw = (width - gap * (n + 1)) / n
            val padH = (height - gap * 2) * 0.52f
            val dy = if (n > 1) ((height - gap * 2) - padH) / (n - 1) else 0f
            row.forEachIndexed { c, pad ->
                val l = gap + c * (cw + gap)
                val b = height - gap - c * dy
                val t = b - padH
                rects.add(pad to RectF(l, t, l + cw, b))
                fillShaders.add(LinearGradient(0f, t, 0f, b, 0xFF1E1E2A.toInt(), 0xFF12121A.toInt(), Shader.TileMode.CLAMP))
                strokeShaders.add(LinearGradient(l, t, l + cw, b, 0xB36B4EFF.toInt(), 0x24FFFFFF, Shader.TileMode.CLAMP))
                activeShaders.add(LinearGradient(l, t, l + cw, t, 0xFF6B4EFF.toInt(), 0xFFFF6B9D.toInt(), Shader.TileMode.CLAMP))
            }
            baseTitle = (minOf(width / 9f, height / 2f) * 0.32f).coerceAtLeast(18f)
            title.textSize = baseTitle
            sub.textSize = baseTitle * 0.55f
            return
        }
        val rh = (height - gap * (rows.size + 1)) / rows.size
        rows.forEachIndexed { r, row ->
            val cw = (width - gap * (row.size + 1)) / row.size
            row.forEachIndexed { c, pad ->
                val l = gap + c * (cw + gap)
                val t = gap + r * (rh + gap)
                val rect = RectF(l, t, l + cw, t + rh)
                rects.add(pad to rect)
                fillShaders.add(LinearGradient(0f, t, 0f, t + rh, 0xFF1E1E2A.toInt(), 0xFF12121A.toInt(), Shader.TileMode.CLAMP))
                strokeShaders.add(LinearGradient(l, t, l + cw, t + rh, 0xB36B4EFF.toInt(), 0x24FFFFFF, Shader.TileMode.CLAMP))
                activeShaders.add(LinearGradient(l, t, l + cw, t, 0xFF6B4EFF.toInt(), 0xFFFF6B9D.toInt(), Shader.TileMode.CLAMP))
            }
        }
        baseTitle = (minOf(width / 9f, height / rows.size.toFloat()) * 0.32f).coerceAtLeast(18f)
        title.textSize = baseTitle
        sub.textSize = baseTitle * 0.55f
    }

    private fun hit(x: Float, y: Float): String? {
        for (i in 0 until rects.size) {
            val e = rects[i]
            if (e.second.contains(x, y)) return e.first.id
        }
        return null
    }

    private fun isHeld(id: String): Boolean {
        for (i in 0 until pressed.size()) if (pressed.valueAt(i) == id) return true
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { requestUnbufferedDispatch(e); down(e, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until pressed.size()) up(pressed.keyAt(i), notify = true, remove = false)
                pressed.clear()
                invalidate()
            }
        }
        return true
    }

    private fun down(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (x < 0f || y < 0f || x > width || y > height) return  // fuera de la vista (p. ej. Mixto con varias vistas)
        val id = hit(x, y) ?: return
        if (editMode) { onEdit(id); return }
        val pid = e.getPointerId(idx)
        pressed.put(pid, id)
        onDown(id)
        invalidate()
    }

    private fun up(pid: Int, notify: Boolean = true, remove: Boolean = true) {
        val id = pressed.get(pid) ?: return
        if (remove) pressed.remove(pid)
        if (notify) onUp(id)
        invalidate()
    }

    /** Reduce el texto hasta que quepa en [maxW]. */
    private fun fit(p: Paint, text: String, maxW: Float, base: Float) {
        p.textSize = base
        while (p.measureText(text) > maxW && p.textSize > 9f) p.textSize -= 1f
    }

    override fun onDraw(c: Canvas) {
        for (idx in 0 until rects.size) {
            val entry = rects[idx]
            val pad = entry.first
            val r = entry.second
            val held = isHeld(pad.id)
            val on = held || pad.id == activeId
            val grow = if (held) 3f else 0f
            tmp.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
            if (on) {
                for (k in 1..3) {
                    glow.color = (0x6B4EFF) or ((0.30f / k * 255).toInt() shl 24)
                    glow.strokeWidth = 8f * k
                    c.drawRoundRect(tmp, 22f, 22f, glow)
                }
                active.shader = activeShaders[idx]
                c.drawRoundRect(tmp, 22f, 22f, active)
            } else {
                fill.shader = fillShaders[idx]
                c.drawRoundRect(tmp, 22f, 22f, fill)
            }
            stroke.shader = strokeShaders[idx]
            c.drawRoundRect(tmp, 22f, 22f, stroke)
            title.color = if (on) 0xFFFFFFFF.toInt() else 0xFFF1F0FA.toInt()
            sub.color = if (on) 0xCCFFFFFF.toInt() else 0xFF9AA3B8.toInt()
            fit(title, pad.title, r.width() - 10f, baseTitle)
            fit(sub, pad.subtitle, r.width() - 10f, baseTitle * 0.55f)
            c.drawText(pad.title, r.centerX(), r.centerY() + title.textSize * 0.1f, title)
            c.drawText(pad.subtitle, r.centerX(), r.centerY() + title.textSize * 0.1f + sub.textSize * 1.4f, sub)
            if (pad.id in edited) c.drawCircle(r.right - 18f, r.top + 18f, 6f, dot)
        }
    }
}
