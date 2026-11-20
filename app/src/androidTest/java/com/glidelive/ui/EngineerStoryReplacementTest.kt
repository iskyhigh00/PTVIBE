package com.glidelive.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.glidelive.princess.Cue
import com.glidelive.princess.CueType
import com.glidelive.princess.PrincessLibrary
import com.glidelive.princess.WavIo
import com.glidelive.ui.components.AppBackground
import com.glidelive.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class EngineerStoryReplacementTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class AudioPicker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var request: Int? = null
        var intent: Intent? = null

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                                     input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            // Verify the actual document request the app would hand to Android.
            intent = contract.createIntent(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, input)
        }

        fun reply(resultCode: Int, data: Intent?) {
            val id = checkNotNull(request)
            request = null
            check(dispatchResult(id, resultCode, data))
        }
    }

    @Test fun cancelsThenReplacesOnlyTheSelectedVoiceAndCanUndo() {
        val context = rule.activity
        val vm = ViewModelProvider(context)[PrincessViewModel::class.java]
        val picker = AudioPicker()
        val title = "Reemplazo ${System.nanoTime()}"
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                AppTheme { AppBackground { StoryScreen(vm, Modifier.fillMaxSize(), onNarrate = {}) } }
            }
        }
        rule.runOnUiThread { vm.newProject(title) }
        rule.waitUntil(10_000) { vm.ui.value.current?.title == title }
        val project = checkNotNull(vm.ui.value.current)
        val folder = PrincessLibrary.dir(context, project)
        fun audio(name: String, frames: Int, hz: Double) = File(folder, name).apply {
            WavIo.write(this, ShortArray(frames) { i -> (6000 * sin(2 * PI * hz * i / 44100)).toInt().toShort() }, 1, 44100)
        }
        val old = audio("ingeniero-original.wav", 132300, 440.0)
        val other = audio("ingeniero-sonido.wav", 44100, 220.0)
        val replacement = audio("ingeniero-reemplazo.wav", 88200, 660.0)
        val original = Cue("parlamento", CueType.VOICE, project.characters.first().id,
            "La puerta ya está abierta", old.name, 3f, 0f, startSeconds = 2.25f, trimStart = 0.25f, durationSeconds = 2.5f)
        val unaffected = Cue("efecto", CueType.SFX, null, "Campana", other.name, 1f, 0f, startSeconds = 5f)
        rule.runOnUiThread { vm.edit { it.copy(cues = listOf(original, unaffected)) } }
        rule.waitUntil(10_000) { vm.ui.value.current?.cues?.size == 2 }

        fun chooseReplacement() {
            rule.onNode(hasContentDescription("La puerta ya está abierta", substring = true)).performScrollTo().performClick()
            rule.onNodeWithText("⋯ Más").performClick()
            rule.onNodeWithText("Cambiar por un archivo", substring = true).performClick()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent?.action)
            assertTrue(picker.intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.contains("audio/*") == true ||
                picker.intent?.type == "audio/*")
        }

        chooseReplacement()
        rule.runOnUiThread { picker.reply(Activity.RESULT_CANCELED, null) }
        rule.waitForIdle()
        assertEquals(listOf(original, unaffected), vm.ui.value.current?.cues)
        assertTrue(old.isFile)

        chooseReplacement()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", replacement)
        rule.runOnUiThread {
            picker.reply(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        rule.waitUntil(10_000) {
            vm.ui.value.current?.cues?.firstOrNull()?.file != old.name && vm.ui.value.busy == null
        }
        assertEquals(null, vm.ui.value.error)
        val after = checkNotNull(vm.ui.value.current).cues
        assertEquals(2, after.size)
        val changed = after.first()
        assertNotEquals(replacement.name, changed.file)
        assertEquals(original.id, changed.id)
        assertEquals(original.characterId, changed.characterId)
        assertEquals(original.startSeconds, changed.startSeconds)
        assertEquals(unaffected, after.last())
        val imported = File(folder, checkNotNull(changed.file))
        val audio = checkNotNull(WavIo.read(imported))
        assertEquals(2.0, audio.seconds, 0.01)
        assertTrue(checkNotNull(WavIo.read(replacement)).pcm.contentEquals(audio.pcm))

        rule.onNodeWithContentDescription("Deshacer").performClick()
        rule.waitUntil(10_000) { vm.ui.value.current?.cues == listOf(original, unaffected) }
        assertTrue(old.isFile)
        rule.onNodeWithContentDescription("Rehacer").performClick()
        rule.waitUntil(10_000) { vm.ui.value.current?.cues == listOf(changed, unaffected) }
        rule.onNodeWithContentDescription("Volver a Mis cuentos").performClick()
        rule.onNodeWithContentDescription("Abrir $title").performScrollTo().performClick()
        rule.waitUntil(10_000) { vm.ui.value.current?.id == project.id }
        assertEquals(listOf(changed, unaffected), checkNotNull(PrincessLibrary.load(context, project.id)).cues)
        assertTrue(imported.isFile)
    }
}
