package com.glidelive.ui

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glidelive.audio.NativeEngine
import com.glidelive.princess.AacWriter
import com.glidelive.princess.Cue
import com.glidelive.princess.CueType
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.SoundCatalog
import com.glidelive.princess.SoundStore
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import com.glidelive.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class ChildStoryPersonaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val evidence = StringBuilder()

    private fun note(message: String) {
        evidence.appendLine(message)
        Log.i("CuentosPerfil10", message)
        File(output(), "perfil-nino.txt").writeText(evidence.toString())
    }

    private fun output() = File(rule.activity.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File(output(), "nino-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shown(label: String, substring: Boolean = false) =
        rule.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun click(label: String, substring: Boolean = false) {
        rule.onNodeWithText(label, substring = substring).assertIsDisplayed().performClick()
    }

    private fun waitFor(step: String, timeout: Long = 12_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = timeout, condition = condition)
        } catch (failure: Throwable) {
            runCatching { shot("error") }
            note("FALLO: $step")
            throw failure
        }
    }

    private fun openTitle(title: String) {
        rule.onNodeWithContentDescription("Abrir $title").performScrollTo().performClick()
    }

    private fun captureVoice(vm: PrincessViewModel): Boolean {
        val before = vm.ui.value.current!!.cues.size
        click("● Grabar", substring = true)
        waitFor("iniciar micrófono") { vm.ui.value.recordingFor != null || vm.ui.value.error != null }
        if (vm.ui.value.recordingFor != null) {
            waitFor("capturar un segundo de micrófono") { vm.ui.value.recSeconds >= 1f || vm.ui.value.recordingFor == null }
            if (vm.ui.value.recordingFor != null) {
                shot("02-grabando")
                click("Terminar", substring = true)
            }
        }
        waitFor("resultado al terminar la grabación") {
            vm.ui.value.current!!.cues.size > before || vm.ui.value.error != null ||
                vm.ui.value.notice.orEmpty().contains("muy corta")
        }
        val captured = vm.ui.value.current!!.cues.size > before
        note(if (captured) "Micrófono: tomó audio real de la entrada del emulador."
        else "Micrófono: ${vm.ui.value.error ?: vm.ui.value.notice}. Se sigue con WAV de ejemplo, identificado como fixture.")
        if (vm.ui.value.error != null && shown("Entendido")) click("Entendido")
        return captured
    }

    private fun recoverMissingVoices(vm: PrincessViewModel, title: String, captured: Set<String>) {
        val project = vm.ui.value.current!!
        val dir = PrincessLibrary.dir(rule.activity, project)
        project.characters.take(2).forEachIndexed { index, character ->
            if (character.id !in captured) {
                rule.activity.assets.open("pocket-voices/${if (index == 0) "grave" else "alegre"}.wav").use { input ->
                    File(dir, "grabando_${character.id}_perfilnino.wav").outputStream().use { input.copyTo(it) }
                }
            }
        }
        if (captured.size != 2) {
            rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
            openTitle(title)
            waitFor("recuperar las dos líneas de voz identificadas") { vm.ui.value.current!!.cues.count { it.type == CueType.VOICE } == 2 }
            note("Recuperación: las tomas WAV de ejemplo reaparecieron en el carril de su personaje.")
        }
    }

    /** Solo para la versión basal si exige una descarga completa. Sin llamadas que inserten clips en el editor. */
    private fun seedCatalog(vm: PrincessViewModel) {
        val ctx = rule.activity
        val random = Random(110)
        val rain = ShortArray(44_100) { random.nextInt(-3300, 3301).toShort() }
        SoundCatalog.groups.flatMap { it.sounds }.forEach { sound ->
            val dir = File(SoundStore.root(ctx), "esc50/${sound.id}").apply { mkdirs() }
            WavIo.write(File(dir, "perfilnino.wav"), rain, 1, 44_100)
        }
        val track = SoundCatalog.music.first { it.file.endsWith(".m4a") }
        val music = ShortArray(88_200 * 2) { i ->
            val time = (i / 2) / 44_100.0
            (1800.0 * sin(2 * PI * (if (time < 1) 293.66 else 369.99) * time)).toInt().toShort()
        }
        AacWriter(File(SoundStore.musicDir(ctx), track.file)).use { it.write(music) }
        vm.refreshSounds()
        waitFor("cargar el catálogo de prueba") { vm.ui.value.hasSfx && vm.ui.value.musicCount > 0 }
        note("Catálogo basal: fixture local con audio de lluvia y música. No se acredita descarga ni acceso inicial.")
    }

    private fun awaitCue(vm: PrincessViewModel, type: CueType): Cue {
        waitFor("poner $type en el cuento") { vm.ui.value.current!!.cues.any { it.type == type } }
        return vm.ui.value.current!!.cues.first { it.type == type }
    }

    @Test fun laRanaYLaLluvia() {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(rule.activity.packageName, Manifest.permission.RECORD_AUDIO)
        rule.runOnUiThread { NativeEngine.start() }
        try {
            rule.setContent {
                AppTheme { Box(Modifier.fillMaxSize()) { StoryScreen(vm, Modifier.fillMaxSize(), onNarrate = {}) } }
            }
            rule.waitForIdle()
            if (rule.onAllNodesWithContentDescription("Volver a Mis cuentos").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
            }
            shot("01-mis-cuentos")
            if (shown("Empezar un cuento", true)) click("Empezar un cuento", true) else click("Nuevo cuento", true)
            waitFor("abrir el cuento nuevo") { vm.ui.value.current != null && !vm.ui.value.creating }
            val p = vm.ui.value.current!!
            val title = "La rana y la lluvia ${System.nanoTime().toString().takeLast(5)}"
            rule.onNodeWithContentDescription("Cambiar el nombre del cuento: ${p.title}").performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(title)
            click("Guardar")
            waitFor("guardar el título") { vm.ui.value.current?.title == title }
            note("Guía visible: creé el cuento y le puse nombre por la pantalla.")
            val first = p.characters.first().id
            val captured = mutableSetOf<String>()
            if (captureVoice(vm)) captured += first
            rule.onNodeWithContentDescription("Quién habla:", substring = true).performClick()
            click("Personaje nuevo", true)
            rule.onNode(hasSetTextAction()).performTextInput("Rana")
            click("Agregar")
            waitFor("crear a Rana") { vm.ui.value.current!!.characters.any { it.name == "Rana" } }
            val frog = vm.ui.value.current!!.characters.first { it.name == "Rana" }.id
            if (captureVoice(vm)) captured += frog
            recoverMissingVoices(vm, title, captured)
            assertEquals(setOf(first, frog), vm.ui.value.current!!.cues.filter { it.type == CueType.VOICE }.mapNotNull { it.characterId }.toSet())
            note("Dos personajes conservan su voz en carriles separados.")

            click("🔊 Sonido")
            shot("03-sonidos-primera-vez")
            val noSounds = !shown("Naturaleza", true)
            if (noSounds) {
                note("Primera vez sin pack: no puedo escoger lluvia directamente.")
                click("Listo")
                seedCatalog(vm)
                click("🔊 Sonido")
            }
            click("Naturaleza", true)
            val addRain = rule.onNodeWithContentDescription("Poner Lluvia en la línea roja")
            rule.onNodeWithContentDescription("Oír Lluvia").performScrollTo().performClick()
            waitFor("escuchar la lluvia antes de usarla") { vm.ui.value.previewing && vm.ui.value.loaded }
            val beforeRain = vm.overlayTime()
            addRain.performClick()
            val rainCue = awaitCue(vm, CueType.SFX)
            assertEquals(beforeRain, rainCue.startSeconds!!.toDouble(), .11)
            note("Escuché y puse Lluvia en la línea roja (${if (noSounds) "fixture" else "catálogo sin sembrar"}).")
            shot("04-poner-lluvia")
            if (shown("Listo")) click("Listo")

            click("🎵 Música")
            shot("05-musica")
            val track = rule.onAllNodes(hasContentDescription("Poner ", substring = true)).fetchSemanticsNodes()
            assertTrue("No encontré música que pudiera poner", track.isNotEmpty())
            rule.onAllNodes(hasContentDescription("Oír ", substring = true))[0].performScrollTo().performClick()
            waitFor("escuchar la música antes de usarla") { vm.ui.value.previewing && vm.ui.value.loaded }
            rule.onAllNodes(hasContentDescription("Poner ", substring = true))[0].performClick()
            val musicCue = awaitCue(vm, CueType.MUSIC)
            assertTrue("La música de fondo se superpone con la voz", musicCue.volume in .01f..0.75f)
            note("Música de fondo: vista previa y puesta desde la pantalla.")
            if (shown("Listo")) click("Listo")

            rule.onNodeWithContentDescription("Efecto Lluvia", substring = true).performClick()
            click("Quitar", true)
            waitFor("quitar la lluvia") { vm.ui.value.current!!.cues.none { it.id == rainCue.id } }
            rule.onNodeWithContentDescription("Deshacer").performClick()
            waitFor("deshacer y recuperar la lluvia") { vm.ui.value.current!!.cues.any { it.id == rainCue.id } }
            note("Quité la lluvia y deshice el error sin perderla.")

            val expectedIds = vm.ui.value.current!!.cues.map { it.id }.toSet()
            waitFor("guardar todas las pistas en el almacenamiento") {
                PrincessLibrary.load(rule.activity, p.id)?.cues?.map { it.id }?.toSet() == expectedIds
            }
            rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
            openTitle(title)
            waitFor("reabrir el cuento con los cuatro audios") { vm.ui.value.current?.cues?.map { it.id }?.toSet() == expectedIds }
            shot("06-cuento-reabierto")
            click("▶ Escuchar", true)
            waitFor("reproducir la mezcla del cuento") { vm.ui.value.loaded && vm.clock.value.playing && vm.clock.value.pos > 0.1 }
            click("■ Parar")
            note("Reabrí y escuché cuento con dos voces, efecto y música.")

            click("📤 Enviar", true)
            val dir = File(PrincessLibrary.dir(rule.activity, p), "enviar")
            waitFor("preparar audio para enviar", timeout = 35_000) {
                vm.ui.value.busy == null && dir.listFiles().orEmpty().any { it.extension == "m4a" && it.length() > 2_000 }
            }
            val sent = dir.listFiles()!!.first { it.extension == "m4a" }
            val decoded = AudioDecoder.decode(sent)
            assertTrue("El archivo exportado es audible y conserva la historia", decoded.seconds > 2.0 && decoded.pcm.any { kotlin.math.abs(it.toInt()) > 300 })
            sent.copyTo(File(output(), "nino-la-rana-y-la-lluvia.m4a"), overwrite = true)
            shot("07-enviar")
            note("Enviar generó M4A reproducible (${decoded.seconds} s, ${sent.length()} bytes).")
        } finally {
            rule.runOnUiThread { vm.stop(); NativeEngine.stop() }
        }
    }
}
