package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class NarrationPresetsTest {
    @Test fun everyPresetCanUseTheInstalledPackWithoutClampingItsSpeed() {
        assertTrue(NarrationPresets.all.size >= 50)
        for (preset in NarrationPresets.all) {
            assertTrue(preset.name, preset.speaker in 0 until NeuralNarrator.VOICES)
            assertTrue(preset.name, preset.pitch in -6..6)
            assertTrue(preset.name, preset.pace in 0.75f..1.4f)
            val engineSpeed = preset.pace / 2.0.pow(preset.pitch / 12.0)
            assertTrue("${preset.name}: $engineSpeed", engineSpeed in 0.7..1.7)
        }
    }

    @Test fun familiesOfferDistinctRecipesAndStableIds() {
        val presets = NarrationPresets.all
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { listOf(it.speaker, it.pace, it.pitch, it.texture) }.toSet().size)
        for (family in NarrationFamily.entries) assertTrue(presets.count { it.family == family } >= 4)
        assertNull(NarrationPresets.find("preset-from-a-future-version"))
        assertNull(NarrationPresets.find(null))
        assertEquals(NarrationPresets.default, NarrationPresets.find(NarrationPresets.default.id))
    }

    @Test fun characterPresetsAndNarratedTracksSurviveProjectRoundTrip() {
        val characters = listOf(
            Character("bird", "Chincol", 1L, narrationPresetId = "chincol"),
            Character("wolf", "Lobo", 2L, narrationPresetId = "lobo"),
        )
        val cues = characters.mapIndexed { i, c ->
            Cue("cue$i", CueType.VOICE, c.id, "¡Hola, vecina! ¿Cómo está?", "narracion_$i.wav", 2f)
        }
        val project = Project("test", "Bosque del sur", characters, cues)
        assertEquals(project, PrincessLibrary.parse(PrincessLibrary.toJson(project).toString()))
    }

    @Test fun oldProjectsWithoutPresetFieldRemainReadable() {
        val project = Project("old", "Obra antigua", listOf(Character("n", "Narrador", 1L)), emptyList())
        val json = PrincessLibrary.toJson(project)
        json.getJSONArray("characters").getJSONObject(0).remove("narrationPreset")
        assertEquals(project, PrincessLibrary.parse(json.toString()))
    }
}
