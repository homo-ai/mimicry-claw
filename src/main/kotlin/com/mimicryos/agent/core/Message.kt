package com.mimicryos.agent.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed class Message {
    abstract val uuid: String
    abstract val timestamp: Long
    abstract val isMeta: Boolean
    abstract val isVisibleInTranscriptOnly: Boolean
    abstract val parentToolUseId: String?

    @Serializable
    data class User(
        override val uuid: String,
        val message: UserMessage,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
        val toolUseResult: ToolResultBlockParam? = null,
    ) : Message()

    @Serializable
    data class Assistant(
        override val uuid: String,
        val message: AssistantMessage,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
        val isApiErrorMessage: Boolean = false,
    ) : Message()

    @Serializable
    data class System(
        override val uuid: String,
        val subtype: String,
        val content: String? = null,
        val compactMetadata: CompactMetadata? = null,
        val retryAttempt: Int? = null,
        val maxRetries: Int? = null,
        val retryInMs: Long? = null,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
    ) : Message()

    @Serializable
    data class Progress(
        override val uuid: String,
        val data: ToolProgressData,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
    ) : Message()

    @Serializable
    data class Attachment(
        override val uuid: String,
        val attachment: AttachmentData,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
    ) : Message()

    @Serializable
    data class Tombstone(
        override val uuid: String,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
    ) : Message()

    @Serializable
    data class StreamEvent(
        override val uuid: String,
        val event: StreamEventData,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
    ) : Message()

    @Serializable
    data class ToolUseSummary(
        override val uuid: String,
        val summary: String,
        val precedingToolUseIds: List<String>,
        override val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val isMeta: Boolean = false,
        override val isVisibleInTranscriptOnly: Boolean = false,
        override val parentToolUseId: String? = null,
    ) : Message()
}

@Serializable data class UserMessage(val role: String = "user", val content: Content)
@Serializable data class AssistantMessage(val role: String = "assistant", val content: List<ContentBlock>, val usage: Usage? = null, val stopReason: String? = null, val model: String? = null)

@Serializable sealed class Content {
    @Serializable data class Text(val text: String) : Content()
    @Serializable data class ToolUse(val id: String, val name: String, val input: Map<String, String>) : Content()
    @Serializable data class ToolResult(val toolUseId: String, val content: List<ResultContent>, val isError: Boolean = false) : Content()
    @Serializable data class Thinking(val thinking: String, val signature: String? = null) : Content()
}

@Serializable sealed class ContentBlock {
    @Serializable data class Text(val text: String) : ContentBlock()
    @Serializable data class ToolUse(val id: String, val name: String, val input: Map<String, String>) : ContentBlock()
    @Serializable data class ToolResult(val toolUseId: String, val content: List<ResultContent>, val isError: Boolean = false) : ContentBlock()
    @Serializable data class Thinking(val thinking: String, val signature: String? = null) : ContentBlock()
}

@Serializable sealed class ResultContent {
    @Serializable data class Text(val text: String) : ResultContent()
    @Serializable data class Image(val source: ImageSource) : ResultContent()
    @Serializable data class ToolUse(val id: String, val name: String, val input: Map<String, String>) : ResultContent()
}

@Serializable data class ImageSource(val type: String, val mediaType: String, val data: String)
@Serializable data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheCreationInputTokens: Long = 0, val cacheReadInputTokens: Long = 0)
@Serializable data class CompactMetadata(val preservedSegment: PreservedSegment? = null)
@Serializable data class PreservedSegment(val tailUuid: String? = null)

@Serializable sealed class ToolProgressData {
    @Serializable data class BashProgress(val type: String = "bash_progress", val command: String, val output: String) : ToolProgressData()
    @Serializable data class AgentToolProgress(val type: String = "agent_progress", val agentId: String, val description: String) : ToolProgressData()
    @Serializable data class MCPProgress(val type: String = "mcp_progress", val serverName: String, val toolName: String) : ToolProgressData()
    @Serializable data class GenericProgress(val type: String = "generic", val message: String) : ToolProgressData()
}

@Serializable sealed class AttachmentData {
    @Serializable data class StructuredOutput(val data: String) : AttachmentData()
    @Serializable data class MaxTurnsReached(val turnCount: Int, val maxTurns: Int) : AttachmentData()
    @Serializable data class QueuedCommand(val prompt: String, val sourceUuid: String? = null) : AttachmentData()
}

@Serializable sealed class StreamEventData {
    @Serializable data class MessageStart(val message: AssistantMessage) : StreamEventData()
    @Serializable data class MessageDelta(val delta: Delta, val usage: Usage? = null) : StreamEventData()
    @Serializable class MessageStop : StreamEventData()
    @Serializable data class ContentBlockStart(val index: Int, val contentBlock: ContentBlock) : StreamEventData()
    @Serializable data class ContentBlockDelta(val index: Int, val delta: Delta) : StreamEventData()
    @Serializable data class ContentBlockStop(val index: Int) : StreamEventData()
}

@Serializable sealed class Delta {
    @Serializable data class TextDelta(val text: String) : Delta()
    @Serializable data class ThinkingDelta(val thinking: String) : Delta()
    @Serializable data class ToolUseDelta(val id: String? = null, val name: String? = null, val input: Map<String, String>? = null) : Delta()
    @Serializable data class StopReason(val reason: String) : Delta()
}

@Serializable data class ToolResultBlockParam(val content: List<ResultContent>, val isError: Boolean = false)
@Serializable data class ToolUseBlockParam(val id: String, val name: String, val input: Map<String, String>)
@Serializable data class ContentBlockParam(val type: String, val text: String? = null, val thinking: String? = null, val signature: String? = null, val id: String? = null, val name: String? = null, val input: Map<String, String>? = null, val toolUseId: String? = null, val content: List<ResultContent>? = null, val isError: Boolean = false)
