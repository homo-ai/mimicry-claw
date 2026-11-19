package com.mimicryos.agent.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AbortController {
    private val _aborted = AtomicBoolean(false)
    val isAborted: Boolean get() = _aborted.get()
    fun abort() { _aborted.set(true) }
    val signal: AbortSignal = AbortSignal(this)
}

class AbortSignal(private val controller: AbortController) {
    val aborted: Boolean get() = controller.isAborted
    fun addEventListener(type: String, listener: (Map<String, Any>) -> Unit) {}
}

data class ToolUseContext(
    val options: ToolOptions,
    val abortController: AbortController,
    val readFileState: FileStateCache,
    val getAppState: () -> AppState,
    val setAppState: (f: (prev: AppState) -> AppState) -> Unit,
    val setAppStateForTasks: ((f: (prev: AppState) -> AppState) -> Unit)? = null,
    val handleElicitation: (suspend (String, ElicitRequestParams, AbortSignal) -> ElicitResult)? = null,
    val setToolJSX: ((args: ToolJSXArgs) -> Unit)? = null,
    val addNotification: ((notification: Notification) -> Unit)? = null,
    val appendSystemMessage: ((msg: Message) -> Unit)? = null,
    val sendOSNotification: ((opts: OSNotificationOpts) -> Unit)? = null,
    val nestedMemoryAttachmentTriggers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val loadedNestedMemoryPaths: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val dynamicSkillDirTriggers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val discoveredSkillNames: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val userModified: Boolean = false,
    val setInProgressToolUseIDs: (f: (prev: Set<String>) -> Set<String>) -> Unit = { it },
    val setHasInterruptibleToolInProgress: ((v: Boolean) -> Unit)? = null,
    val setResponseLength: (f: (prev: Int) -> Int) -> Unit = { it },
    val pushApiMetricsEntry: ((ttftMs: Long) -> Unit)? = null,
    val setStreamMode: ((mode: String) -> Unit)? = null,
    val onCompactProgress: ((event: CompactProgressEvent) -> Unit)? = null,
    val setSDKStatus: ((status: String) -> Unit)? = null,
    val openMessageSelector: (() -> Unit)? = null,
    val updateFileHistoryState: (updater: (prev: FileHistoryState) -> FileHistoryState) -> Unit = { _ -> },
    val updateAttributionState: (updater: (prev: AttributionState) -> AttributionState) -> Unit = { _ -> },
    val setConversationId: ((id: String) -> Unit)? = null,
    val agentId: String? = null,
    val agentType: String? = null,
    val requireCanUseTool: Boolean = false,
    val messages: List<Message> = emptyList(),
    val fileReadingLimits: FileReadingLimits? = null,
    val globLimits: GlobLimits? = null,
    val toolDecisions: Map<String, ToolDecision> = emptyMap(),
    val queryTracking: QueryChainTracking? = null,
    val requestPrompt: (suspend (sourceName: String, toolInputSummary: String?) -> PromptCallback)? = null,
    val toolUseId: String? = null,
    val criticalSystemReminderExperimental: String? = null,
    val preserveToolUseResults: Boolean = false,
    val localDenialTracking: DenialTrackingState? = null,
    val contentReplacementState: ContentReplacementState? = null,
    val renderedSystemPrompt: String? = null,
)

data class ToolOptions(val commands: List<Command> = emptyList(), val debug: Boolean = false, val mainLoopModel: String, val tools: Tools, val verbose: Boolean = false, val thinkingConfig: ThinkingConfig, val mcpClients: List<MCPServerConnection> = emptyList(), val mcpResources: Map<String, List<ServerResource>> = emptyMap(), val isNonInteractiveSession: Boolean = false, val agentDefinitions: AgentDefinitionsResult, val maxBudgetUsd: Double? = null, val customSystemPrompt: String? = null, val appendSystemPrompt: String? = null, val querySource: String = "sdk", val ideInstallationStatus: String? = null, val theme: String = "default", val refreshTools: (() -> Tools)? = null, val toolPermissionContext: ToolPermissionContext = ToolPermissionContext())

data class AgentDefinitionsResult(val activeAgents: List<AgentDefinition>, val allAgents: List<AgentDefinition>)
data class ThinkingConfig(val type: String = "disabled", val budgetTokens: Int? = null, val enableReasoning: Boolean = false)
data class FileReadingLimits(val maxTokens: Int? = null, val maxSizeBytes: Long? = null)
data class GlobLimits(val maxResults: Int? = null)
data class ToolDecision(val source: String, val decision: String, val timestamp: Long)
data class QueryChainTracking(val chainId: String, val depth: Int)
data class ToolJSXArgs(val jsx: Any? = null, val shouldHidePromptInput: Boolean, val shouldContinueAnimation: Boolean = false, val showSpinner: Boolean = false, val isLocalJSXCommand: Boolean = false, val isImmediate: Boolean = false, val clearLocalJSX: Boolean = false)
data class OSNotificationOpts(val message: String, val notificationType: String)
data class Notification(val type: String, val message: String, val level: String = "info")
data class PromptCallback(val sourceName: String, val toolInputSummary: String? = null)
data class ElicitRequestParams(val message: String, val requestedSchema: Map<String, Any>)
data class ElicitResult(val action: String, val content: Map<String, Any>? = null)
data class CompactProgressEvent(val type: String, val hookType: String? = null)
data class FileHistoryState(val snapshots: Map<String, FileSnapshot> = emptyMap())
data class FileSnapshot(val uuid: String, val timestamp: Long, val contentHash: String)
data class AttributionState(val commits: List<AttributionCommit> = emptyList())
data class AttributionCommit(val sha: String, val message: String, val author: String)
data class DenialTrackingState(val count: Int = 0, val lastDenial: Long = 0)
data class ContentReplacementState(val decisions: Map<String, Boolean> = emptyMap())
class FileStateCache { private val cache = ConcurrentHashMap<String, CachedFile>(); private val maxEntries = 100
    fun get(path: String): CachedFile? = cache[path]
    fun put(path: String, content: CachedFile) { if (cache.size >= maxEntries) cache.keys.firstOrNull()?.let { cache.remove(it) }; cache[path] = content }
    fun has(path: String): Boolean = cache.containsKey(path)
    fun clear() = cache.clear()
    fun size(): Int = cache.size
}
data class CachedFile(val path: String, val content: String, val timestamp: Long, val hash: String)
data class MCPServerConnection(val name: String, val command: String, val args: List<String> = emptyList(), val env: Map<String, String> = emptyMap(), val transport: String = "stdio")
data class ServerResource(val uri: String, val name: String, val mimeType: String? = null, val text: String? = null)
