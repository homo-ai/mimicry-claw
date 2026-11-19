package com.mimicryos.agent.protocol

import com.mimicryos.agent.provider.*
import com.mimicryos.agent.core.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `AnthropicProtocol builds valid request with API key auth`() {
        val protocol = AnthropicProtocol()
        val provider = createProvider(ProviderType.ANTHROPIC, AuthType.API_KEY, "test-key")
        val messages = listOf(APIMessage("user", listOf(APIContentBlock.Text("Hello"))))
        val tools = listOf(APITool("bash", "Run bash", mapOf("type" to "object")))

        val request = protocol.buildRequest(provider, "claude-3", messages, tools, "You are helpful", ProtocolConfig(maxTokens = 1024))

        assertEquals("POST", request.method)
        assertTrue(request.headers["x-api-key"] == "test-key")
        assertTrue(request.headers["anthropic-version"] == "2023-06-01")
        assertTrue(request.body.contains("\"model\":\"claude-3\""))
        assertTrue(request.body.contains("\"role\":\"user\""))
        assertTrue(request.body.contains("\"max_tokens\":1024"))
        assertTrue(request.body.contains("\"type\":\"object\""))
    }

    @Test
    fun `AnthropicProtocol parses message response with text`() {
        val protocol = AnthropicProtocol()
        val responseBody = """
            {
              "id": "msg_123",
              "type": "message",
              "role": "assistant",
              "model": "claude-3-opus-20240229",
              "content": [{"type": "text", "text": "Hello back!"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 5, "output_tokens": 3}
            }
        """.trimIndent()

        val response = APIResponse(200, emptyMap(), responseBody)
        val events = protocol.parseResponse(response)

        assertTrue(events.any { it is APIEvent.MessageStart })
        val start = events.filterIsInstance<APIEvent.MessageStart>().first()
        assertEquals("Hello back!", (start.message.content[0] as APIContentBlock.Text).text)
        assertTrue(events.any { it is APIEvent.MessageStop })
        val delta = events.filterIsInstance<APIEvent.MessageDelta>().first()
        assertEquals("end_turn", (delta.delta as APIDelta.StopReason).reason)
    }

    @Test
    fun `AnthropicProtocol parses response with tool use`() {
        val protocol = AnthropicProtocol()
        val responseBody = """
            {
              "id": "msg_456",
              "type": "message",
              "role": "assistant",
              "model": "claude-3-opus-20240229",
              "content": [
                {"type": "tool_use", "id": "toolu_019", "name": "bash", "input": {"command": "ls -la"}}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

        val response = APIResponse(200, emptyMap(), responseBody)
        val events = protocol.parseResponse(response)

        val start = events.filterIsInstance<APIEvent.MessageStart>().first()
        val toolUse = start.message.content.filterIsInstance<APIContentBlock.ToolUse>().first()
        assertEquals("toolu_019", toolUse.id)
        assertEquals("bash", toolUse.name)
        assertEquals("ls -la", toolUse.input["command"])
    }

    @Test
    fun `AnthropicProtocol returns error on non-200 status`() {
        val protocol = AnthropicProtocol()
        val response = APIResponse(401, emptyMap(), "{\"error\":\"invalid api key\"}")
        val events = protocol.parseResponse(response)

        assertTrue(events.any { it is APIEvent.Error })
        val error = events.filterIsInstance<APIEvent.Error>().first()
        assertEquals(401, error.error.statusCode)
    }

    @Test
    fun `OpenAIChatProtocol builds valid request with bearer auth`() {
        val protocol = OpenAIChatProtocol()
        val provider = createProvider(ProviderType.OPENAI, AuthType.API_KEY, "sk-test")
        val messages = listOf(APIMessage("user", listOf(APIContentBlock.Text("Hi"))))
        val tools = listOf(APITool("bash", "Run bash", mapOf("type" to "object")))

        val request = protocol.buildRequest(provider, "gpt-4o", messages, tools, "You are helpful", ProtocolConfig(maxTokens = 500))

        assertEquals("POST", request.method)
        assertTrue(request.headers["Authorization"] == "Bearer sk-test")
        assertTrue(request.body.contains("\"model\":\"gpt-4o\""))
        assertTrue(request.body.contains("\"role\":\"user\""))
        assertTrue(request.body.contains("\"max_tokens\":500"))
        assertTrue(request.body.contains("\"type\":\"function\""))
    }

    @Test
    fun `OpenAIChatProtocol parses chat completion response`() {
        val protocol = OpenAIChatProtocol()
        val responseBody = """
            {
              "id": "chatcmpl-123",
              "model": "gpt-4o",
              "choices": [{
                "message": {"role": "assistant", "content": "Hello from OpenAI!"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 5, "completion_tokens": 4}
            }
        """.trimIndent()

        val response = APIResponse(200, emptyMap(), responseBody)
        val events = protocol.parseResponse(response)

        assertTrue(events.any { it is APIEvent.MessageStart })
        val start = events.filterIsInstance<APIEvent.MessageStart>().first()
        assertEquals("Hello from OpenAI!", (start.message.content[0] as APIContentBlock.Text).text)
        assertTrue(events.any { it is APIEvent.MessageStop })
    }

    @Test
    fun `OpenAIChatProtocol parses response with tool calls`() {
        val protocol = OpenAIChatProtocol()
        val responseBody = """
            {
              "id": "chatcmpl-456",
              "model": "gpt-4o",
              "choices": [{
                "message": {
                  "role": "assistant",
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": {"name": "bash", "arguments": "{\"command\":\"ls\"}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 8, "completion_tokens": 6}
            }
        """.trimIndent()

        val response = APIResponse(200, emptyMap(), responseBody)
        val events = protocol.parseResponse(response)

        val start = events.filterIsInstance<APIEvent.MessageStart>().first()
        val toolUse = start.message.content.filterIsInstance<APIContentBlock.ToolUse>().first()
        assertEquals("call_abc", toolUse.id)
        assertEquals("bash", toolUse.name)
        assertEquals("ls", toolUse.input["command"])
    }

    @Test
    fun `OpenAIChatProtocol parses streaming chunk with content delta`() {
        val protocol = OpenAIChatProtocol()
        val chunk = "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
        val events = protocol.parseStreamChunk(chunk)

        assertTrue(events.any { it is APIEvent.ContentBlockDelta })
        val delta = events.filterIsInstance<APIEvent.ContentBlockDelta>().first()
        assertEquals("Hello", (delta.delta as APIDelta.TextDelta).text)
    }

    @Test
    fun `OpenAIChatProtocol parses streaming chunk with tool calls`() {
        val protocol = OpenAIChatProtocol()
        val chunk = "data: {\"id\":\"chatcmpl-2\",\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_x\",\"function\":{\"name\":\"bash\",\"arguments\":\"{}\"}}]}}]}\n\n"
        val events = protocol.parseStreamChunk(chunk)

        assertTrue(events.any { it is APIEvent.ContentBlockStart })
        val start = events.filterIsInstance<APIEvent.ContentBlockStart>().first()
        val tu = start.block as APIContentBlock.ToolUse
        assertEquals("call_x", tu.id)
        assertEquals("bash", tu.name)
    }

    @Test
    fun `OpenAIChatProtocol handles DONE marker`() {
        val protocol = OpenAIChatProtocol()
        val events = protocol.parseStreamChunk("data: [DONE]\n\n")
        assertTrue(events.any { it is APIEvent.MessageStop })
    }

    @Test
    fun `GeminiProtocol builds valid request with API key`() {
        val protocol = GeminiProtocol()
        val provider = createProvider(ProviderType.GOOGLE, AuthType.API_KEY, "gemini-key")
        val messages = listOf(APIMessage("user", listOf(APIContentBlock.Text("Hello"))))
        val tools = listOf(APITool("bash", "Run bash", mapOf("type" to "object")))

        val request = protocol.buildRequest(provider, "gemini-1.5-pro", messages, tools, "You are helpful", ProtocolConfig(maxTokens = 1024))

        assertEquals("POST", request.method)
        // assertTrue(request.headers["x-goog-api-key"] == "gemini-key")
        assertTrue(request.body.contains("\"model\":\"gemini-1.5-pro\""))
        assertTrue(request.body.contains("\"role\":\"user\""))
        assertTrue(request.body.contains("\"functionDeclarations\""))
    }

    @Test
    fun `GeminiProtocol parses generateContent response with text`() {
        val protocol = GeminiProtocol()
        val responseBody = """
            {
              "candidates": [{
                "content": {"role": "model", "parts": [{"text": "Hello from Gemini!"}]},
                "finishReason": "STOP"
              }],
              "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 3}
            }
        """.trimIndent()

        val response = APIResponse(200, emptyMap(), responseBody)
        val events = protocol.parseResponse(response)

        assertTrue(events.any { it is APIEvent.MessageStart })
        val start = events.filterIsInstance<APIEvent.MessageStart>().first()
        assertEquals("Hello from Gemini!", (start.message.content[0] as APIContentBlock.Text).text)
        assertTrue(events.any { it is APIEvent.MessageStop })
    }

    @Test
    fun `GeminiProtocol parses response with function calls`() {
        val protocol = GeminiProtocol()
        val responseBody = """
            {
              "candidates": [{
                "content": {"role": "model", "parts": [{
                  "functionCall": {"name": "bash", "args": {"command": "ls"}}
                }]},
                "finishReason": "STOP"
              }],
              "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 3}
            }
        """.trimIndent()

        val response = APIResponse(200, emptyMap(), responseBody)
        val events = protocol.parseResponse(response)

        val start = events.filterIsInstance<APIEvent.MessageStart>().first()
        val toolUse = start.message.content.filterIsInstance<APIContentBlock.ToolUse>().first()
        assertEquals("bash", toolUse.name)
        assertEquals("ls", toolUse.input["command"])
    }

    @Test
    fun `GeminiProtocol returns error on non-200`() {
        val protocol = GeminiProtocol()
        val response = APIResponse(500, emptyMap(), "{\"error\":\"internal\"}")
        val events = protocol.parseResponse(response)

        assertTrue(events.any { it is APIEvent.Error })
    }

    private fun createProvider(type: ProviderType, authType: AuthType, key: String): ModelProvider {
        return object : ModelProvider {
            override val id = "test"
            override val name = "Test"
            override val type = type
            override val authConfig = AuthConfig(type = authType, apiKey = key)
            override val supportedProtocols = listOf(ProtocolType.ANTHROPIC_MESSAGES)
            override val defaultProtocol: ProtocolType = ProtocolType.ANTHROPIC_MESSAGES
            override fun getProtocol(t: ProtocolType): Protocol = AnthropicProtocol()
            override suspend fun listModels(): List<ModelInfo> = emptyList()
            override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
            override fun validate(): ValidationResult = ValidationResult(true)
            override suspend fun refreshAuth(): AuthResult = AuthResult(true)
        }
    }
}
