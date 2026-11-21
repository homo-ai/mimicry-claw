package com.mimicryos.agent.runtime

import com.mimicryos.agent.app.AppConfig
import com.mimicryos.agent.app.AppFactory
import com.mimicryos.agent.app.MimicryApplicationService
import com.mimicryos.agent.app.TaskRequest
import com.mimicryos.agent.app.TaskRunStatus
import com.mimicryos.agent.provider.APIResponse
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Starts the real loopback HTTP server and drives it through the real client.
 * No runtime.json is published so the developer's own runtime is never touched.
 */
class RuntimeServerTest {

    private val answerBody = """
        {
          "role": "assistant",
          "content": [{"type": "text", "text": "Served answer"}],
          "stop_reason": "end_turn",
          "usage": {"input_tokens": 3, "output_tokens": 2}
        }
    """.trimIndent()

    @Test
    fun `serves health, snapshot, tasks and sessions over HTTP`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-runtime").toFile()
        val config = AppConfig(
            projectDir = dir.path,
            providerId = "anthropic",
            model = "claude-3-5-sonnet-20241022",
            apiKey = "test-key",
            interactivePermissions = false,
        )
        val service = MimicryApplicationService(
            AppFactory.create(config),
            config,
            requestExecutor = { _, _ -> APIResponse(200, emptyMap(), answerBody) },
        )
        val server = RuntimeServer(
            application = service,
            publishRuntimeInfo = false,
            checkExisting = false,
        )
        val info = server.start()
        try {
            val client = RuntimeClient(info)

            assertTrue(client.health(), "health endpoint should answer")
            assertEquals("anthropic", client.snapshot().providerId)

            val task = client.submit(TaskRequest(prompt = "hello over http"))
            val finished = client.awaitTask(task.id, 20_000)

            assertNotNull(finished)
            assertEquals(TaskRunStatus.COMPLETED, finished.status)
            assertEquals("Served answer", finished.result)

            assertTrue(client.listSessions().any { it.id == task.sessionId })
            val transcript = client.sessionTranscript(task.sessionId)
            assertNotNull(transcript)
            assertTrue(transcript.messages.any { it.role == "user" && it.text == "hello over http" })
            assertTrue(transcript.messages.any { it.role == "assistant" && it.text == "Served answer" })
            assertEquals(transcript.messages.size, transcript.messages.map { it.id }.distinct().size)
            assertEquals(null, client.sessionTranscript("nonexistent"))
            val newSession = client.createSession()
            assertEquals(0, newSession.messageCount)
            assertTrue(client.listSessions().any { it.id == newSession.id })
            assertTrue(client.sessionTranscript(newSession.id)!!.messages.isEmpty())
            assertTrue(client.eventHistory().isNotEmpty())
            assertEquals("claude-3-5-sonnet-20241022", client.runtimeConfig().model)
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejects requests without the bearer token`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-runtime-auth").toFile()
        val config = AppConfig(projectDir = dir.path, providerId = "anthropic", apiKey = "test-key")
        val service = MimicryApplicationService(
            AppFactory.create(config),
            config,
            requestExecutor = { _, _ -> APIResponse(200, emptyMap(), answerBody) },
        )
        val server = RuntimeServer(
            application = service,
            publishRuntimeInfo = false,
            checkExisting = false,
        )
        val info = server.start()
        try {
            val unauthorized = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("${info.baseUrl}/v1/snapshot"))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthorized.statusCode())

            val wrongToken = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("${info.baseUrl}/health"))
                    .header("Authorization", "Bearer wrong")
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, wrongToken.statusCode())
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }
}
