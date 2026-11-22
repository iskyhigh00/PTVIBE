package com.glidelive.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.glidelive.princess.Character
import com.glidelive.princess.Cue
import com.glidelive.princess.CueMode
import com.glidelive.princess.CueType
import com.glidelive.princess.PrincessLibrary
import com.glidelive.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class StoryScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val dir = File(rule.activity.getExternalFilesDir(null), "shots").also { it.mkdirs() }
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun vm() = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 8000
        while (!cond() && System.currentTimeMillis() < end) { rule.waitForIdle(); Thread.sleep(100) }
        assertTrue("Tiempo agotado: $what", cond())
    }

    private fun prepare(fontScale: Float = 1f): PrincessViewModel {
        val vm = vm()
        rule.setContent {
            AppTheme {
                val d = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale)) {
                    Box(Modifier.fillMaxSize()) { StoryScreen(vm, Modifier.fillMaxSize(), onNarrate = {}) }
                }
            }
        }
        rule.waitForIdle()
        return vm
    }

    private fun story(vm: PrincessViewModel) {
        val title = "Prueba ${System.nanoTime()}"
        vm.newProject(title)
        waitFor("proyecto abierto") { vm.ui.value.current?.title == title }
        vm.edit { p ->
            p.copy(
                characters = p.characters + Character("b", "Bruja", 0xFF60A5FA),
                cues = listOf(
                    Cue("v1", CueType.VOICE, p.characters.first().id, "Había una vez", "x.wav", 6f, 0f, CueMode.SEQ, startSeconds = 1f, durationSeconds = 6f),
                    Cue("s1", CueType.SFX, null, "Gato", "y.wav", 3f, 0f, CueMode.SEQ, startSeconds = 8f, durationSeconds = 3f),
                    Cue("m1", CueType.MUSIC, null, "Amazing Plan", "z.mp3", 40f, 0f, CueMode.BED, startSeconds = 0f, durationSeconds = 20f),
                ),
            )
        }
        waitFor("cuentos cargados") { vm.ui.value.current?.cues?.size == 3 }
        rule.waitForIdle()
    }

    private fun cue(vm: PrincessViewModel, id: String) = vm.ui.value.current!!.cues.first { it.id == id }

    @Test fun listaYEditorSeVen() {
        val vm = prepare()
        shot("1-inicio")
        story(vm)
        shot("2-cuento")
        rule.onNodeWithText("📤 Enviar", substring = true).assertIsDisplayed()
        rule.onNodeWithText("▶ Escuchar", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Grabar mi voz").assertIsDisplayed()
        rule.onNodeWithText("🎵 Música").assertIsDisplayed()
    }

    @Test fun cuentoVacioDejaLaLineaDeTiempoDisponible() {
        val vm = prepare()
        val title = "Cuento vacío ${System.nanoTime()}"
        vm.newProject(title)
        waitFor("proyecto vacío abierto") { vm.ui.value.current?.title == title }
        rule.onNodeWithText("Tu cuento empieza aquí").assertDoesNotExist()
        val ruler = rule.onNodeWithContentDescription("Regla de tiempo", substring = true)
        ruler.assertIsDisplayed().performTouchInput { click(percentOffset(0.25f, 0.5f)) }
        waitFor("línea roja movida") { vm.ui.value.cursor > 0 }
        rule.onNodeWithText("● Sonidos").assertIsDisplayed()
        rule.onNodeWithText("● Música de fondo").assertIsDisplayed()
        shot("9-linea-vacia")
    }

    @Test fun moverManteniendoApretado() {
        val vm = prepare(); story(vm)
        val before = cue(vm, "s1").startSeconds!!
        rule.onNodeWithText("Gato", substring = true).performTouchInput {
            down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(120f, 0f)); advanceEventTime(100); moveBy(androidx.compose.ui.geometry.Offset(120f, 0f)); up()
        }
        rule.waitForIdle()
        waitFor("movió") { cue(vm, "s1").startSeconds != before }
        assertTrue("debería ir más tarde", cue(vm, "s1").startSeconds!! > before)
        shot("3-movido")
    }

    @Test fun pellizcarConservaElInstanteBajoLosDedosYPermiteDesplazar() {
        val vm = prepare(); story(vm)
        val timeline = rule.onNodeWithTag("story-timeline")
        fun tap(fraction: Float) {
            timeline.performTouchInput { click(Offset(width * fraction, 16f)) }
            rule.waitForIdle()
        }
        tap(0.48f)
        val anchor = vm.ui.value.cursor
        tap(0.64f)
        val originalSpan = vm.ui.value.cursor - anchor
        tap(0.48f)
        timeline.performTouchInput {
            down(Offset(width * 0.48f, 16f))
            moveTo(Offset(width * 0.56f, 16f), delayMillis = 80)
            moveTo(Offset(width * 0.64f, 16f), delayMillis = 80)
            up()
        }
        assertEquals("Arrastrar en la regla mueve el cursor", anchor + originalSpan, vm.ui.value.cursor, 0.06)
        tap(0.48f)
        timeline.performTouchInput {
            down(0, Offset(width * 0.40f, 16f))
            down(1, Offset(width * 0.56f, 16f))
            updatePointerTo(0, Offset(width * 0.31f, 16f))
            updatePointerTo(1, Offset(width * 0.65f, 16f))
            move()
            up(1); up(0)
        }
        assertEquals(anchor, vm.ui.value.cursor, 0.06)
        tap(0.48f)
        assertEquals("El instante queda debajo del centro del pellizco", anchor, vm.ui.value.cursor, 0.06)
        tap(0.64f)
        assertTrue("Debe mostrar menos tiempo después de ampliar", vm.ui.value.cursor - anchor < originalSpan * .6)
        timeline.performTouchInput {
            down(0, Offset(width * 0.40f, 16f))
            down(1, Offset(width * 0.56f, 16f))
            updatePointerTo(0, Offset(width * 0.30f, 16f))
            updatePointerTo(1, Offset(width * 0.46f, 16f))
            move()
            up(1); up(0)
        }
        tap(0.48f)
        assertTrue("Los dos dedos también desplazan la ventana", vm.ui.value.cursor > anchor + .2)
        rule.onNodeWithContentDescription("Más opciones").performClick()
        rule.onNodeWithText("Encuadrar cuento completo").performClick()
        tap(0.98f)
        assertEquals("El final del cuento cabe en la ventana", 20.09, vm.ui.value.cursor, 0.18)
        rule.onNodeWithText("Amazing Plan", substring = true).assertIsDisplayed()
    }

    @Test fun recortarArrastrandoElBordeEsReversible() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Había una vez", substring = true).performClick()
        rule.onNodeWithContentDescription("Recortar final", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(-60f, 0f), delayMillis = 80)
            moveBy(Offset(-60f, 0f), delayMillis = 80)
            up()
        }
        waitFor("recorte por borde") { checkNotNull(cue(vm, "v1").durationSeconds) < 5.6f }
        assertEquals(1f, checkNotNull(cue(vm, "v1").startSeconds), .001f)
        assertEquals(8f, checkNotNull(cue(vm, "s1").startSeconds), .001f)
        rule.onNodeWithContentDescription("Deshacer").performClick()
        waitFor("recorte deshecho") { cue(vm, "v1").durationSeconds == 6f }
    }

    @Test fun tocarElegeYMuestraControlesSinTaparLaLinea() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Quitar", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Gato", substring = true).assertIsDisplayed()           // la línea de tiempo sigue a la vista
        shot("4-elegido")
        openMore()
        rule.onNodeWithText("Voz graciosa", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Normal", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Gato", substring = true).assertIsDisplayed()
        shot("5-tono")
        rule.onNodeWithText("Listo", substring = true).performClick()
        rule.onNodeWithText("Volumen", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Gato", substring = true).assertIsDisplayed()
        shot("6-volumen")
    }

    @Test fun quitarYDeshacer() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato", substring = true).performClick()
        rule.onNodeWithText("Quitar", substring = true).performClick()
        waitFor("quitado") { vm.ui.value.current!!.cues.none { it.id == "s1" } }
        assertNull("La desaparición del clip confirma que se quitó", vm.ui.value.notice)
        rule.onNodeWithContentDescription("Deshacer").performClick()
        waitFor("deshecho") { vm.ui.value.current!!.cues.any { it.id == "s1" } }
    }

    @Test fun moverEntreLineasDeVoz() {
        val vm = prepare(); story(vm)
        val h = 56f
        rule.onNodeWithText("Había una vez", substring = true).performTouchInput {
            down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(0f, 30f)); advanceEventTime(100)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 90f)); up()
        }
        rule.waitForIdle()
        waitFor("cambió de voz") { cue(vm, "v1").characterId == "b" }
        shot("7-otra-voz")
    }

    @Test fun cortar() {
        val vm = prepare(); story(vm)
        vm.setCursor(4.0)
        rule.waitForIdle()
        rule.onNodeWithText("Había una vez", substring = true).performClick()
        rule.waitForIdle()
        vm.setCursor(4.0)
        rule.onNodeWithText("Cortar", substring = true).performClick()
        waitFor("se cortó") { vm.ui.value.current!!.cues.count { it.type == CueType.VOICE } == 2 }
        assertNull("El corte ya se ve en la línea de tiempo", vm.ui.value.notice)
    }

    private fun insideRoot(label: String, byDescription: Boolean = false) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = (if (byDescription) rule.onNodeWithContentDescription(label) else rule.onNodeWithText(label, substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue("$label se sale de la pantalla: $b vs $root", b.left >= root.left - 1 && b.right <= root.right + 1 && b.top >= root.top - 1 && b.bottom <= root.bottom + 1)
    }

    @Test fun nadaSeSaleConLetraGrande() {
        val vm = prepare(1.3f); story(vm)
        for (t in listOf("📤 Enviar", "▶ Escuchar", "Más ▾", "● Grabar", "✎ Escribir", "🔊 Sonido", "🎵 Música")) insideRoot(t)
        for (d in listOf("Deshacer", "Rehacer", "Volver a Mis cuentos")) insideRoot(d, byDescription = true)
        rule.onNodeWithText("Gato", substring = true).performClick(); rule.waitForIdle()
        for (t in listOf("✂ Cortar aquí", "🔉 Volumen", "▶ Oír", "🗑 Quitar", "⋯ Más", "✓ Listo")) insideRoot(t)
        shot("8-letra-grande")
        openMore()
        rule.onNodeWithText("🎚 Voz graciosa", substring = true).assertIsDisplayed()
    }

    @Test fun lineaDeTiempoNoQuedaTapada() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato", substring = true).performClick(); rule.waitForIdle()
        val gato = rule.onNodeWithText("Gato", substring = true).fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithText("🗑 Quitar").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("el audio elegido queda tapado por los botones: $gato / $bar", gato.bottom <= bar.top + 1)
        assertTrue("la línea de tiempo es muy chica: ${gato.height}", gato.height > 40f && root.height > 0f)
        openMore()
        rule.onNodeWithText("Voz graciosa", substring = true).performClick(); rule.waitForIdle()
        val gato2 = rule.onNodeWithText("Gato", substring = true).fetchSemanticsNode().boundsInRoot
        val normal = rule.onNodeWithText("Normal", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("el tono tapa el audio: $gato2 / $normal", gato2.bottom <= normal.top + 1)
    }

    @Test fun moverDosVeces() {
        val vm = prepare(); story(vm)
        fun drag() = rule.onNodeWithText("Gato", substring = true).performTouchInput {
            down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(100f, 0f)); advanceEventTime(100); moveBy(androidx.compose.ui.geometry.Offset(100f, 0f)); up()
        }
        val a = cue(vm, "s1").startSeconds!!
        drag(); waitFor("1.er movimiento") { cue(vm, "s1").startSeconds != a }
        val b = cue(vm, "s1").startSeconds!!
        rule.waitForIdle()
        drag(); waitFor("2.º movimiento") { cue(vm, "s1").startSeconds != b }
        assertTrue(cue(vm, "s1").startSeconds!! > b)
    }

    @Test fun enviarGeneraUnM4aPequenoConLaDuracionCorrecta() {
        val vm = prepare(); story(vm)
        val project = vm.ui.value.current!!
        // audio real: 3 s de un tono, referido por las pistas del cuento
        val dir = PrincessLibrary.dir(rule.activity, project)
        val pcm = ShortArray(44100 * 3) { i -> (6000 * Math.sin(2 * Math.PI * 440 * i / 44100.0)).toInt().toShort() }
        for (n in listOf("x.wav", "y.wav")) com.glidelive.princess.WavIo.write(File(dir, n), pcm, 1, 44100)
        val file = kotlinx.coroutines.runBlocking { vm.renderExport(project.copy(cues = project.cues.filter { it.type != CueType.MUSIC })) }
        assertTrue("debería ser m4a: ${file.name}; causa: ${vm.exportFallback?.let { it.toString() + " @ " + it.stackTrace.take(4).joinToString(" < ") }}", file.extension == "m4a")
        assertTrue("archivo vacío", file.length() > 2000)
        val r = android.media.MediaMetadataRetriever().apply { setDataSource(file.path) }
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        // voz 1→7 s y sonido 8→11 s: el cuento dura ~11 s (+ medio segundo de cola)
        assertTrue("duración inesperada: $ms ms", ms in 10_500..12_500)
        assertTrue("debería pesar poco: ${file.length()}", file.length() < 11.5 * 44100 * 4 / 4)
    }

    @Test fun moverConBotones() {
        val vm = prepare(); story(vm)
        val before = cue(vm, "s1").startSeconds!!
        rule.onNodeWithText("Gato", substring = true).performClick(); rule.waitForIdle()
        rule.onNodeWithText("↔ Mover").performClick(); rule.waitForIdle()
        rule.onNodeWithText("1 s después", substring = true).performClick()
        waitFor("movió 1 s") { cue(vm, "s1").startSeconds == before + 1f }
        rule.onNodeWithText("5 s antes", substring = true).performClick()
        waitFor("movió -5 s") { cue(vm, "s1").startSeconds == before - 4f }
        rule.onNodeWithText("Gato", substring = true).assertIsDisplayed()
    }

    @Test fun cambiarDeVozConBotones() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Había una vez", substring = true).performClick(); rule.waitForIdle()
        rule.onNodeWithText("↔ Mover").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Voz siguiente", substring = true).performClick()
        waitFor("pasó a la otra voz") { cue(vm, "v1").characterId == "b" }
    }

    @Test fun elClipTieneDescripcionParaTalkBack() {
        val vm = prepare(); story(vm)
        rule.onNode(hasContentDescription("Efecto Gato", substring = true)).assertExists()
    }

    private fun openMore() {
        rule.onNodeWithText("⋯ Más").performClick(); rule.waitForIdle()
    }

    @Test fun quitarYJuntarCierraElHueco() {
        val vm = prepare(); story(vm)
        vm.edit { p -> p.copy(cues = p.cues + Cue("v2", CueType.VOICE, p.characters.first().id, "Y entonces", "x.wav", 3f, 0f, CueMode.SEQ, startSeconds = 12f, durationSeconds = 3f)) }
        waitFor("se agregó la segunda voz") { vm.ui.value.current?.cues?.any { it.id == "v2" } == true }
        rule.onNodeWithText("Había una vez", substring = true).performClick(); rule.waitForIdle()
        openMore()
        rule.onNodeWithText("Quitar este pedacito y juntar el resto", substring = true).performClick()
        waitFor("se quitó la primera voz") { vm.ui.value.current!!.cues.none { it.id == "v1" } }
        waitFor("la siguiente se corrió 6 s") { cue(vm, "v2").startSeconds == 6f }
    }

    @Test fun recortarElPrincipioHastaLaLineaRoja() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Había una vez", substring = true).performClick(); rule.waitForIdle()
        vm.setCursor(3.0); rule.waitForIdle()
        openMore()
        rule.onNodeWithText("Borrar lo de antes de la línea roja", substring = true).performClick()
        waitFor("recortó") { cue(vm, "v1").startSeconds == 3f }
        assertEquals(2f, cue(vm, "v1").trimStart, 0.01f)
        assertEquals(4f, cue(vm, "v1").durationSeconds!!, 0.01f)
    }

    @Test fun copiarSilenciarYFundidos() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato", substring = true).performClick(); rule.waitForIdle()
        openMore(); rule.onNodeWithText("Hacer una copia", substring = true).performClick()
        waitFor("hay dos efectos") { vm.ui.value.current!!.cues.count { it.type == CueType.SFX } == 2 }
        rule.onAllNodesWithText("Gato", substring = true)[0].performClick(); rule.waitForIdle()
        openMore(); rule.onNodeWithText("Silenciar este audio", substring = true).performClick()
        waitFor("silenciado") { cue(vm, "s1").muted }
        openMore(); rule.onNodeWithText("Que empiece bajito y suba", substring = true).performScrollTo().assertIsDisplayed().performClick()
        waitFor("entrada suave") { cue(vm, "s1").fadeIn > 0f }
        openMore(); rule.onNodeWithText("Bajar mientras hay voz").performScrollTo().assertIsDisplayed().performClick()
        waitFor("efecto baja bajo voces al activarlo") { cue(vm, "s1").duckUnderVoice }
        openMore(); rule.onNodeWithText("✓ Bajar mientras hay voz").performScrollTo().assertIsDisplayed().performClick()
        waitFor("efecto recupera nivel fijo al desactivarlo") { !cue(vm, "s1").duckUnderVoice }
    }

    @Test fun recuperaUnaTomaQueQuedoAMedias() {
        val vm = prepare(); story(vm)
        val project = vm.ui.value.current!!
        val dir = PrincessLibrary.dir(rule.activity, project)
        val pcm = ShortArray(44100 * 2) { i -> (6000 * Math.sin(2 * Math.PI * 330 * i / 44100.0)).toInt().toShort() }
        com.glidelive.princess.WavIo.write(File(dir, "grabando_prueba.wav"), pcm, 1, 44100)
        rule.runOnUiThread { vm.open(project) }
        waitFor("toma recuperada") { vm.ui.value.current?.cues?.any { it.text == "Toma recuperada" } == true }
        assertTrue(File(dir, "grabando_prueba.wav").exists().not())
    }
}
