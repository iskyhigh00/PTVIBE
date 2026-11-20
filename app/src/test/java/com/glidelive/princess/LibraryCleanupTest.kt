package com.glidelive.princess

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryCleanupTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("lib").toFile()
    private fun file(dir: File, name: String, ageMinutes: Long): File = File(dir, name).also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - ageMinutes * 60_000L) }
    private fun project(vararg files: String) = Project("p", "T", emptyList(), files.map { Cue(it, CueType.VOICE, null, it, it, 1f) })

    @Test fun cleanupDeletesOldUnusedFilesButKeepsUsedAndRecentOnes() {
        val d = tmp()
        file(d, "project.json", 999); file(d, "used.wav", 999); file(d, "old.wav", 30); file(d, "fresh.wav", 1)
        PrincessLibrary.cleanupDir(d, project("used.wav"))
        assertTrue(File(d, "project.json").exists()); assertTrue(File(d, "used.wav").exists())
        assertFalse(File(d, "old.wav").exists()); assertTrue("lo reciente no se toca", File(d, "fresh.wav").exists())
        d.deleteRecursively()
    }

    @Test fun sweepRemovesStoryFoldersWithoutProjectJsonOnlyWhenOld() {
        val root = tmp()
        val dead = File(root, "dead").also { it.mkdirs(); file(it, "voz.wav", 1); it.setLastModified(System.currentTimeMillis() - 120 * 60_000L) }
        val recent = File(root, "recent").also { it.mkdirs() }
        val alive = File(root, "alive").also { it.mkdirs(); file(it, "project.json", 999); file(it, "export.part", 120); file(it, "voz.wav", 120) }
        PrincessLibrary.sweepDir(root)
        assertFalse(dead.exists()); assertTrue(recent.exists())
        assertTrue(File(alive, "project.json").exists()); assertTrue(File(alive, "voz.wav").exists())
        assertFalse("los .part viejos se borran", File(alive, "export.part").exists())
        root.deleteRecursively()
    }

    @Test fun aLateSaveDoesNotResurrectADeletedStory() {
        val d = tmp()
        val p = project("a.wav")
        PrincessLibrary.saveInto(d, p, createIfMissing = true)
        assertTrue(File(d, "project.json").exists())
        d.deleteRecursively()
        PrincessLibrary.saveInto(d, p, createIfMissing = false)   // guardado tardío después de borrar
        assertFalse("el cuento borrado no debe reaparecer", File(d, "project.json").exists())
    }

    @Test fun savingAnExistingStoryRewritesIt() {
        val d = tmp()
        PrincessLibrary.saveInto(d, project("a.wav"), createIfMissing = true)
        PrincessLibrary.saveInto(d, project("a.wav", "b.wav"), createIfMissing = false)
        assertTrue(File(d, "project.json").readText().contains("b.wav"))
        d.deleteRecursively()
    }

    @Test fun duplicateCopiesAudiosButNotProjectFileTempsOrParts() {
        val src = tmp(); val dst = File(tmp(), "copia")
        file(src, "voz.wav", 5); file(src, "project.json", 5); file(src, "project-1.tmp", 5); file(src, "x.part", 5)
        PrincessLibrary.duplicateDirs(src, dst)
        assertTrue(File(dst, "voz.wav").exists())
        assertFalse(File(dst, "project.json").exists()); assertFalse(File(dst, "project-1.tmp").exists()); assertFalse(File(dst, "x.part").exists())
        src.deleteRecursively(); dst.deleteRecursively()
    }
}
