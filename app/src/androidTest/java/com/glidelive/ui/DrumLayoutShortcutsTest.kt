package com.glidelive.ui

import android.content.Context
import android.graphics.Bitmap
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class DrumLayoutShortcutsTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun cambiaLaCuadriculaSinCambiarLaBase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
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
        } finally {
            start.edit().putString("start", oldStart).commit()
            kit.edit().putString("pad_layout", oldLayout).commit()
        }
    }

    private fun findPads(view: View): DrumPadsView? =
        if (view is DrumPadsView) view else (view as? ViewGroup)?.children?.firstNotNullOfOrNull(::findPads)
}
