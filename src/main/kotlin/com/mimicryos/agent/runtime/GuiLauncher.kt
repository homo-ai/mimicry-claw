package com.mimicryos.agent.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/** Process boundaries keep a GUI window's lifetime independent of the runtime. */
object GuiLauncher {
    fun installationDir(): File = try {
        val location = File(GuiLauncher::class.java.protectionDomain.codeSource.location.toURI())
        if (location.isFile) location.parentFile else File(System.getProperty("user.dir"))
    } catch (e: Exception) {
        File(System.getProperty("user.dir"))
    }

    fun resolveExecutable(installation: File, overridePath: String? = System.getenv("MIMICRY_GUI_PATH")): File? {
        if (!overridePath.isNullOrBlank()) return File(overridePath).takeIf { it.isFile }
        val name = if (System.getProperty("os.name").startsWith("Windows", true)) "Mimicry.Gui.exe" else "Mimicry.Gui"
        return listOf(File(installation, "gui/$name"), File(installation, name))
            .firstOrNull { it.isFile }
    }

    suspend fun launch(runtimeArgs: List<String>, sessionId: String? = null): Int {
        // Resolve first: a JAR-only distribution must not start a background
        // runtime and then fail to show the missing desktop frontend.
        val executable = resolveExecutable(installationDir())
            ?: error("Desktop frontend not found. Extract a complete GUI-enabled platform ZIP, or set MIMICRY_GUI_PATH to its executable.")
        var info = RuntimeDiscovery.find()
        if (info == null) {
            val javaName = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
            val java = File(System.getProperty("java.home"), "bin/$javaName")
            val runtimeDir = RuntimeInfo.path().parent.toFile().apply { mkdirs() }
            val log = File(runtimeDir, "runtime-startup.log")
            val command = listOf(java.absolutePath, "-cp", System.getProperty("java.class.path"),
                "com.mimicryos.agent.MainKt", "serve") + runtimeArgs
            val child = ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            // Do not inherit stdin; the server is controlled by authenticated API.
            child.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (info == null && System.nanoTime() < deadline) {
                if (!child.isAlive) error("Runtime startup failed (exit ${child.exitValue()}); inspect ${log.path}")
                kotlinx.coroutines.delay(150)
                info = RuntimeDiscovery.find()
            }
            if (info == null) {
                child.destroy()
                error("Runtime startup timed out; inspect ${log.path}")
            }
        }
        val attached = requireNotNull(info)
        println("Opening GUI attached to ${attached.baseUrl}; closing the window leaves the runtime running.")
        println("Runtime project: ${attached.projectDir}")
        val command = mutableListOf(executable.absolutePath, "--runtime-file", RuntimeInfo.path().toString())
        sessionId?.let { command.addAll(listOf("--session", it)) }
        return ProcessBuilder(command).inheritIO().start().waitFor()
    }
}
