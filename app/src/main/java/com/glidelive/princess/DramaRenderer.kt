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
        cue.durationSeconds?.toDouble()?.coerceAtLeast(0.0) ?: if (cue.type == CueType.SILENCE) cue.seconds.toDouble()
        else (cue.seconds - cue.trimStart).coerceAtLeast(0f) / pitchFactor(pitch)

    fun pitchOf(p: Project, cue: Cue): Float = if (cue.type == CueType.VOICE) p.characters.firstOrNull { it.id == cue.characterId }?.pitch ?: 0f else 0f

    fun layout(p: Project): List<Placement> {
        val out = ArrayList<Placement?>(p.cues.size)
        var cursor = 0.0        // fin de lo último que avanza la obra
        var lastStart = 0.0
        val bedStarts = HashMap<Int, Double>()
        for ((i, c) in p.cues.withIndex()) {
            val dur = playedSeconds(c, pitchOf(p, c))
            if ((c.startSeconds != null && c.mode != CueMode.BED) || c.durationSeconds != null && c.mode == CueMode.BED) {
                val start = (c.startSeconds?.toDouble() ?: (cursor + max(0f, c.gap))).coerceAtLeast(0.0)
                out.add(Placement(i, c, start, start + dur))
                if (c.mode != CueMode.BED) lastStart = start
                cursor = max(cursor, start + dur)
                continue
            }
            when (c.mode) {
                CueMode.BED -> { bedStarts[i] = c.startSeconds?.toDouble() ?: (cursor + max(0f, c.gap)); out.add(null) }
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
            val end = when {
                (followers.isEmpty() && total <= start) || start >= total -> start + playedSeconds(c, pitchOf(p, c))
                c.span <= 0 || followers.isEmpty() -> total
                else -> out[followers[min(c.span, followers.size) - 1]]!!.end
            }
            result.add(Placement(i, c, start, max(start, end)))
        }
        return result.sortedBy { it.start }
    }

    /** Freeze the existing audible arrangement before moving one clip independently. */
    fun anchorTimeline(p: Project): Project {
        val positions = layout(p).associateBy { it.index }
        return p.copy(cues = p.cues.mapIndexed { i, cue ->
            val place = positions.getValue(i)
            cue.copy(startSeconds = place.start.toFloat(), durationSeconds = if (cue.mode == CueMode.BED && cue.span > 0)
                (place.end - place.start).toFloat().takeIf { it > 0f } ?: cue.seconds else cue.durationSeconds)
        })
    }

    fun totalSeconds(p: Project): Double = layout(p).maxOfOrNull { it.end } ?: 0.0

    /** Ganancia (0..1) de la música a 100 Hz: baja mientras hay voces, con subida y bajada suaves. */
    fun duckEnvelope(places: List<Placement>, total: Double, depth: Float = 0.35f): FloatArray {
        val n = (total * 100).toInt() + 2
        val voice = BooleanArray(n)
        for (pl in places) if (pl.cue.type == CueType.VOICE && !pl.cue.muted) {
            val a = (pl.start * 100).toInt().minus(4).coerceIn(0, n - 1); val b = (pl.end * 100).toInt().coerceIn(0, n - 1)
            for (i in a..min(n - 1, b + 40)) voice[i] = true   // 0,4 s más: en las pausas cortas la música no sube y baja
        }
        val env = FloatArray(n)
        var g = 1f
        for (i in 0 until n) {
            val target = if (voice[i]) depth else 1f
            g += (target - g) * (if (target < g) 0.25f else 0.03f)  // baja rápido, sube despacio
            env[i] = g
        }
        return env
    }

    private const val BLOCK = RATE * 10   // la mezcla se arma de a 10 s: la memoria no crece con el largo del cuento

    /** Total de cuadros de la mezcla (incluye medio segundo de cola). */
    fun frameCount(p: Project): Int = ((layout(p).maxOfOrNull { it.end } ?: 0.0) * RATE).toInt() + RATE / 2

    /**
     * Mezcla la obra. [clipOf] entrega el audio de cada pista (null = sin archivo). Devuelve PCM 16 bit estéreo intercalado a 44,1 kHz.
     * [progress] recibe 0..1.
     */
    fun render(p: Project, clipOf: (Cue) -> Clip?, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): ShortArray {
        val frames = frameCount(p)
        val out = ShortArray(frames * 2)
        renderTo(p, clipOf, progress, cancelled) { block, from, n -> System.arraycopy(block, 0, out, from * 2, n * 2) }
        return out
    }

    /** Igual que [render], pero entrega la mezcla por bloques (intercalado L/R, [count] cuadros desde [from]) sin juntarla toda en memoria. */
    fun renderTo(p: Project, clipOf: (Cue) -> Clip?, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }, sink: (ShortArray, Int, Int) -> Unit) {
        val places = layout(p)
        val total = places.maxOfOrNull { it.end } ?: 0.0
        val frames = (total * RATE).toInt() + RATE / 2
        val duck = if (p.duckMusic) duckEnvelope(places, total) else null
        val gains = HashMap<String, Float>()
        val clips = HashMap<String, Clip?>()
        fun clip(c: Cue): Clip? { if (!clips.containsKey(c.id)) clips[c.id] = clipOf(c); return clips[c.id] }
        if (p.normalizeVoices) for (pl in places) {   // las ganancias se miden sin guardar el audio (se decodifica de nuevo al mezclar)
            val c = pl.cue
            if (c.type != CueType.VOICE || c.muted) continue
            if (cancelled()) throw java.util.concurrent.CancellationException("mezcla cancelada")
            clipOf(c)?.let { gains[c.id] = voiceGain(it) }
        }
        val mixL = FloatArray(minOf(BLOCK, maxOf(frames, 1)))
        val mixR = FloatArray(mixL.size)
        val pcm = ShortArray(mixL.size * 2)
        // Pasada 1: el pico global (para bajar todo parejo si se pasa). Pasada 2: la mezcla ya ajustada.
        var from = 0
        clips.clear()
        while (from < frames) {
            if (cancelled()) throw java.util.concurrent.CancellationException("mezcla cancelada")
            val n = minOf(mixL.size, frames - from)
            java.util.Arrays.fill(mixL, 0, n, 0f); java.util.Arrays.fill(mixR, 0, n, 0f)
            for (pl in places) {
                val c = pl.cue
                if (c.type == CueType.SILENCE || c.muted) continue
                val startF = (pl.start * RATE).toInt()
                val endF = min(frames, (pl.end * RATE).toInt())
                if (endF <= from || startF >= from + n) continue
                val clip = clip(c) ?: continue
                if (clip.frames < 2) continue
                mixInto(p, c, clip, startF, endF, from, n, gains[c.id] ?: 1f, duck, mixL, mixR)
            }
            for (i in 0 until n) {
                pcm[2 * i] = Math.round(softLimit(mixL[i]) * 32767f).coerceIn(-32768, 32767).toShort()
                pcm[2 * i + 1] = Math.round(softLimit(mixR[i]) * 32767f).coerceIn(-32768, 32767).toShort()
            }
            sink(pcm, from, n)
            from += n
            for (pl in places) if ((pl.end * RATE) <= from) clips.remove(pl.cue.id)   // lo que ya terminó se suelta
            progress((from.toFloat() / frames).coerceIn(0f, 1f))
        }
    }

    /** Limitador suave: lo normal pasa igual y los picos se curvan hacia 1 sin recortar ni bajar el volumen de todo el cuento. */
    fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * kotlin.math.tanh((a - 0.8f) / 0.2f)
        return if (x < 0f) -y else y
    }

    private fun mixInto(
        p: Project, c: Cue, clip: Clip, startF: Int, endF: Int, from: Int, n: Int, voiceNorm: Float, duck: FloatArray?,
        mixL: FloatArray, mixR: FloatArray,
    ) {
        val ch = if (c.type == CueType.VOICE) p.characters.firstOrNull { it.id == c.characterId } else null
        val pitch = pitchOf(p, c)
        val step = clip.rate * pitchFactor(pitch) / RATE          // muestras de origen por muestra de salida
        val loop = c.mode == CueMode.BED || c.loop
        val gain = c.volume * (ch?.volume ?: 1f) * voiceNorm
        val pan = ch?.pan ?: 0f
        val gl = gain * min(1f, 1f - pan); val gr = gain * min(1f, 1f + pan)
        val fadeInF = (c.fadeIn * RATE).toInt(); val fadeOutF = (c.fadeOut * RATE).toInt()
        val len = endF - startF
        val trimFrames = (c.trimStart.toDouble() * clip.rate).coerceAtLeast(0.0)
        val sourceStart = min(trimFrames.toInt(), clip.frames - 1)
        val trimFrac = if (trimFrames.toInt() < clip.frames - 1) trimFrames - trimFrames.toInt() else 0.0
        val sourceFrames = clip.frames - sourceStart
        val first = max(0, from - startF)
        val last = min(len, from + n - startF)
        var pos = first * step
        for (i in first until last) {
            var sp = pos
            if (loop) sp %= (sourceFrames - trimFrac).coerceAtLeast(1.0) else if (sp >= sourceFrames - 1) break
            sp += sourceStart + trimFrac
            val i0 = min(sp.toInt(), clip.frames - 1)   // el redondeo de Double no debe dejar pasar al índice del final
            val i1 = if (i0 + 1 < clip.frames) i0 + 1 else if (loop) sourceStart else i0
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
            if (c.type == CueType.MUSIC && duck != null) {
                val pos100 = (startF + i) / (RATE / 100.0)
                val k = min(duck.size - 1, pos100.toInt()); val k2 = min(duck.size - 1, k + 1)
                e *= duck[k] + (duck[k2] - duck[k]) * (pos100 - k).toFloat()
            }
            val o = startF + i - from
            mixL[o] += l * (1f / 32768f) * gl * e
            mixR[o] += r * (1f / 32768f) * gr * e
            pos += step
        }
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
