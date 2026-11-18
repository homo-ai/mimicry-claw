package com.mimicryos.agent.session

import com.mimicryos.agent.core.Message
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SessionManager(private val baseDir: String) {
    private val sessions = ConcurrentHashMap<String, SessionData>()
    private val sessionDir = File(baseDir, ".claude/sessions")
    init { sessionDir.mkdirs() }

    fun createSession(): String { val id = java.util.UUID.randomUUID().toString(); sessions[id] = SessionData(id, System.currentTimeMillis(), mutableListOf()); return id }
    fun saveMessages(sessionId: String, messages: List<Message>) {
        val session = sessions.getOrPut(sessionId) { SessionData(sessionId, System.currentTimeMillis(), mutableListOf()) }
        session.messages.clear(); session.messages.addAll(messages); session.lastActiveAt = System.currentTimeMillis()
        persistSession(session)
    }
    fun loadMessages(sessionId: String): List<Message> { sessions[sessionId]?.let { return it.messages.toList() }; val sessionFile = File(sessionDir, "$sessionId.json"); return if (sessionFile.exists()) emptyList() else emptyList() }
    fun listSessions(): List<SessionInfo> = sessions.values.map { SessionInfo(it.id, it.createdAt, it.lastActiveAt, it.messages.size) }
    fun deleteSession(sessionId: String) { sessions.remove(sessionId); File(sessionDir, "$sessionId.json").deleteRecursively() }
    private fun persistSession(session: SessionData) { File(sessionDir, "${session.id}.json").writeText("session: ${session.id}") }
    data class SessionData(val id: String, val createdAt: Long, val messages: MutableList<Message>, var lastActiveAt: Long = System.currentTimeMillis())
    data class SessionInfo(val id: String, val createdAt: Long, val lastActiveAt: Long, val messageCount: Int)
}
