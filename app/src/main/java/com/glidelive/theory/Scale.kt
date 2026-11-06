package com.glidelive.theory

enum class Scale(val label: String, val steps: IntArray) {
    MAJOR("Mayor", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Menor natural", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR("Menor armónica", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR("Menor melódica", intArrayOf(0, 2, 3, 5, 7, 9, 11)),
    DORIAN("Dórica", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("Frigia", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN("Lidia", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN("Mixolidia", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    LOCRIAN("Locria", intArrayOf(0, 1, 3, 5, 6, 8, 10));

    val size: Int get() = steps.size

    fun pitchClasses(tonic: Int): List<Int> = steps.map { Math.floorMod(tonic + it, 12) }

    fun contains(tonic: Int, pc: Int): Boolean = pitchClasses(tonic).contains(Math.floorMod(pc, 12))

    fun degreePitch(tonic: Int, degree: Int): Int = Math.floorMod(tonic + steps[degree % size], 12)

    /** Intervalos (desde la fundamental del grado) de [count] terceras apiladas dentro de la escala. */
    fun stackedThirds(degree: Int, count: Int): List<Int> {
        val root = steps[degree % size]
        return (0 until count).map { k ->
            val idx = degree + 2 * k
            steps[idx % size] + 12 * (idx / size) - root
        }
    }
}
