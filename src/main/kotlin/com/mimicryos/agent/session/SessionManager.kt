package com.mimicryos.agent.session

import com.mimicryos.agent.core.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SessionFile(
    val id: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val messages: List<Message>,
)

class SessionManager(private val baseDir: String) {
    private val sessions = ConcurrentHashMap<String, SessionData>()
    private val sessionDir = File(baseDir, ".mimicry/sessions")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
        prettyPrint = false
    }

    init { sessionDir.mkdirs() }

    fun createSession(): String {
        val id = java.util.UUID.randomUUID().toString()
        sessions[id] = SessionData(id, System.currentTimeMillis(), mutableListOf())
        return id
    }

    fun saveMessages(sessionId: String, messages: List<Message>) {
        val session = sessions.getOrPut(sessionId) { SessionData(sessionId, System.currentTimeMillis(), mutableListOf()) }
        session.messages.clear()
        session.messages.addAll(messages)
        session.lastActiveAt = System.currentTimeMillis()
        persistSession(session)
    }

    fun loadMessages(sessionId: String): List<Message> {
        sessions[sessionId]?.let { return it.messages.toList() }
        val sessionFile = File(sessionDir, "$sessionId.json")
        if (!sessionFile.exists()) return emptyList()
        return try {
            val data = json.decodeFromString(SessionFile.serializer(), sessionFile.readText())
            sessions[sessionId] = SessionData(data.id, data.createdAt, data.messages.toMutableList(), data.lastActiveAt)
            data.messages
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSessionInfo(sessionId: String): SessionInfo? {
        sessions[sessionId]?.let { return SessionInfo(it.id, it.createdAt, it.lastActiveAt, it.messages.size) }
        val sessionFile = File(sessionDir, "$sessionId.json")
        if (!sessionFile.exists()) return null
        return try {
            val data = json.decodeFromString(SessionFile.serializer(), sessionFile.readText())
            SessionInfo(data.id, data.createdAt, data.lastActiveAt, data.messages.size)
        } catch (e: Exception) {
            null
        }
    }

    fun listSessions(): List<SessionInfo> {
        val result = LinkedHashMap<String, SessionInfo>()
        sessions.values.forEach { result[it.id] = SessionInfo(it.id, it.createdAt, it.lastActiveAt, it.messages.size) }
        sessionDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.forEach { file ->
            val id = file.nameWithoutExtension
            if (!result.containsKey(id)) {
                try {
                    val data = json.decodeFromString(SessionFile.serializer(), file.readText())
                    result[id] = SessionInfo(data.id, data.createdAt, data.lastActiveAt, data.messages.size)
                } catch (e: Exception) {
                    // Ignore unreadable session files instead of failing the whole listing.
                }
            }
        }
        return result.values.sortedByDescending { it.lastActiveAt }
    }

    fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        File(sessionDir, "$sessionId.json").delete()
    }

    private fun persistSession(session: SessionData) {
        val data = SessionFile(
            id = session.id,
            createdAt = session.createdAt,
            lastActiveAt = session.lastActiveAt,
            messages = session.messages.toList(),
        )
        sessionDir.mkdirs()
        File(sessionDir, "${session.id}.json").writeText(json.encodeToString(data))
    }

    data class SessionData(val id: String, val createdAt: Long, val messages: MutableList<Message>, var lastActiveAt: Long = System.currentTimeMillis())
    data class SessionInfo(val id: String, val createdAt: Long, val lastActiveAt: Long, val messageCount: Int)
}
