package com.mimicryos.agent.core

import java.util.concurrent.ConcurrentHashMap

data class AppState(
    val toolPermissionContext: ToolPermissionContext = ToolPermissionContext(),
    val mcp: MCPState = MCPState(),
    val tasks: TaskState = TaskState(),
    val fileHistory: FileHistoryState = FileHistoryState(),
    val attribution: AttributionState = AttributionState(),
    val fastMode: FastModeState = FastModeState(),
    val settings: Settings = Settings(),
    val notifications: List<Notification> = emptyList(),
    val messages: List<Message> = emptyList(),
    val hasShownLlmAuth: Boolean = false,
    val primaryBranch: String? = null,
    val worktree: WorktreeState? = null,
    val prompt: String = "",
    val isPromptFocused: Boolean = true,
    val showHiddenModels: Boolean = false,
    val showDesktopNotifications: Boolean = false,
    val showSuggestions: Boolean = true,
    val verbose: Boolean = false,
    val memory: MemoryState = MemoryState(),
    val plugins: PluginsState = PluginsState(),
    val session: SessionState = SessionState(),
    val voice: VoiceState = VoiceState(),
    val planMode: PlanModeState = PlanModeState(),
    val coordinator: CoordinatorState = CoordinatorState(),
)

data class ToolPermissionContext(val mode: String = "default", val additionalWorkingDirectories: Map<String, AdditionalWorkingDirectory> = emptyMap(), val alwaysAllowRules: ToolPermissionRules = ToolPermissionRules(), val alwaysDenyRules: ToolPermissionRules = ToolPermissionRules(), val alwaysAskRules: ToolPermissionRules = ToolPermissionRules(), val isBypassPermissionsModeAvailable: Boolean = false, val isAutoModeAvailable: Boolean = false, val strippedDangerousRules: ToolPermissionRules = ToolPermissionRules(), val shouldAvoidPermissionPrompts: Boolean = false, val awaitAutomatedChecksBeforeDialog: Boolean = false, val prePlanMode: String? = null)

data class AdditionalWorkingDirectory(val path: String, val addedAt: Long = System.currentTimeMillis())
data class ToolPermissionRules(val bash: List<String> = emptyList(), val read: List<String> = emptyList(), val write: List<String> = emptyList(), val edit: List<String> = emptyList(), val glob: List<String> = emptyList(), val grep: List<String> = emptyList(), val agent: List<String> = emptyList(), val mcp: Map<String, List<String>> = emptyMap())
data class MCPState(val tools: List<Tool<*, *>> = emptyList(), val clients: List<MCPServerConnection> = emptyList(), val commands: List<Command> = emptyList(), val resources: Map<String, List<ServerResource>> = emptyMap(), val serverStatus: Map<String, MCPServerStatus> = emptyMap())
data class MCPServerStatus(val name: String, val status: String, val error: String? = null)
data class TaskState(val tasks: Map<String, TaskStateEntry> = emptyMap())
data class TaskStateEntry(val id: String, val type: TaskType, val status: TaskStatus, val description: String, val startTime: Long, val endTime: Long? = null, val output: String = "")
data class FastModeState(val enabled: Boolean = false, val model: String? = null)
data class Settings(val model: String? = null, val verbose: Boolean = false, val theme: String = "default", val permissions: Map<String, Any> = emptyMap())
data class MemoryState(val memoryPath: String? = null, val autoMemory: Boolean = true, val content: String = "")
data class PluginsState(val plugins: List<PluginInfo> = emptyList(), val enabled: List<String> = emptyList())
data class PluginInfo(val name: String, val version: String, val path: String, val enabled: Boolean)
data class SessionState(val sessionId: String = "", val createdAt: Long = System.currentTimeMillis(), val lastActiveAt: Long = System.currentTimeMillis(), val messageCount: Int = 0, val isPersistent: Boolean = true)
data class VoiceState(val enabled: Boolean = false, val mode: String = "push_to_talk")
data class PlanModeState(val active: Boolean = false, val planFilePath: String? = null, val planContent: String = "", val exitOnExit: Boolean = false)
data class CoordinatorState(val mode: String = "disabled", val workers: List<WorkerInfo> = emptyList(), val activeTask: String? = null)
data class WorkerInfo(val id: String, val name: String, val type: String, val status: String, val model: String? = null)
data class WorktreeState(val path: String, val branch: String, val isActive: Boolean = false)
data class Command(val name: String, val type: String = "local", val description: String = "", val source: String = "builtin", val loadedFrom: String = "builtin", val availability: List<String> = emptyList(), val aliases: List<String> = emptyList(), val isEnabled: Boolean = true, val hasUserSpecifiedDescription: Boolean = false, val whenToUse: String? = null, val disableModelInvocation: Boolean = false, val kind: String? = null, val pluginInfo: PluginInfo? = null, val contentLength: Int = 0, val progressMessage: String = "", val isNonInteractive: Boolean = false)
