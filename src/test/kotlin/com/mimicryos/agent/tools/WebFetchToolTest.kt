package com.mimicryos.agent.tools

import com.mimicryos.agent.core.AgentDefinitionsResult
import com.mimicryos.agent.core.AbortController
import com.mimicryos.agent.core.AppState
import com.mimicryos.agent.core.AssistantMessage
import com.mimicryos.agent.core.FileStateCache
import com.mimicryos.agent.core.Message
import com.mimicryos.agent.permission.PermissionResult
import com.mimicryos.agent.core.ThinkingConfig
import com.mimicryos.agent.core.Tool
import com.mimicryos.agent.core.ToolOptions
import com.mimicryos.agent.core.ToolUseContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebFetchToolTest {
    private val allow = object : com.mimicryos.agent.core.CanUseToolFn {
        override suspend fun invoke(
            tool: Tool<*, *>,
            input: Any,
            context: ToolUseContext,
            assistantMessage: Message.Assistant,
            toolUseID: String,
            forceDecision: String?,
        ): PermissionResult = PermissionResult.allow(input)
    }
    private val parent = Message.Assistant(uuid = "parent", message = AssistantMessage("assistant", emptyList()))
    private val context = ToolUseContext(
        options = ToolOptions(
            mainLoopModel = "test-model",
            tools = ToolRegistry.createDefault().getAll(),
            thinkingConfig = ThinkingConfig(),
            agentDefinitions = AgentDefinitionsResult(emptyList(), emptyList()),
            cwd = File(System.getProperty("java.io.tmpdir")).path,
        ),
        abortController = AbortController(),
        readFileState = FileStateCache(),
        getAppState = { AppState() },
        setAppState = {},
    )

    @Test
    fun `rejects non-http and private URLs before using the transport`() = runBlocking {
        val transport = RecordingTransport()
        val original = WebFetchTool.transport
        WebFetchTool.transport = transport
        try {
            val fileResult = WebFetchTool.call(WebFetchInput("file:///etc/passwd"), context, allow, parent)
            assertTrue(fileResult.data.error!!.contains("http"))
            val localResult = WebFetchTool.call(WebFetchInput("http://127.0.0.1:8080"), context, allow, parent)
            assertTrue(localResult.data.error!!.contains("private"))
            assertEquals(0, transport.calls)
        } finally {
            WebFetchTool.transport = original
        }
    }

    @Test
    fun `fetches a public page and extracts readable text`() = runBlocking {
        val transport = RecordingTransport(
            WebFetchResponse(
                statusCode = 200,
                contentType = "text/html; charset=utf-8",
                body = "<html><head><script>secret()</script><style>.x{}</style></head>" +
                    "<body><h1>Hello</h1><p>World &amp; friends</p></body></html>",
                finalUrl = "https://93.184.216.34/page",
            ),
        )
        val original = WebFetchTool.transport
        WebFetchTool.transport = transport
        try {
            val result = WebFetchTool.call(
                WebFetchInput("https://93.184.216.34/page", "find greeting", 5_000),
                context, allow, parent,
            )
            println("WebFetch result: ${result.data}")
            assertTrue(result.data.error == null, "unexpected WebFetch error: ${result.data.error}")
            assertTrue(result.data.content.contains("Hello"), "missing heading: ${result.data.content}")
            assertTrue(result.data.content.contains("World & friends"), "missing decoded text: ${result.data.content}")
            assertTrue(!result.data.content.contains("secret"), "script body leaked into text")
            assertEquals("200", result.data.metadata["status"])
            assertEquals("find greeting", result.data.metadata["prompt"])
            assertEquals(1, transport.calls)
            assertEquals(5_000, transport.timeoutMs)
        } finally {
            WebFetchTool.transport = original
        }
    }

    @Test
    fun `html extractor decodes entities and ignores presentation markup`() {
        val text = HtmlTextExtractor.extract("<div>A&nbsp;B</div><script>bad()</script><br><span>C</span>")
        assertEquals("A B\nC", text)
    }

    private class RecordingTransport(private val response: WebFetchResponse? = null) : WebFetchTransport {
        var calls = 0
        var timeoutMs = 0
        override fun fetch(url: String, timeoutMs: Int): WebFetchResponse {
            calls++
            this.timeoutMs = timeoutMs
            return response ?: throw AssertionError("Transport should not be called")
        }
    }
}
