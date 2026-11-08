package com.glidelive.princess

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Dónde cae cada pista en la obra (segundos). */
data class Placement(val index: Int, val cue: Cue, val start: Double, val end: Double)

/** Audio ya decodificado de una pista (PCM 16 bit intercalado). */
class Clip(val pcm: ShortArray, val channels: Int, val rate: Int) {
    val frames: Int get() = pcm.size / channels.coerceAtLeast(1)
}

/**
 * Arma la línea de tiempo de la obra a partir de la lista de pistas y la mezcla a 44,1 kHz estéreo.
 * Reglas: cada pista entra después de la anterior (+ su espacio); "junto a la anterior" empieza a la vez que la anterior
 * con un desfase; la música de fondo empieza donde está y acompaña a las [Cue.span] pistas siguientes (0 = hasta el final).
 */
object DramaRenderer {
    const val RATE = 44100

    fun pitchFactor(semitones: Float): Double = 2.0.pow(semitones / 12.0)

    /** Largo (s) de una pista una vez cambiado su tono (cambiar el tono por remuestreo cambia también la duración). */
    fun playedSeconds(cue: Cue, pitch: Float): Double =
        if (cue.type == CueType.SILENCE) cue.seconds.toDouble() else cue.seconds / pitchFactor(pitch)

    fun pitchOf(p: Project, cue: Cue): Float = if (cue.type == CueType.VOICE) p.characters.firstOrNull { it.id == cue.characterId }?.pitch ?: 0f else 0f

    fun layout(p: Project): List<Placement> {
        val out = ArrayList<Placement?>(p.cues.size)
        var cursor = 0.0        // fin de lo último que avanza la obra
        var lastStart = 0.0
        val bedStarts = HashMap<Int, Double>()
        for ((i, c) in p.cues.withIndex()) {
            val dur = playedSeconds(c, pitchOf(p, c))
            when (c.mode) {
                CueMode.BED -> { bedStarts[i] = cursor + max(0f, c.gap); out.add(null) }
                CueMode.WITH_PREV -> {
                    val s = lastStart + max(0f, c.gap)
                    out.add(Placement(i, c, s, s + dur))
                    cursor = max(cursor, s + dur)
                }
                CueMode.SEQ -> {
                    val s = cursor + max(0f, c.gap)
                    out.add(Placement(i, c, s, s + dur))
                    lastStart = s
                    cursor = s + dur
                }
            }
        }
        val total = cursor
        // Música de fondo: termina al acabar la última pista que acompaña (o al final de la obra).
        val result = ArrayList<Placement>()
        for ((i, pl) in out.withIndex()) {
            if (pl != null) { result.add(pl); continue }
            val c = p.cues[i]
            val start = bedStarts[i] ?: 0.0
            val followers = (i + 1 until p.cues.size).filter { p.cues[it].mode != CueMode.BED && out[it] != null }
            val end = if (c.span <= 0 || followers.isEmpty()) total else out[followers[min(c.span, followers.size) - 1]]!!.end
            result.add(Placement(i, c, start, max(start, end)))
        }
        return result.sortedBy { it.start }
    }

    fun totalSeconds(p: Project): Double = layout(p).maxOfOrNull { it.end } ?: 0.0

    /** Ganancia (0..1) de la música a 100 Hz: baja mientras hay voces, con subida y bajada suaves. */
    fun duckEnvelope(places: List<Placement>, total: Double, depth: Float = 0.35f): FloatArray {
        val n = (total * 100).toInt() + 2
        val voice = BooleanArray(n)
        for (pl in places) if (pl.cue.type == CueType.VOICE) {
            val a = (pl.start * 100).toInt().coerceIn(0, n - 1); val b = (pl.end * 100).toInt().coerceIn(0, n - 1)
            for (i in a..b) voice[i] = true
        }
        val env = FloatArray(n)
        var g = 1f
        for (i in 0 until n) {
            val target = if (voice[i]) depth else 1f
            g += (target - g) * (if (target < g) 0.25f else 0.04f)  // baja rápido, sube despacio
            env[i] = g
        }
        return env
    }

    /**
     * Mezcla la obra. [clipOf] entrega el audio de cada pista (null = sin archivo). Devuelve PCM 16 bit estéreo intercalado a 44,1 kHz.
     * [progress] recibe 0..1.
     */
    fun render(p: Project, clipOf: (Cue) -> Clip?, progress: (Float) -> Unit = {}): ShortArray {
        val places = layout(p)
        val total = places.maxOfOrNull { it.end } ?: 0.0
        val frames = (total * RATE).toInt() + RATE / 2  // medio segundo de cola
        val mixL = FloatArray(frames)
        val mixR = FloatArray(frames)
        val duck = if (p.duckMusic) duckEnvelope(places, total) else null

        for ((n, pl) in places.withIndex()) {
            val c = pl.cue
            if (c.type == CueType.SILENCE) continue
            val clip = clipOf(c) ?: continue
            if (clip.frames < 2) continue
            val ch = if (c.type == CueType.VOICE) p.characters.firstOrNull { it.id == c.characterId } else null
            val pitch = pitchOf(p, c)
            val step = clip.rate * pitchFactor(pitch) / RATE          // muestras de origen por muestra de salida
            val startF = (pl.start * RATE).toInt()
            val endF = min(frames, (pl.end * RATE).toInt())
            val loop = c.mode == CueMode.BED
            val gain = c.volume * (ch?.volume ?: 1f) * (if (c.type == CueType.VOICE && p.normalizeVoices) voiceGain(clip) else 1f)
            val pan = ch?.pan ?: 0f
            val gl = gain * min(1f, 1f - pan); val gr = gain * min(1f, 1f + pan)
            val fadeInF = (c.fadeIn * RATE).toInt(); val fadeOutF = (c.fadeOut * RATE).toInt()
            val len = endF - startF
            var pos = 0.0
            for (i in 0 until len) {
                var sp = pos
                if (loop) sp %= clip.frames.toDouble() else if (sp >= clip.frames - 1) break
                val i0 = sp.toInt()
                val i1 = if (i0 + 1 < clip.frames) i0 + 1 else if (loop) 0 else i0
                val f = (sp - i0).toFloat()
                val l: Float; val r: Float
                if (clip.channels >= 2) {
                    l = clip.pcm[i0 * clip.channels] + (clip.pcm[i1 * clip.channels] - clip.pcm[i0 * clip.channels]) * f
                    r = clip.pcm[i0 * clip.channels + 1] + (clip.pcm[i1 * clip.channels + 1] - clip.pcm[i0 * clip.channels + 1]) * f
                } else {
                    l = clip.pcm[i0] + (clip.pcm[i1] - clip.pcm[i0]) * f; r = l
                }
                var e = 1f
                if (fadeInF > 0 && i < fadeInF) e = i.toFloat() / fadeInF
                if (fadeOutF > 0 && len - i < fadeOutF) e = min(e, (len - i).toFloat() / fadeOutF)
                if (loop && duck != null) e *= duck[min(duck.size - 1, (startF + i) / (RATE / 100))]
                val o = startF + i
                mixL[o] += l * (1f / 32768f) * gl * e
                mixR[o] += r * (1f / 32768f) * gr * e
                pos += step
            }
            progress((n + 1f) / places.size)
        }
        // Limitador: si la suma pasa de 1, se baja todo parejo (sin recortar).
        var peak = 0f
        for (i in 0 until frames) peak = max(peak, max(abs(mixL[i]), abs(mixR[i])))
        val scale = if (peak > 0.98f) 0.98f / peak else 1f
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = (mixL[i] * scale * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (mixR[i] * scale * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Ganancia para llevar el pico de una voz a ~0,7 (sin subir más de 8 veces ni bajar de 0,25). */
    fun voiceGain(clip: Clip): Float {
        var peak = 0
        var i = 0
        while (i < clip.pcm.size) { peak = max(peak, abs(clip.pcm[i].toInt())); i += 3 }
        if (peak < 200) return 1f
        return (0.7f * 32768f / peak).coerceIn(0.25f, 8f)
    }
}
