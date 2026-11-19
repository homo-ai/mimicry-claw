package com.mimicryos.agent.subagent

import com.mimicryos.agent.core.*
import com.mimicryos.agent.provider.ModelRegistry
import com.mimicryos.agent.permission.PermissionResult
import com.mimicryos.agent.tools.ToolRegistry
import java.util.concurrent.ConcurrentHashMap

class SubagentManager(private val registry: ModelRegistry, private val toolRegistry: ToolRegistry) {
    private val agents = ConcurrentHashMap<String, SubagentContext>()

    suspend fun spawnAgent(config: SubagentConfig): SubagentHandle {
        val agentId = AgentId.generate().value
        val context = SubagentContext(agentId, config, mutableListOf(), SubagentStatus.PENDING)
        agents[agentId] = context
        return SubagentHandle(agentId) { killAgent(agentId) }
    }

    suspend fun executeTask(task: SubagentTask): SubagentResult {
        val agentId = task.agentId ?: spawnAgent(task.config).agentId
        val agent = agents[agentId] ?: error("Agent not found: $agentId")
        agent.status = SubagentStatus.RUNNING
        return try { val result = runSubagentLoop(agent, task); agent.status = SubagentStatus.COMPLETED; SubagentResult.success(agentId, result) } catch (e: Exception) { agent.status = SubagentStatus.FAILED; SubagentResult.failure(agentId, e.message ?: "Unknown error") }
    }

    private suspend fun runSubagentLoop(agent: SubagentContext, task: SubagentTask): String {
        agent.messages.add(Message.User(uuid = generateUuid(), message = UserMessage("user", Content.Text(task.prompt))))
        val provider = registry.getProvider(agent.config.providerId ?: "anthropic") ?: error("Provider not found")
        val model = agent.config.model ?: "claude-sonnet-4-20250514"
        val toolContext = ToolUseContext(options = ToolOptions(mainLoopModel = model, tools = toolRegistry.getEnabled(), thinkingConfig = ThinkingConfig(type = "adaptive"), agentDefinitions = AgentDefinitionsResult(emptyList(), emptyList()), isNonInteractiveSession = true), abortController = AbortController(), readFileState = FileStateCache(), getAppState = { AppState() }, setAppState = { _ -> }, agentId = agent.id, agentType = agent.config.agentType, messages = agent.messages)
        val engine = QueryEngine(QueryEngine.QueryEngineConfig(sessionId = agent.id, cwd = agent.config.cwd, tools = toolRegistry.getEnabled(), commands = emptyList(), mcpClients = emptyList(), agents = emptyList(), canUseTool = object : CanUseToolFunction { override suspend fun invoke(tool: Tool<*, *>, input: Map<String, Any>, context: ToolUseContext, assistantMessage: Message.Assistant, toolUseID: String, forceDecision: String? = null): PermissionResult = PermissionResult.allow() }, getAppState = { AppState() }, setAppState = { _ -> }, mainLoopModel = model, providerId = agent.config.providerId ?: "anthropic", registry = registry, readFileCache = FileStateCache(), userSpecifiedModel = model, maxTurns = agent.config.maxTurns))
        val result = engine.submitMessage(task.prompt).run()
        return result.lastOrNull()?.let { msg -> when (msg) { is Message.Assistant -> (msg.message.content.lastOrNull() as? ContentBlock.Text)?.text ?: ""; else -> "" } } ?: ""
    }

    private fun killAgent(agentId: String) { agents.remove(agentId) }
    fun getAgent(agentId: String): SubagentContext? = agents[agentId]
    fun getAllAgents(): List<SubagentContext> = agents.values.toList()
    private fun generateUuid(): String = java.util.UUID.randomUUID().toString()

    data class SubagentConfig(val agentType: String, val model: String? = null, val providerId: String? = null, val cwd: String, val maxTurns: Int = 50, val permissionMode: String = "default", val prompt: String = "", val tools: List<String>? = null, val disallowedTools: List<String>? = null)
    data class SubagentContext(val id: String, val config: SubagentConfig, val messages: MutableList<Message>, var status: SubagentStatus)
    data class SubagentTask(val agentId: String? = null, val config: SubagentConfig, val prompt: String)
    data class SubagentHandle(val agentId: String, val onKill: () -> Unit)
    data class SubagentResult(val success: Boolean, val agentId: String, val output: String, val error: String? = null) { companion object { fun success(agentId: String, output: String) = SubagentResult(true, agentId, output); fun failure(agentId: String, error: String) = SubagentResult(false, agentId, "", error) } }
    enum class SubagentStatus { PENDING, RUNNING, COMPLETED, FAILED, KILLED }
}
