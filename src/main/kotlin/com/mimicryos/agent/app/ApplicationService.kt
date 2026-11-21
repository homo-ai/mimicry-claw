package com.mimicryos.agent.app

import com.mimicryos.agent.events.EventEnvelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

@Serializable
enum class TaskRunStatus {
    QUEUED,
    RUNNING,
    WAITING_PERMISSION,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

@Serializable
data class TaskInfo(
    val id: String,
    val sessionId: String,
    val prompt: String,
    val status: TaskRunStatus,
    val providerId: String,
    val model: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val turns: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

@Serializable
data class SessionSummary(
    val id: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val messageCount: Int,
    val projectDir: String,
)

@Serializable
data class TranscriptMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long,
    val toolUseId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
)

@Serializable
data class SessionTranscript(
    val sessionId: String,
    val messages: List<TranscriptMessage>,
    val lastEventSeq: Long,
)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val configured: Boolean,
    val defaultProtocol: String,
    val supportedProtocols: List<String> = emptyList(),
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val implemented: Boolean,
)

@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
    val status: String,
    val currentTaskId: String? = null,
)

@Serializable
data class PlanSnapshot(
    val planId: String,
    val prompt: String,
    val status: String,
    val steps: List<com.mimicryos.agent.events.PlanStepInfo> = emptyList(),
)

@Serializable
data class PermissionRequestInfo(
    val id: String,
    val taskId: String,
    val toolName: String,
    val target: String,
    val reason: String,
    val scope: String = "tool",
    val requestedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppSnapshot(
    val runtimeId: String,
    val startedAt: Long,
    val projectDir: String,
    val providerId: String,
    val model: String,
    val permissionMode: String,
    val sessions: List<SessionSummary> = emptyList(),
    val tasks: List<TaskInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    val plan: PlanSnapshot? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val tools: List<ToolInfo> = emptyList(),
    val pendingPermissions: List<PermissionRequestInfo> = emptyList(),
    val lastEventSeq: Long = 0,
    val runningTaskId: String? = null,
)

@Serializable
data class RuntimeConfig(
    val runtimeId: String,
    val projectDir: String,
    val providerId: String,
    val model: String,
    val permissionMode: String,
    val maxTurns: Int,
    val interactivePermissions: Boolean,
    val apiKeyConfigured: Boolean,
)

@Serializable
data class TaskRequest(
    val prompt: String,
    val sessionId: String? = null,
    val providerId: String? = null,
    val model: String? = null,
)

@Serializable
data class PermissionResponse(
    val approved: Boolean,
    val reason: String? = null,
)

/**
 * The single application-layer contract shared by CLI, TUI, GUI and the
 * local Runtime server. Frontends must not implement their own agent loop.
 */
interface ApplicationService {
    val events: SharedFlow<EventEnvelope>

    fun snapshot(): AppSnapshot
    fun eventHistory(since: Long = 0L): List<EventEnvelope>
    fun task(taskId: String): TaskInfo?

    suspend fun submit(request: TaskRequest): TaskInfo
    suspend fun awaitTask(taskId: String, timeoutMs: Long = 0L): TaskInfo?

    fun interrupt(taskId: String? = null): Boolean
    suspend fun respondToPermission(requestId: String, approved: Boolean, reason: String? = null): Boolean

    suspend fun createSession(): SessionSummary
    suspend fun resumeSession(sessionId: String): SessionSummary?
    fun sessionTranscript(sessionId: String): SessionTranscript?
    fun listSessions(): List<SessionSummary>

    fun setModel(model: String)
    fun setProvider(providerId: String): Boolean

    fun runtimeConfig(): RuntimeConfig
    fun shutdown()
}
