package com.glidelive.theory

/** Pad de acorde. [voicing]: notas MIDI fijas (bancos C1..C8); null = las elige el voice leading. */
data class PadDef(val id: String, val chord: Chord, val roman: String, val voicing: List<Int>? = null)

enum class Bank(val label: String, val curated: String? = null) {
    DIATONIC("Del tono"),
    II_V_I("Cadena ii–V–I"),
    C1("C1 · Nocturno", "C1"),
    C2("C2 · Amanecer", "C2"),
    C3("C3 · Cuarto menor", "C3"),
    C4("C4 · Soul", "C4"),
    C5("C5 · Cielo abierto", "C5"),
    C6("C6 · Gospel", "C6"),
    C7("C7 · Cine", "C7"),
    C8("C8 · Brillo", "C8"),
    C9("C9 · Lo-fi", "C9"),
    C10("C10 · Bossa", "C10"),
    C11("C11 · Melancolía", "C11"),
    C12("C12 · Épico", "C12"),
    C13("C13 · Neo soul", "C13"),
    C14("C14 · Atardecer", "C14"),
    C15("C15 · Jazz menor", "C15"),
    C16("C16 · Suspensión", "C16"),
    C17("C17 · Última tocata", "C17"),
}

object ChordBanks {
    /** Fila 0: tríadas (o la extensión elegida); fila 1: séptimas; fila 2: prestados. */
    fun diatonic(tonic: Int, scale: Scale, quality: ChordType?): List<List<PadDef>> {
        val n = minOf(scale.size, 7)
        val triads = (0 until n).map { d ->
            val base = Chord.diatonic(scale, tonic, d, seventh = false)
            val ch = if (quality != null) Chord(base.rootPc, quality) else base
            PadDef("A$d", ch, Chord.roman(d, ch.type))
        }
        val sevenths = (0 until n).map { d ->
            val ch = Chord.diatonic(scale, tonic, d, seventh = true)
            PadDef("B$d", ch, Chord.roman(d, ch.type) + "7")
        }
        val borrowed = listOf(
            Triple(1, ChordType.MAJ7, "♭II"),
            Triple(3, ChordType.MAJ7, "♭III"),
            Triple(5, ChordType.MIN7, "iv"),
            Triple(8, ChordType.MAJ7, "♭VI"),
            Triple(10, ChordType.DOM7, "♭VII"),
            Triple(7, ChordType.DOM7, "V7"),
            Triple(2, ChordType.DOM7, "II7"),
        ).mapIndexed { i, (off, type, rn) ->
            PadDef("C$i", Chord(Math.floorMod(tonic + off, 12), type), rn)
        }
        return listOf(triads, sevenths, borrowed)
    }

    /** Fm7 – B♭7 – E♭maj7 – A♭maj7 – Dm7(♭5) – G7(♭9) – Cm9 – C7(♭9) – Fm9, transpuesta por [transpose]. */
    fun iiVI(transpose: Int): List<List<PadDef>> {
        val chain = listOf(
            5 to ChordType.MIN7, 10 to ChordType.DOM7, 3 to ChordType.MAJ7, 8 to ChordType.MAJ7,
            2 to ChordType.M7B5, 7 to ChordType.DOM7B9, 0 to ChordType.MIN9, 0 to ChordType.DOM7B9,
            5 to ChordType.MIN9,
        )
        val romans = listOf("ii", "V", "I", "IV", "iiø", "V7♭9", "i9", "I7♭9", "iv9")
        val pads = chain.mapIndexed { i, (root, type) ->
            PadDef("P$i", Chord(Math.floorMod(root + transpose, 12), type), romans[i])
        }
        return listOf(pads.subList(0, 5), pads.subList(5, 9))
    }
}
