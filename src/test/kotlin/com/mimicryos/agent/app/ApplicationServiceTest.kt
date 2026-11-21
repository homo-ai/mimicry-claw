package com.mimicryos.agent.app

import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.provider.APIResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplicationServiceTest {

    private val answerBody = """
        {
          "role": "assistant",
          "model": "claude-3-5-sonnet-20241022",
          "content": [{"type": "text", "text": "Fake answer"}],
          "stop_reason": "end_turn",
          "usage": {"input_tokens": 12, "output_tokens": 4}
        }
    """.trimIndent()

    private fun createService(dir: File, executor: suspend (com.mimicryos.agent.provider.APIRequest, com.mimicryos.agent.provider.ModelProvider) -> APIResponse): MimicryApplicationService {
        val config = AppConfig(
            projectDir = dir.path,
            providerId = "anthropic",
            model = "claude-3-5-sonnet-20241022",
            apiKey = "test-key",
            interactivePermissions = false,
        )
        val components = AppFactory.create(config)
        return MimicryApplicationService(components, config, requestExecutor = executor)
    }

    @Test
    fun `runs a task end to end, emits events and persists the session`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-app").toFile()
        val service = createService(dir) { _, _ -> APIResponse(200, emptyMap(), answerBody) }
        try {
            val snapshot = service.snapshot()
            assertEquals("anthropic", snapshot.providerId)
            assertEquals("claude-3-5-sonnet-20241022", snapshot.model)
            assertTrue(snapshot.tools.any { it.name == "Bash" && it.implemented })
            assertTrue(snapshot.providers.any { it.id == "anthropic" && it.configured })

            val task = service.submit(TaskRequest(prompt = "hello"))
            val finished = service.awaitTask(task.id, 20_000)

            assertNotNull(finished)
            assertEquals(TaskRunStatus.COMPLETED, finished.status)
            assertEquals("Fake answer", finished.result)
            assertEquals(4L, finished.outputTokens)

            val events = service.eventHistory().map { it.event }
            assertTrue(events.any { it is AgentEvent.TaskCreated })
            assertTrue(events.any { it is AgentEvent.TaskStarted })
            assertTrue(events.any { it is AgentEvent.TaskCompleted })

            val sessionFile = File(dir, ".mimicry/sessions/${task.sessionId}.json")
            assertTrue(sessionFile.exists(), "the session transcript must be persisted")
            assertTrue(service.listSessions().any { it.id == task.sessionId })

            val resumed = service.resumeSession(task.sessionId)
            assertNotNull(resumed)
            assertTrue(resumed.messageCount >= 2)
        } finally {
            service.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `interrupt marks the running task as cancelled`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-app-interrupt").toFile()
        val service = createService(dir) { _, _ ->
            delay(10_000)
            APIResponse(200, emptyMap(), answerBody)
        }
        try {
            val task = service.submit(TaskRequest(prompt = "long running"))
            delay(500)
            assertEquals(TaskRunStatus.RUNNING, service.task(task.id)?.status)

            assertTrue(service.interrupt(task.id))
            val finished = service.awaitTask(task.id, 10_000)

            assertNotNull(finished)
            assertEquals(TaskRunStatus.CANCELLED, finished.status)
            assertTrue(service.eventHistory().map { it.event }.any { it is AgentEvent.TaskCancelled })
        } finally {
            service.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `permission snapshot exposes waiting state and explicit approval resumes the task`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-app-permission").toFile()
        val config = AppConfig(projectDir = dir.path, providerId = "anthropic", apiKey = "test-key", interactivePermissions = true)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val service = MimicryApplicationService(AppFactory.create(config), config, requestExecutor = { _, _ ->
            val body = if (calls.getAndIncrement() == 0) """
                {"role":"assistant","content":[{"type":"tool_use","id":"write-permission","name":"Write","input":{"file_path":"approved.txt","content":"ok"}}],"stop_reason":"tool_use"}
            """ else answerBody
            APIResponse(200, emptyMap(), body)
        })
        try {
            val task = service.submit(TaskRequest("write a file"))
            kotlinx.coroutines.withTimeout(5_000) {
                while (service.snapshot().pendingPermissions.isEmpty()) delay(20)
            }
            val snapshot = service.snapshot()
            assertEquals(TaskRunStatus.WAITING_PERMISSION, service.task(task.id)?.status)
            assertTrue(!File(dir, "approved.txt").exists())
            val request = snapshot.pendingPermissions.single()
            assertTrue(service.respondToPermission(request.id, true, null))
            assertTrue(!service.respondToPermission(request.id, true, null))
            val completed = service.awaitTask(task.id, 20_000)
            assertEquals(TaskRunStatus.COMPLETED, completed?.status)
            assertEquals("ok", File(dir, "approved.txt").readText())
            val transcript = service.sessionTranscript(task.sessionId)!!
            assertTrue(transcript.messages.any { it.toolName == "Write" && it.role == "tool_call" })
            assertTrue(transcript.messages.any { it.toolUseId == "write-permission" && it.role == "tool" })
        } finally {
            service.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `new sessions persist and path traversal ids are rejected`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-app-new-session").toFile()
        val service = createService(dir) { _, _ -> APIResponse(200, emptyMap(), answerBody) }
        try {
            val session = service.createSession()
            assertTrue(File(dir, ".mimicry/sessions/${session.id}.json").exists())
            assertEquals(0, service.sessionTranscript(session.id)?.messages?.size)
            assertEquals(null, service.sessionTranscript("../../outside"))
            assertEquals(null, service.resumeSession("../../outside"))
            assertTrue(runCatching { service.submit(TaskRequest("hi", sessionId = "../../outside")) }.isFailure)
            val task = service.submit(TaskRequest("new session task"))
            assertEquals(session.id, task.sessionId)
            service.awaitTask(task.id, 20_000)
            assertTrue(service.sessionTranscript(session.id)!!.messages.any { it.text == "Fake answer" })
        } finally {
            service.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejects an unknown provider instead of silently falling back`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-app-provider").toFile()
        val service = createService(dir) { _, _ -> APIResponse(200, emptyMap(), answerBody) }
        try {
            val failed = runCatching { service.submit(TaskRequest(prompt = "hi", providerId = "nope")) }
            assertTrue(failed.isFailure)
            assertTrue(!service.setProvider("nope"))
            assertTrue(service.setProvider("openai"))
            assertEquals("openai", service.snapshot().providerId)
        } finally {
            service.shutdown()
            dir.deleteRecursively()
        }
    }
}
