package com.mimicryos.agent.permission

import com.mimicryos.agent.core.*

sealed class PermissionResult {
    data class Allow(val input: Map<String, Any>? = null) : PermissionResult()
    data class Deny(val message: String, val code: String? = null) : PermissionResult()
    data class Ask(val message: String, val code: String? = null) : PermissionResult()

    companion object {
        fun allow(input: Map<String, Any>? = null) = Allow(input)
        fun deny(message: String, code: String? = null) = Deny(message, code)
        fun ask(message: String, code: String? = null) = Ask(message, code)
    }
}

class PermissionSystem {
    private val denialTracking = DenialTrackingState()

    suspend fun canUseTool(tool: Tool<*, *>, input: Map<String, Any>, context: ToolUseContext, assistantMessage: Message.Assistant, toolUseID: String, forceDecision: String? = null): PermissionResult {
        val permissionContext = context.options.toolPermissionContext
        if (forceDecision != null) return when (forceDecision) { "allow" -> PermissionResult.allow(input); "deny" -> PermissionResult.deny("Force denied"); else -> PermissionResult.ask("Force ask") }
        if (isDeniedByRules(tool, permissionContext)) { recordDenial(tool.name); return PermissionResult.deny("Tool ${tool.name} is denied by permission rules", "deny_rule") }
        if (isAllowedByRules(tool, permissionContext)) return PermissionResult.allow(input)
        if (permissionContext.mode == "bypass" && permissionContext.isBypassPermissionsModeAvailable) return PermissionResult.allow(input)
        if (permissionContext.mode == "auto" && permissionContext.isAutoModeAvailable ?: false) return checkAutoMode(tool, input, context)
        if (tool.requiresUserInteraction()) return PermissionResult.ask("Tool ${tool.name} requires user interaction", "user_interaction")
        val isDestructive = try { (tool as Tool<Any, Any>).isDestructive(input as Any) } catch (e: Exception) { false }
        if (isDestructive && permissionContext.mode == "default") return PermissionResult.ask("Tool ${tool.name} performs destructive operations", "destructive")
        if (permissionContext.mode == "default" && !permissionContext.shouldAvoidPermissionPrompts) return PermissionResult.ask("Tool ${tool.name} requires permission", "default")
        return PermissionResult.allow(input)
    }

    private fun isDeniedByRules(tool: Tool<*, *>, context: ToolPermissionContext): Boolean {
        val rules = context.alwaysDenyRules
        return rules.bash.any { matchesPattern(tool.name, it) } || rules.read.any { matchesPattern(tool.name, it) } || rules.write.any { matchesPattern(tool.name, it) } || rules.edit.any { matchesPattern(tool.name, it) } || rules.glob.any { matchesPattern(tool.name, it) } || rules.grep.any { matchesPattern(tool.name, it) } || rules.agent.any { matchesPattern(tool.name, it) } || rules.mcp.values.flatMap { it }.any { matchesPattern(tool.name, it) }
    }

    private fun isAllowedByRules(tool: Tool<*, *>, context: ToolPermissionContext): Boolean {
        val rules = context.alwaysAllowRules
        return rules.bash.any { matchesPattern(tool.name, it) } || rules.read.any { matchesPattern(tool.name, it) } || rules.write.any { matchesPattern(tool.name, it) } || rules.edit.any { matchesPattern(tool.name, it) } || rules.glob.any { matchesPattern(tool.name, it) } || rules.grep.any { matchesPattern(tool.name, it) } || rules.agent.any { matchesPattern(tool.name, it) } || rules.mcp.values.flatMap { it }.any { matchesPattern(tool.name, it) }
    }

    private fun matchesPattern(name: String, pattern: String): Boolean = when { pattern.endsWith("*") -> name.startsWith(pattern.dropLast(1)); pattern.contains("*") -> { val p = pattern.split("*"); p.size == 2 && name.startsWith(p[0]) && name.endsWith(p[1]) }; else -> name == pattern }

    private suspend fun checkAutoMode(tool: Tool<*, *>, input: Map<String, Any>, context: ToolUseContext): PermissionResult {
        val typedTool = tool as Tool<Any, Any>
        val classifierInput = typedTool.toAutoClassifierInput(input as Any)
        if (classifierInput as? String == "") return PermissionResult.allow(input)
        return if (typedTool.isReadOnly(input as Any) && !typedTool.isDestructive(input as Any)) PermissionResult.allow(input) else PermissionResult.ask("Auto mode requires review for ${tool.name}", "auto_mode_review")
    }

    private fun recordDenial(toolName: String) { denialTracking.count++; denialTracking.lastDenial = System.currentTimeMillis() }
    fun getDenialTracking(): DenialTrackingState = denialTracking
    companion object { val INSTANCE = PermissionSystem() }
}

enum class PermissionMode { DEFAULT, BYPASS, AUTO, PLAN, ACCEPT_EDITS, DON'T_KASK }
data class ToolPermissionRulesBySource(val user: List<String> = emptyList(), val project: List<String> = emptyList(), val local: List<String> = emptyList())
object PermissionContextFactory {
    fun create(mode: String = "default", additionalWorkingDirectories: Map<String, AdditionalWorkingDirectory> = emptyMap(), alwaysAllowRules: ToolPermissionRules = ToolPermissionRules(), alwaysDenyRules: ToolPermissionRules = ToolPermissionRules(), alwaysAskRules: ToolPermissionRules = ToolPermissionRules(), isBypassPermissionsModeAvailable: Boolean = false, isAutoModeAvailable: Boolean = false): ToolPermissionContext = ToolPermissionContext(mode, additionalWorkingDirectories, alwaysAllowRules, alwaysDenyRules, alwaysAskRules, isBypassPermissionsModeAvailable, isAutoModeAvailable)
    fun default(): ToolPermissionContext = create()
    fun bypass(): ToolPermissionContext = create("bypass", isBypassPermissionsModeAvailable = true)
    fun acceptEdits(): ToolPermissionContext = create("accept_edits", alwaysAllowRules = ToolPermissionRules(edit = listOf("*"), write = listOf("*")))
    fun plan(): ToolPermissionContext = create()
}