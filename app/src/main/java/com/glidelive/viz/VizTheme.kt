package com.glidelive.viz

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

/** Estilos de la visualización de voces. El agudo siempre es la línea más clara y el bajo la más distinta. */
enum class VizTheme(
    val label: String,
    val bgTop: Int,
    val bgBottom: Int,
    /** Halo radial más claro detrás del nombre del acorde (0 = sin halo). */
    val halo: Int,
) {
    MULTI("Multicolor oscuro", 0xFF1B120C.toInt(), 0xFF0C0807.toInt(), 0),
    ORANGE("Naranja cálido", 0xFFC24A1F.toInt(), 0xFFA33718.toInt(), 0x66FF9A52),
    MONO("Monocromo", 0xFF0B0B0D.toInt(), 0xFF000000.toInt(), 0x22FFFFFF);

    /** Color de la voz [rank] (0 = más aguda) cuando suenan [n] voces distintas. */
    fun color(rank: Int, n: Int): Int = when (this) {
        MULTI -> multi(rank, n)
        ORANGE -> orange(rank, n)
        MONO -> mono(rank, n)
    }

    private fun multi(rank: Int, n: Int): Int {
        if (n <= 1 || rank == 0) return 0xFFFFFFFF.toInt()
        if (rank >= n - 1) return 0xFFFF5A4D.toInt()
        val pal = MULTI_INTERIOR[min(n, 6) - 3]
        return pal[min(rank - 1, pal.size - 1)]
    }

    private fun orange(rank: Int, n: Int): Int {
        if (n <= 1 || rank == 0) return 0xFFFFFFFF.toInt()
        if (rank >= n - 1) return 0xFFFFE070.toInt()  // el bajo, amarillo cálido
        return ORANGE_INTERIOR[min(rank - 1, ORANGE_INTERIOR.size - 1)]
    }

    private fun mono(rank: Int, n: Int): Int {
        if (n <= 1) return 0xFFFFFFFF.toInt()
        val g = 255 - (rank * 85 / (n - 1))
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    companion object {
        private val MULTI_INTERIOR = arrayOf(
            intArrayOf(0xFF5AA0FF.toInt()),                                                   // 3 voces: blanco, azul, rojo
            intArrayOf(0xFFB48CFF.toInt(), 0xFF48E08C.toInt()),                               // 4: blanco, lila, verde, rojo
            intArrayOf(0xFFB48CFF.toInt(), 0xFF5AA0FF.toInt(), 0xFF48E08C.toInt()),          // 5
            intArrayOf(0xFFB48CFF.toInt(), 0xFF5AA0FF.toInt(), 0xFF48E08C.toInt(), 0xFFFFA040.toInt()),  // 6
        )
        private val ORANGE_INTERIOR = intArrayOf(
            0xFFFFF4E8.toInt(), 0xFFDDF3F8.toInt(), 0xFFFFE0EA.toInt(), 0xFFFFEFB0.toInt(),
        )
    }
}

/** Estilo elegido (se recuerda entre sesiones). La vista lo lee en cada frame sin recomponer. */
object VizPrefs {
    @Volatile var current: VizTheme = VizTheme.MULTI
        private set
    private val _theme = MutableStateFlow(VizTheme.MULTI)
    val theme: StateFlow<VizTheme> = _theme

    fun init(ctx: Context) {
        val name = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("viz_theme", null)
        val t = VizTheme.entries.firstOrNull { it.name == name } ?: VizTheme.MULTI
        current = t
        _theme.value = t
    }

    fun set(ctx: Context, t: VizTheme) {
        current = t
        _theme.value = t
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("viz_theme", t.name).apply()
    }
}
