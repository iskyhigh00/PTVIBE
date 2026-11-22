package com.glidelive.ui

object Pads {
    const val KICK = 0
    const val SNARE = 1
    const val RIM = 2
    const val HH_CLOSED = 3
    const val HH_OPEN = 4
    const val HH_PEDAL = 5
    const val TOM1 = 6
    const val TOM2 = 7
    const val FLOOR = 8
    const val CRASH = 9
    const val RIDE = 10
    const val BELL = 11
    const val SPLASH = 12
    const val GUIRO_SHORT = 13
    const val GUIRO_LONG = 14
    const val MARACAS = 15
    const val CONGA = 16
    const val QUINTO = 17
    const val TUMBA = 18
    const val BONGO = 19
    const val COWBELL = 20
    // Los IDs 21–28 de los combos ya se guardan en las asignaciones MIDI y de teclado.
    private const val FIRST_COMBO = 21
    const val COMBO_KICK_CRASH = FIRST_COMBO
    const val COMBO_KICK_RIDE = FIRST_COMBO + 1
    const val COMBO_KICK_HH = FIRST_COMBO + 2
    const val COMBO_SNARE_CRASH = FIRST_COMBO + 3
    const val COMBO_KICK_SNARE = FIRST_COMBO + 4
    const val COMBO_KICK_HH_OPEN = FIRST_COMBO + 5
    const val COMBO_KICK_BELL = FIRST_COMBO + 6
    const val COMBO_KICK_COWBELL = FIRST_COMBO + 7
    const val CRASH_DARK = 29
    const val CRASH_SHORT = 30
    const val COMBO_KICK_CRASH_SHORT = 31
    const val TOTAL = 32
    const val COUNT = 23

    private const val DARK_SOUND = 21
    private const val SHORT_SOUND = 22

    private val combos = mapOf(
        COMBO_KICK_CRASH to intArrayOf(KICK, CRASH),
        COMBO_KICK_RIDE to intArrayOf(KICK, RIDE),
        COMBO_KICK_HH to intArrayOf(KICK, HH_CLOSED),
        COMBO_SNARE_CRASH to intArrayOf(SNARE, CRASH),
        COMBO_KICK_SNARE to intArrayOf(KICK, SNARE),
        COMBO_KICK_HH_OPEN to intArrayOf(KICK, HH_OPEN),
        COMBO_KICK_BELL to intArrayOf(KICK, BELL),
        COMBO_KICK_COWBELL to intArrayOf(KICK, COWBELL),
        COMBO_KICK_CRASH_SHORT to intArrayOf(KICK, SHORT_SOUND),
    )

    private val singleSounds = Array(TOTAL) { pad ->
        intArrayOf(when (pad) {
            CRASH_DARK -> DARK_SOUND
            CRASH_SHORT -> SHORT_SOUND
            else -> pad
        })
    }

    /** IDs que recibe el motor nativo. */
    fun members(pad: Int): IntArray = combos[pad] ?: singleSounds[pad]
    fun isCombo(pad: Int) = pad in combos

    fun padForSound(sound: Int) = when (sound) {
        DARK_SOUND -> CRASH_DARK
        SHORT_SOUND -> CRASH_SHORT
        else -> sound
    }

    fun isCymbalSound(sound: Int) = sound in CRASH..SPLASH || sound in DARK_SOUND..SHORT_SOUND

    val names = arrayOf(
        "BOMBO", "CAJA", "AROS\n(rim)", "HI-HAT\nCERRADO", "HI-HAT\nABIERTO", "PEDAL\nHI-HAT",
        "TOM 1", "TOM 2", "TOM DE\nPISO", "CRASH", "RIDE", "CAMPANA\nRIDE", "SPLASH\n/ CHINA",
        "GÜIRO\ncorto", "GÜIRO\nlargo", "MARACAS", "CONGA", "QUINTO\n(alegre)", "TUMBA", "BONGÓ", "CAMPANA\n(cencerro)",
        "BOMBO\n+ CRASH", "BOMBO\n+ RIDE", "BOMBO\n+ HI-HAT", "CAJA\n+ CRASH",
        "BOMBO\n+ CAJA", "BOMBO\n+ HH ABIERTO", "BOMBO\n+ CAMPANA", "BOMBO\n+ CENCERRO",
        "CRASH\nOSCURO", "CRASH\nCORTO", "BOMBO\n+ CRASH CORTO",
    )

    /** Líneas de cada nombre, calculadas una sola vez (onDraw no debe asignar memoria). */
    val lines: Array<Array<String>> = Array(names.size) { names[it].split("\n").toTypedArray() }

    fun isCymbal(pad: Int) = pad in CRASH..SPLASH || pad == CRASH_DARK || pad == CRASH_SHORT
    fun isHat(pad: Int) = pad in HH_CLOSED..HH_PEDAL
    fun isPercussion(pad: Int) = pad in GUIRO_SHORT..COWBELL
}

