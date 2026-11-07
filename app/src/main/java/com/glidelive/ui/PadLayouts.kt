package com.glidelive.ui

/**
 * Distribuciones de los pads de batería. Cada una es una cuadrícula; [cells] da, para cada pad (0..12),
 * [columna, fila, columnas, filas] o null si ese pad no se muestra en esa distribución (los que faltan al final, ocultos).
 */
enum class PadLayout(val label: String, val hint: String, val cols: Int, val rows: Int, base: Array<IntArray?>) {
    KIT(
        "Kit clásico", "Como una batería vista desde arriba", 12, 6,
        arrayOf(
            intArrayOf(4, 4, 4, 2),   // bombo
            intArrayOf(4, 2, 2, 2),   // caja
            intArrayOf(6, 2, 2, 2),   // aros
            intArrayOf(0, 2, 2, 2),   // hi-hat cerrado
            intArrayOf(2, 2, 2, 2),   // hi-hat abierto
            intArrayOf(0, 4, 4, 2),   // pedal hi-hat
            intArrayOf(4, 0, 2, 2),   // tom 1
            intArrayOf(6, 0, 2, 2),   // tom 2
            intArrayOf(8, 2, 4, 4),   // tom de piso
            intArrayOf(2, 0, 2, 2),   // crash
            intArrayOf(8, 0, 2, 2),   // ride
            intArrayOf(10, 0, 2, 2),  // campana
            intArrayOf(0, 0, 2, 2),   // splash / china
        ),
    ),
    KIT_LEFT(
        "Kit zurdo", "El clásico en espejo", 12, 6,
        Array(13) { i -> KIT.cells[i]!!.let { intArrayOf(12 - it[0] - it[2], it[1], it[2], it[3]) } },
    ),
    MPC(
        "Pads 4×4", "Cuadrícula uniforme como una MPC", 4, 4,
        arrayOf(
            intArrayOf(0, 3, 2, 1),   // bombo
            intArrayOf(2, 3, 2, 1),   // caja
            intArrayOf(3, 1, 1, 1),   // aros
            intArrayOf(0, 2, 1, 1),   // hi-hat cerrado
            intArrayOf(1, 2, 1, 1),   // hi-hat abierto
            intArrayOf(2, 2, 2, 1),   // pedal hi-hat
            intArrayOf(0, 1, 1, 1),   // tom 1
            intArrayOf(1, 1, 1, 1),   // tom 2
            intArrayOf(2, 1, 1, 1),   // tom de piso
            intArrayOf(1, 0, 1, 1),   // crash
            intArrayOf(2, 0, 1, 1),   // ride
            intArrayOf(3, 0, 1, 1),   // campana
            intArrayOf(0, 0, 1, 1),   // splash / china
        ),
    ),
    ESSENTIAL(
        "Esencial (9 pads)", "Menos pads, mucho más grandes", 12, 6,
        arrayOf(
            intArrayOf(0, 4, 12, 2),  // bombo
            intArrayOf(6, 2, 3, 2),   // caja
            null,                     // aros
            intArrayOf(0, 2, 3, 2),   // hi-hat cerrado
            intArrayOf(3, 2, 3, 2),   // hi-hat abierto
            null,                     // pedal
            intArrayOf(3, 0, 3, 2),   // tom 1
            intArrayOf(6, 0, 3, 2),   // tom 2
            intArrayOf(9, 2, 3, 2),   // tom de piso
            intArrayOf(0, 0, 3, 2),   // crash
            intArrayOf(9, 0, 3, 2),   // ride
            null,                     // campana
            null,                     // splash
        ),
    ),
    BIG_SNARE(
        "Caja y bombo grandes", "Lo que más se toca, más grande", 12, 6,
        arrayOf(
            intArrayOf(4, 4, 8, 2),   // bombo
            intArrayOf(4, 2, 4, 2),   // caja
            intArrayOf(10, 2, 2, 2),  // aros
            intArrayOf(0, 2, 2, 2),   // hi-hat cerrado
            intArrayOf(2, 2, 2, 2),   // hi-hat abierto
            intArrayOf(0, 4, 4, 2),   // pedal hi-hat
            intArrayOf(4, 0, 2, 2),   // tom 1
            intArrayOf(6, 0, 2, 2),   // tom 2
            intArrayOf(8, 2, 2, 2),   // tom de piso
            intArrayOf(2, 0, 2, 2),   // crash
            intArrayOf(8, 0, 2, 2),   // ride
            intArrayOf(10, 0, 2, 2),  // campana
            intArrayOf(0, 0, 2, 2),   // splash / china
        ),
    ),
    CUMBIA(
        "Cumbia", "Percusión de cumbia + bombo, caja y hi-hat", 12, 6,
        arrayOf(
            intArrayOf(0, 4, 4, 2),   // bombo
            intArrayOf(4, 4, 3, 2),   // caja
            intArrayOf(10, 4, 2, 2),  // aros
            intArrayOf(7, 4, 3, 2),   // hi-hat cerrado
            null, null, null, null, null, null, null, null, null,
            intArrayOf(0, 0, 3, 2),   // güiro corto
            intArrayOf(3, 0, 3, 2),   // güiro largo
            intArrayOf(6, 0, 3, 2),   // maracas
            intArrayOf(6, 2, 3, 2),   // conga
            intArrayOf(3, 2, 3, 2),   // quinto (alegre)
            intArrayOf(9, 2, 3, 2),   // tumba
            intArrayOf(0, 2, 3, 2),   // bongó
            intArrayOf(9, 0, 3, 2),   // campana
        ),
    );

    /** Celda de cada pad (null = oculto); siempre de largo [Pads.COUNT]. */
    val cells: Array<IntArray?> = Array(Pads.COUNT) { base.getOrNull(it) }

    /** Dueño de cada celda (fila * cols + columna), o -1; calculado una sola vez. */
    val owner: IntArray = IntArray(cols * rows) { -1 }.also { o ->
        for (p in cells.indices) {
            val c = cells[p] ?: continue
            for (r in c[1] until c[1] + c[3]) for (q in c[0] until c[0] + c[2]) o[r * cols + q] = p
        }
    }

    fun visible(pad: Int) = cells[pad] != null
}
