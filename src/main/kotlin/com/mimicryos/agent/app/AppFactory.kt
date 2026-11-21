package com.mimicryos.agent.app

import com.mimicryos.agent.context.ContextManager
import com.mimicryos.agent.hooks.HookManager
import com.mimicryos.agent.mcp.MCPManager
import com.mimicryos.agent.permission.PermissionSystem
import com.mimicryos.agent.plan.PlanModeManager
import com.mimicryos.agent.provider.ModelRegistry
import com.mimicryos.agent.provider.ProviderCatalog
import com.mimicryos.agent.session.SessionManager
import com.mimicryos.agent.skills.SkillManager
import com.mimicryos.agent.subagent.SubagentManager
import com.mimicryos.agent.tools.ToolRegistry
import java.io.File

data class AppComponents(
    val registry: ModelRegistry,
    val toolRegistry: ToolRegistry,
    val permissionSystem: PermissionSystem,
    val sessionManager: SessionManager,
    val contextManager: ContextManager,
    val skillManager: SkillManager,
    val subagentManager: SubagentManager,
    val hookManager: HookManager,
    val mcpManager: MCPManager,
    val planModeManager: PlanModeManager,
)

object AppFactory {
    fun create(config: AppConfig): AppComponents {
        val normalized = config.normalized()
        val registry = ProviderCatalog.createRegistry(
            ProviderCatalog.Settings(normalized.providerId, normalized.apiKey, normalized.baseUrl)
        )
        if (registry.getProvider(normalized.providerId) == null) {
            val available = registry.getAllProviders().joinToString(", ") { it.id }
            throw IllegalArgumentException("Unknown provider '${normalized.providerId}'. Available providers: $available")
        }

        val toolRegistry = ToolRegistry.createDefault()
        val sessionManager = SessionManager(normalized.projectDir)
        val contextManager = ContextManager(normalized.projectDir)
        val skillManager = SkillManager(
            normalized.skillsDir ?: File(normalized.projectDir, ".mimicry/skills").path
        )
        val subagentManager = SubagentManager(registry, toolRegistry)

        return AppComponents(
            registry = registry,
            toolRegistry = toolRegistry,
            permissionSystem = PermissionSystem.INSTANCE,
            sessionManager = sessionManager,
            contextManager = contextManager,
            skillManager = skillManager,
            subagentManager = subagentManager,
            hookManager = HookManager(),
            mcpManager = MCPManager(),
            planModeManager = PlanModeManager(),
        )
    }
}
