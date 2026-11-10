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
import androidx.compose.ui.unit.DpRect
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
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("The viewport test must run in landscape", root.right - root.left > root.bottom - root.top)
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        val screen = with(rule.density) {
            DpRect(root.left + insets.left.toDp(), root.top + insets.top.toDp(),
                root.right - insets.right.toDp(), root.bottom - insets.bottom.toDp())
        }
        val button = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
        assertTrue("$label extends past the left edge: $button / $screen", button.left >= screen.left)
        assertTrue("$label extends past the right edge: $button / $screen", button.right <= screen.right)
        assertTrue("$label extends above the window: $button / $screen", button.top >= screen.top)
        assertTrue("$label extends below the window: $button / $screen", button.bottom <= screen.bottom)
        rule.onNodeWithText(label).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun landscapeButtonsStayInsideActivityAtLargeFontSize() {
        open(fontScale = 1.3f)
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
        assertInsideWindow("Volver")
        assertInsideWindow("Generar y escuchar")
        capture("narration-landscape-keyboard")
    }
}
