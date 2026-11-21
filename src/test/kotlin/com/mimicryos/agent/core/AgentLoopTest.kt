package com.mimicryos.agent.core

import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.permission.PermissionResult
import com.mimicryos.agent.provider.APIRequest
import com.mimicryos.agent.provider.APIResponse
import com.mimicryos.agent.provider.ModelProvider
import com.mimicryos.agent.provider.ProviderCatalog
import com.mimicryos.agent.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real agent loop against a fake transport: the model asks for
 * the real Write tool, the loop must decode the input, run the tool, feed the
 * result back and then surface the final assistant text.
 */
class AgentLoopTest {

    private val toolUseResponse = """
        {
          "role": "assistant",
          "model": "claude-3-5-sonnet-20241022",
          "content": [
            {"type": "tool_use", "id": "toolu_01", "name": "Write",
             "input": {"file_path": "out.txt", "content": "written by the agent"}}
          ],
          "stop_reason": "tool_use",
          "usage": {"input_tokens": 11, "output_tokens": 7}
        }
    """.trimIndent()

    private val finalResponse = """
        {
          "role": "assistant",
          "model": "claude-3-5-sonnet-20241022",
          "content": [{"type": "text", "text": "Done: file written."}],
          "stop_reason": "end_turn",
          "usage": {"input_tokens": 20, "output_tokens": 5}
        }
    """.trimIndent()

    @Test
    fun `runs a tool call and returns the tool result to the model`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-loop").toFile()
        try {
            val registry = ProviderCatalog.createRegistry(
                ProviderCatalog.Settings("anthropic", "test-key", null)
            )
            val tools = ToolRegistry.createDefault().getEnabled()
            val responses = listOf(toolUseResponse, finalResponse)
            val calls = AtomicInteger(0)
            val events = mutableListOf<AgentEvent>()

            val executor: suspend (APIRequest, ModelProvider) -> APIResponse = { _, _ ->
                val index = calls.getAndIncrement().coerceAtMost(responses.lastIndex)
                APIResponse(200, emptyMap(), responses[index])
            }

            val canUseTool = object : CanUseToolFn {
                override suspend fun invoke(
                    tool: Tool<*, *>,
                    input: Any,
                    context: ToolUseContext,
                    assistantMessage: Message.Assistant,
                    toolUseID: String,
                    forceDecision: String?,
                ): PermissionResult = PermissionResult.allow(input)
            }

            val engine = QueryEngine(
                QueryEngine.QueryEngineConfig(
                    sessionId = "session-test",
                    cwd = dir.path,
                    tools = tools,
                    canUseTool = canUseTool,
                    getAppState = { AppState() },
                    setAppState = { },
                    mainLoopModel = "claude-3-5-sonnet-20241022",
                    providerId = "anthropic",
                    registry = registry,
                    readFileCache = FileStateCache(),
                    maxTurns = 5,
                    eventListener = { events += it },
                    eventTaskId = "task-test",
                    eventSessionId = "session-test",
                    requestExecutor = executor,
                )
            )

            val messages = engine.submitMessage("Write out.txt").run()

            assertEquals(2, calls.get(), "the loop should call the model twice")
            assertEquals("written by the agent", File(dir, "out.txt").readText())

            val toolResults = messages.filterIsInstance<Message.User>()
                .map { it.message.content }
                .filterIsInstance<Content.ToolResult>()
            assertEquals(1, toolResults.size, "the tool result must be added to the transcript")
            assertEquals("toolu_01", toolResults.single().toolUseId)
            assertTrue(
                (toolResults.single().content.first() as ResultContent.Text).text.contains("File written"),
                "the tool result should report the successful write",
            )

            val finalText = (messages.last() as Message.Assistant).message.content
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("") { it.text }
            assertEquals("Done: file written.", finalText)

            assertTrue(events.any { it is AgentEvent.ToolStarted && it.toolName == "Write" })
            assertTrue(events.any { it is AgentEvent.ToolCompleted && !it.isError })
            assertTrue(events.any { it is AgentEvent.FileChanged && it.path.endsWith("out.txt") })
            assertTrue(events.any { it is AgentEvent.TaskCompleted || it is AgentEvent.UsageUpdated })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `denied tool calls are reported back instead of executed`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-loop-denied").toFile()
        try {
            val registry = ProviderCatalog.createRegistry(
                ProviderCatalog.Settings("anthropic", "test-key", null)
            )
            val tools = ToolRegistry.createDefault().getEnabled()
            val calls = AtomicInteger(0)

            val executor: suspend (APIRequest, ModelProvider) -> APIResponse = { _, _ ->
                val body = if (calls.getAndIncrement() == 0) toolUseResponse else finalResponse
                APIResponse(200, emptyMap(), body)
            }

            val canUseTool = object : CanUseToolFn {
                override suspend fun invoke(
                    tool: Tool<*, *>,
                    input: Any,
                    context: ToolUseContext,
                    assistantMessage: Message.Assistant,
                    toolUseID: String,
                    forceDecision: String?,
                ): PermissionResult = PermissionResult.deny("not allowed in this test")
            }

            val engine = QueryEngine(
                QueryEngine.QueryEngineConfig(
                    sessionId = "session-denied",
                    cwd = dir.path,
                    tools = tools,
                    canUseTool = canUseTool,
                    getAppState = { AppState() },
                    setAppState = { },
                    mainLoopModel = "claude-3-5-sonnet-20241022",
                    providerId = "anthropic",
                    registry = registry,
                    readFileCache = FileStateCache(),
                    maxTurns = 5,
                    requestExecutor = executor,
                )
            )

            val messages = engine.submitMessage("Write out.txt").run()

            assertTrue(!File(dir, "out.txt").exists(), "a denied tool must not touch the filesystem")
            val toolResult = messages.filterIsInstance<Message.User>()
                .map { it.message.content }
                .filterIsInstance<Content.ToolResult>()
                .single()
            assertTrue(toolResult.isError)
            assertTrue((toolResult.content.first() as ResultContent.Text).text.contains("not allowed"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
