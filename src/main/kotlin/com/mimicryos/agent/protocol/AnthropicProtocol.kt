package com.mimicryos.agent.protocol

import com.mimicryos.agent.provider.*
import com.mimicryos.agent.core.*
import kotlinx.serialization.json.*

private fun anyToJsonElement(value: Any): JsonElement = when (value) {
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), anyToJsonElement(v!!)) } }
    is List<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it!!)) } }
    else -> JsonPrimitive(value.toString())
}

class AnthropicProtocol(
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiVersion: String = "2023-06-01",
) : Protocol {
    override val type: ProtocolType = ProtocolType.ANTHROPIC_MESSAGES
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false }

    override fun getEndpoint(provider: ModelProvider, model: String): String = "$baseUrl/v1/messages"

    override fun getHeaders(provider: ModelProvider, config: ProtocolConfig): Map<String, String> {
        val headers = mutableMapOf("Content-Type" to "application/json", "anthropic-version" to apiVersion, "anthropic-beta" to "messages-2023-12-07,tools-2023-12-07")
        when (provider.authConfig.type) {
            AuthType.API_KEY -> provider.authConfig.apiKey?.let { headers["x-api-key"] = it }
            AuthType.BEARER_TOKEN, AuthType.OAUTH2 -> provider.authConfig.accessToken?.let { headers["Authorization"] = "Bearer $it" }
            else -> {}
        }
        return headers
    }

    override fun buildRequest(model: String, messages: List<APIMessage>, tools: List<APITool>, systemPrompt: String, config: ProtocolConfig): APIRequest {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { msg -> add(buildApiMessage(msg)) }
            })
            put("system", systemPrompt)
            put("max_tokens", config.maxTokens ?: 4096)
            // put("stream", true)  // streaming handled separately via parseStreamChunk
            config.temperature?.let { put("temperature", it) }
            config.topP?.let { put("top_p", it) }
            if (config.stopSequences.isNotEmpty()) put("stop_sequences", buildJsonArray { config.stopSequences.forEach { add(it) } })
            if (tools.isNotEmpty()) put("tools", buildJsonArray { tools.forEach { tool -> add(buildJsonObject { put("name", tool.name); put("description", tool.description); put("input_schema", buildJsonObject { tool.inputSchema.forEach { (k, v) -> put(k, anyToJsonElement(v)) } }) }) } })
            if (config.thinkingBudget != null) put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", config.thinkingBudget) })
        }
        return APIRequest(url = getEndpoint(emptyProvider(), model), method = "POST", headers = getHeaders(emptyProvider(), config), body = body.toString(), timeoutMs = config.timeoutMs)
    }

    private fun buildApiMessage(msg: APIMessage): JsonObject = buildJsonObject {
        put("role", msg.role)
        put("content", buildJsonArray {
            msg.content.forEach { block -> add(buildApiContentBlock(block)) }
        })
    }

    private fun buildApiContentBlock(block: APIContentBlock): JsonObject = when (block) {
        is APIContentBlock.Text -> buildJsonObject { put("type", "text"); put("text", block.text) }
        is APIContentBlock.Thinking -> buildJsonObject { put("type", "thinking"); put("thinking", block.thinking); block.signature?.let { put("signature", it) } }
        is APIContentBlock.ToolUse -> buildJsonObject { put("type", "tool_use"); put("id", block.id); put("name", block.name); put("input", buildJsonObject { block.input.forEach { (k, v) -> put(k, v) } }) }
        is APIContentBlock.ToolResult -> buildJsonObject { put("type", "tool_result"); put("tool_use_id", block.toolUseId); put("content", buildJsonArray { block.content.forEach { add(buildApiResultContent(it)) } }); put("is_error", block.isError) }
        is APIContentBlock.Image -> buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", block.mediaType); put("data", block.data) }) }
    }

    private fun buildApiResultContent(content: APIResultContent): JsonObject = when (content) {
        is APIResultContent.Text -> buildJsonObject { put("type", "text"); put("text", content.text) }
        is APIResultContent.Image -> buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", content.mediaType); put("data", content.data) }) }
    }

    

    override fun parseResponse(response: APIResponse): List<APIEvent> {
        if (response.statusCode != 200) return listOf(APIEvent.Error(APIError("api_error", "HTTP ${response.statusCode}: ${response.body}", response.statusCode, response.statusCode in 429..599)))
        val events = mutableListOf<APIEvent>()
        try {
            val obj = json.parseToJsonElement(response.body).jsonObject
            obj["content"]?.jsonArray?.forEach { block -> val b = block.jsonObject; when (b["type"]?.jsonPrimitive?.content) { "text" -> { events.add(APIEvent.ContentBlockStart(0, APIContentBlock.Text(b["text"]?.jsonPrimitive?.content ?: ""))); events.add(APIEvent.ContentBlockStop(0)) }; "tool_use" -> { val input = b["input"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(); events.add(APIEvent.ContentBlockStart(0, APIContentBlock.ToolUse(b["id"]?.jsonPrimitive?.content ?: "", b["name"]?.jsonPrimitive?.content ?: "", input))); events.add(APIEvent.ContentBlockStop(0)) }; "thinking" -> { events.add(APIEvent.ContentBlockStart(0, APIContentBlock.Thinking(b["thinking"]?.jsonPrimitive?.content ?: "", b["signature"]?.jsonPrimitive?.content))); events.add(APIEvent.ContentBlockStop(0)) } } }
            obj["stop_reason"]?.let { events.add(APIEvent.MessageDelta(APIDelta.StopReason(it.jsonPrimitive.content), null)) }
            obj["usage"]?.let { u -> val us = u.jsonObject; events.add(APIEvent.Usage(APIUsage(us["input_tokens"]?.jsonPrimitive?.content?.toLong() ?: 0, us["output_tokens"]?.jsonPrimitive?.content?.toLong() ?: 0, us["cache_creation_input_tokens"]?.jsonPrimitive?.content?.toLong() ?: 0, us["cache_read_input_tokens"]?.jsonPrimitive?.content?.toLong() ?: 0))) }
            events.add(APIEvent.MessageStop())
        } catch (e: Exception) { events.add(APIEvent.Error(APIError("parse_error", "Failed to parse: ${e.message}", null, false))) }
        return events
    }

    override fun parseStreamChunk(chunk: String): List<APIEvent> {
        val events = mutableListOf<APIEvent>()
        if (chunk.trim().isEmpty()) return events
        chunk.split("\n").forEach { line -> if (!line.startsWith("data: ")) return@forEach; val data = line.substring(6); if (data == "[DONE]") { events.add(APIEvent.MessageStop()); return@forEach }
            try { val obj = json.parseToJsonElement(data).jsonObject; when (obj["type"]?.jsonPrimitive?.content) { "message_start" -> { val m = obj["message"]?.jsonObject; events.add(APIEvent.MessageStart(APIMessage(m?.get("role")?.jsonPrimitive?.content ?: "assistant", emptyList(), model = m?.get("model")?.jsonPrimitive?.content))) }; "content_block_start" -> { val b = obj["content_block"]?.jsonObject; val idx = obj["index"]?.jsonPrimitive?.content?.toInt() ?: 0; when (b?.get("type")?.jsonPrimitive?.content) { "text" -> events.add(APIEvent.ContentBlockStart(idx, APIContentBlock.Text(""))); "tool_use" -> events.add(APIEvent.ContentBlockStart(idx, APIContentBlock.ToolUse(b["id"]?.jsonPrimitive?.content ?: "", b["name"]?.jsonPrimitive?.content ?: "", emptyMap()))); "thinking" -> events.add(APIEvent.ContentBlockStart(idx, APIContentBlock.Thinking(""))) } }; "content_block_delta" -> { val d = obj["delta"]?.jsonObject; val idx = obj["index"]?.jsonPrimitive?.content?.toInt() ?: 0; when (d?.get("type")?.jsonPrimitive?.content) { "text_delta" -> events.add(APIEvent.ContentBlockDelta(idx, APIDelta.TextDelta(d["text"]?.jsonPrimitive?.content ?: ""))); "thinking_delta" -> events.add(APIEvent.ContentBlockDelta(idx, APIDelta.ThinkingDelta(d["thinking"]?.jsonPrimitive?.content ?: ""))); "tool_use_delta" -> events.add(APIEvent.ContentBlockDelta(idx, APIDelta.ToolUseDelta(d["id"]?.jsonPrimitive?.content, d["name"]?.jsonPrimitive?.content, d["input"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }))) } }; "content_block_stop" -> { events.add(APIEvent.ContentBlockStop(obj["index"]?.jsonPrimitive?.content?.toInt() ?: 0)) }; "message_delta" -> { val d = obj["delta"]?.jsonObject; val u = obj["usage"]?.jsonObject; events.add(APIEvent.MessageDelta(if (d?.get("stop_reason") != null) APIDelta.StopReason(d["stop_reason"]!!.jsonPrimitive.content) else APIDelta.TextDelta(""), u?.let { APIUsage(it["input_tokens"]?.jsonPrimitive?.content?.toLong() ?: 0, it["output_tokens"]?.jsonPrimitive?.content?.toLong() ?: 0) })) }; "message_stop" -> events.add(APIEvent.MessageStop()); "error" -> { val e = obj["error"]?.jsonObject; events.add(APIEvent.Error(APIError(e?.get("type")?.jsonPrimitive?.content ?: "error", e?.get("message")?.jsonPrimitive?.content ?: "Unknown error", null, true))) } } } catch (_: Exception) {}
        }
        return events
    }

    override fun formatToolResult(toolUseId: String, result: ToolResultBlockParam): APIMessage = APIMessage("user", listOf(APIContentBlock.ToolResult(toolUseId, result.content.map { when (it) { is ResultContent.Text -> APIResultContent.Text(it.text); is ResultContent.Image -> APIResultContent.Image(it.source.mediaType, it.source.data); is ResultContent.ToolUse -> APIResultContent.Text("") } }, result.isError)))

    private fun emptyProvider(): ModelProvider = object : ModelProvider {
        override val id = ""; override val name = ""; override val type = ProviderType.ANTHROPIC
        override val authConfig = AuthConfig(type = AuthType.NONE)
        override val supportedProtocols = listOf(ProtocolType.ANTHROPIC_MESSAGES)
        override val defaultProtocol: ProtocolType = ProtocolType.ANTHROPIC_MESSAGES
        override fun getProtocol(type: ProtocolType): Protocol = this@AnthropicProtocol
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
        override fun validate(): ValidationResult = ValidationResult(true)
        override suspend fun refreshAuth(): AuthResult = AuthResult(true)
    }
}

class OpenAIChatProtocol(private val baseUrl: String = "https://api.openai.com/v1") : Protocol {
    override val type: ProtocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false }

    override fun getEndpoint(provider: ModelProvider, model: String): String = "$baseUrl/chat/completions"

    override fun getHeaders(provider: ModelProvider, config: ProtocolConfig): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json")
        when (provider.authConfig.type) {
            AuthType.API_KEY -> provider.authConfig.apiKey?.let { h["Authorization"] = "Bearer $it" }
            AuthType.BEARER_TOKEN, AuthType.OAUTH2 -> provider.authConfig.accessToken?.let { h["Authorization"] = "Bearer $it" }
            else -> {}
        }
        return h
    }

    override fun buildRequest(model: String, messages: List<APIMessage>, tools: List<APITool>, systemPrompt: String, config: ProtocolConfig): APIRequest {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                if (systemPrompt.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                messages.forEach { msg -> add(buildApiMessage(msg)) }
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", buildJsonObject { tool.inputSchema.forEach { (k, v) -> put(k, anyToJsonElement(v)) } })
                        })
                    })
                }
            })
            config.maxTokens?.let { put("max_tokens", it) }
            config.temperature?.let { put("temperature", it) }
            config.topP?.let { put("top_p", it) }
        }
        return APIRequest(url = getEndpoint(emptyProvider(), model), method = "POST", headers = getHeaders(emptyProvider(), config), body = body.toString(), timeoutMs = config.timeoutMs)
    }

    private fun buildApiMessage(msg: APIMessage): JsonObject = buildJsonObject {
        put("role", msg.role)
        val contentBlocks = msg.content.filterIsInstance<APIContentBlock.Text>()
        val toolUseBlocks = msg.content.filterIsInstance<APIContentBlock.ToolUse>()
        val toolResultBlocks = msg.content.filterIsInstance<APIContentBlock.ToolResult>()

        if (contentBlocks.isNotEmpty()) {
            put("content", buildJsonArray { contentBlocks.forEach { add(it.text) } })
        }
        if (toolUseBlocks.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolUseBlocks.forEach { block ->
                    add(buildJsonObject {
                        put("id", block.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", block.name)
                            put("input", buildJsonObject { block.input.forEach { (k, v) -> put(k, v) } })
                        })
                    })
                }
            })
        }
        if (toolResultBlocks.isNotEmpty()) {
            put("tool_call_id", toolResultBlocks.first().toolUseId)
            put("content", buildJsonArray {
                toolResultBlocks.forEach { block ->
                    block.content.forEach { rc ->
                        when (rc) {
                            is APIResultContent.Text -> add(rc.text)
                            else -> add("")
                        }
                    }
                }
            })
        }
    }

    override fun parseResponse(response: APIResponse): List<APIEvent> {
        if (response.statusCode != 200) {
            return listOf(APIEvent.Error(APIError(response.statusCode, "HTTP ${response.statusCode}", response.body.substring(0, minOf(500, response.body.length)))))
        }
        return try {
            val obj = json.parseToJsonElement(response.body).jsonObject
            val events = mutableListOf<APIEvent>()
            val choices = obj["choices"]?.jsonArray ?: return emptyList()
            val choice = choices.firstOrNull()?.jsonObject ?: return emptyList()
            val message = choice["message"]?.jsonObject
            val finishReason = choice["finishReason"]?.jsonPrimitive?.content

            val content = message?.get("content")?.jsonPrimitive?.content ?: ""
            val toolCalls = message?.get("tool_calls")?.jsonArray?.mapNotNull { tc ->
                val tcObj = tc.jsonObject
                val fn = tcObj["function"]?.jsonObject
                ToolCall(
                    id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                    name = fn?.get("name")?.jsonPrimitive?.content ?: "",
                    input = fn?.get("input")?.jsonObject?.mapValues { it.value }
                )
            } ?: emptyList()

            val usage = obj["usage"]?.jsonObject
            val inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            val contentBlocks = mutableListOf<APIContentBlock>()
            if (content.isNotEmpty()) contentBlocks.add(APIContentBlock.Text(content))
            toolCalls.forEach { tc ->
                contentBlocks.add(APIContentBlock.ToolUse(tc.id, tc.name, tc.input ?: emptyMap()))
            }

            val apiMessage = APIMessage(
                role = "assistant",
                content = contentBlocks,
                usage = APIUsage(inputTokens, outputTokens, 0, 0),
                stopReason = finishReason,
                model = obj["model"]?.jsonPrimitive?.content
            )

            events.add(APIEvent.MessageStart(apiMessage))
            contentBlocks.forEachIndexed { i, _ -> events.add(APIEvent.ContentBlockStart(i, contentBlocks[i])) }
            contentBlocks.forEachIndexed { i, _ -> events.add(APIEvent.ContentBlockStop(i)) }
            events.add(APIEvent.MessageDelta(APIDelta.StopReason(finishReason ?: "stop"), APIUsage(inputTokens, outputTokens, 0, 0)))
            events.add(APIEvent.MessageStop())
            events
        } catch (e: Exception) {
            listOf(APIEvent.Error(APIError(0, "Parse error: ${e.message}", response.body.substring(0, minOf(500, response.body.length)))))
        }
    }

    override fun parseStreamChunk(chunk: String): List<APIEvent> {
        val events = mutableListOf<APIEvent>()
        val lines = chunk.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") {
                events.add(APIEvent.MessageStop())
                continue
            }
            try {
                val obj = json.parseToJsonElement(data).jsonObject
                val choices = obj["choices"]?.jsonArray ?: continue
                val choice = choices.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject
                val finishReason = choice["finishReason"]?.jsonPrimitive?.content
                val content = delta?.get("content")?.jsonPrimitive?.content
                val toolCalls = delta?.get("tool_calls")?.jsonArray?.mapNotNull { tc ->
                    val tcObj = tc.jsonObject
                    val fn = tcObj["function"]?.jsonObject
                    ToolCall(
                        id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                        name = fn?.get("name")?.jsonPrimitive?.content ?: "",
                        input = fn?.get("arguments")?.jsonPrimitive?.content?.let {
                            try { json.parseToJsonElement(it).jsonObject?.mapValues { v -> v.value } } catch (e: Exception) { emptyMap() }
                        } ?: emptyMap()
                    )
                } ?: emptyList()

                if (content != null && content.isNotEmpty()) {
                    events.add(APIEvent.ContentBlockDelta(0, APIDelta.TextDelta(content)))
                }
                toolCalls.forEach { tc ->
                    events.add(APIEvent.ContentBlockStart(0, APIContentBlock.ToolUse(tc.id, tc.name, tc.input)))
                }
                if (finishReason != null) {
                    events.add(APIEvent.MessageDelta(APIDelta.StopReason(finishReason), null))
                }
            } catch (e: Exception) { /* skip malformed chunks */ }
        }
        return events
    }

    override fun formatToolResult(toolUseId: String, result: ToolResultBlockParam): APIMessage = APIMessage("tool", listOf(APIContentBlock.ToolResult(toolUseId, result.content.map { when (it) { is ResultContent.Text -> APIResultContent.Text(it.text); is ResultContent.Image -> APIResultContent.Image(it.source.mediaType, it.source.data); is ResultContent.ToolUse -> APIResultContent.Text("") } }, result.isError)))

    private fun emptyProvider(): ModelProvider = object : ModelProvider {
        override val id = ""; override val name = ""; override val type = ProviderType.OPENAI
        override val authConfig = AuthConfig(type = AuthType.NONE)
        override val supportedProtocols = listOf(ProtocolType.OPENAI_CHAT_COMPLETIONS)
        override val defaultProtocol: ProtocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS
        override fun getProtocol(type: ProtocolType): Protocol = this@OpenAIChatProtocol
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
        override fun validate(): ValidationResult = ValidationResult(true)
        override suspend fun refreshAuth(): AuthResult = AuthResult(true)
    }
}

class GeminiProtocol(private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta") : Protocol {
    override val type: ProtocolType = ProtocolType.GOOGLE_GEMINI
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false }

    override fun getEndpoint(provider: ModelProvider, model: String): String = "$baseUrl/models/$model:generateContent"

    override fun getHeaders(provider: ModelProvider, config: ProtocolConfig): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json")
        when (provider.authConfig.type) {
            AuthType.API_KEY -> provider.authConfig.apiKey?.let { put("x-goog-api-key", it) }
            else -> {}
        }
        return h
    }

    override fun buildRequest(model: String, messages: List<APIMessage>, tools: List<APITool>, systemPrompt: String, config: ProtocolConfig): APIRequest {
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            msg.content.forEach { block ->
                                when (block) {
                                    is APIContentBlock.Text -> add(buildJsonObject { put("text", block.text) })
                                    is APIContentBlock.ToolUse -> add(buildJsonObject {
                                        put("functionCall", buildJsonObject {
                                            put("name", block.name)
                                            put("args", buildJsonObject { block.input.forEach { (k, v) -> put(k, anyToJsonElement(v)) } })
                                        })
                                    })
                                    is APIContentBlock.ToolResult -> add(buildJsonObject {
                                        put("functionResponse", buildJsonObject {
                                            put("name", block.toolUseId)
                                            put("response", buildJsonObject {
                                                block.content.forEach { rc ->
                                                    when (rc) {
                                                        is APIResultContent.Text -> put("result", buildJsonObject { put("text", rc.text) })
                                                        else -> {}
                                                    }
                                                }
                                            })
                                        })
                                    })
                                    else -> add(buildJsonObject { put("text", "") })
                                }
                            }
                        })
                    })
                }
            })
            if (systemPrompt.isNotEmpty()) put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", systemPrompt) }) }) })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", buildJsonObject { tool.inputSchema.forEach { (k, v) -> put(k, anyToJsonElement(v)) } })
                            })
                        }
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                config.maxTokens?.let { put("maxOutputTokens", it) }
                config.temperature?.let { put("temperature", it) }
                config.topP?.let { put("topP", it) }
            })
        }
        return APIRequest(url = getEndpoint(emptyProvider(), model), method = "POST", headers = getHeaders(emptyProvider(), config), body = body.toString(), timeoutMs = config.timeoutMs)
    }

    override fun parseResponse(response: APIResponse): List<APIEvent> {
        if (response.statusCode != 200) {
            return listOf(APIEvent.Error(APIError(response.statusCode, "HTTP ${response.statusCode}", response.body.substring(0, minOf(500, response.body.length)))))
        }
        return try {
            val obj = json.parseToJsonElement(response.body).jsonObject
            val events = mutableListOf<APIEvent>()
            val candidates = obj["candidates"]?.jsonArray ?: return emptyList()
            val candidate = candidates.firstOrNull()?.jsonObject ?: return emptyList()
            val content = candidate["content"]?.jsonObject
            val parts = content?.get("parts")?.jsonArray ?: return emptyList()
            val finishReason = candidate["finishReason"]?.jsonPrimitive?.content

            val contentBlocks = mutableListOf<APIContentBlock>()
            val toolCalls = mutableListOf<ToolCall>()

            parts.forEach { part ->
                val partObj = part.jsonObject
                partObj["text"]?.let { text -> contentBlocks.add(APIContentBlock.Text(text.jsonPrimitive.content)) }
                partObj["functionCall"]?.let { fc ->
                    val fcObj = fc.jsonObject
                    val name = fcObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = fcObj["args"]?.jsonObject?.mapValues { it.value } ?: emptyMap()
                    toolCalls.add(ToolCall("call_${toolCalls.size}", name, args))
                }
            }

            toolCalls.forEach { tc ->
                contentBlocks.add(APIContentBlock.ToolUse(tc.id, tc.name, tc.input))
            }

            val usage = obj["usageMetadata"]?.jsonObject
            val inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            val apiMessage = APIMessage(
                role = "assistant",
                content = contentBlocks,
                usage = APIUsage(inputTokens, outputTokens, 0, 0),
                stopReason = finishReason,
                model = obj["modelVersion"]?.jsonPrimitive?.content
            )

            events.add(APIEvent.MessageStart(apiMessage))
            contentBlocks.forEachIndexed { i, _ -> events.add(APIEvent.ContentBlockStart(i, contentBlocks[i])) }
            contentBlocks.forEachIndexed { i, _ -> events.add(APIEvent.ContentBlockStop(i)) }
            events.add(APIEvent.MessageDelta(APIDelta.StopReason(finishReason ?: "STOP"), APIUsage(inputTokens, outputTokens, 0, 0)))
            events.add(APIEvent.MessageStop())
            events
        } catch (e: Exception) {
            listOf(APIEvent.Error(APIError(0, "Parse error: ${e.message}", response.body.substring(0, minOf(500, response.body.length)))))
        }
    }

    override fun parseStreamChunk(chunk: String): List<APIEvent> {
        val events = mutableListOf<APIEvent>()
        val lines = chunk.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val data = trimmed.removePrefix("data:").trim()
            try {
                val obj = json.parseToJsonElement(data).jsonObject
                val candidates = obj["candidates"]?.jsonArray ?: continue
                val candidate = candidates.firstOrNull()?.jsonObject ?: continue
                val content = candidate["content"]?.jsonObject
                val parts = content?.get("parts")?.jsonArray
                val finishReason = candidate["finishReason"]?.jsonPrimitive?.content

                parts?.forEach { part ->
                    val partObj = part.jsonObject
                    partObj["text"]?.let { text ->
                        events.add(APIEvent.ContentBlockDelta(0, APIDelta.TextDelta(text.jsonPrimitive.content)))
                    }
                    partObj["functionCall"]?.let { fc ->
                        val fcObj = fc.jsonObject
                        val name = fcObj["name"]?.jsonPrimitive?.content ?: ""
                        val args = fcObj["args"]?.jsonObject?.mapValues { it.value } ?: emptyMap()
                        events.add(APIEvent.ContentBlockStart(0, APIContentBlock.ToolUse("call_0", name, args)))
                    }
                }
                if (finishReason != null) {
                    events.add(APIEvent.MessageDelta(APIDelta.StopReason(finishReason), null))
                }
            } catch (e: Exception) { /* skip malformed chunks */ }
        }
        return events
    }

    override fun formatToolResult(toolUseId: String, result: ToolResultBlockParam): APIMessage {
        val blocks = result.content.map { rc ->
            when (rc) {
                is ResultContent.Text -> APIResultContent.Text(rc.text)
                is ResultContent.Image -> APIResultContent.Image(rc.source.mediaType, rc.source.data)
                is ResultContent.ToolUse -> APIResultContent.Text("")
            }
        }
        return APIMessage("user", listOf(APIContentBlock.ToolResult(toolUseId, blocks, result.isError)))
    }

    private fun emptyProvider(): ModelProvider = object : ModelProvider {
        override val id = ""; override val name = ""; override val type = ProviderType.GOOGLE
        override val authConfig = AuthConfig(type = AuthType.NONE)
        override val supportedProtocols = listOf(ProtocolType.GOOGLE_GEMINI)
        override val defaultProtocol: ProtocolType = ProtocolType.GOOGLE_GEMINI
        override fun getProtocol(type: ProtocolType): Protocol = this@GeminiProtocol
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
        override fun validate(): ValidationResult = ValidationResult(true)
        override suspend fun refreshAuth(): AuthResult = AuthResult(true)
    }
}
