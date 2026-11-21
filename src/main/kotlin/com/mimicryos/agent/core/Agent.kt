package com.mimicryos.agent.core

import com.mimicryos.agent.provider.*
import com.mimicryos.agent.permission.PermissionSystem
import com.mimicryos.agent.permission.PermissionResult
import com.mimicryos.agent.session.SessionManager
import com.mimicryos.agent.context.ContextManager
import com.mimicryos.agent.skills.SkillManager
import com.mimicryos.agent.subagent.SubagentManager
import com.mimicryos.agent.hooks.HookManager
import com.mimicryos.agent.mcp.MCPManager
import com.mimicryos.agent.plan.PlanModeManager
import com.mimicryos.agent.tools.ToolRegistry
import com.github.ajalt.mordant.terminal.Terminal
import java.util.concurrent.ConcurrentHashMap

class MimicryAgent(private val config: AgentConfig, private val registry: ModelRegistry, private val toolRegistry: ToolRegistry, private val permissionSystem: PermissionSystem, private val sessionManager: SessionManager, private val contextManager: ContextManager, private val skillManager: SkillManager, private val subagentManager: SubagentManager, private val hookManager: HookManager, private val mcpManager: MCPManager, private val planModeManager: PlanModeManager, private val terminal: Terminal? = null) {
    private val messages = mutableListOf<Message>()
    private val fileStateCache = FileStateCache()
    private var currentSessionId: String = sessionManager.createSession()
    private val activeTasks = ConcurrentHashMap<String, TaskStateEntry>()
    @Volatile private var activeQueryEngine: QueryEngine? = null

    suspend fun submit(prompt: String): String {
        val userMessage = Message.User(uuid = generateUuid(), message = UserMessage("user", Content.Text(prompt)))
        messages.add(userMessage)
        sessionManager.saveMessages(currentSessionId, messages)
        val systemPrompt = buildSystemPrompt()
        val provider = registry.getProvider(config.providerId) ?: error("Provider not found: ${config.providerId}")
        val model = config.model
        val toolPermissionContext = createPermissionContext()
        val toolUseContext = ToolUseContext(options = ToolOptions(mainLoopModel = model, tools = toolRegistry.getEnabled(), thinkingConfig = ThinkingConfig(type = "adaptive"), agentDefinitions = AgentDefinitionsResult(emptyList(), emptyList()), isNonInteractiveSession = true, mcpClients = mcpManager.getClients(), cwd = config.cwd), abortController = AbortController(), readFileState = fileStateCache, getAppState = { createAppState() }, setAppState = { _ -> }, messages = messages)
        val canUseTool: CanUseToolFn = object : CanUseToolFn { override suspend fun invoke(tool: Tool<*, *>, input: Any, context: ToolUseContext, assistantMessage: Message.Assistant, toolUseID: String, forceDecision: String?): PermissionResult = permissionSystem.canUseTool(tool, input, context, assistantMessage, toolUseID, forceDecision) }
        val queryEngine = QueryEngine(QueryEngine.QueryEngineConfig(sessionId = currentSessionId, cwd = config.cwd, tools = toolRegistry.getEnabled(), commands = emptyList(), mcpClients = mcpManager.getClients(), agents = emptyList(), canUseTool = canUseTool, getAppState = { createAppState() }, setAppState = { _ -> }, mainLoopModel = model, providerId = config.providerId, registry = registry, readFileCache = fileStateCache, userSpecifiedModel = model, maxTurns = config.maxTurns, verbose = config.verbose))
        activeQueryEngine = queryEngine
        try {
            val result = queryEngine.submitMessage(prompt).run()
            val lastMessage = result.lastOrNull()
            return when (lastMessage) { is Message.Assistant -> (lastMessage.message.content.lastOrNull() as? ContentBlock.Text)?.text ?: ""; is Message.User -> "Task completed"; else -> "" }
        } finally {
            activeQueryEngine = null
        }
    }

    fun interrupt() { activeQueryEngine?.interrupt() }
    fun getSessionId(): String = currentSessionId
    fun getMessages(): List<Message> = messages.toList()
    suspend fun loadSession(sessionId: String) { currentSessionId = sessionId; messages.clear(); messages.addAll(sessionManager.loadMessages(sessionId)) }

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are an AI coding assistant.\n")
        sb.append("Working directory: ${config.cwd}\n")
        val skillPrompt = skillManager.buildPrompt()
        if (skillPrompt.isNotEmpty()) { sb.append("\n## Skills\n\n"); sb.append(skillPrompt) }
        return sb.toString()
    }

    private fun createPermissionContext(): ToolPermissionContext = when (config.permissionMode) {
        "bypass" -> ToolPermissionContext(mode = "bypass", isBypassPermissionsModeAvailable = true)
        "accept-edits" -> ToolPermissionContext(mode = "accept_edits", alwaysAllowRules = ToolPermissionRules(edit = listOf("*"), write = listOf("*")))
        "plan" -> ToolPermissionContext(mode = "plan")
        else -> ToolPermissionContext()
    }

    private fun createAppState(): AppState = AppState(toolPermissionContext = createPermissionContext(), settings = Settings(model = config.model))
    private fun generateUuid(): String = java.util.UUID.randomUUID().toString()
}

data class AgentConfig(val cwd: String, val model: String, val providerId: String, val apiKey: String? = null, val baseUrl: String? = null, val permissionMode: String = "default", val maxTurns: Int = 50, val maxBudgetUsd: Long = 100, val skillsDir: String? = null, val verbose: Boolean = false)
