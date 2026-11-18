package com.mimicryos.agent.cli

import com.mimicryos.agent.core.*
import com.mimicryos.agent.provider.*
import com.mimicryos.agent.protocol.AnthropicProtocol
import com.mimicryos.agent.tools.ToolRegistry
import com.mimicryos.agent.permission.PermissionSystem
import com.mimicryos.agent.session.SessionManager
import com.mimicryos.agent.context.ContextManager
import com.mimicryos.agent.skills.SkillManager
import com.mimicryos.agent.subagent.SubagentManager
import com.mimicryos.agent.hooks.HookManager
import com.mimicryos.agent.mcp.MCPManager
import com.mimicryos.agent.plan.PlanModeManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.argument
import com.github.ajalt.clikt.parameters.types.choice
import com.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import java.io.File

class ClaudeCodeCLI : CliktCommand(name = "claude-code-kotlin", help = "Claude Code Kotlin/JVM - AI Coding Agent") {
    private val prompt by argument(help = "Prompt to send to the agent").optional()
    private val print by flag("--print", "-p", help = "Print mode (non-interactive)")
    private val verbose by flag("--verbose", "-v", help = "Verbose output")
    private val model by option("--model", "-m", help = "Model to use").default("claude-sonnet-4-20250514")
    private val provider by option("--provider", help = "Provider to use").choice("anthropic", "openai", "google", "openai-zencode", "kimi", "xiaomi-mimo")
    private val apiKey by option("--api-key", help = "API key for the provider")
    private val baseUrl by option("--base-url", help = "Custom base URL")
    private val cwd by option("--cwd", help = "Working directory").default(".")
    private val maxTurns by option("--max-turns", help = "Maximum turns").int().default(50)
    private val maxBudget by option("--max-budget", help = "Maximum budget in USD").long().default(100)
    private val permissions by option("--permissions", help = "Permission mode").choice("default", "bypass", "auto", "plan", "accept-edits", "don't-ask").default("default")
    private val skillsDir by option("--skills-dir", help = "Skills directory")
    private val repl by flag("--repl", help = "Start REPL mode")

    override fun run() = runBlocking {
        val terminal = Terminal()
        terminal.println("╔══════════════════════════════════════════════════════════╗")
        terminal.println("║       Claude Code Kotlin/JVM - AI Coding Agent         ║")
        terminal.println("╚══════════════════════════════════════════════════════════╝")
        terminal.println()
        val config = AgentConfig(File(cwd).absolutePath, model, provider ?: "anthropic", apiKey ?: System.getenv("ANTHROPIC_API_KEY") ?: System.getenv("OPENAI_API_KEY"), baseUrl, permissions, maxTurns, maxBudget, skillsDir, verbose)
        val agent = initializeAgent(config, terminal)
        if (repl || prompt == null) startREPL(agent, terminal) else { val result = agent.submit(prompt!!); terminal.println(result) }
    }

    private suspend fun initializeAgent(config: AgentConfig, terminal: Terminal): ClaudeCodeAgent {
        val registry = ModelRegistry()
        registry.registerProvider(createAnthropicProvider(config))
        registry.registerProvider(createOpenAIProvider(config))
        registry.registerProvider(createGoogleProvider(config))
        val toolRegistry = ToolRegistry.createDefault()
        val permissionSystem = PermissionSystem.INSTANCE
        val sessionManager = SessionManager(config.cwd)
        val contextManager = ContextManager(config.cwd)
        val skillManager = SkillManager(config.skillsDir ?: "${config.cwd}/.claude/skills")
        val subagentManager = SubagentManager(registry, toolRegistry)
        val hookManager = HookManager()
        val mcpManager = MCPManager()
        val planModeManager = PlanModeManager()
        return ClaudeCodeAgent(config, registry, toolRegistry, permissionSystem, sessionManager, contextManager, skillManager, subagentManager, hookManager, mcpManager, planModeManager, terminal)
    }

    private fun createAnthropicProvider(config: AgentConfig): ModelProvider = object : ModelProvider {
        override val id = "anthropic"; override val name = "Anthropic"; override val type = ProviderType.ANTHROPIC
        override val authConfig = AuthConfig(if (config.apiKey != null) AuthType.API_KEY else AuthType.OAUTH2, apiKey = config.apiKey)
        override val supportedProtocols = listOf(ProtocolType.ANTHROPIC_MESSAGES)
        override val defaultProtocol: ProtocolType = ProtocolType.ANTHROPIC_MESSAGES
        override fun getProtocol(type: ProtocolType): Protocol = AnthropicProtocol(baseUrl = config.baseUrl ?: "https://api.anthropic.com")
        override suspend fun listModels(): List<ModelInfo> = listOf(ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", "anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsToolCalling = true, supportsThinking = true, supportsVision = true, supportsStructuredOutput = true))
        override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
        override fun validate(): ValidationResult = if (config.apiKey != null) ValidationResult(true) else ValidationResult.failure("API key required")
        override suspend fun refreshAuth(): AuthResult = AuthResult(true)
    }

    private fun createOpenAIProvider(config: AgentConfig): ModelProvider = object : ModelProvider {
        override val id = "openai"; override val name = "OpenAI"; override val type = ProviderType.OPENAI
        override val authConfig = AuthConfig(AuthType.BEARER_TOKEN, accessToken = config.apiKey ?: System.getenv("OPENAI_API_KEY"))
        override val supportedProtocols = listOf(ProtocolType.OPENAI_CHAT_COMPLETIONS, ProtocolType.OPENAI_RESPONSES)
        override val defaultProtocol: ProtocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS
        override fun getProtocol(type: ProtocolType): Protocol = com.mimicryos.agent.protocol.OpenAIChatProtocol(baseUrl = config.baseUrl ?: "https://api.openai.com/v1")
        override suspend fun listModels(): List<ModelInfo> = listOf(ModelInfo("gpt-4o", "GPT-4o", "openai", contextWindow = 128_000, maxOutputTokens = 16_384, supportsToolCalling = true, supportsVision = true, supportsStructuredOutput = true))
        override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
        override fun validate(): ValidationResult = if (config.apiKey != null) ValidationResult(true) else ValidationResult.failure("API key required")
        override suspend fun refreshAuth(): AuthResult = AuthResult(true)
    }

    private fun createGoogleProvider(config: AgentConfig): ModelProvider = object : ModelProvider {
        override val id = "google"; override val name = "Google"; override val type = ProviderType.GOOGLE
        override val authConfig = AuthConfig(AuthType.API_KEY, apiKey = config.apiKey ?: System.getenv("GOOGLE_API_KEY") ?: System.getenv("GEMINI_API_KEY"))
        override val supportedProtocols = listOf(ProtocolType.GOOGLE_GEMINI)
        override val defaultProtocol: ProtocolType = ProtocolType.GOOGLE_GEMINI
        override fun getProtocol(type: ProtocolType): Protocol = com.mimicryos.agent.protocol.GeminiProtocol()
        override suspend fun listModels(): List<ModelInfo> = listOf(ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", "google", contextWindow = 1_000_000, maxOutputTokens = 8_192, supportsToolCalling = true, supportsVision = true, supportsThinking = true))
        override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null
        override fun validate(): ValidationResult = if (config.apiKey != null) ValidationResult(true) else ValidationResult.failure("API key required")
        override suspend fun refreshAuth(): AuthResult = AuthResult(true)
    }

    private suspend fun startREPL(agent: ClaudeCodeAgent, terminal: Terminal) {
        terminal.println("Type your prompt (or 'exit' to quit):"); terminal.println()
        while (true) { terminal.print("> "); val input = readLine() ?: break; if (input.equals("exit", ignoreCase = true)) break; if (input.isEmpty()) continue
            try { val result = agent.submit(input); terminal.println(result) } catch (e: Exception) { terminal.println("Error: ${e.message}") }; terminal.println() }
    }
}

data class AgentConfig(val cwd: String, val model: String, val providerId: String, val apiKey: String? = null, val baseUrl: String? = null, val permissionMode: String = "default", val maxTurns: Int = 50, val maxBudgetUsd: Long = 100, val skillsDir: String? = null, val verbose: Boolean = false)
