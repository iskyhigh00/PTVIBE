package com.glidelive.princess

import kotlin.math.max

/** Operaciones de edición sobre la obra, sin tocar los archivos de audio: todo se expresa con inicio, duración y recorte. */
object TimelineOps {
    /** Lo mínimo que debe quedar a cada lado de un corte (segundos). */
    const val MIN_PIECE = 0.15

    /** Por qué no se puede cortar ahí, para decírselo a la persona con palabras simples (null = se puede). */
    fun cutProblem(p: Project, cueId: String, at: Double): String? {
        val cue = p.cues.firstOrNull { it.id == cueId } ?: return "Toca primero el audio que quieres cortar."
        if (cue.type == CueType.SILENCE) return "Ese es un silencio; no hace falta cortarlo."
        if (cue.loop || cue.mode == CueMode.BED) return "La música de fondo no se corta. Si sobra, acórtala desde su borde."
        val place = DramaRenderer.layout(DramaRenderer.anchorTimeline(p)).firstOrNull { it.cue.id == cueId } ?: return "No encontré ese audio."
        if (at <= place.start + MIN_PIECE || at >= place.end - MIN_PIECE) return "Mueve la línea roja hasta el punto del audio donde quieres cortar."
        return null
    }

    /** Divide el audio [cueId] en el segundo [at]. Devuelve la obra nueva, o null si no se puede (ver [cutProblem]). */
    fun split(p: Project, cueId: String, at: Double, newId: String): Project? {
        if (cutProblem(p, cueId, at) != null) return null
        val anchored = DramaRenderer.anchorTimeline(p)
        val place = DramaRenderer.layout(anchored).first { it.cue.id == cueId }
        val cue = place.cue
        val pf = DramaRenderer.pitchFactor(DramaRenderer.pitchOf(anchored, cue))
        // Los tiempos se guardan en Float: el corte se fija en Float y el pedazo izquierdo termina exactamente donde empieza el derecho.
        val leftStart = place.start.toFloat()
        val cutAt = at.toFloat()
        val headF = cutAt - leftStart
        val head = headF.toDouble()
        val left = cue.copy(startSeconds = leftStart, durationSeconds = headF, fadeOut = 0f)
        val right = cue.copy(
            id = newId, startSeconds = cutAt, durationSeconds = (place.end - cutAt.toDouble()).toFloat(),
            trimStart = (cue.trimStart + head * pf).toFloat(), fadeIn = 0f,
        )
        val out = ArrayList<Cue>(anchored.cues.size + 1)
        for (c in anchored.cues) { if (c.id == cueId) { out += left; out += right } else out += c }
        return anchored.copy(cues = out)
    }

    fun delete(p: Project, cueId: String): Project = p.copy(cues = p.cues.filter { it.id != cueId })

    /** Mueve un audio a otro instante, sin desplazar a los demás. */
    fun moveTo(p: Project, cueId: String, start: Double): Project {
        val anchored = DramaRenderer.anchorTimeline(p)
        return anchored.copy(cues = anchored.cues.map { if (it.id == cueId) it.copy(startSeconds = max(0.0, start).toFloat()) else it })
    }
}

/** Historial para deshacer y rehacer: guarda versiones completas de la obra (son datos inmutables, copiar es barato). */
class History<T>(private val limit: Int = 40) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Anota que se pasa de [before] a otra versión: lo anterior se puede deshacer y lo rehecho se pierde. */
    fun record(before: T) {
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: T): T? { val prev = undo.removeLastOrNull() ?: return null; redo.addLast(current); return prev }
    fun redo(current: T): T? { val next = redo.removeLastOrNull() ?: return null; undo.addLast(current); return next }
    fun clear() { undo.clear(); redo.clear() }
}
