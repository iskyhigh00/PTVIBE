package com.glidelive.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.glidelive.audio.NativeEngine
import com.glidelive.viz.EngineEvent
import com.glidelive.viz.EventHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections

class DrumLayoutShortcutsTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun cambiaLaCuadriculaSinCambiarLaBase() = withDrums { scenario, context, kit ->
        rule.onNodeWithContentDescription("Más distribuciones de pads").assertIsDisplayed()
        rule.onNodeWithContentDescription("Variaciones de la base").assertIsDisplayed().performClick()
        var variationName = ""
        scenario.onActivity {
            val transport = ViewModelProvider(it)[TransportViewModel::class.java]
            variationName = checkNotNull(transport.ui.value.pattern).variations[1].name
        }
        rule.onNodeWithText("Variación $variationName").performClick()
        rule.onNodeWithText("Batería ▾").performScrollTo()
        val caja = rule.onNodeWithContentDescription("Pads: Caja y bombo grandes")
        caja.assertIsDisplayed().performClick()
        caja.assertIsSelected()
        rule.waitForIdle()
        scenario.onActivity { activity ->
            assertEquals(PadLayout.BIG_SNARE, findPads(activity.window.decorView)?.padLayout)
            assertEquals(1, ViewModelProvider(activity)[TransportViewModel::class.java].ui.value.variation)
        }
        val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
        val shots = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(shots, "bateria-atajos-pads.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        rule.onNodeWithContentDescription("Más distribuciones de pads").performScrollTo().performClick()
        rule.onNodeWithText("Kit zurdo").performScrollTo().performClick()
        rule.waitForIdle()
        scenario.onActivity { assertEquals(PadLayout.KIT_LEFT, findPads(it.window.decorView)?.padLayout) }
        assertEquals(PadLayout.KIT_LEFT.name, kit.getString("pad_layout", null))
    }

    @Test fun losPulsosTocanSoloElPlatoElegidoAlTempoDeTap() = withDrums { scenario, _, _ ->
        lateinit var transport: TransportViewModel
        scenario.onActivity { transport = ViewModelProvider(it)[TransportViewModel::class.java] }
        val originalBase = transport.ui.value.patternId
        val base = rule.onNodeWithContentDescription("Variaciones de la base")
        val tap = rule.onNodeWithContentDescription("Marcar tempo con Tap")
        val events = Collections.synchronizedList(mutableListOf<EngineEvent>())
        val listener: (EngineEvent) -> Unit = { if (it.type == 0) events += it }

        fun hitsSince(time: Double) = synchronized(events) { events.filter { it.tMs >= time } }
        fun checkPulse(from: Double, pad: Int, bpm: Int) {
            rule.waitUntil(timeoutMillis = 10_000) { hitsSince(from).size >= 4 }
            val hits = hitsSince(from).take(4)
            assertTrue("La base toca solo el pad $pad: ${hits.map { it.a }}", hits.all { it.a == pad })
            val interval = (hits.last().tMs - hits.first().tMs) / 3
            assertEquals("Intervalo a $bpm BPM", 60_000.0 / bpm, interval, 90.0)
        }

        EventHub.addListener(listener)
        try {
            tap.assertIsDisplayed().performClick()
            SystemClock.sleep(320)
            tap.performClick()
            val bpm = transport.ui.value.bpm
            assertTrue("El Tap debe fijar un tempo distinto al inicial", bpm > 110)

            base.assertIsDisplayed().performClick()
            rule.onNodeWithText("Hi-hat cerrado").performClick()
            assertEquals(bpm, transport.ui.value.bpm)
            checkPulse(EventHub.nowMs(), Pads.HH_CLOSED, bpm)

            base.performClick()
            rule.onNodeWithText("Ride").performClick()
            assertEquals(bpm, transport.ui.value.bpm)
            checkPulse(EventHub.nowMs() + 140, Pads.RIDE, bpm)

            base.performClick()
            rule.onNodeWithText("Parar pulso").performClick()
            rule.waitUntil(timeoutMillis = 5_000) { NativeEngine.seqStatus()[8] < .5 }
            val stopped = EventHub.nowMs() + 100
            SystemClock.sleep(900)
            assertTrue("Parar pulso no deja golpes en cola", hitsSince(stopped).isEmpty())
            assertTrue("El resto del transporte sigue en marcha", transport.ui.value.playing)

            base.performClick()
            rule.onNodeWithText("Volver a la base anterior").performClick()
            assertEquals(originalBase, transport.ui.value.patternId)
            assertEquals(bpm, transport.ui.value.bpm)
        } finally {
            EventHub.removeListener(listener)
            scenario.onActivity { transport.stop() }
        }
    }

    private fun withDrums(
        test: (ActivityScenario<MainActivity>, Context, android.content.SharedPreferences) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = context.getSharedPreferences("glide_app", Context.MODE_PRIVATE)
        val kit = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val oldStart = start.getString("start", null)
        val oldLayout = kit.getString("pad_layout", null)
        start.edit().putString("start", "music").commit()
        kit.edit().putString("pad_layout", PadLayout.KIT.name).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.onNodeWithText("Acordes ▾").performClick()
                rule.onNodeWithText("Batería").performClick()
                test(scenario, context, kit)
            }
        } finally {
            start.edit().putString("start", oldStart).commit()
            kit.edit().putString("pad_layout", oldLayout).commit()
        }
    }

    private fun findPads(view: View): DrumPadsView? =
        if (view is DrumPadsView) view else (view as? ViewGroup)?.children?.firstNotNullOfOrNull(::findPads)
}
