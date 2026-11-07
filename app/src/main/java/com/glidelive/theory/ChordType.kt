package com.glidelive.theory

enum class ChordType(val symbol: String, val label: String, val intervals: List<Int>) {
    MAJ("", "Mayor", listOf(0, 4, 7)),
    MIN("m", "m", listOf(0, 3, 7)),
    DIM("dim", "dim", listOf(0, 3, 6)),
    AUG("aug", "aug", listOf(0, 4, 8)),
    DOM7("7", "7", listOf(0, 4, 7, 10)),
    MAJ7("maj7", "maj7", listOf(0, 4, 7, 11)),
    MIN7("m7", "m7", listOf(0, 3, 7, 10)),
    MIN9("m9", "m9", listOf(0, 3, 7, 10, 14)),
    MAJ9("maj9", "maj9", listOf(0, 4, 7, 11, 14)),
    DOM7B9("7(♭9)", "7♭9", listOf(0, 4, 7, 10, 13)),
    M7B5("m7(♭5)", "m7♭5", listOf(0, 3, 6, 10)),
    SUS2("sus2", "sus2", listOf(0, 2, 7)),
    SUS4("sus4", "sus", listOf(0, 5, 7)),
    ADD9("add9", "add9", listOf(0, 4, 7, 14)),
    SIX("6", "6", listOf(0, 4, 7, 9)),
    MIN6("m6", "m6", listOf(0, 3, 7, 9)),
    DOM11("11", "11", listOf(0, 4, 7, 10, 14, 17)),
    DOM13("13", "13", listOf(0, 4, 7, 10, 14, 21)),
    DIM7("dim7", "dim7", listOf(0, 3, 6, 9)),
    MMAJ7("m(maj7)", "mMaj7", listOf(0, 3, 7, 11)),
    DOM7SUS4("7sus4", "7sus4", listOf(0, 5, 7, 10)),
    DOM9("9", "9", listOf(0, 4, 7, 10, 14)),
    SIX_NINE("6/9", "6/9", listOf(0, 4, 7, 9, 14));

    /** Descripción corta en palabras, para el subtítulo del pad. */
    val plain: String
        get() = when (this) {
            MAJ -> "mayor"; MIN -> "menor"; DIM -> "disminuido"; AUG -> "aumentado"
            DOM7 -> "dominante"; MAJ7 -> "mayor 7ª"; MIN7 -> "menor 7ª"
            MIN9 -> "menor 9ª"; MAJ9 -> "mayor 9ª"; DOM7B9 -> "tenso ♭9"
            M7B5 -> "semidism."; SUS2 -> "suspendido 2"; SUS4 -> "suspendido"
            ADD9 -> "mayor + 9ª"; SIX -> "mayor 6ª"; MIN6 -> "menor 6ª"
            DOM11 -> "dominante 11ª"; DOM13 -> "dominante 13ª"
            DIM7 -> "disminuido 7ª"; MMAJ7 -> "menor-mayor 7ª"
            DOM7SUS4 -> "dominante sus4"; DOM9 -> "dominante 9ª"; SIX_NINE -> "mayor 6/9"
        }

    /** Nombre para botones: palabras primero, símbolo entre paréntesis. */
    val friendly: String
        get() = if (symbol.isEmpty()) "Mayor" else "${plain.replaceFirstChar { it.uppercase() }} ($symbol)"

    /** Versión corta para los botones de variante. */
    val short: String
        get() = when (this) {
            DOM7 -> "Dominante 7"; MAJ7 -> "Mayor 7"; MIN7 -> "Menor 7"; MIN9 -> "Menor 9"
            MAJ9 -> "Mayor 9"; DOM7B9 -> "Tenso ♭9"; M7B5 -> "Semidism."; SUS4 -> "Suspendido"
            ADD9 -> "Con 9ª"; SIX -> "Con 6ª"; DOM11 -> "11ª"; DOM13 -> "13ª"
            else -> plain.replaceFirstChar { it.uppercase() }
        }

    val pitchClasses: List<Int> get() = intervals.map { it % 12 }.distinct()

    companion object {
        /** Calidades ofrecidas como "extensión" rápida. */
        val extensions = listOf(DOM7, MAJ7, MIN7, MIN9, MAJ9, DOM7B9, M7B5, SUS4, ADD9, SIX, DOM11, DOM13)

        private val diatonicCandidates =
            listOf(MAJ, MIN, DIM, AUG, MAJ7, DOM7, MIN7, M7B5, DIM7, MMAJ7)

        /** Identifica el tipo por sus intervalos (mod 12); null si no coincide. */
        fun identify(intervals: List<Int>): ChordType? {
            val set = intervals.map { it % 12 }.toSet()
            return diatonicCandidates.firstOrNull { it.pitchClasses.toSet() == set }
        }
    }
}
