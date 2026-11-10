package com.glidelive.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.glidelive.princess.Character
import com.glidelive.princess.Project
import com.glidelive.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class VoiceStudioViewportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        rule.waitForIdle()
    }

    private fun open(fontScale: Float = 1f) {
        val vm = ViewModelProvider(rule.activity)[PrincessViewModel::class.java]
        val project = Project("viewport-test", "Cuento", listOf(Character("n", "Narrador", 0xFFFF6B9D)), emptyList())
        rule.setContent {
            AppTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    var document by remember { mutableStateOf(TextFieldValue("Había una vez una niña que vivía junto al bosque.")) }
                    // Same host and constraints as MainActivity, without another Window.
                    Box(Modifier.fillMaxSize()) {
                        VoiceStudioScreen(project, vm, document, { document = it }, {}, Modifier.matchParentSize())
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertInsideWindow(label: String) {
        assertTrue("The test must run in landscape", rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        val decor = rule.activity.window.decorView
        val screen = android.graphics.Rect()
        val origin = IntArray(2)
        rule.runOnUiThread {
            decor.getWindowVisibleDisplayFrame(screen)
            decor.getLocationOnScreen(origin)
        }
        // Use physical window coordinates. A Compose root can start inside system
        // insets, and keyboard cursor handles can introduce additional roots.
        val node = rule.onNodeWithText(label).fetchSemanticsNode()
        val position = node.positionInWindow
        val button = androidx.compose.ui.geometry.Rect(
            position.x + origin[0], position.y + origin[1],
            position.x + origin[0] + node.size.width, position.y + origin[1] + node.size.height)
        assertTrue("$label extends past the left edge: $button / $screen", button.left >= screen.left)
        assertTrue("$label extends past the right edge: $button / $screen", button.right <= screen.right)
        assertTrue("$label extends above the window: $button / $screen", button.top >= screen.top)
        assertTrue("$label extends below the window: $button / $screen", button.bottom <= screen.bottom)
        rule.onNodeWithText(label).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use {
            rule.onNodeWithTag("voice-studio-viewport").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun landscapeButtonsStayInsideActivityAtLargeFontSize() {
        open(fontScale = 1.3f)
        capture("narration-landscape-large-font")
        assertInsideWindow("Volver")
        assertInsideWindow("Generar y escuchar")
        rule.onNodeWithText("Opciones de voz").performScrollTo().performClick()
        assertInsideWindow("Volver")
        assertInsideWindow("Generar y escuchar")
        capture("narration-landscape-large-font")
    }

    @Test fun keyboardLeavesMainActionInsideWindow() {
        open()
        rule.onNodeWithText("Escribe o pega tu libreto").performClick()
        rule.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        rule.waitForIdle()
        capture("narration-landscape-keyboard")
        assertInsideWindow("Volver")
        assertInsideWindow("Generar y escuchar")
    }
}
