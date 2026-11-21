package com.mimicryos.agent.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuiLauncherTest {
    @Test
    fun `resolves packaged desktop executable without launching processes`() {
        val dir = Files.createTempDirectory("mimicry-gui-launch").toFile()
        try {
            assertNull(GuiLauncher.resolveExecutable(dir, null))
            val guiDir = File(dir, "gui").apply { mkdirs() }
            val name = if (System.getProperty("os.name").startsWith("Windows", true)) "Mimicry.Gui.exe" else "Mimicry.Gui"
            val executable = File(guiDir, name).apply { writeText("fixture") }
            assertEquals(executable, GuiLauncher.resolveExecutable(dir, null))
            val custom = File(dir, "custom").apply { writeText("fixture") }
            assertEquals(custom, GuiLauncher.resolveExecutable(dir, custom.path))
            assertNull(GuiLauncher.resolveExecutable(dir, File(dir, "missing").path))
        } finally { dir.deleteRecursively() }
    }
}
