package com.glidelive.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import com.glidelive.audio.NativeEngine
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.NarratedAudio
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.SoundStore
import com.glidelive.stems.AudioDecoder
import com.glidelive.ui.components.AppBackground
import com.glidelive.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class TeacherStoryPersonaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun releaseAudio() {
        rule.runOnUiThread { NativeEngine.stop() }
    }

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
        rule.runOnUiThread { NativeEngine.start() }
        rule.setContent {
            AppTheme {
                AppBackground {
                    val u by vm.ui.collectAsState()
                    var writingFor by remember { mutableStateOf<String?>(null) }
                    var script by remember { mutableStateOf(TextFieldValue()) }
                    StoryScreen(vm, Modifier.fillMaxSize(), onNarrate = { writingFor = it })
                    writingFor?.let { speakerId ->
                        u.current?.let { project ->
                            VoiceStudioScreen(project, vm, script, { script = it }, onClose = { writingFor = null },
                                modifier = Modifier.matchParentSize(), initialCharacterId = speakerId)
                        }
                    }
                }
            }
        }
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

        rule.onNodeWithText("✎ Escribir").assertIsDisplayed().performClick()
        rule.onNodeWithText("Crear una voz").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextReplacement("Había una vez una rana que esperaba la lluvia.")
        rule.onNodeWithText("Volver").performClick()
        rule.onNodeWithText("✎ Escribir").performClick()
        rule.onNodeWithText("Había una vez una rana que esperaba la lluvia.").assertExists()
        rule.onNodeWithText("Volver").performClick()

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

        // Sin descargas o fixtures de biblioteca: ambos salen de los assets
        // incluidos en el APK y se localizan por sus nombres en la interfaz.
        val musicTitle = "Paseo tranquilo"
        waitFor("efecto y música incluidos sin descarga") {
            SoundStore.clips(context, "rain").any { it.parentFile?.name == "included-v1" } &&
                SoundStore.musicFiles(context).any { (track, file) ->
                    track.label == musicTitle && file.parentFile?.name == "included-v1"
                } && vm.ui.value.soundGroups.any { group -> group.sounds.any { it.id == "rain" } }
        }
        rule.runOnIdle { vm.setCursor(2.0) }
        rule.onNodeWithText("🔊 Sonido").performClick()
        rule.onNodeWithText("Naturaleza", substring = true).performClick()
        rule.onNodeWithContentDescription("Oír Lluvia").performScrollTo().performClick()
        waitFor("reproducir la lluvia incluida") {
            vm.ui.value.previewing && vm.clock.value.playing && vm.clock.value.pos > 0.08
        }
        capture("2-lluvia-incluida")
        rule.onNodeWithContentDescription("Poner Lluvia en la línea roja").performScrollTo().performClick()
        waitFor("agregar lluvia") { vm.ui.value.current?.cues?.any { it.type == CueType.SFX } == true }
        val effect = checkNotNull(vm.ui.value.current).cues.first { it.type == CueType.SFX }
        assertEquals(2f, checkNotNull(effect.startSeconds), 0.01f)
        assertTrue(effect.duckUnderVoice)
        rule.onNodeWithText("Listo").performClick()

        rule.runOnIdle { vm.setCursor(0.0) }
        rule.onNodeWithText("🎵 Música").performClick()
        rule.onNodeWithContentDescription("Poner $musicTitle en la línea roja").performScrollTo().performClick()
        waitFor("agregar música") { vm.ui.value.current?.cues?.any { it.type == CueType.MUSIC } == true }
        rule.onNodeWithText("Listo").performClick()

        rule.onNodeWithContentDescription("Música $musicTitle", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("🔉 Volumen").performClick()
        rule.onNodeWithContentDescription("Volumen").performTouchInput { click(percentOffset(0.35f, 0.5f)) }
        waitFor("aplicar mezcla más suave") {
            vm.ui.value.current?.cues?.firstOrNull { it.type == CueType.MUSIC }?.volume?.let { it in 0.1f..0.49f } == true
        }
        rule.onNodeWithText("▶ Solo").assertIsDisplayed()
        rule.onNodeWithText("▶ Mezcla").performClick()
        waitFor("escuchar la mezcla en Volumen") {
            vm.clock.value.playing && !vm.ui.value.previewing && vm.clock.value.pos > 0.08
        }
        waitFor("terminar la escucha breve") { !vm.clock.value.playing }
        rule.onNodeWithText("✓ Listo").performClick()
        capture("3-mezcla")

        rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
        rule.onNodeWithContentDescription("Abrir $title").performScrollTo().performClick()
        waitFor("reabrir cuento montado") { vm.ui.value.current?.title == title && vm.ui.value.current?.cues?.size == 4 }
        val saved = checkNotNull(PrincessLibrary.load(context, project.id))
        assertEquals(4, saved.cues.size)
        assertEquals(2, saved.cues.filter { it.type == CueType.VOICE }.map { it.characterId }.distinct().size)
        assertTrue(saved.cues.single { it.type == CueType.SFX }.duckUnderVoice)
        assertTrue(saved.cues.all { checkNotNull(it.file).let { name -> File(PrincessLibrary.dir(context, saved), name).length() > 44 } })
        capture("4-reabierto")
        rule.onNodeWithText("▶ Escuchar", substring = true).performClick()
        waitFor("escuchar cuento preparado para aula") {
            vm.clock.value.playing && vm.clock.value.pos > 0.1
        }
        rule.onNodeWithText("■ Parar").performClick()

        rule.onNodeWithText("📤 Enviar").performClick()
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
        capture("5-guardado")
        File(evidence, "educadora-resultado.txt").writeText(
            "M4A estéreo decodificado: ${decoded.seconds} s, esperado: $expected s, ${output.length()} bytes.\n" +
                "Grabaciones de prueba: dos referencias WAV incluidas. Música y lluvia incluidas en el APK.\n"
        )
        context.contentResolver.delete(uri, null, null)
    }
}
