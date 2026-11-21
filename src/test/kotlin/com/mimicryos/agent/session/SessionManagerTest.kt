package com.mimicryos.agent.session

import com.mimicryos.agent.core.Content
import com.mimicryos.agent.core.Message
import com.mimicryos.agent.core.UserMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionManagerTest {

    @Test
    fun `persists messages and restores them from disk`() {
        val dir = Files.createTempDirectory("mimicry-session").toFile()
        try {
            val manager = SessionManager(dir.path)
            val sessionId = manager.createSession()
            val messages = listOf(
                Message.User("u-1", UserMessage("user", Content.Text("first question"))),
                Message.User("u-2", UserMessage("user", Content.Text("second question"))),
            )

            manager.saveMessages(sessionId, messages)

            val sessionFile = dir.resolve(".mimicry/sessions/$sessionId.json")
            assertTrue(sessionFile.exists(), "session file should exist on disk")

            // A fresh manager must read the session back instead of returning an empty list.
            val reopened = SessionManager(dir.path)
            val loaded = reopened.loadMessages(sessionId)

            assertEquals(2, loaded.size)
            val first = loaded.first() as Message.User
            assertEquals("first question", (first.message.content as Content.Text).text)
            assertNotNull(reopened.getSessionInfo(sessionId))
            assertEquals(2, reopened.listSessions().single().messageCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `returns empty list for unknown session`() {
        val dir = Files.createTempDirectory("mimicry-session-missing").toFile()
        try {
            val manager = SessionManager(dir.path)
            assertTrue(manager.loadMessages("does-not-exist").isEmpty())
            assertEquals(null, manager.getSessionInfo("does-not-exist"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
