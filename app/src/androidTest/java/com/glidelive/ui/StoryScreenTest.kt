package com.glidelive.ui

import android.graphics.Bitmap
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
import com.glidelive.princess.CueMode
import com.glidelive.princess.CueType
import com.glidelive.princess.PrincessLibrary
import com.glidelive.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        rule.onNodeWithText("📤 Enviar").assertIsDisplayed()
        rule.onNodeWithText("Escuchar", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Grabar mi voz", substring = true).assertIsDisplayed()
        rule.onNodeWithText("🎵 Música").assertIsDisplayed()
    }

    @Test fun moverManteniendoApretado() {
        val vm = prepare(); story(vm)
        val before = cue(vm, "s1").startSeconds!!
        rule.onNodeWithText("Gato").performTouchInput {
            down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(120f, 0f)); advanceEventTime(100); moveBy(androidx.compose.ui.geometry.Offset(120f, 0f)); up()
        }
        rule.waitForIdle()
        waitFor("movió") { cue(vm, "s1").startSeconds != before }
        assertTrue("debería ir más tarde", cue(vm, "s1").startSeconds!! > before)
        shot("3-movido")
    }

    @Test fun tocarElegeYMuestraControlesSinTaparLaLinea() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Quitar", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Gato").assertIsDisplayed()           // la línea de tiempo sigue a la vista
        shot("4-elegido")
        rule.onNodeWithText("Tono", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Normal").assertIsDisplayed()
        rule.onNodeWithText("Gato").assertIsDisplayed()
        shot("5-tono")
        rule.onNodeWithText("Listo", substring = true).performClick()
        rule.onNodeWithText("Volumen", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Gato").assertIsDisplayed()
        shot("6-volumen")
    }

    @Test fun quitarYDeshacer() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato").performClick()
        rule.onNodeWithText("Quitar", substring = true).performClick()
        waitFor("quitado") { vm.ui.value.current!!.cues.none { it.id == "s1" } }
        rule.onNodeWithText("↶ Deshacer").performClick()
        waitFor("deshecho") { vm.ui.value.current!!.cues.any { it.id == "s1" } }
    }

    @Test fun moverEntreLineasDeVoz() {
        val vm = prepare(); story(vm)
        val h = 56f
        rule.onNodeWithText("Había una vez").performTouchInput {
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
        rule.onNodeWithText("Había una vez").performClick()
        rule.waitForIdle()
        vm.setCursor(4.0)
        rule.onNodeWithText("Cortar", substring = true).performClick()
        waitFor("se cortó") { vm.ui.value.current!!.cues.count { it.type == CueType.VOICE } == 2 }
    }

    private fun insideRoot(label: String) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertTrue("$label se sale de la pantalla: $b vs $root", b.left >= root.left - 1 && b.right <= root.right + 1 && b.top >= root.top - 1 && b.bottom <= root.bottom + 1)
    }

    @Test fun nadaSeSaleConLetraGrande() {
        val vm = prepare(1.3f); story(vm)
        for (t in listOf("📤 Enviar", "▶ Escuchar", "↶ Deshacer", "Más ▾", "● Grabar mi voz", "🔊 Sonido", "🎵 Música")) insideRoot(t)
        rule.onNodeWithText("Gato").performClick(); rule.waitForIdle()
        for (t in listOf("✂ Cortar", "🎚 Tono", "🔉 Volumen", "▶ Oír", "🗑 Quitar", "✓ Listo")) insideRoot(t)
        shot("8-letra-grande")
    }

    @Test fun lineaDeTiempoNoQuedaTapada() {
        val vm = prepare(); story(vm)
        rule.onNodeWithText("Gato").performClick(); rule.waitForIdle()
        val gato = rule.onNodeWithText("Gato").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithText("🗑 Quitar").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("el audio elegido queda tapado por los botones: $gato / $bar", gato.bottom <= bar.top + 1)
        assertTrue("la línea de tiempo es muy chica: ${gato.height}", gato.height > 40f && root.height > 0f)
        rule.onNodeWithText("Tono", substring = true).performClick(); rule.waitForIdle()
        val gato2 = rule.onNodeWithText("Gato").fetchSemanticsNode().boundsInRoot
        val normal = rule.onNodeWithText("Normal").fetchSemanticsNode().boundsInRoot
        assertTrue("el tono tapa el audio: $gato2 / $normal", gato2.bottom <= normal.top + 1)
    }

    @Test fun moverDosVeces() {
        val vm = prepare(); story(vm)
        fun drag() = rule.onNodeWithText("Gato").performTouchInput {
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
        assertTrue("debería ser m4a: ${file.name}", file.extension == "m4a")
        assertTrue("archivo vacío", file.length() > 2000)
        val r = android.media.MediaMetadataRetriever().apply { setDataSource(file.path) }
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        // voz 1→7 s y sonido 8→11 s: el cuento dura ~11 s (+ medio segundo de cola)
        assertTrue("duración inesperada: $ms ms", ms in 10_500..12_500)
        assertTrue("debería pesar poco: ${file.length()}", file.length() < 11.5 * 44100 * 4 / 4)
    }
}
