package com.glidelive.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glidelive.princess.CueType
import com.glidelive.princess.EmbeddedPocketPack
import com.glidelive.princess.NarrationPack
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.WavIo
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

    @Test fun escribeDescargaGeneraYGuardaUnaFraseEnEspanol() {
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
        rule.onNodeWithText("✎ Escribir").assertIsDisplayed().performClick()
        rule.onNodeWithText("Crear una voz").assertIsDisplayed()

        val text = "En el bosque, la rana abrió los ojos. ¿Ya empezó a llover?"
        rule.onNode(hasSetTextAction()).performTextReplacement(text)
        if (EmbeddedPocketPack.supportsDevice()) {
            rule.onNodeWithText("Opciones de voz").performScrollTo().performClick()
            rule.onNodeWithText("Supertonic").performScrollTo().performClick()
        }

        val download = "Descargar voces (129 MB)"
        val generate = "Generar y escuchar"
        val add = "Agregar al timeline"
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
        rule.onNodeWithText(generate).assertIsDisplayed().assertIsEnabled().performClick()
        waitFor("sintetizar la frase", 120_000) { shown(add) || shown(generate) }
        rule.onNodeWithText(add).assertIsDisplayed().assertIsEnabled()
        capture("2-audio-listo")
        rule.onNodeWithText(add).performClick()
        waitFor("guardar la voz en el cuento") {
            vm.ui.value.current?.cues?.any { it.type == CueType.VOICE && it.text == text } == true
        }
        rule.onNodeWithText("Volver").performClick()
        val project = checkNotNull(vm.ui.value.current)
        val persisted = checkNotNull(PrincessLibrary.load(rule.activity, project.id))
        val cue = persisted.cues.single { it.type == CueType.VOICE && it.text == text }
        val output = File(PrincessLibrary.dir(rule.activity, persisted), checkNotNull(cue.file))
        val audio = checkNotNull(WavIo.read(output))
        assertTrue("Narración demasiado corta o demasiado larga: ${audio.seconds}", audio.seconds in 2.0..12.0)
        assertTrue("Archivo silencioso", audio.pcm.any { abs(it.toInt()) > 1500 })
        output.copyTo(File(evidence, "educadora-supertonic.wav"), overwrite = true)
        File(evidence, "educadora-supertonic.txt").writeText(
            "$text\nModelo Supertonic generado en Android: ${audio.seconds} s. " +
                "Descarga desde esta ejecución: $needsPack.\n"
        )
        capture("3-timeline")
    }
}
