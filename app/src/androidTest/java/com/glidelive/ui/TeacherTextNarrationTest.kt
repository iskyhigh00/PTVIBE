package com.glidelive.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.EmbeddedPocketPack
import com.glidelive.princess.NarrationPack
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.WavIo
import com.glidelive.stems.AudioDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class TeacherTextNarrationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shown(label: String) = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private val evidence: File
        get() = File(rule.activity.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun capture(label: String) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(evidence, "educadora-texto-$label.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun waitFor(step: String, timeout: Long = 15_000, ready: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = timeout, condition = ready)
        } catch (failure: Throwable) {
            runCatching { capture("fallo") }
            throw AssertionError("No completó $step en narración con Supertonic.", failure)
        }
    }

    private fun selectVoice(speaker: Int) {
        if (EmbeddedPocketPack.supportsDevice()) {
            if (shown("Opciones de voz")) rule.onNodeWithText("Opciones de voz").performScrollTo().performClick()
            rule.onNodeWithText("Supertonic").performScrollTo().performClick()
        }
        rule.onNodeWithText("· Supertonic", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Voz $speaker").performClick()
    }

    private fun addLine(vm: PrincessViewModel, text: String) {
        val generate = "Generar y escuchar"
        val add = "Agregar al timeline"
        rule.onNode(hasSetTextAction()).performTextReplacement(text)
        rule.onNodeWithText(generate).assertIsDisplayed().assertIsEnabled().performClick()
        val addButton = rule.onNodeWithText(add)
        waitFor("sintetizar el parlamento «$text»", 120_000) { addButton.isDisplayed() }
        addButton.assertIsEnabled().performClick()
        waitFor("guardar el parlamento «$text»") {
            vm.ui.value.current?.cues?.any { it.type == CueType.VOICE && it.text == text } == true
        }
        rule.onNodeWithText(generate).assertIsDisplayed()
    }

    @Test fun generaDialogoEnEspanolYExportaElCuentoConAmbiente() {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        val chooseStories = "🎙 Hacer un cuento con mi voz y sonidos"
        rule.waitForIdle()
        if (shown(chooseStories)) rule.onNodeWithText(chooseStories).performClick()
        val backToStories = "Volver a Mis cuentos"
        if (rule.onAllNodesWithContentDescription(backToStories).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription(backToStories).performClick()
        }
        rule.onNode(hasText("Empezar un cuento", substring = true) or hasText("Nuevo cuento", substring = true)).performClick()
        waitFor("crear cuento") { vm.ui.value.current != null && !vm.ui.value.creating }
        rule.onNodeWithContentDescription("Quién habla:", substring = true).performClick()
        rule.onNodeWithText("Personaje nuevo", substring = true).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Zorro")
        rule.onNodeWithText("Agregar").performClick()
        waitFor("crear el personaje del zorro") { vm.ui.value.current?.characters?.any { it.name == "Zorro" } == true }
        rule.onNodeWithText("✎ Escribir").assertIsDisplayed().performClick()
        rule.onNodeWithText("Crear una voz").assertIsDisplayed()
        val narrator = checkNotNull(vm.ui.value.current).characters.first().name
        rule.onNodeWithText("Personaje: Zorro").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText(narrator).performClick()

        val opening = "El zorro encendió su farol, pero la lluvia lo apagó."
        val reply = "¿Quién anda ahí? ¡No veo ni mis patas!"
        val ending = "Una luciérnaga lo guio a casa. Desde esa noche, el zorro dejó una luz en su ventana."
        rule.onNode(hasSetTextAction()).performTextReplacement(opening)
        if (EmbeddedPocketPack.supportsDevice() && shown("Opciones de voz")) {
            rule.onNodeWithText("Opciones de voz").performScrollTo().performClick()
            rule.onNodeWithText("Supertonic").performScrollTo().performClick()
        }

        val download = "Descargar voces (129 MB)"
        val generate = "Generar y escuchar"
        val needsPack = !NarrationPack.isReady(rule.activity)
        if (needsPack) {
            capture("1-descarga")
            rule.onNodeWithText(download).assertIsDisplayed().assertIsEnabled().performClick()
            waitFor("descargar y verificar el modelo desde la app", 210_000) {
                shown(generate) || shown(download)
            }
            if (!NarrationPack.isReady(rule.activity)) {
                capture("fallo-descarga")
                throw AssertionError("La descarga del modelo no se instaló. Revisar disponibilidad de red y captura.")
            }
        }

        addLine(vm, opening)
        rule.onNodeWithText("Personaje: $narrator").performScrollTo().performClick()
        rule.onNodeWithText("Zorro").performClick()
        selectVoice(6)
        addLine(vm, reply)
        rule.onNodeWithText("Personaje: Zorro").performScrollTo().performClick()
        rule.onNodeWithText(narrator).performClick()
        addLine(vm, ending)
        rule.onNodeWithText("Volver").performClick()

        val project = checkNotNull(vm.ui.value.current)
        val persisted = checkNotNull(PrincessLibrary.load(rule.activity, project.id))
        val voices = persisted.cues.filter { it.type == CueType.VOICE }
        assertEquals(listOf(opening, reply, ending), voices.map { it.text })
        assertEquals(2, voices.map { it.characterId }.distinct().size)
        val dir = PrincessLibrary.dir(rule.activity, persisted)
        for (cue in voices) {
            val audio = checkNotNull(WavIo.read(File(dir, checkNotNull(cue.file))))
            assertTrue("Duración de «${cue.text}»: ${audio.seconds}", audio.seconds in 1.0..16.0)
            assertTrue("Parlamento silencioso", audio.pcm.any { abs(it.toInt()) > 1500 })
        }
        File(dir, checkNotNull(voices.first().file)).copyTo(File(evidence, "educadora-supertonic.wav"), overwrite = true)
        File(evidence, "educadora-supertonic.txt").writeText(
            "$opening\n$reply\n$ending\nDos voces de Supertonic generadas en Android. Modelo descargado en esta ejecución: $needsPack.\n"
        )

        rule.runOnUiThread { vm.setCursor(1.0) }
        rule.onNodeWithText("🔊 Sonido").performClick()
        rule.onNodeWithText("Naturaleza", substring = true).performClick()
        rule.onNodeWithContentDescription("Poner Lluvia en la línea roja").performScrollTo().performClick()
        waitFor("agregar lluvia") { vm.ui.value.current?.cues?.any { it.type == CueType.SFX } == true }
        rule.onNodeWithText("Listo").performClick()
        rule.runOnUiThread { vm.setCursor(0.0) }
        rule.onNodeWithText("🎵 Música").performClick()
        rule.onNodeWithContentDescription("Poner Bosque de noche en la línea roja").performScrollTo().performClick()
        waitFor("agregar música") { vm.ui.value.current?.cues?.any { it.type == CueType.MUSIC } == true }
        rule.onNodeWithText("Listo").performClick()
        capture("3-timeline")

        val mixed = checkNotNull(vm.ui.value.current)
        rule.onNodeWithContentDescription("Enviar o guardar el cuento").performClick()
        rule.onNodeWithText("Guardar el cuento en mi teléfono").performClick()
        waitFor("exportar el cuento", 35_000) {
            vm.ui.value.notice?.startsWith("Guardé tu cuento") == true || vm.ui.value.error != null
        }
        assertTrue(vm.ui.value.error.orEmpty(), vm.ui.value.error == null)
        val exported = checkNotNull(File(dir, "enviar").listFiles()?.singleOrNull { it.extension == "m4a" })
        val mix = AudioDecoder.decode(exported)
        assertEquals(2, mix.channels)
        assertEquals(DramaRenderer.totalSeconds(mixed) + 0.5, mix.seconds, 0.20)
        exported.copyTo(File(evidence, "el-farol-del-zorro.m4a"), overwrite = true)
    }
}
