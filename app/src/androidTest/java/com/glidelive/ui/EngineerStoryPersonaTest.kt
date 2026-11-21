package com.glidelive.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.glidelive.princess.Character
import com.glidelive.princess.Cue
import com.glidelive.princess.CueType
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.SoundCatalog
import com.glidelive.princess.SoundStore
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import com.glidelive.stems.Decoded
import com.glidelive.ui.components.AppBackground
import com.glidelive.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class EngineerStoryPersonaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun editsTwoCharactersAndSavesTheMixedStory() {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        val ctx = rule.activity
        val title = "Bosque de prueba ${System.nanoTime()}"
        val fixtures = mutableListOf<File>()
        val effect = preparePackFixtures(fixtures)
        vm.refreshSounds()
        rule.setContent {
            AppTheme {
                AppBackground {
                    Box(Modifier.fillMaxSize()) { StoryScreen(vm, onNarrate = {}) }
                }
            }
        }
        rule.runOnUiThread { vm.newProject(title) }
        rule.waitUntil(10_000) { vm.ui.value.current?.title == title && vm.ui.value.hasSfx && vm.ui.value.musicCount > 0 }
        val project = checkNotNull(vm.ui.value.current)
        val folder = PrincessLibrary.dir(ctx, project)
        // Defined audio signals make both speech tracks, effect, and bed individually measurable
        // after export. The fixtures stand in for two takes already recorded by the user.
        tone(File(folder, "narrador.wav"), 3, 440.0)
        tone(File(folder, "zorro.wav"), 3, 660.0)
        val fox = Character("zorro", "Zorro", 0xFF60A5FA)
        rule.runOnUiThread {
            vm.edit { p -> p.copy(
                characters = p.characters + fox,
                cues = listOf(
                    Cue("narrador", CueType.VOICE, p.characters.first().id, "Había una vez", "narrador.wav", 3f, 0f, startSeconds = 1f),
                    Cue("zorro", CueType.VOICE, fox.id, "Entró el zorro", "zorro.wav", 3f, 0f, startSeconds = 5f),
                ),
            ) }
        }
        rule.waitUntil(10_000) { vm.ui.value.current?.cues?.size == 2 }
        try {
            rule.onNodeWithText("🎵 Música").performClick()
            rule.onNodeWithContentDescription("Poner ${SoundCatalog.music.first().label} en la línea roja")
                .performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.ui.value.current?.cues?.any { it.type == CueType.MUSIC } == true }
            rule.onNodeWithText("Listo").performClick()

            // Navigate using the same time ruler action exposed to TalkBack.
            repeat(6) {
                rule.onNode(hasContentDescription("Regla de tiempo", substring = true))
                    .performCustomAccessibilityActionWithLabel("Línea roja un segundo después")
            }
            assertEquals(6.0, vm.ui.value.cursor, 0.01)
            rule.onNodeWithText("🔊 Sonido").performClick()
            rule.onNodeWithContentDescription("Poner Gato en la línea roja").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.ui.value.current?.cues?.any { it.type == CueType.SFX } == true }
            rule.onNodeWithText("Listo").performClick()
            fun effectStart() = vm.ui.value.current!!.cues.first { it.type == CueType.SFX }.startSeconds
            assertEquals(6f, checkNotNull(effectStart()), 0.01f)
            rule.onNode(hasContentDescription("Efecto Gato", substring = true)).performScrollTo().performClick()
            rule.onNodeWithText("↔ Mover").performClick()
            rule.onNodeWithText("1 s después ▶").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { effectStart() == 7f }
            rule.onNodeWithContentDescription("Deshacer").performClick()
            rule.waitUntil(10_000) { effectStart() == 6f }
            rule.onNodeWithContentDescription("Rehacer").performClick()
            rule.waitUntil(10_000) { effectStart() == 7f }
            shot("ingeniero-1-edicion")

            rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
            rule.onNodeWithContentDescription("Abrir $title").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.ui.value.current?.id == project.id && vm.ui.value.current?.cues?.size == 4 }
            val persisted = checkNotNull(PrincessLibrary.load(ctx, project.id))
            assertEquals(7f, checkNotNull(persisted.cues.first { it.type == CueType.SFX }.startSeconds), 0.01f)
            assertEquals(2, persisted.cues.filter { it.type == CueType.VOICE }.map { it.characterId }.distinct().size)
            persisted.cues.forEach { assertTrue("Audio ausente: ${it.text}", File(folder, checkNotNull(it.file)).length() > 44) }
            shot("ingeniero-2-reabierto")

            rule.onNodeWithContentDescription("Más opciones").performClick()
            rule.onNodeWithText("Guardar el cuento en mi teléfono", substring = true).performClick()
            rule.waitUntil(25_000) { vm.ui.value.notice?.startsWith("Guardé tu cuento") == true || vm.ui.value.error != null }
            assertTrue(vm.ui.value.error.orEmpty(), vm.ui.value.error == null)
            val exported = checkNotNull(File(folder, "enviar").listFiles()?.singleOrNull { it.extension == "m4a" })
            val decoded = AudioDecoder.decode(exported)
            assertEquals(2, decoded.channels)
            assertTrue("Duración ${decoded.seconds}", decoded.seconds in 8.35..8.80)
            assertTrue("Falta la primera voz", amplitude(decoded, 2.0, 0.4, 440.0) > 1000)
            assertTrue("Falta la segunda voz", amplitude(decoded, 5.4, 0.4, 660.0) > 1000)
            assertTrue("La música no suena dentro de la mezcla", amplitude(decoded, 4.4, 0.4, 220.0) > 300)
            assertTrue("La música debe bajar cuando habla el narrador", amplitude(decoded, 2.5, 0.4, 220.0) < amplitude(decoded, 4.4, 0.4, 220.0) * 0.75)
            assertTrue("El efecto no debería aparecer antes de moverlo", amplitude(decoded, 6.3, 0.4, 880.0) < 200)
            assertTrue("Falta el efecto movido de ${effect.name}", amplitude(decoded, 7.2, 0.4, 880.0) > 500)
            val files = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = ctx.contentResolver
            val saved = resolver.query(files, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(exported.name), null)?.use { result ->
                assertTrue("No se guardó el archivo en la biblioteca del teléfono", result.moveToFirst())
                assertEquals(exported.length(), result.getLong(1))
                ContentUris.withAppendedId(files, result.getLong(0))
            }
            assertTrue("No se encontró un audio en MediaStore", saved != null)
            saved?.let { resolver.delete(it, null, null) }
            val artifactDir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
            exported.copyTo(File(artifactDir, "ingeniero-mezcla.m4a"), overwrite = true)
        } finally {
            fixtures.forEach { it.delete() }
            vm.refreshSounds()
        }
    }

    private fun preparePackFixtures(files: MutableList<File>): File {
        val ctx = rule.activity
        val root = SoundStore.root(ctx)
        var effect: File? = null
        for (sound in SoundCatalog.groups.flatMap { it.sounds }) {
            val file = File(root, "esc50/${sound.id}/000-ingeniero.wav")
            file.parentFile!!.mkdirs()
            files += file
            tone(file, 1, 880.0)
            if (sound.id == "cat") effect = file
        }
        val music = File(SoundStore.musicDir(ctx), SoundCatalog.music.first().file)
        check(!music.exists()) { "Este test requiere el emulador sin paquetes descargados" }
        files += music
        tone(music, 2, 220.0)
        return checkNotNull(effect)
    }

    private fun tone(file: File, seconds: Int, hz: Double) {
        val rate = 44100
        val pcm = ShortArray(rate * seconds) { i -> (6000 * sin(2 * PI * hz * i / rate)).toInt().toShort() }
        WavIo.write(file, pcm, 1, rate)
    }

    private fun amplitude(audio: Decoded, start: Double, duration: Double, hz: Double): Double {
        val first = (start * audio.rate).toInt()
        val count = (duration * audio.rate).toInt()
        val omega = 2 * PI * hz / audio.rate
        var re = 0.0
        var im = 0.0
        for (i in 0 until count) {
            val sample = audio.pcm[(first + i) * audio.channels].toDouble()
            re += sample * cos(omega * i)
            im += sample * sin(omega * i)
        }
        return 2 * hypot(re, im) / count
    }

    private fun shot(name: String) {
        val dir = File(rule.activity.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
