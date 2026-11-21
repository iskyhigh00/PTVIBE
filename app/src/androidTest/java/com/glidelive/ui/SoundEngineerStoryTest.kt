package com.glidelive.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.glidelive.princess.CueType
import com.glidelive.princess.DramaRenderer
import com.glidelive.princess.NarrationPack
import com.glidelive.princess.PrincessLibrary
import com.glidelive.stems.AudioDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class SoundEngineerStoryTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val evidence: File
        get() = File(rule.activity.getExternalFilesDir(null), "shots/ingeniero-sonido").apply { mkdirs() }

    private fun shown(label: String, substring: Boolean = false) =
        rule.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun textButton(label: String, substring: Boolean = false) =
        rule.onNodeWithText(label, substring = substring)

    private fun click(label: String, substring: Boolean = false) =
        textButton(label, substring).assertIsDisplayed().performClick()

    private fun capture(label: String) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(evidence, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitFor(step: String, timeout: Long = 15_000, ready: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = timeout, condition = ready)
        } catch (error: Throwable) {
            runCatching { capture("fallo") }
            throw AssertionError("No se completó $step", error)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun positionAt(seconds: Int) {
        val ruler = rule.onNodeWithContentDescription("Regla de tiempo", substring = true)
        ruler.performCustomAccessibilityActionWithLabel("Línea roja al principio")
        repeat(seconds) {
            ruler.performCustomAccessibilityActionWithLabel("Línea roja un segundo después")
        }
    }

    private fun enterLine(vm: PrincessViewModel, line: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(line)
        click("Generar y escuchar")
        waitFor("generar «$line»", 120_000) { shown("Agregar al timeline") || shown("Generar y escuchar") }
        click("Agregar al timeline")
        waitFor("guardar «$line»") {
            vm.ui.value.current?.cues?.any { it.type == CueType.VOICE && it.text == line } == true
        }
        textButton("Generar y escuchar").assertIsDisplayed()
    }

    @Test fun montaEscuchaYExportaLaLuzDeLaEstacion() {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        val firstLine = "La lluvia borró el camino a la estación. Inés se tapó los oídos y esperó."
        val reply = "¿Oyes el reloj? Si lo seguimos, encontramos el andén."
        val ending = "Inés siguió el ritmo hasta ver la luz del último tren. Por fin estamos en casa."
        val title = "La luz de la estación ${System.nanoTime().toString().takeLast(5)}"
        try {
            rule.waitForIdle()
            if (shown("Hacer un cuento con mi voz y sonidos", true)) click("Hacer un cuento con mi voz y sonidos", true)
            val back = rule.onAllNodesWithContentDescription("Volver a Mis cuentos")
            if (back.fetchSemanticsNodes().isNotEmpty()) back[0].performClick()
            rule.onNode(hasText("Empezar un cuento", substring = true) or hasText("Nuevo cuento", substring = true)).performClick()
            waitFor("crear el cuento") { vm.ui.value.current != null && !vm.ui.value.creating }
            val initial = checkNotNull(vm.ui.value.current)
            rule.onNodeWithContentDescription("Cambiar el nombre del cuento: ${initial.title}").performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(title)
            click("Guardar")
            waitFor("nombrar el cuento") { vm.ui.value.current?.title == title }

            rule.onNodeWithContentDescription("Quién habla:", substring = true).performClick()
            click("Personaje nuevo", true)
            rule.onNode(hasSetTextAction()).performTextInput("Guía")
            click("Agregar")
            waitFor("crear personaje") { vm.ui.value.current?.characters?.any { it.name == "Guía" } == true }

            click("✎ Escribir")
            rule.onNode(hasSetTextAction()).performTextReplacement(firstLine)
            val download = "Descargar voces (129 MB)"
            if (!NarrationPack.isReady(rule.activity)) {
                click(download)
                waitFor("descargar el modelo en la app", 210_000) {
                    shown("Generar y escuchar") || shown(download)
                }
                assertTrue("Modelo Supertonic disponible", NarrationPack.isReady(rule.activity))
            }
            val narrator = checkNotNull(vm.ui.value.current).characters.first().name
            enterLine(vm, firstLine)
            textButton("Personaje: $narrator").performScrollTo().performClick()
            click("Guía")
            textButton("· Supertonic", substring = true).performScrollTo().performClick()
            textButton("Voz 6").performScrollTo().performClick()
            enterLine(vm, reply)
            textButton("Personaje: Guía").performScrollTo().performClick()
            click(narrator)
            enterLine(vm, ending)
            capture("01-voz")
            click("Volver")

            val project = checkNotNull(vm.ui.value.current)
            val voices = DramaRenderer.layout(project).filter { it.cue.type == CueType.VOICE }
            assertEquals(listOf(firstLine, reply, ending), voices.map { it.cue.text })
            assertEquals(2, voices.map { it.cue.characterId }.distinct().size)

            positionAt(1)
            click("🔊 Sonido")
            click("Naturaleza", true)
            rule.onNodeWithContentDescription("Poner Lluvia en la línea roja").performScrollTo().performClick()
            waitFor("agregar ambiente") { vm.ui.value.current?.cues?.any { it.text == "Lluvia" } == true }
            click("Listo")
            positionAt(voices[1].start.roundToInt())
            click("🔊 Sonido")
            click("Casa", true)
            rule.onNodeWithContentDescription("Poner Reloj en la línea roja").performScrollTo().performClick()
            waitFor("agregar reloj") { vm.ui.value.current?.cues?.any { it.text == "Reloj" } == true }
            click("Listo")
            positionAt(0)
            click("🎵 Música")
            rule.onNodeWithContentDescription("Poner Bosque de noche en la línea roja").performScrollTo().performClick()
            waitFor("agregar música") { vm.ui.value.current?.cues?.any { it.type == CueType.MUSIC } == true }
            click("Listo")
            capture("02-mezcla")

            rule.onNodeWithContentDescription("Efecto Lluvia", substring = true).performClick()
            click("🔉 Volumen")
            val hasContext = shown("Oír mezcla", true)
            capture("03-volumen")
            if (hasContext) {
                click("Oír mezcla", true)
                waitFor("reproducir mezcla en contexto") {
                    vm.clock.value.playing && vm.ui.value.loaded && !vm.ui.value.previewing
                }
                if (shown("■ Parar")) click("■ Parar")
            } else {
                click("▶ Oír")
                waitFor("reproducir solo la lluvia") { vm.clock.value.playing && vm.ui.value.previewing }
                click("■ Parar")
            }
            click("✓ Listo")
            if (shown("✓ Listo")) click("✓ Listo")

            positionAt(0)
            click("▶ Escuchar", true)
            waitFor("reproducir la mezcla completa") { vm.clock.value.playing && !vm.ui.value.previewing }
            waitFor("terminar la reproducción completa", 55_000) { !vm.clock.value.playing }
            rule.onNodeWithContentDescription("Enviar o guardar el cuento").performClick()
            click("Guardar el cuento en mi teléfono")
            waitFor("exportar el cuento", 35_000) {
                vm.ui.value.notice?.startsWith("Guardé tu cuento") == true || vm.ui.value.error != null
            }
            assertTrue(vm.ui.value.error.orEmpty(), vm.ui.value.error == null)
            val final = checkNotNull(vm.ui.value.current)
            val dir = PrincessLibrary.dir(rule.activity, final)
            val mix = checkNotNull(File(dir, "enviar").listFiles()?.singleOrNull { it.extension == "m4a" })
            val decoded = AudioDecoder.decode(mix)
            assertEquals(2, decoded.channels)
            assertEquals(DramaRenderer.totalSeconds(final) + .5, decoded.seconds, .20)
            mix.copyTo(File(evidence, "La-luz-de-la-estacion.m4a"), overwrite = true)
            voices.forEachIndexed { index, place ->
                File(dir, checkNotNull(place.cue.file)).copyTo(File(evidence, "voz-${index+1}.wav"), overwrite = true)
            }
            File(evidence, "pistas.txt").writeText(buildString {
                appendLine("Revisión contextual desde Volumen: $hasContext")
                for (place in DramaRenderer.layout(final)) {
                    val c = place.cue
                    appendLine("${c.type}\t${place.start}\t${place.end}\t${c.volume}\t${c.fadeIn}\t${c.fadeOut}\t${c.text}")
                }
                appendLine("Exportación: ${decoded.seconds} s, ${decoded.channels} canales")
            })
            capture("04-exportacion")
        } catch (error: Throwable) {
            runCatching { capture("fallo") }
            throw error
        }
    }
}
