package com.mimicryos.agent.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared event model used by CLI, TUI, GUI and the local Runtime server.
 *
 * The same event stream is the single source of truth for task, tool,
 * permission, file-change and plan state. Frontends may render events
 * differently, but must not invent incompatible state.
 */
@Serializable
sealed class AgentEvent {
    @Serializable
    @SerialName("runtime_status")
    data class RuntimeStatus(val status: String, val detail: String? = null) : AgentEvent()

    @Serializable
    @SerialName("session_created")
    data class SessionCreated(val sessionId: String, val projectDir: String) : AgentEvent()

    @Serializable
    @SerialName("session_resumed")
    data class SessionResumed(val sessionId: String, val messageCount: Int) : AgentEvent()

    @Serializable
    @SerialName("task_created")
    data class TaskCreated(val taskId: String, val sessionId: String, val prompt: String) : AgentEvent()

    @Serializable
    @SerialName("task_queued")
    data class TaskQueued(val taskId: String, val sessionId: String, val reason: String) : AgentEvent()

    @Serializable
    @SerialName("task_started")
    data class TaskStarted(val taskId: String, val sessionId: String) : AgentEvent()

    @Serializable
    @SerialName("task_completed")
    data class TaskCompleted(
        val taskId: String,
        val sessionId: String,
        val result: String,
        val turns: Int = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
    ) : AgentEvent()

    @Serializable
    @SerialName("task_failed")
    data class TaskFailed(val taskId: String, val sessionId: String, val error: String) : AgentEvent()

    @Serializable
    @SerialName("task_cancelled")
    data class TaskCancelled(
        val taskId: String,
        val sessionId: String,
        val reason: String = "cancelled by user",
    ) : AgentEvent()

    @Serializable
    @SerialName("agent_state")
    data class AgentStateChanged(
        val taskId: String? = null,
        val state: String,
        val detail: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("message")
    data class MessageEvent(
        val taskId: String,
        val messageId: String,
        val role: String,
        val text: String,
        val streaming: Boolean = false,
    ) : AgentEvent()

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val taskId: String, val messageId: String, val text: String) : AgentEvent()

    @Serializable
    @SerialName("tool_started")
    data class ToolStarted(
        val taskId: String,
        val toolUseId: String,
        val toolName: String,
        val summary: String,
        val input: Map<String, String> = emptyMap(),
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_progress")
    data class ToolProgressEvent(val taskId: String, val toolUseId: String, val message: String) : AgentEvent()

    @Serializable
    @SerialName("tool_completed")
    data class ToolCompleted(
        val taskId: String,
        val toolUseId: String,
        val toolName: String,
        val output: String,
        val isError: Boolean = false,
    ) : AgentEvent()

    @Serializable
    @SerialName("tool_failed")
    data class ToolFailed(
        val taskId: String,
        val toolUseId: String,
        val toolName: String,
        val error: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("permission_requested")
    data class PermissionRequested(
        val requestId: String,
        val taskId: String,
        val toolName: String,
        val target: String,
        val reason: String,
        val scope: String = "tool",
    ) : AgentEvent()

    @Serializable
    @SerialName("permission_resolved")
    data class PermissionResolved(
        val requestId: String,
        val taskId: String,
        val approved: Boolean,
        val reason: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("plan_updated")
    data class PlanUpdated(
        val planId: String,
        val status: String,
        val steps: List<PlanStepInfo> = emptyList(),
    ) : AgentEvent()

    @Serializable
    @SerialName("file_changed")
    data class FileChanged(
        val taskId: String? = null,
        val path: String,
        val operation: String,
        val summary: String? = null,
    ) : AgentEvent()

    @Serializable
    @SerialName("usage")
    data class UsageUpdated(val taskId: String, val inputTokens: Long, val outputTokens: Long) : AgentEvent()

    @Serializable
    @SerialName("log")
    data class LogEvent(val level: String, val message: String, val taskId: String? = null) : AgentEvent()

    @Serializable
    @SerialName("error")
    data class ErrorEvent(val message: String, val taskId: String? = null) : AgentEvent()
}

@Serializable
data class PlanStepInfo(
    val title: String,
    val status: String = "pending",
    val detail: String? = null,
)

@Serializable
data class EventEnvelope(
    val seq: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val event: AgentEvent,
)
