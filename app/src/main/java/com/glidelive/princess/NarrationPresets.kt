package com.glidelive.princess

enum class NarrationFamily(val label: String) {
    NARRATORS("Narradores"), CHILDREN("Niños y niñas"), ADULTS("Adultos"),
    ELDERS("Viejitos y viejitas"), STORY("Personajes de cuento"), BIRDS("Aves"), ANIMALS("Animales"),
}

enum class NarrationTexture(val label: String) {
    NATURAL("Natural"), BRIGHT("Brillante"), WARM("Cálida"),
    ROUGH("Áspera"), TREMBLING("Temblor suave"), VIBRANT("Vibrante"),
}

data class NarrationPreset(
    val id: String,
    val name: String,
    val family: NarrationFamily,
    val description: String,
    val speaker: Int,
    val pace: Float,
    val pitch: Int,
    val texture: NarrationTexture = NarrationTexture.NATURAL,
)

/** Characterizations, not trained animal/child voices or an accent model.
 * voice.bin is built in filename order: F1–F5 (0–4), then M1–M5 (5–9).
 * Keep IDs stable: projects store the chosen ID on each character.
 */
object NarrationPresets {
    val all: List<NarrationPreset> = buildList {
        fun preset(id: String, name: String, family: NarrationFamily, description: String,
                   speaker: Int, pace: Float, pitch: Int, texture: NarrationTexture = NarrationTexture.NATURAL) {
            add(NarrationPreset(id, name, family, description, speaker, pace, pitch, texture))
        }
        val n = NarrationFamily.NARRATORS
        preset("narradora", "Narradora cercana", n, "Clara y tranquila para contar una historia.", 0, 0.96f, 0)
        preset("narrador", "Narrador cercano", n, "Registro medio y ritmo conversado.", 5, 0.98f, 0)
        preset("cuentacuentos", "Cuentacuentos", n, "Grave y pausado para presentar la obra.", 7, 0.86f, -2, NarrationTexture.WARM)
        preset("relatora", "Relatora entusiasta", n, "Brillante y rápida para una aventura.", 2, 1.14f, 1, NarrationTexture.BRIGHT)

        val c = NarrationFamily.CHILDREN
        preset("nino-curioso", "Niño curioso", c, "Agudo y despierto, con ritmo ligero.", 5, 1.10f, 5, NarrationTexture.BRIGHT)
        preset("nino-travieso", "Niño travieso", c, "Más rápido y chispeante.", 8, 1.22f, 6, NarrationTexture.BRIGHT)
        preset("nino-timido", "Niño tímido", c, "Suave, tranquilo y de tono alto.", 6, 0.90f, 4, NarrationTexture.WARM)
        preset("nina-curiosa", "Niña curiosa", c, "Ligera y clara para hacer preguntas.", 0, 1.08f, 4, NarrationTexture.BRIGHT)
        preset("nina-aventurera", "Niña aventurera", c, "Ágil y muy aguda para la acción.", 2, 1.20f, 5, NarrationTexture.BRIGHT)
        preset("nina-tranquila", "Niña tranquila", c, "Dulce y pausada.", 3, 0.90f, 3, NarrationTexture.WARM)

        val a = NarrationFamily.ADULTS
        preset("mujer-amable", "Mujer amable", a, "Cálida y reposada.", 1, 0.94f, 0, NarrationTexture.WARM)
        preset("hombre-amable", "Hombre amable", a, "Cercano y de tono medio.", 6, 0.95f, -1, NarrationTexture.WARM)
        preset("vecina-alegre", "Vecina alegre", a, "Conversación rápida y luminosa.", 4, 1.15f, 2, NarrationTexture.BRIGHT)
        preset("vecino-sereno", "Vecino sereno", a, "Grave y sin apuro.", 9, 0.89f, -2)
        preset("heroina", "Heroína decidida", a, "Clara, ágil y de registro medio.", 2, 1.08f, -1, NarrationTexture.BRIGHT)
        preset("heroe", "Héroe decidido", a, "Grave y de ritmo firme.", 7, 1.06f, -3)

        val e = NarrationFamily.ELDERS
        preset("abuelita-tierna", "Abuelita tierna", e, "Pausada, cálida y con temblor leve.", 1, 0.82f, -1, NarrationTexture.TREMBLING)
        preset("abuelito-sabio", "Abuelito sabio", e, "Grave, lento y con temblor leve.", 7, 0.80f, -4, NarrationTexture.TREMBLING)
        preset("abuelita-alegre", "Abuelita alegre", e, "Más ligera, con un pequeño temblor.", 4, 1.02f, 1, NarrationTexture.TREMBLING)
        preset("abuelito-jugueton", "Abuelito juguetón", e, "Ritmo vivo y un toque áspero.", 8, 1.04f, -1, NarrationTexture.ROUGH)
        preset("anciana-misteriosa", "Anciana misteriosa", e, "Baja y lenta para guardar secretos.", 3, 0.78f, -3, NarrationTexture.TREMBLING)
        preset("anciano-grunon", "Anciano gruñón", e, "Muy grave, lento y áspero.", 9, 0.83f, -5, NarrationTexture.ROUGH)

        val s = NarrationFamily.STORY
        preset("bandido-grave", "Bandido grave", s, "Oscuro y áspero, para un villano grande.", 7, 0.90f, -6, NarrationTexture.ROUGH)
        preset("bandido-escurridizo", "Bandido escurridizo", s, "Rápido y agudo, para un pillo de cuento.", 8, 1.24f, 2, NarrationTexture.ROUGH)
        preset("bandida-astuta", "Bandida astuta", s, "Registro bajo y ritmo ágil.", 3, 1.10f, -3, NarrationTexture.ROUGH)
        preset("bruja", "Bruja de cuento", s, "Aguda, áspera y lenta.", 4, 0.90f, 4, NarrationTexture.ROUGH)
        preset("mago", "Mago bondadoso", s, "Grave y cálido, con calma.", 6, 0.84f, -4, NarrationTexture.WARM)
        preset("hada", "Hada luminosa", s, "Aguda y brillante, con ritmo suave.", 0, 0.97f, 5, NarrationTexture.BRIGHT)
        preset("gigante", "Gigante", s, "Muy grave y lento.", 9, 0.76f, -6, NarrationTexture.WARM)
        preset("duende", "Duende travieso", s, "Agudo y vibrante, con mucho apuro.", 5, 1.26f, 6, NarrationTexture.VIBRANT)
        preset("rey", "Rey solemne", s, "Registro bajo y ritmo ceremonial.", 7, 0.85f, -3)
        preset("reina", "Reina serena", s, "Cálida, clara y pausada.", 1, 0.87f, -2, NarrationTexture.WARM)

        val b = NarrationFamily.BIRDS
        preset("loro", "Loro parlanchín", b, "Agudo, rápido y vibrante.", 8, 1.25f, 5, NarrationTexture.VIBRANT)
        preset("cotorra", "Cotorra conversadora", b, "Brillante, muy aguda y apurada.", 2, 1.30f, 6, NarrationTexture.BRIGHT)
        preset("chincol", "Chincol curioso", b, "Pequeño, ligero y brillante.", 0, 1.18f, 6, NarrationTexture.BRIGHT)
        preset("chucao", "Chucao del bosque", b, "Vibrante y de tono medio alto.", 5, 1.08f, 3, NarrationTexture.VIBRANT)
        preset("loica", "Loica alegre", b, "Clara y ligera para contar novedades.", 1, 1.12f, 4, NarrationTexture.BRIGHT)
        preset("picaflor", "Picaflor veloz", b, "La más rápida y aguda de las aves.", 4, 1.38f, 6, NarrationTexture.VIBRANT)
        preset("buho", "Búho sabio", b, "Grave y lento, de bosque nocturno.", 6, 0.80f, -5, NarrationTexture.WARM)
        preset("condor", "Cóndor solemne", b, "Muy grave y pausado.", 9, 0.84f, -6, NarrationTexture.WARM)
        preset("pato", "Pato de cuento", b, "Áspero, agudo y un poco apurado.", 8, 1.12f, 4, NarrationTexture.ROUGH)
        preset("gallina", "Gallina inquieta", b, "Aguda y vibrante, de ritmo rápido.", 3, 1.21f, 5, NarrationTexture.VIBRANT)

        val t = NarrationFamily.ANIMALS
        preset("vaca", "Vaca tranquila", t, "Baja, cálida y sin apuro.", 1, 0.80f, -6, NarrationTexture.WARM)
        preset("toro", "Toro fuerte", t, "Muy grave y áspero.", 7, 0.91f, -6, NarrationTexture.ROUGH)
        preset("perrito", "Perrito juguetón", t, "Ágil y brillante, de tono alto.", 5, 1.23f, 4, NarrationTexture.BRIGHT)
        preset("perro", "Perro guardián", t, "Grave y áspero, de ritmo firme.", 9, 1.02f, -4, NarrationTexture.ROUGH)
        preset("gata", "Gata elegante", t, "Ligera, cálida y pausada.", 3, 0.93f, 2, NarrationTexture.WARM)
        preset("gato", "Gato pícaro", t, "Ágil y de tono medio alto.", 8, 1.11f, 3, NarrationTexture.BRIGHT)
        preset("raton", "Ratón pequeño", t, "Muy agudo y rápido.", 0, 1.29f, 6, NarrationTexture.BRIGHT)
        preset("conejo", "Conejo nervioso", t, "Ligero y rápido, con temblor leve.", 2, 1.24f, 4, NarrationTexture.TREMBLING)
        preset("zorro", "Zorro astuto", t, "Ritmo ágil y un toque áspero.", 6, 1.10f, 1, NarrationTexture.ROUGH)
        preset("lobo", "Lobo de cuento", t, "Oscuro y áspero, de ritmo lento.", 7, 0.88f, -5, NarrationTexture.ROUGH)
        preset("oso", "Oso bonachón", t, "Muy grave y cálido, sin apuro.", 9, 0.78f, -6, NarrationTexture.WARM)
        preset("leon", "León imponente", t, "Grave y áspero para liderar la aventura.", 5, 0.94f, -5, NarrationTexture.ROUGH)
        preset("rana", "Rana de laguna", t, "Vibrante y baja, de ritmo moderado.", 8, 0.94f, -2, NarrationTexture.VIBRANT)
        preset("tortuga", "Tortuga paciente", t, "Muy lenta y cálida.", 3, 0.75f, -4, NarrationTexture.WARM)
    }

    val default: NarrationPreset get() = all.first()
    fun find(id: String?): NarrationPreset? = all.firstOrNull { it.id == id }
}
