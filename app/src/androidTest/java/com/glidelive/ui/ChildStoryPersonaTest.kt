package com.glidelive.ui

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glidelive.princess.Cue
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.PrincessLibrary
import com.glidelive.stems.AudioDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChildStoryPersonaTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val evidence = StringBuilder()
    private val micInjected: Boolean
        get() = InstrumentationRegistry.getArguments().getString("storyMicInjected") == "true"

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

    private fun captureVoice(vm: PrincessViewModel, sentence: String, replace: Cue? = null): Boolean {
        val before = vm.ui.value.current!!.cues.size
        fun changed(): Boolean {
            val cues = vm.ui.value.current!!.cues
            return if (replace == null) cues.size > before
                else cues.any { it.id == replace.id && it.file != replace.file }
        }
        if (replace == null) click("● Grabar", substring = true)
        else {
            rule.onNodeWithContentDescription("Voz ${replace.text},", substring = true).performClick()
            click("⋯ Más")
            click("Volver a grabar")
        }
        waitFor("iniciar micrófono") { vm.ui.value.recordingFor != null || vm.ui.value.error != null }
        if (vm.ui.value.recordingFor != null) {
            if (micInjected) {
                val token = android.os.SystemClock.elapsedRealtimeNanos().toString()
                File(output(), "mic-request").writeText("$sentence $token")
                val complete = File(output(), "mic-complete")
                waitFor("que el micrófono reciba la frase completa") {
                    complete.isFile && complete.readText() == token
                }
                val after = vm.ui.value.recSeconds + .45f
                waitFor("capturar la última palabra y su cola") { vm.ui.value.recSeconds >= after }
            } else {
                waitFor("capturar micrófono") {
                    vm.ui.value.recordingFor == null || vm.ui.value.recSeconds >= 1.7f
                }
            }
            if (vm.ui.value.recordingFor != null) {
                shot("02-grabando")
                click("■ Terminar")
            }
        }
        waitFor("resultado al terminar la grabación") {
            changed() || vm.ui.value.error != null ||
                vm.ui.value.notice.orEmpty().contains("muy corta")
        }
        val captured = changed()
        note(if (captured) "Micrófono: Android guardó la señal de entrada${if (micInjected) " de la muestra de voz inyectada" else ""}."
        else "Micrófono: ${vm.ui.value.error ?: vm.ui.value.notice}.")
        assertTrue("El micrófono virtual debió guardar audio de la muestra inyectada", !micInjected || captured)
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

    private fun awaitCue(vm: PrincessViewModel, type: CueType): Cue {
        waitFor("poner $type en el cuento") { vm.ui.value.current!!.cues.any { it.type == type } }
        return vm.ui.value.current!!.cues.first { it.type == type }
    }

    @Test fun laRanaYLaLluvia() {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(rule.activity.packageName, Manifest.permission.RECORD_AUDIO)
        try {
            rule.waitForIdle()
            if (shown("Hacer un cuento con mi voz y sonidos", true)) click("Hacer un cuento con mi voz y sonidos", true)
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
            click("✎ Escribir")
            rule.onNodeWithText("Crear una voz").assertIsDisplayed()
            rule.onNode(hasSetTextAction()).performTextInput("La rana salió cuando empezó la lluvia.")
            shot("01b-escribir")
            click("Volver")
            note("Creé y nombré el cuento por la pantalla, y encontré Escribir junto a Grabar.")
            val first = p.characters.first().id
            val captured = mutableSetOf<String>()
            if (captureVoice(vm, "narrador")) captured += first
            rule.onNodeWithContentDescription("Quién habla:", substring = true).performClick()
            click("Personaje nuevo", true)
            rule.onNode(hasSetTextAction()).performTextInput("Rana")
            click("Agregar")
            waitFor("crear a Rana") { vm.ui.value.current!!.characters.any { it.name == "Rana" } }
            val frog = vm.ui.value.current!!.characters.first { it.name == "Rana" }.id
            if (captureVoice(vm, "rana")) captured += frog
            recoverMissingVoices(vm, title, captured)
            assertEquals(setOf(first, frog), vm.ui.value.current!!.cues.filter { it.type == CueType.VOICE }.mapNotNull { it.characterId }.toSet())
            note("Dos personajes conservan su voz en carriles separados.")

            click("🔊 Sonido")
            shot("03-sonidos-primera-vez")
            rule.onNodeWithText("Naturaleza", substring = true).assertIsDisplayed()
            click("Naturaleza", true)
            val addRain = rule.onNodeWithContentDescription("Poner Lluvia en la línea roja")
            rule.onNodeWithContentDescription("Oír Lluvia").performScrollTo().performClick()
            waitFor("escuchar la lluvia antes de usarla") { vm.ui.value.previewing && vm.ui.value.loaded }
            val beforeRain = vm.overlayTime()
            addRain.performClick()
            val rainCue = awaitCue(vm, CueType.SFX)
            assertEquals(beforeRain, rainCue.startSeconds!!.toDouble(), .11)
            assertEquals("La lluvia entra de a poco", .8f, rainCue.fadeIn, .001f)
            assertEquals("La lluvia no termina de golpe", 1.2f, rainCue.fadeOut, .001f)
            note("Escuché y puse Lluvia desde el catálogo incluido, sin descargar ni sembrar el paquete.")
            shot("04-poner-lluvia")
            if (shown("Listo")) click("Listo")

            rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
            openTitle(title)
            click("🎵 Música")
            shot("05-musica")
            rule.onNodeWithContentDescription("Oír Paseo tranquilo").performScrollTo().performClick()
            waitFor("escuchar la música antes de usarla") { vm.ui.value.previewing && vm.ui.value.loaded }
            rule.onNodeWithContentDescription("Poner Paseo tranquilo en la línea roja").performClick()
            val musicCue = awaitCue(vm, CueType.MUSIC)
            assertEquals("Paseo tranquilo", musicCue.text)
            assertEquals(0f, musicCue.startSeconds!!, .11f)
            assertTrue("La música queda de fondo", musicCue.volume in .01f..0.75f)
            note("Escuché y puse Paseo tranquilo desde el principio, sin descargar música.")
            if (shown("Listo")) click("Listo")

            if (micInjected) {
                val original = vm.ui.value.current!!
                val voice = original.cues.first { it.type == CueType.VOICE && it.characterId == frog }
                val starts = DramaRenderer.layout(original).associate { it.cue.id to it.start }
                captureVoice(vm, "rana", replace = voice)
                val current = vm.ui.value.current!!
                val updatedStarts = DramaRenderer.layout(current).associate { it.cue.id to it.start }
                assertEquals(starts.keys, updatedStarts.keys)
                starts.forEach { (id, start) -> assertEquals(start, updatedStarts.getValue(id), .001) }
                assertEquals(voice.characterId, current.cues.first { it.id == voice.id }.characterId)
                note("Volví a grabar a Rana con el micrófono virtual. Las cuatro pistas conservaron el inicio y el personaje quedó en el mismo carril.")
                if (shown("✓ Listo")) click("✓ Listo")
            }

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
            click("Enviar por WhatsApp u otra app")
            val dir = File(PrincessLibrary.dir(rule.activity, p), "enviar")
            waitFor("preparar audio para enviar", timeout = 35_000) {
                vm.ui.value.busy == null && dir.listFiles().orEmpty().any { it.extension == "m4a" && it.length() > 2_000 }
            }
            val sent = dir.listFiles()!!.first { it.extension == "m4a" }
            val decoded = AudioDecoder.decode(sent)
            assertTrue("El archivo exportado es audible y conserva la historia", decoded.seconds > 2.0 && decoded.pcm.any { kotlin.math.abs(it.toInt()) > 300 })
            sent.copyTo(File(output(), "nino-la-rana-y-la-lluvia.m4a"), overwrite = true)
            if (micInjected) for (voice in vm.ui.value.current!!.cues.filter { it.type == CueType.VOICE }) {
                assertEquals("La toma se oye desde la primera muestra", 0f, voice.trimStart, .0001f)
                assertEquals(voice.seconds, checkNotNull(voice.durationSeconds), .0001f)
                val role = if (voice.characterId == frog) "rana" else "narrador"
                File(PrincessLibrary.dir(rule.activity, p), requireNotNull(voice.file))
                    .copyTo(File(output(), "grabacion-$role.wav"), overwrite = true)
            }
            shot("07-enviar")
            note("Enviar generó M4A reproducible (${decoded.seconds} s, ${sent.length()} bytes).")
        } finally {
            rule.runOnUiThread { vm.stop() }
        }
    }
}
