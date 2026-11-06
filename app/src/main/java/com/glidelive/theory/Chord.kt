package com.glidelive.theory

data class Chord(val rootPc: Int, val type: ChordType) {
    /** Clases de altura en orden de acorde (fundamental primero). */
    val tones: List<Int>
        get() = type.intervals.map { Math.floorMod(rootPc + it, 12) }.distinct()

    fun name(useSharps: Boolean = false): String = NoteNames.name(rootPc, useSharps) + type.symbol

    companion object {
        /** Acorde diatónico del grado [degree] (0-based). [seventh] apila 4 notas en vez de 3. */
        fun diatonic(scale: Scale, tonic: Int, degree: Int, seventh: Boolean): Chord {
            val root = scale.degreePitch(tonic, degree)
            val iv = scale.stackedThirds(degree, if (seventh) 4 else 3)
            val type = ChordType.identify(iv)
                ?: if (iv[1] == 3) ChordType.MIN else ChordType.MAJ
            return Chord(root, type)
        }

        private val numerals = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")

        fun roman(degree: Int, type: ChordType): String {
            val n = numerals[degree % 7]
            val minor = type == ChordType.MIN || type == ChordType.MIN7 || type == ChordType.MIN9 ||
                type == ChordType.DIM || type == ChordType.DIM7 || type == ChordType.M7B5 ||
                type == ChordType.MIN6 || type == ChordType.MMAJ7
            val base = if (minor) n.lowercase() else n
            return when (type) {
                ChordType.DIM, ChordType.DIM7 -> "$base°"
                ChordType.M7B5 -> "${base}ø"
                ChordType.AUG -> "$base+"
                else -> base
            }
        }
    }
}
