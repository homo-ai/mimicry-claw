package com.mimicryos.agent.core

import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.permission.PermissionResult
import com.mimicryos.agent.provider.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class QueryEngine(val config: QueryEngineConfig) {
    private var mutableMessages: MutableList<Message> = config.initialMessages.toMutableList()
    internal var abortController: AbortController = config.abortController
    private var permissionDenials: MutableList<PermissionDenial> = mutableListOf()
    private var totalUsage: APIUsage = APIUsage()
    private var discoveredSkillNames = ConcurrentHashMap.newKeySet<String>()
    private var loadedNestedMemoryPaths = ConcurrentHashMap.newKeySet<String>()

    fun getMessages(): List<Message> = mutableMessages.toList()
    fun getReadFileState(): FileStateCache = config.readFileCache
    fun getSessionId(): String = config.sessionId
    fun setModel(model: String) { config.userSpecifiedModel = model }
    fun interrupt() { abortController.abort() }

    suspend fun submitMessage(prompt: String, options: SubmitOptions = SubmitOptions()): MessageStream = MessageStream(this, prompt, options)

    suspend fun runAgentLoop(messages: MutableList<Message>, systemPrompt: String, canUseTool: CanUseToolFn, toolUseContext: ToolUseContext): AgentLoopResult {
        val turnCount = AtomicInteger(1)
        var lastStopReason: String? = null
        val toolResults = mutableListOf<ToolResultBlockParam>()

        while (!abortController.isAborted && turnCount.get() <= (config.maxTurns ?: Int.MAX_VALUE)) {
            val apiMessages = buildAPIMessages(messages)
            val model = config.userSpecifiedModel ?: config.mainLoopModel
            val provider = config.registry.getProvider(config.providerId) ?: error("Provider not found: ${config.providerId}")
            val protocol = provider.getProtocol(provider.defaultProtocol)
            val protocolConfig = buildProtocolConfig(toolUseContext)
            val apiTools = buildAPITools(toolUseContext.options.tools)
            val request = protocol.buildRequest(provider, model, apiMessages, apiTools, systemPrompt, protocolConfig)

            emit(AgentEvent.AgentStateChanged(config.eventTaskId, "waiting_model", "turn ${turnCount.get()}"))
            val response = executeAPIRequest(request, provider)
            val events = protocol.parseResponse(response)

            var assistantMessage: Message.Assistant? = null
            val toolCalls = mutableListOf<ToolCall>()

            for (event in events) {
                when (event) {
                    is APIEvent.MessageStart -> assistantMessage = buildAssistantMessage(event.message)
                    is APIEvent.MessageDelta -> {
                        if (event.delta is APIDelta.StopReason) lastStopReason = event.delta.reason
                    }
                    is APIEvent.ContentBlockDelta -> {
                        val delta = event.delta
                        if (delta is APIDelta.TextDelta && delta.text.isNotEmpty()) {
                            val messageId = assistantMessage?.uuid ?: config.eventTaskId ?: ""
                            emit(AgentEvent.TextDelta(config.eventTaskId ?: "", messageId, delta.text))
                        }
                    }
                    is APIEvent.Error -> throw AgentLoopError(event.error)
                    is APIEvent.Usage -> totalUsage = event.usage
                    else -> {}
                }
            }

            emit(AgentEvent.AgentStateChanged(config.eventTaskId, "processing", null))

            assistantMessage?.let { msg ->
                toolCalls.addAll(extractToolCalls(msg))
                messages.add(msg)
                val text = msg.message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
                if (text.isNotEmpty()) {
                    emit(AgentEvent.MessageEvent(config.eventTaskId ?: "", msg.uuid, "assistant", text))
                }
            }
            if (toolCalls.isEmpty()) break

            for (toolCall in toolCalls) {
                executeToolCall(toolCall, messages, toolResults, canUseTool, toolUseContext, assistantMessage!!)
            }
            turnCount.incrementAndGet()
        }

        if (config.eventTaskId != null && (totalUsage.inputTokens > 0 || totalUsage.outputTokens > 0)) {
            emit(AgentEvent.UsageUpdated(config.eventTaskId, totalUsage.inputTokens, totalUsage.outputTokens))
        }
        return AgentLoopResult(messages, lastStopReason, totalUsage, turnCount.get(), toolResults)
    }

    private suspend fun executeToolCall(
        toolCall: ToolCall,
        messages: MutableList<Message>,
        toolResults: MutableList<ToolResultBlockParam>,
        canUseTool: CanUseToolFn,
        toolUseContext: ToolUseContext,
        assistantMessage: Message.Assistant,
    ) {
        val tool = findToolByName(toolUseContext.options.tools, toolCall.name)
        if (tool == null) {
            appendToolError(messages, toolResults, toolCall, "Tool not found: ${toolCall.name}")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as Tool<Any, Any>
        val typedInput: Any = try {
            typedTool.parseInput(toolCall.input)
        } catch (e: Exception) {
            appendToolError(messages, toolResults, toolCall, "Invalid input for ${toolCall.name}: ${e.message}")
            emit(AgentEvent.ToolFailed(config.eventTaskId ?: "", toolCall.id, toolCall.name, e.message ?: "invalid input"))
            return
        }

        val permissionResult = canUseTool(tool, typedInput, toolUseContext, assistantMessage, toolCall.id)
        when (permissionResult) {
            is PermissionResult.Allow -> {
                val summary = toolSummary(typedTool, typedInput)
                emit(AgentEvent.ToolStarted(config.eventTaskId ?: "", toolCall.id, toolCall.name, summary))
                try {
                    val result = typedTool.call(typedInput, toolUseContext, canUseTool, assistantMessage)
                    val block = typedTool.mapToolResultToToolResultBlockParam(result.data, toolCall.id)
                    toolResults.add(block)
                    messages.add(toolResultMessage(toolCall.id, block))
                    emit(AgentEvent.ToolCompleted(config.eventTaskId ?: "", toolCall.id, toolCall.name, toolResultText(block), block.isError))
                    if (!block.isError) {
                        val path = try { typedTool.getPath(typedInput) } catch (e: Exception) { null }
                        if (path != null && (toolCall.name == "Write" || toolCall.name == "Edit")) {
                            emit(AgentEvent.FileChanged(config.eventTaskId, path, toolCall.name.lowercase(), "Tool ${toolCall.name} completed"))
                        }
                    }
                } catch (e: Exception) {
                    val message = e.message ?: e.toString()
                    appendToolError(messages, toolResults, toolCall, "Tool ${toolCall.name} failed: $message")
                    emit(AgentEvent.ToolFailed(config.eventTaskId ?: "", toolCall.id, toolCall.name, message))
                }
            }
            is PermissionResult.Deny -> {
                appendToolError(messages, toolResults, toolCall, permissionResult.message)
                emit(AgentEvent.ToolFailed(config.eventTaskId ?: "", toolCall.id, toolCall.name, permissionResult.message))
            }
            is PermissionResult.Ask -> {
                val message = permissionResult.message.ifBlank { "Tool ${toolCall.name} requires permission" }
                appendToolError(messages, toolResults, toolCall, message)
                emit(AgentEvent.ToolFailed(config.eventTaskId ?: "", toolCall.id, toolCall.name, message))
            }
        }
    }

    private fun appendToolError(messages: MutableList<Message>, toolResults: MutableList<ToolResultBlockParam>, toolCall: ToolCall, message: String) {
        val block = ToolResultBlockParam(listOf(ResultContent.Text(message)), true)
        toolResults.add(block)
        messages.add(toolResultMessage(toolCall.id, block))
    }

    private fun toolResultMessage(toolUseId: String, block: ToolResultBlockParam): Message.User =
        Message.User(
            uuid = generateUuid(),
            message = UserMessage("user", Content.ToolResult(toolUseId, block.content, block.isError)),
        )

    private fun toolSummary(tool: Tool<Any, Any>, input: Any): String =
        try { tool.getToolUseSummary(input) ?: tool.userFacingName(input) } catch (e: Exception) { tool.name }

    private fun toolResultText(block: ToolResultBlockParam): String {
        val text = block.content.filterIsInstance<ResultContent.Text>().joinToString("\n") { it.text }
        return if (text.isBlank() && block.isError) "Tool failed" else text
    }

    private fun emit(event: AgentEvent) {
        config.eventListener?.invoke(event)
    }

    private fun buildAPIMessages(messages: List<Message>): List<APIMessage> = messages.mapNotNull { msg ->
        when (msg) {
            is Message.User -> {
                val blocks = when (val content = msg.message.content) {
                    is Content.Text -> if (content.text.isEmpty()) emptyList() else listOf(APIContentBlock.Text(content.text))
                    is Content.ToolResult -> listOf(APIContentBlock.ToolResult(content.toolUseId, content.content.map { toAPIResultContent(it) }, content.isError))
                    is Content.ToolUse -> listOf(APIContentBlock.ToolUse(content.id, content.name, content.input))
                    is Content.Thinking -> listOf(APIContentBlock.Thinking(content.thinking, content.signature))
                }
                if (blocks.isEmpty()) null else APIMessage("user", blocks)
            }
            is Message.Assistant -> APIMessage("assistant", msg.message.content.map { toAPIContentBlock(it) })
            else -> null
        }
    }

    private fun toAPIContentBlock(block: ContentBlock): APIContentBlock = when (block) {
        is ContentBlock.Text -> APIContentBlock.Text(block.text)
        is ContentBlock.Thinking -> APIContentBlock.Thinking(block.thinking, block.signature)
        is ContentBlock.ToolUse -> APIContentBlock.ToolUse(block.id, block.name, block.input)
        is ContentBlock.ToolResult -> APIContentBlock.ToolResult(block.toolUseId, block.content.map { toAPIResultContent(it) }, block.isError)
    }

    private fun toAPIResultContent(content: ResultContent): APIResultContent = when (content) {
        is ResultContent.Text -> APIResultContent.Text(content.text)
        is ResultContent.Image -> APIResultContent.Image(content.source.mediaType, content.source.data)
        is ResultContent.ToolUse -> APIResultContent.Text("")
    }

    private fun buildAPITools(tools: List<Tool<*, *>>): List<APITool> = tools.map { tool ->
        val schema = tool.inputJSONSchema
        APITool(
            name = tool.name,
            description = tool.prompt(PromptOptions({ ToolPermissionContext() }, tools, emptyList())),
            inputSchema = mapOf(
                "type" to "object",
                "properties" to (schema?.properties ?: emptyMap<String, Any>()),
                "required" to (schema?.required ?: emptyList<String>()),
            ),
        )
    }

    private fun buildProtocolConfig(context: ToolUseContext): ProtocolConfig = ProtocolConfig(maxTokens = context.options.maxBudgetUsd?.let { (it * 1000).toInt() }, thinkingBudget = context.options.thinkingConfig.budgetTokens, timeoutMs = 120_000)

    private suspend fun executeAPIRequest(request: APIRequest, provider: ModelProvider): APIResponse {
        config.requestExecutor?.let { return it(request, provider) }
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build()

            val builder = HttpRequest.newBuilder()
                .uri(java.net.URI.create(request.url))
                .timeout(java.time.Duration.ofMillis(request.timeoutMs))
                .header("Content-Type", "application/json")

            request.headers.forEach { (k, v) -> builder.header(k, v) }

            val httpRequest = when (request.method.uppercase()) {
                "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(request.body)).build()
                "GET" -> builder.GET().build()
                else -> builder.POST(HttpRequest.BodyPublishers.ofString(request.body)).build()
            }

            val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            APIResponse(response.statusCode(), response.headers().map().mapValues { it.value.joinToString(",") }, response.body())
        } catch (e: Exception) {
            APIResponse(0, emptyMap(), "{\"error\":\"${e.message}\"}")
        }
    }

    private fun buildAssistantMessage(apiMessage: APIMessage): Message.Assistant = Message.Assistant(uuid = generateUuid(), message = AssistantMessage("assistant", apiMessage.content.map { block -> when (block) { is APIContentBlock.Text -> ContentBlock.Text(block.text); is APIContentBlock.Thinking -> ContentBlock.Thinking(block.thinking, block.signature); is APIContentBlock.ToolUse -> ContentBlock.ToolUse(block.id, block.name, block.input); is APIContentBlock.ToolResult -> ContentBlock.ToolResult(block.toolUseId, block.content.map { when (it) { is APIResultContent.Text -> ResultContent.Text(it.text); is APIResultContent.Image -> ResultContent.Image(ImageSource("base64", it.mediaType, it.data)); else -> ResultContent.Text("") } }, block.isError); is APIContentBlock.Image -> ContentBlock.Text("") } }, usage = apiMessage.usage?.let { Usage(it.inputTokens, it.outputTokens, it.cacheCreationInputTokens, it.cacheReadInputTokens) }, stopReason = apiMessage.stopReason, model = apiMessage.model))

    private fun extractToolCalls(message: Message.Assistant): List<ToolCall> = message.message.content.filterIsInstance<ContentBlock.ToolUse>().map { ToolCall(it.id, it.name, it.input) }
    private fun generateUuid(): String = java.util.UUID.randomUUID().toString()

    data class QueryEngineConfig(
        val sessionId: String,
        val cwd: String,
        val tools: List<Tool<*, *>>,
        val commands: List<Command> = emptyList(),
        val mcpClients: List<MCPServerConnection> = emptyList(),
        val agents: List<AgentDefinition> = emptyList(),
        val canUseTool: CanUseToolFn,
        val getAppState: () -> AppState,
        val setAppState: (f: (prev: AppState) -> AppState) -> Unit,
        val mainLoopModel: String,
        val providerId: String,
        val registry: ModelRegistry,
        val readFileCache: FileStateCache,
        val abortController: AbortController = AbortController(),
        val customSystemPrompt: String? = null,
        val appendSystemPrompt: String? = null,
        var userSpecifiedModel: String? = null,
        val fallbackModel: String? = null,
        val thinkingConfig: ThinkingConfig = ThinkingConfig(),
        val maxTurns: Int? = null,
        val maxBudgetUsd: Double? = null,
        val taskBudget: TaskBudget? = null,
        val verbose: Boolean = false,
        val handleElicitation: (suspend (String, ElicitRequestParams, AbortSignal) -> ElicitResult)? = null,
        val initialMessages: List<Message> = emptyList(),
        val eventListener: ((AgentEvent) -> Unit)? = null,
        val eventTaskId: String? = null,
        val eventSessionId: String? = null,
        /** Optional transport override for tests and embedding. */
        val requestExecutor: (suspend (APIRequest, ModelProvider) -> APIResponse)? = null,
    )
    data class SubmitOptions(val uuid: String? = null, val isMeta: Boolean = false, val replayUserMessages: Boolean = false, val includePartialMessages: Boolean = false)
    data class TaskBudget(val total: Int)
    data class AgentLoopResult(val messages: List<Message>, val lastStopReason: String?, val totalUsage: APIUsage, val turnCount: Int, val toolResults: List<ToolResultBlockParam>)
    data class ToolCall(val id: String, val name: String, val input: Map<String, String>)
    data class PermissionDenial(val toolName: String, val toolUseId: String, val toolInput: Map<String, String>)
    class AgentLoopError(val error: APIError) : Exception(error.message)
}

class MessageStream(private val engine: QueryEngine, private val prompt: String, private val options: QueryEngine.SubmitOptions) {
    var lastResult: QueryEngine.AgentLoopResult? = null
        private set

    suspend fun run(): List<Message> {
        val userMessage = Message.User(uuid = options.uuid ?: java.util.UUID.randomUUID().toString(), message = UserMessage("user", Content.Text(prompt)))
        val messages = engine.getMessages().toMutableList()
        messages.add(userMessage)
        val systemPrompt = engine.config.customSystemPrompt ?: "You are a helpful AI assistant."
        val toolUseContext = ToolUseContext(options = ToolOptions(mainLoopModel = engine.config.mainLoopModel, tools = engine.config.tools, thinkingConfig = engine.config.thinkingConfig, agentDefinitions = AgentDefinitionsResult(engine.config.agents, engine.config.agents), isNonInteractiveSession = true, cwd = engine.config.cwd), abortController = engine.abortController, readFileState = engine.config.readFileCache, getAppState = engine.config.getAppState, setAppState = engine.config.setAppState, messages = messages)
        val result = engine.runAgentLoop(messages, systemPrompt, engine.config.canUseTool, toolUseContext)
        lastResult = result
        return result.messages
    }
}
