package com.glidelive.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Contenedor táctil único para tocar varias superficies a la vez (acordes, solista, batería) con multitouch real.
 * Al estar todo dentro de un mismo LinearLayout con "motion event splitting", Android entrega a cada vista solo los
 * dedos que empezaron sobre ella, así que se puede sostener un acorde, deslizar el solista y golpear la batería
 * simultáneamente. La visualización de voces no es táctil.
 *
 * Las vistas táctiles comprueban los límites solo en DOWN/POINTER_DOWN con coordenadas locales: con el reparto de
 * dedos cada hijo recibe únicamente los suyos, así que esa comprobación no interfiere.
 */
class PerformHost(context: Context) : LinearLayout(context) {
    val viz = ChordVizView(context)
    val chords = ChordPadsView(context)
    val drums = DrumPadsView(context)
    private val soloFrame = FrameLayout(context).apply { isMotionEventSplittingEnabled = true }
    private var soloView: SoloTouchView? = null
    private var soloLayout: SoloLayout? = null
    private val gap = (6 * resources.displayMetrics.density).toInt()

    private var showViz = false
    private var showChords = false
    private var showSolo = false
    private var showDrums = false
    private var horizontalMode = false

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
    }

    private fun lp(weight: Float): LayoutParams {
        val p = if (horizontalMode) LayoutParams(0, LayoutParams.MATCH_PARENT, weight) else LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
        if (horizontalMode) p.marginEnd = gap else p.bottomMargin = gap
        return p
    }

    fun configure(viz: Boolean, chords: Boolean, solo: Boolean, drums: Boolean, horizontal: Boolean = false) {
        if (viz == showViz && chords == showChords && solo == showSolo && drums == showDrums && horizontal == horizontalMode) return
        if (!solo && showSolo) releaseAll()
        showViz = viz; showChords = chords; showSolo = solo; showDrums = drums; horizontalMode = horizontal
        orientation = if (horizontal) HORIZONTAL else VERTICAL
        removeAllViews()
        if (viz) addView(this.viz, lp(1.1f))
        if (chords) addView(this.chords, lp(1.2f))
        if (solo) addView(soloFrame, lp(if (drums && !horizontal) 1.0f else 1.3f))
        if (drums) addView(this.drums, lp(1.3f))
    }

    /** Crea (o cambia) la superficie del solista. */
    fun setSolo(layout: SoloLayout, config: SoloConfig) {
        if (layout != soloLayout) {
            soloView?.let { it.releaseAll(); soloFrame.removeView(it) }
            val v: SoloTouchView = if (layout == SoloLayout.SURFACE) SoloSurfaceView(context) else FingerboardView(context)
            soloFrame.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            soloView = v
            soloLayout = layout
        }
        soloView?.config = config
    }

    fun releaseAll() {
        soloView?.releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }
}
