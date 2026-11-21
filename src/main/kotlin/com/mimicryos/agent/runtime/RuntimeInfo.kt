package com.mimicryos.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class RuntimeInfo(
    val host: String,
    val port: Int,
    val token: String,
    val pid: Long,
    val startedAt: Long,
    val projectDir: String,
    val runtimeId: String,
) {
    val baseUrl: String get() = "http://$host:$port"

    fun isProcessAlive(): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun path(): Path = Paths.get(System.getProperty("user.home"), ".mimicry", "runtime.json")

        fun generateToken(): String {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun read(): RuntimeInfo? = try {
            val file = path()
            if (!Files.exists(file)) null else json.decodeFromString(RuntimeInfo.serializer(), Files.readString(file))
        } catch (e: Exception) {
            null
        }

        fun write(info: RuntimeInfo) {
            val file = path()
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(info))
        }

        fun delete() {
            try {
                Files.deleteIfExists(path())
            } catch (e: Exception) {
                // Ignore cleanup failures.
            }
        }
    }
}

object RuntimeDiscovery {
    suspend fun find(): RuntimeInfo? {
        val info = RuntimeInfo.read() ?: return null
        if (!info.isProcessAlive()) {
            RuntimeInfo.delete()
            return null
        }
        return if (RuntimeClient(info).health()) info else null
    }

    suspend fun require(): RuntimeInfo =
        find() ?: error("No running Mimicry runtime found. Start one with: mimicry serve")
}
