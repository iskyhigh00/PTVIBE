package com.glidelive.theory

/**
 * Bancos de acordes C1..C16: progresiones con voicings fijos diseñados y verificados (fundamental en el bajo,
 * sin segundas menores, intervalos amplios en el grave, todas las notas del acorde y voz aguda melódica).
 * Están escritos en su tonalidad de origen ([home]) y se trasladan a la tonalidad elegida por el camino más corto.
 */
data class CuratedPad(val root: Int, val type: ChordType, val roman: String, val voicing: List<Int>)

data class CuratedBank(val id: String, val name: String, val home: Int, val scale: Scale, val pads: List<CuratedPad>) {
    /** Semitonos para llevar el banco a [tonic] (entre -5 y +6, para no salirse del registro). */
    fun shiftTo(tonic: Int): Int { val d = Math.floorMod(tonic - home, 12); return if (d > 6) d - 12 else d }
}

object CuratedBanks {
    val all: List<CuratedBank> = listOf(
        CuratedBank("C1", "Nocturno", 0, Scale.NATURAL_MINOR, listOf(
            CuratedPad(3, ChordType.MAJ7, "III∆", listOf(39, 58, 62, 67, 70)),
            CuratedPad(8, ChordType.MAJ7, "VI∆", listOf(44, 60, 63, 67, 72)),
            CuratedPad(2, ChordType.M7B5, "iiø", listOf(38, 60, 65, 68, 72)),
            CuratedPad(7, ChordType.DOM7B9, "V7♭9", listOf(43, 62, 65, 68, 71)),
            CuratedPad(0, ChordType.MIN9, "i9", listOf(48, 63, 67, 70, 74)),
            CuratedPad(0, ChordType.DOM7B9, "I7♭9", listOf(48, 64, 67, 70, 73)),
            CuratedPad(5, ChordType.MIN9, "iv9", listOf(41, 63, 68, 72, 79)),
            CuratedPad(7, ChordType.DOM7SUS4, "V7sus", listOf(43, 62, 67, 72, 77)),
        )),
        CuratedBank("C2", "Amanecer", 10, Scale.MAJOR, listOf(
            CuratedPad(10, ChordType.ADD9, "Iadd9", listOf(46, 60, 62, 65, 72)),
            CuratedPad(5, ChordType.SUS4, "Vsus", listOf(41, 58, 60, 65, 72)),
            CuratedPad(5, ChordType.MAJ, "V", listOf(41, 57, 60, 65, 72)),
            CuratedPad(7, ChordType.MIN7, "vi7", listOf(43, 58, 62, 65, 70)),
            CuratedPad(3, ChordType.MAJ9, "IV∆9", listOf(39, 55, 62, 65, 70)),
            CuratedPad(10, ChordType.MAJ, "I", listOf(46, 58, 62, 65, 70)),
            CuratedPad(2, ChordType.MIN7, "iii7", listOf(50, 60, 62, 65, 69)),
            CuratedPad(0, ChordType.MIN7, "ii7", listOf(48, 60, 63, 67, 70)),
        )),
        CuratedBank("C3", "Cuarto menor", 4, Scale.NATURAL_MINOR, listOf(
            CuratedPad(4, ChordType.MIN9, "i9", listOf(40, 55, 62, 66, 71)),
            CuratedPad(0, ChordType.MAJ9, "VI∆9", listOf(36, 55, 62, 64, 71)),
            CuratedPad(7, ChordType.MAJ, "III", listOf(43, 55, 62, 67, 71)),
            CuratedPad(2, ChordType.SUS4, "VIIsus", listOf(38, 55, 62, 67, 69)),
            CuratedPad(2, ChordType.MAJ, "VII", listOf(38, 54, 62, 66, 69)),
            CuratedPad(9, ChordType.MIN9, "iv9", listOf(45, 55, 60, 64, 71)),
            CuratedPad(11, ChordType.MIN7, "v7", listOf(47, 57, 62, 66, 71)),
            CuratedPad(11, ChordType.DOM7B9, "V7♭9", listOf(47, 57, 63, 66, 72)),
        )),
        CuratedBank("C4", "Soul", 2, Scale.DORIAN, listOf(
            CuratedPad(2, ChordType.MIN9, "i9", listOf(38, 53, 60, 64, 69)),
            CuratedPad(7, ChordType.DOM13, "IV13", listOf(43, 53, 59, 62, 64, 69)),
            CuratedPad(5, ChordType.MAJ9, "III∆9", listOf(41, 60, 64, 67, 69)),
            CuratedPad(4, ChordType.MIN7, "ii7", listOf(40, 62, 64, 67, 71)),
            CuratedPad(9, ChordType.DOM7B9, "V7♭9", listOf(45, 61, 64, 67, 70)),
            CuratedPad(10, ChordType.MAJ7, "♭VI∆", listOf(46, 57, 62, 65, 70)),
            CuratedPad(7, ChordType.MIN9, "iv9", listOf(43, 57, 62, 65, 70)),
            CuratedPad(0, ChordType.DOM9, "♭VII9", listOf(48, 55, 62, 64, 70)),
        )),
        CuratedBank("C5", "Cielo abierto", 5, Scale.MAJOR, listOf(
            CuratedPad(5, ChordType.MAJ9, "I∆9", listOf(41, 64, 67, 69, 72)),
            CuratedPad(9, ChordType.MIN7, "iii7", listOf(45, 64, 67, 69, 72)),
            CuratedPad(2, ChordType.MIN9, "vi9", listOf(50, 65, 69, 72, 76)),
            CuratedPad(10, ChordType.MAJ9, "IV∆9", listOf(46, 65, 69, 72, 74)),
            CuratedPad(7, ChordType.MIN9, "ii9", listOf(43, 58, 65, 69, 74)),
            CuratedPad(0, ChordType.DOM7SUS4, "V7sus", listOf(48, 58, 65, 67, 72)),
            CuratedPad(0, ChordType.DOM9, "V9", listOf(48, 58, 64, 67, 74)),
            CuratedPad(5, ChordType.SIX_NINE, "I6/9", listOf(41, 57, 62, 67, 72)),
        )),
        CuratedBank("C6", "Gospel", 7, Scale.MAJOR, listOf(
            CuratedPad(7, ChordType.MAJ9, "I∆9", listOf(43, 57, 62, 66, 71)),
            CuratedPad(4, ChordType.MIN9, "vi9", listOf(40, 55, 62, 66, 71)),
            CuratedPad(9, ChordType.MIN9, "ii9", listOf(45, 55, 60, 64, 71)),
            CuratedPad(2, ChordType.DOM7SUS4, "V7sus", listOf(38, 55, 60, 62, 69)),
            CuratedPad(2, ChordType.DOM9, "V9", listOf(38, 54, 60, 64, 69)),
            CuratedPad(11, ChordType.MIN7, "iii7", listOf(47, 54, 59, 62, 69)),
            CuratedPad(4, ChordType.DOM7B9, "VI7♭9", listOf(40, 53, 59, 62, 68)),
            CuratedPad(0, ChordType.MAJ9, "IV∆9", listOf(36, 52, 59, 62, 67)),
        )),
        CuratedBank("C7", "Cine", 9, Scale.NATURAL_MINOR, listOf(
            CuratedPad(9, ChordType.MIN9, "i9", listOf(45, 59, 64, 67, 72)),
            CuratedPad(5, ChordType.MAJ9, "VI∆9", listOf(41, 57, 64, 67, 72)),
            CuratedPad(0, ChordType.MAJ9, "III∆9", listOf(36, 59, 64, 67, 74)),
            CuratedPad(7, ChordType.SIX, "VII6", listOf(43, 59, 64, 67, 74)),
            CuratedPad(2, ChordType.MIN9, "iv9", listOf(38, 60, 65, 69, 76)),
            CuratedPad(4, ChordType.DOM7SUS4, "V7sus", listOf(40, 59, 64, 69, 74)),
            CuratedPad(4, ChordType.DOM7B9, "V7♭9", listOf(40, 59, 65, 68, 74)),
            CuratedPad(5, ChordType.MAJ7, "VI∆", listOf(41, 60, 65, 69, 76)),
        )),
        CuratedBank("C8", "Brillo", 3, Scale.MAJOR, listOf(
            CuratedPad(3, ChordType.SIX_NINE, "I6/9", listOf(39, 65, 67, 70, 72)),
            CuratedPad(8, ChordType.MAJ9, "IV∆9", listOf(44, 63, 67, 70, 72)),
            CuratedPad(5, ChordType.MIN9, "ii9", listOf(41, 56, 63, 67, 72)),
            CuratedPad(10, ChordType.DOM13, "V13", listOf(46, 53, 56, 62, 67, 72)),
            CuratedPad(7, ChordType.MIN7, "iii7", listOf(43, 55, 62, 65, 70)),
            CuratedPad(0, ChordType.MIN9, "vi9", listOf(48, 58, 63, 67, 74)),
            CuratedPad(8, ChordType.SIX, "IV6", listOf(44, 60, 63, 65, 75)),
            CuratedPad(10, ChordType.DOM7SUS4, "V7sus", listOf(46, 63, 65, 68, 75)),
        )),
        CuratedBank("C9", "Lo-fi", 3, Scale.MAJOR, listOf(
            CuratedPad(5, ChordType.MIN9, "ii9", listOf(41, 56, 63, 67, 72)),
            CuratedPad(10, ChordType.DOM13, "V13", listOf(46, 53, 56, 62, 67, 72)),
            CuratedPad(3, ChordType.MAJ9, "I∆9", listOf(39, 55, 62, 65, 70)),
            CuratedPad(8, ChordType.MAJ9, "IV∆9", listOf(44, 55, 60, 63, 70)),
            CuratedPad(2, ChordType.M7B5, "viiø", listOf(38, 56, 60, 65, 68)),
            CuratedPad(7, ChordType.DOM7B9, "III7♭9", listOf(43, 59, 62, 65, 68)),
            CuratedPad(0, ChordType.MIN9, "vi9", listOf(48, 58, 63, 67, 74)),
            CuratedPad(10, ChordType.DOM7SUS4, "V7sus", listOf(46, 63, 65, 68, 75)),
        )),
        CuratedBank("C10", "Bossa", 5, Scale.MAJOR, listOf(
            CuratedPad(5, ChordType.MAJ9, "I∆9", listOf(41, 64, 67, 69, 72)),
            CuratedPad(2, ChordType.DOM7B9, "VI7♭9", listOf(38, 63, 66, 69, 72)),
            CuratedPad(7, ChordType.MIN9, "ii9", listOf(43, 58, 65, 69, 74)),
            CuratedPad(0, ChordType.DOM13, "V13", listOf(48, 55, 58, 64, 69, 74)),
            CuratedPad(9, ChordType.MIN7, "iii7", listOf(45, 57, 64, 67, 72)),
            CuratedPad(2, ChordType.MIN9, "vi9", listOf(50, 60, 65, 69, 76)),
            CuratedPad(10, ChordType.MAJ9, "IV∆9", listOf(46, 60, 65, 69, 74)),
            CuratedPad(0, ChordType.DOM7SUS4, "V7sus", listOf(48, 58, 65, 67, 72)),
        )),
        CuratedBank("C11", "Melancolía", 2, Scale.NATURAL_MINOR, listOf(
            CuratedPad(2, ChordType.MIN9, "i9", listOf(38, 53, 60, 64, 69)),
            CuratedPad(10, ChordType.MAJ9, "VI∆9", listOf(46, 53, 60, 62, 69)),
            CuratedPad(5, ChordType.MAJ9, "III∆9", listOf(41, 55, 60, 64, 69)),
            CuratedPad(0, ChordType.SIX, "VII6", listOf(36, 55, 60, 64, 69)),
            CuratedPad(7, ChordType.MIN9, "iv9", listOf(43, 58, 62, 65, 69)),
            CuratedPad(4, ChordType.M7B5, "iiø", listOf(40, 58, 62, 67, 70)),
            CuratedPad(9, ChordType.DOM7SUS4, "V7sus", listOf(45, 62, 64, 67, 69)),
            CuratedPad(9, ChordType.DOM7B9, "V7♭9", listOf(45, 61, 64, 67, 70)),
        )),
        CuratedBank("C12", "Épico", 0, Scale.MAJOR, listOf(
            CuratedPad(9, ChordType.MIN9, "vi9", listOf(45, 59, 64, 67, 72)),
            CuratedPad(5, ChordType.MAJ9, "IV∆9", listOf(41, 57, 64, 67, 72)),
            CuratedPad(0, ChordType.ADD9, "Iadd9", listOf(36, 62, 64, 67, 72)),
            CuratedPad(7, ChordType.SIX, "V6", listOf(43, 62, 64, 67, 71)),
            CuratedPad(4, ChordType.MIN7, "iii7", listOf(40, 62, 64, 67, 71)),
            CuratedPad(5, ChordType.MAJ7, "IV∆", listOf(41, 60, 64, 69, 72)),
            CuratedPad(2, ChordType.MIN9, "ii9", listOf(38, 60, 65, 69, 76)),
            CuratedPad(7, ChordType.DOM7SUS4, "V7sus", listOf(43, 60, 65, 67, 74)),
        )),
        CuratedBank("C13", "Neo soul", 10, Scale.DORIAN, listOf(
            CuratedPad(10, ChordType.MIN9, "i9", listOf(46, 61, 65, 68, 72)),
            CuratedPad(3, ChordType.DOM9, "IV9", listOf(39, 61, 65, 67, 70)),
            CuratedPad(6, ChordType.MAJ9, "♭VI∆9", listOf(42, 61, 65, 68, 70)),
            CuratedPad(5, ChordType.MIN7, "v7", listOf(41, 63, 65, 68, 72)),
            CuratedPad(3, ChordType.MIN9, "iv9", listOf(39, 61, 66, 70, 77)),
            CuratedPad(8, ChordType.DOM13, "♭VII13", listOf(44, 60, 63, 66, 70, 77)),
            CuratedPad(1, ChordType.MAJ9, "♭III∆9", listOf(49, 63, 68, 72, 77)),
            CuratedPad(5, ChordType.DOM7B9, "V7♭9", listOf(41, 63, 69, 72, 78)),
        )),
        CuratedBank("C14", "Atardecer", 9, Scale.MAJOR, listOf(
            CuratedPad(9, ChordType.MAJ9, "I∆9", listOf(45, 61, 64, 68, 71)),
            CuratedPad(1, ChordType.MIN7, "iii7", listOf(49, 61, 64, 68, 71)),
            CuratedPad(2, ChordType.MAJ9, "IV∆9", listOf(50, 61, 64, 66, 69)),
            CuratedPad(4, ChordType.SIX_NINE, "V6/9", listOf(40, 59, 61, 66, 68)),
            CuratedPad(6, ChordType.MIN9, "vi9", listOf(42, 57, 61, 64, 68)),
            CuratedPad(11, ChordType.MIN9, "ii9", listOf(47, 57, 62, 66, 73)),
            CuratedPad(4, ChordType.DOM13, "V13", listOf(40, 56, 59, 62, 66, 73)),
            CuratedPad(9, ChordType.SIX_NINE, "I6/9", listOf(45, 59, 64, 66, 73)),
        )),
        CuratedBank("C15", "Jazz menor", 7, Scale.NATURAL_MINOR, listOf(
            CuratedPad(7, ChordType.MIN9, "i9", listOf(43, 57, 62, 65, 70)),
            CuratedPad(0, ChordType.MIN9, "iv9", listOf(48, 58, 63, 67, 74)),
            CuratedPad(5, ChordType.DOM13, "♭VII13", listOf(41, 57, 60, 63, 67, 74)),
            CuratedPad(10, ChordType.MAJ9, "III∆9", listOf(46, 60, 65, 69, 74)),
            CuratedPad(3, ChordType.MAJ9, "VI∆9", listOf(39, 58, 65, 67, 74)),
            CuratedPad(9, ChordType.M7B5, "iiø", listOf(45, 60, 63, 67, 75)),
            CuratedPad(2, ChordType.DOM7SUS4, "V7sus", listOf(50, 60, 67, 69, 74)),
            CuratedPad(2, ChordType.DOM7B9, "V7♭9", listOf(50, 60, 66, 69, 75)),
        )),
        CuratedBank("C16", "Suspensión", 2, Scale.MAJOR, listOf(
            CuratedPad(2, ChordType.SIX_NINE, "I6/9", listOf(38, 64, 66, 69, 71)),
            CuratedPad(9, ChordType.DOM7SUS4, "V7sus", listOf(45, 64, 67, 69, 74)),
            CuratedPad(11, ChordType.MIN9, "vi9", listOf(47, 61, 66, 69, 74)),
            CuratedPad(7, ChordType.MAJ9, "IV∆9", listOf(43, 59, 66, 69, 74)),
            CuratedPad(4, ChordType.MIN9, "ii9", listOf(40, 62, 67, 71, 78)),
            CuratedPad(6, ChordType.MIN7, "iii7", listOf(42, 64, 69, 73, 78)),
            CuratedPad(7, ChordType.MAJ7, "IV∆", listOf(43, 66, 71, 74, 78)),
            CuratedPad(9, ChordType.SUS4, "Vsus", listOf(45, 64, 69, 74, 76)),
        )),
        // Acordes de "Última tocata" (Fa# menor), en el orden en que aparecen en la canción.
        CuratedBank("C17", "Última tocata", 6, Scale.NATURAL_MINOR, listOf(
            CuratedPad(6, ChordType.MIN, "i", listOf(42, 57, 61, 66, 69)),
            CuratedPad(2, ChordType.MAJ, "VI", listOf(38, 54, 57, 62, 66)),
            CuratedPad(1, ChordType.MIN, "v", listOf(37, 56, 61, 64, 68)),
            CuratedPad(8, ChordType.MIN, "ii", listOf(44, 59, 63, 68, 71)),
            CuratedPad(11, ChordType.MAJ, "IV", listOf(35, 54, 59, 63, 66)),
            CuratedPad(7, ChordType.MAJ, "♭II", listOf(43, 59, 62, 67, 71)),
            CuratedPad(9, ChordType.MAJ, "III", listOf(45, 61, 64, 69, 73)),
            CuratedPad(6, ChordType.MAJ, "I", listOf(42, 58, 61, 66, 70)),
        )),
    )

    fun byId(id: String): CuratedBank? = all.firstOrNull { it.id == id }

    /** Pads del banco en [tonic]: dos filas de 4, con el voicing ya trasladado. */
    fun rows(bank: CuratedBank, tonic: Int): List<List<PadDef>> {
        val d = bank.shiftTo(tonic)
        val pads = bank.pads.mapIndexed { i, p ->
            PadDef("${bank.id}_$i", Chord(Math.floorMod(p.root + d, 12), p.type), p.roman, p.voicing.map { it + d })
        }
        return pads.chunked(4)
    }
}
