package com.mimicryos.agent.core

import com.mimicryos.agent.provider.ToolPermissionContext
import com.mimicryos.agent.permission.PermissionResult

interface ToolInputSchema {
    val type: String get() = "object"
    val properties: Map<String, Any>? get() = null
}

data class ToolInputJSONSchema(val type: String = "object", val properties: Map<String, Any>? = null, val required: List<String>? = null)

data class ValidationResult(val result: Boolean, val message: String? = null, val errorCode: Int? = null) {
    companion object {
        val SUCCESS = ValidationResult(true)
        fun failure(message: String, errorCode: Int = 0) = ValidationResult(false, message, errorCode)
    }
}

data class ToolResult<T>(val data: T, val newMessages: List<Message> = emptyList(), val contextModifier: ((ToolUseContext) -> ToolUseContext)? = null, val mcpMeta: Map<String, Any>? = null)
typealias ToolCallProgress<P> = (progress: ToolProgress<P>) -> Unit
data class ToolProgress<P>(val toolUseID: String, val data: P)

fun toolMatchesName(tool: Any, name: String): Boolean {
    return when (tool) {
        is Tool<*, *> -> tool.name == name || (tool.aliases?.contains(name) == true)
        else -> false
    }
}

fun findToolByName(tools: List<Tool<*, *>>, name: String): Tool<*, *>? = tools.find { toolMatchesName(it, name) }

interface Tool<Input : Any, Output : Any> {
    val name: String
    val aliases: List<String>? get() = null
    val searchHint: String? get() = null
    val inputSchema: ToolInputSchema
    val inputJSONSchema: ToolInputJSONSchema? get() = null
    val outputSchema: Any? get() = null
    val maxResultSizeChars: Int get() = 80_000
    val strict: Boolean get() = false
    val shouldDefer: Boolean get() = false
    val alwaysLoad: Boolean get() = false
    val isMcp: Boolean get() = false
    val isLsp: Boolean get() = false

    fun isEnabled(): Boolean = true
    fun isConcurrencySafe(input: Input): Boolean = false
    fun isReadOnly(input: Input): Boolean = false
    fun isDestructive(input: Input): Boolean = false
    fun requiresUserInteraction(): Boolean = false
    fun isTransparentWrapper(): Boolean = false
    fun interruptBehavior(): InterruptBehavior = InterruptBehavior.BLOCK
    fun inputsEquivalent(a: Input, b: Input): Boolean = a == b

    suspend fun call(args: Input, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>? = null): ToolResult<Output>
    suspend fun description(input: Input, options: DescriptionOptions): String
    suspend fun validateInput(input: Input, context: ToolUseContext): ValidationResult = ValidationResult.SUCCESS
    suspend fun checkPermissions(input: Input, context: ToolUseContext): PermissionResult = PermissionResult.allow()
    fun getPath(input: Input): String? = null
    suspend fun preparePermissionMatcher(input: Input): ((pattern: String) -> Boolean)? = null
    fun prompt(options: PromptOptions): String = ""
    fun userFacingName(input: Input): String = name
    fun userFacingNameBackgroundColor(input: Input): String? = null
    fun toAutoClassifierInput(input: Input): Any = ""
    fun mapToolResultToToolResultBlockParam(content: Output, toolUseID: String): ToolResultBlockParam
    fun getToolUseSummary(input: Input): String? = null
    fun getActivityDescription(input: Input): String? = null
    fun isSearchOrReadCommand(input: Input): SearchOrReadResult? = null
    fun isOpenWorld(input: Input): Boolean = false
    fun renderToolResultMessage(content: Output, progressMessages: List<ProgressMessage<ToolProgressData>>, options: RenderOptions): Any? = null
    fun extractSearchText(out: Output): String? = null
    fun renderToolUseMessage(input: Input, options: RenderUseOptions): Any = ""
    fun isResultTruncated(output: Output): Boolean = false
    fun renderToolUseTag(input: Input): Any? = null
    fun renderToolUseProgressMessage(progressMessages: List<ProgressMessage<ToolProgressData>>, options: RenderProgressOptions): Any? = null
    fun renderToolUseQueuedMessage(): Any? = null
    fun renderToolUseRejectedMessage(input: Input, options: RenderRejectedOptions): Any? = null
    fun renderToolUseErrorMessage(result: ToolResultBlockParam, options: RenderErrorOptions): Any? = null
    fun renderGroupedToolUse(toolUses: List<GroupedToolUse>, options: GroupedOptions): Any? = null
    fun backfillObservableInput(input: Map<String, Any>) {}
}

enum class InterruptBehavior { CANCEL, BLOCK }

data class DescriptionOptions(val isNonInteractiveSession: Boolean, val toolPermissionContext: ToolPermissionContext, val tools: List<Tool<*, *>>)
data class PromptOptions(val getToolPermissionContext: suspend () -> ToolPermissionContext, val tools: List<Tool<*, *>>, val agents: List<AgentDefinition>, val allowedAgentTypes: List<String>? = null)
data class RenderOptions(val style: String? = null, val theme: String, val tools: List<Tool<*, *>>, val verbose: Boolean, val isTranscriptMode: Boolean = false, val isBriefOnly: Boolean = false, val input: Any? = null)
data class RenderUseOptions(val theme: String, val verbose: Boolean, val commands: List<Command> = emptyList())
data class RenderProgressOptions(val tools: List<Tool<*, *>>, val verbose: Boolean, val terminalSize: TerminalSize? = null, val inProgressToolCallCount: Int = 0, val isTranscriptMode: Boolean = false)
data class RenderRejectedOptions(val columns: Int, val messages: List<Message>, val style: String? = null, val theme: String, val tools: List<Tool<*, *>>, val verbose: Boolean, val progressMessages: List<ProgressMessage<ToolProgressData>>, val isTranscriptMode: Boolean = false)
data class RenderErrorOptions(val progressMessages: List<ProgressMessage<ToolProgressData>>, val tools: List<Tool<*, *>>, val verbose: Boolean, val isTranscriptMode: Boolean = false)
data class GroupedOptions(val shouldAnimate: Boolean, val tools: List<Tool<*, *>>)
data class GroupedToolUse(val param: ToolUseBlockParam, val isResolved: Boolean, val isError: Boolean, val isInProgress: Boolean, val progressMessages: List<ProgressMessage<ToolProgressData>>, val result: GroupedToolUseResult? = null)
data class GroupedToolUseResult(val param: ToolResultBlockParam, val output: Any)
data class SearchOrReadResult(val isSearch: Boolean, val isRead: Boolean, val isList: Boolean = false)
data class TerminalSize(val columns: Int, val rows: Int)
data class ProgressMessage<P>(val type: String, val data: P)

typealias Tools = List<Tool<*, *>>
typealias CanUseToolFn = suspend CanUseToolFunction

interface CanUseToolFunction {
    suspend operator fun invoke(tool: Tool<*, *>, input: Map<String, Any>, context: ToolUseContext, assistantMessage: Message.Assistant, toolUseID: String, forceDecision: String? = null): PermissionResult
}

data class AgentDefinition(val name: String, val description: String, val prompt: String, val tools: List<String>? = null, val disallowedTools: List<String>? = null, val model: String? = null, val permissionMode: String? = null, val maxTurns: Int? = null)
