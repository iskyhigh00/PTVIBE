package com.glidelive.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.SoundCatalog
import com.glidelive.princess.SoundStore
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import com.glidelive.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class TeacherStoryPersonaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val evidence: File
        get() = File(rule.activity.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "educadora-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(12_000, condition)
        } catch (e: AssertionError) {
            throw AssertionError("No se completó: $description", e)
        }
    }

    private fun findSavedCuento(title: String): Uri? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        rule.activity.contentResolver.query(
            collection, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
            arrayOf("$title.m4a"), null,
        )?.use { result ->
            if (result.moveToFirst()) return ContentUris.withAppendedId(collection, result.getLong(0))
        }
        return null
    }

    @Test fun montaReabreYGuardaCuentoParaElAula() {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        rule.setContent { AppTheme { StoryScreen(vm, Modifier.fillMaxSize(), onNarrate = {}) } }
        rule.waitForIdle()
        if (rule.onAllNodesWithContentDescription("Volver a Mis cuentos").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
        }
        rule.onNode(hasText("Empezar un cuento", substring = true) or hasText("Nuevo cuento", substring = true)).performClick()
        waitFor("abrir cuento nuevo") { vm.ui.value.current != null && !vm.ui.value.creating }

        val title = "Rana y lluvia ${System.nanoTime().toString().takeLast(6)}"
        rule.onNodeWithContentDescription("Cambiar el nombre del cuento", substring = true).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(title)
        rule.onNodeWithText("Guardar").performClick()
        waitFor("guardar el nombre") { vm.ui.value.current?.title == title }
        capture("1-cuento-vacio")

        rule.onNodeWithContentDescription("Quién habla", substring = true).performClick()
        rule.onNodeWithText("Personaje nuevo", substring = true).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Rana")
        rule.onNodeWithText("Agregar").performClick()
        waitFor("crear personaje Rana") { vm.ui.value.current?.characters?.any { it.name == "Rana" } == true }

        // El emulador no recibe la actuación del docente por micrófono. Se utilizan
        // dos muestras habladas ya incluidas en GlideLive y se guardan por la API
        // normal de tomas. Esto prueba el montaje, no la grabación ni la síntesis.
        val project = checkNotNull(vm.ui.value.current)
        val context = rule.activity
        for ((index, sample) in listOf("grave", "alegre").withIndex()) {
            val source = File(context.cacheDir, "educadora-$sample.wav")
            context.assets.open("pocket-voices/$sample.wav").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val speech = AudioDecoder.decode(source)
            val character = if (index == 0) project.characters.first() else project.characters.first { it.name == "Rana" }
            val takeName = if (index == 0) "Inicio del cuento" else "Respuesta de Rana"
            runBlocking { vm.addNarration(project.id, character.id, NarratedAudio(takeName, source, speech.seconds.toFloat())) }
            source.delete()
        }
        waitFor("guardar las dos pistas habladas") { vm.ui.value.current?.cues?.size == 2 }
        val first = checkNotNull(vm.ui.value.current).cues.first()
        vm.moveCueTo(first.id, 3.0)
        val second = checkNotNull(vm.ui.value.current).cues[1]
        vm.moveCueTo(second.id, 17.0)
        waitFor("disponer los parlamentos de prueba") { vm.ui.value.current?.cues?.lastOrNull()?.startSeconds == 17f }

        // Archivos de prueba deterministas, cargados por el catálogo real. Un
        // archivo sentinela por categoría hace innecesaria la red en este ensayo.
        val created = mutableListOf<File>()
        val noise = Random(10)
        val rain = ShortArray(44_100) { noise.nextInt(-4_000, 4_000).toShort() }
        val categories = SoundCatalog.groups.flatMap { it.sounds }
        for (sound in categories) {
            val dir = File(SoundStore.root(context), "esc50/${sound.id}").apply { mkdirs() }
            val file = File(dir, "educadora-fixture.wav")
            WavIo.write(file, rain, 1, 44_100)
            created += file
        }
        val musicTrack = SoundCatalog.music.first()
        val backing = File(SoundStore.musicDir(context), musicTrack.file)
        if (!backing.exists()) {
            val melody = ShortArray(44_100 * 8) { frame ->
                val t = frame / 44_100.0
                val note = doubleArrayOf(523.25, 659.25, 587.33, 440.0)[(t / 2).toInt()]
                val env = minOf(1.0, t * 12.0, (8.0 - t) * 12.0)
                ((sin(2 * PI * note * t) * 2_800 + sin(2 * PI * 440 * t) * 700) * env).toInt().toShort()
            }
            WavIo.write(backing, melody, 1, 44_100)
            created += backing
        }

        try {
            vm.refreshSounds()
            waitFor("biblioteca de prueba") { vm.ui.value.hasSfx && vm.ui.value.musicCount > 0 }
            rule.runOnIdle { vm.setCursor(2.0) }
            rule.onNodeWithText("🔊 Sonido").performClick()
            rule.onNodeWithText("Naturaleza").performClick()
            rule.onNodeWithContentDescription("Oír Lluvia").performScrollTo().performClick()
            waitFor("cargar preescucha de lluvia") { vm.ui.value.loaded }
            rule.onNodeWithContentDescription("Poner Lluvia en la línea roja").performScrollTo().performClick()
            waitFor("agregar lluvia") { vm.ui.value.current?.cues?.any { it.type == CueType.SFX } == true }
            val effect = checkNotNull(vm.ui.value.current).cues.first { it.type == CueType.SFX }
            assertEquals(2f, checkNotNull(effect.startSeconds), 0.01f)
            rule.onNodeWithText("Listo").performClick()

            rule.runOnIdle { vm.setCursor(0.0) }
            rule.onNodeWithText("🎵 Música").performClick()
            rule.onNodeWithContentDescription("Poner ${musicTrack.label} en la línea roja").performScrollTo().performClick()
            waitFor("agregar música") { vm.ui.value.current?.cues?.any { it.type == CueType.MUSIC } == true }
            rule.onNodeWithText("Listo").performClick()

            val musicTitle = musicTrack.label.substringBefore(" ·")
            rule.onNodeWithContentDescription("Música $musicTitle", substring = true).performScrollTo().performClick()
            rule.onNodeWithText("🔉 Volumen").performClick()
            rule.onNodeWithContentDescription("Volumen").performTouchInput { click(percentOffset(0.35f, 0.5f)) }
            waitFor("aplicar mezcla más suave") {
                vm.ui.value.current?.cues?.firstOrNull { it.type == CueType.MUSIC }?.volume?.let { it in 0.1f..0.49f } == true
            }
            rule.onNodeWithText("✓ Listo").performClick()
            capture("2-mezcla")

            rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
            rule.onNodeWithContentDescription("Abrir $title").performScrollTo().performClick()
            waitFor("reabrir cuento montado") { vm.ui.value.current?.title == title && vm.ui.value.current?.cues?.size == 4 }
            val saved = checkNotNull(PrincessLibrary.load(context, project.id))
            assertEquals(4, saved.cues.size)
            assertEquals(2, saved.cues.filter { it.type == CueType.VOICE }.map { it.characterId }.distinct().size)
            assertTrue(saved.cues.all { checkNotNull(it.file).let { name -> File(PrincessLibrary.dir(context, saved), name).length() > 44 } })
            capture("3-reabierto")

            rule.onNodeWithText("Más ▾").performClick()
            rule.onNodeWithText("Guardar el cuento en mi teléfono", substring = true).performClick()
            waitFor("encontrar M4A guardado en Música") { findSavedCuento(title) != null || vm.ui.value.error != null }
            val uri = findSavedCuento(title)
            assertNotNull("M4A no guardado: ${vm.ui.value.error}; ${vm.exportFallback}", uri)
            val decoded = AudioDecoder.decode(context, checkNotNull(uri))
            val expected = DramaRenderer.totalSeconds(saved) + 0.5
            assertEquals(expected, decoded.seconds, 0.20)
            assertEquals(2, decoded.channels)
            assertTrue("La mezcla no tiene señal audible", decoded.pcm.any { abs(it.toInt()) > 2000 })
            val output = File(evidence, "educadora-cuento.m4a")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
            capture("4-guardado")
            context.contentResolver.delete(uri, null, null)
        } finally {
            created.forEach { it.delete() }
            vm.refreshSounds()
        }
    }
}
