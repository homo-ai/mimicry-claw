package com.mimicryos.agent.core

import com.mimicryos.agent.provider.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class QueryEngine(private val config: QueryEngineConfig) {
    private var mutableMessages: MutableList<Message> = config.initialMessages.toMutableList()
    private var abortController: AbortController = config.abortController ?: AbortController()
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

        while (!abortController.isCancelled && turnCount.get() <= (config.maxTurns ?: Int.MAX_VALUE)) {
            val apiMessages = buildAPIMessages(messages)
            val model = config.userSpecifiedModel ?: config.mainLoopModel
            val provider = config.registry.getProvider(config.providerId) ?: error("Provider not found: ${config.providerId}")
            val protocol = provider.getProtocol(provider.defaultProtocol)
            val protocolConfig = buildProtocolConfig(toolUseContext)
            val apiTools = buildAPITools(toolUseContext.options.tools)
            val request = protocol.buildRequest(model, apiMessages, apiTools, systemPrompt, protocolConfig)
            val response = executeAPIRequest(request, provider)
            val events = protocol.parseResponse(response)

            var assistantMessage: Message.Assistant? = null
            val toolCalls = mutableListOf<ToolCall>()

            for (event in events) {
                when (event) {
                    is APIEvent.MessageStart -> assistantMessage = buildAssistantMessage(event.message)
                    is APIEvent.MessageDelta -> { if (event.delta is APIDelta.StopReason) lastStopReason = (event.delta as APIDelta.StopReason).reason }
                    is APIEvent.Error -> throw AgentLoopError(event.error)
                    is APIEvent.Usage -> totalUsage = event.usage
                    else -> {}
                }
            }

            assistantMessage?.let { msg -> toolCalls.addAll(extractToolCalls(msg)); messages.add(msg) }
            if (toolCalls.isEmpty()) break

            for (toolCall in toolCalls) {
                val tool = findToolByName(toolUseContext.options.tools, toolCall.name)
                if (tool != null) {
                    val permissionResult = canUseTool(tool, toolCall.input, toolUseContext, assistantMessage!!, toolCall.id)
                    if (permissionResult.behavior == "allow") {
                        val result = tool.call(toolCall.input, toolUseContext, canUseTool, assistantMessage!!)
                        toolResults.add(tool.mapToolResultToToolResultBlockParam(result.data, toolCall.id))
                        messages.add(Message.User(uuid = generateUuid(), message = UserMessage("user", Content.Text("")), toolUseResult = result.data as? ToolResultBlockParam))
                    } else {
                        toolResults.add(ToolResultBlockParam(listOf(APIResultContent.Text(permissionResult.message ?: "Permission denied")), true))
                    }
                } else {
                    toolResults.add(ToolResultBlockParam(listOf(APIResultContent.Text("Tool not found: ${toolCall.name}")), true))
                }
            }
            turnCount.incrementAndGet()
        }
        return AgentLoopResult(messages, lastStopReason, totalUsage, turnCount.get(), toolResults)
    }

    private fun buildAPIMessages(messages: List<Message>): List<APIMessage> = messages.map { msg -> when (msg) { is Message.User -> APIMessage("user", listOf(APIContentBlock.Text((msg.message.content as? Content.Text)?.text ?: ""))); is Message.Assistant -> APIMessage("assistant", msg.message.content.map { block -> when (block) { is ContentBlock.Text -> APIContentBlock.Text(block.text); is ContentBlock.Thinking -> APIContentBlock.Thinking(block.thinking, block.signature); is ContentBlock.ToolUse -> APIContentBlock.ToolUse(block.id, block.name, block.input); is ContentBlock.ToolResult -> APIContentBlock.ToolResult(block.toolUseId, block.content, block.isError); else -> APIContentBlock.Text("") } }); else -> APIMessage("user", listOf(APIContentBlock.Text(""))) } }

    private fun buildAPITools(tools: List<Tool<*, *>>): List<APITool> = tools.map { APITool(it.name, it.prompt(PromptOptions({ ToolPermissionContext() }, tools, emptyList())), mapOf("type" to "object", "properties" to (it.inputJSONSchema?.properties ?: emptyMap()))) }

    private fun buildProtocolConfig(context: ToolUseContext): ProtocolConfig = ProtocolConfig(maxTokens = context.options.maxBudgetUsd?.let { (it * 1000).toInt() }, thinkingBudget = context.options.thinkingConfig.budgetTokens, timeoutMs = 120_000)

    private suspend fun executeAPIRequest(request: APIRequest, provider: ModelProvider): APIResponse = APIResponse(200, emptyMap(), "")

    private fun buildAssistantMessage(apiMessage: APIMessage): Message.Assistant = Message.Assistant(uuid = generateUuid(), message = AssistantMessage("assistant", apiMessage.content.map { block -> when (block) { is APIContentBlock.Text -> ContentBlock.Text(block.text); is APIContentBlock.Thinking -> ContentBlock.Thinking(block.thinking, block.signature); is APIContentBlock.ToolUse -> ContentBlock.ToolUse(block.id, block.name, block.input); is APIContentBlock.ToolResult -> ContentBlock.ToolResult(block.toolUseId, block.content, block.isError); else -> ContentBlock.Text("") } }, usage = apiMessage.usage, stopReason = apiMessage.stopReason, model = apiMessage.model))

    private fun extractToolCalls(message: Message.Assistant): List<ToolCall> = message.message.content.filterIsInstance<ContentBlock.ToolUse>().map { ToolCall(it.id, it.name, it.input) }
    private fun generateUuid(): String = java.util.UUID.randomUUID().toString()

    data class QueryEngineConfig(val sessionId: String, val cwd: String, val tools: List<Tool<*, *>>, val commands: List<Command> = emptyList(), val mcpClients: List<MCPServerConnection> = emptyList(), val agents: List<AgentDefinition> = emptyList(), val canUseTool: CanUseToolFn, val getAppState: () -> AppState, val setAppState: (f: (prev: AppState) -> AppState) -> Unit, val mainLoopModel: String, val providerId: String, val registry: ModelRegistry, val readFileCache: FileStateCache, val customSystemPrompt: String? = null, val appendSystemPrompt: String? = null, val userSpecifiedModel: String? = null, val fallbackModel: String? = null, val thinkingConfig: ThinkingConfig = ThinkingConfig(), val maxTurns: Int? = null, val maxBudgetUsd: Double? = null, val taskBudget: TaskBudget? = null, val verbose: Boolean = false, val handleElicitation: (suspend (String, ElicitRequestParams, AbortSignal) -> ElicitResult)? = null, val initialMessages: List<Message> = emptyList())
    data class SubmitOptions(val uuid: String? = null, val isMeta: Boolean = false, val replayUserMessages: Boolean = false, val includePartialMessages: Boolean = false)
    data class TaskBudget(val total: Int)
    data class AgentLoopResult(val messages: List<Message>, val lastStopReason: String?, val totalUsage: APIUsage, val turnCount: Int, val toolResults: List<ToolResultBlockParam>)
    data class ToolCall(val id: String, val name: String, val input: Map<String, String>)
    data class PermissionDenial(val toolName: String, val toolUseId: String, val toolInput: Map<String, String>)
    class AgentLoopError(val error: APIError) : Exception(error.message)
}

class MessageStream(private val engine: QueryEngine, private val prompt: String, private val options: QueryEngine.SubmitOptions) {
    suspend fun run(): List<Message> {
        val userMessage = Message.User(uuid = options.uuid ?: java.util.UUID.randomUUID().toString(), message = UserMessage("user", Content.Text(prompt)))
        val messages = engine.getMessages().toMutableList()
        messages.add(userMessage)
        val systemPrompt = engine.config.customSystemPrompt ?: "You are a helpful AI assistant."
        val toolUseContext = ToolUseContext(options = ToolOptions(mainLoopModel = engine.config.mainLoopModel, tools = engine.config.tools, thinkingConfig = engine.config.thinkingConfig, agentDefinitions = AgentDefinitionsResult(engine.config.agents, engine.config.agents), isNonInteractiveSession = true), abortController = engine.abortController, readFileState = engine.config.readFileCache, getAppState = engine.config.getAppState, setAppState = engine.config.setAppState, messages = messages)
        val result = engine.runAgentLoop(messages, systemPrompt, engine.config.canUseTool, toolUseContext)
        return result.messages
    }
}
