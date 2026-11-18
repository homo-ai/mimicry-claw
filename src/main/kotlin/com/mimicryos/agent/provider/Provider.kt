package com.mimicryos.agent.provider

import com.mimicryos.agent.core.*
import java.util.concurrent.ConcurrentHashMap

interface ModelProvider {
    val id: String
    val name: String
    val type: ProviderType
    val authConfig: AuthConfig
    val supportedProtocols: List<ProtocolType>
    val defaultProtocol: ProtocolType
    fun getProtocol(type: ProtocolType): Protocol
    suspend fun listModels(): List<ModelInfo>
    suspend fun getModelCapabilities(modelId: String): ModelCapabilities?
    fun validate(): ValidationResult
    suspend fun refreshAuth(): AuthResult
}

enum class ProviderType { ANTHROPIC, OPENAI, GOOGLE, OPENCODE_ZEN, ZCODE, KIMI, XIAOMI_MIMO, CUSTOM }
enum class ProtocolType { ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, OPENAI_RESPONSES, GOOGLE_GEMINI, CUSTOM_HTTP }
enum class AuthType { API_KEY, OAUTH2, BEARER_TOKEN, BASIC_AUTH, NONE }

data class AuthConfig(val type: AuthType, val apiKey: String? = null, val accessToken: String? = null, val refreshToken: String? = null, val clientId: String? = null, val clientSecret: String? = null, val tokenEndpoint: String? = null, val extra: Map<String, String> = emptyMap())
data class AuthResult(val success: Boolean, val accessToken: String? = null, val refreshToken: String? = null, val expiresIn: Long? = null, val error: String? = null)
data class ModelInfo(val id: String, val name: String, val providerId: String, val description: String = "", val contextWindow: Int = 0, val maxOutputTokens: Int = 0, val supportsStreaming: Boolean = true, val supportsToolCalling: Boolean = true, val supportsVision: Boolean = false, val supportsThinking: Boolean = false, val supportsStructuredOutput: Boolean = false, val costPerMillionInput: Double = 0.0, val costPerMillionOutput: Double = 0.0)
data class ModelCapabilities(val modelId: String, val maxContextTokens: Int, val maxOutputTokens: Int, val supportsToolCalling: Boolean, val supportsStreaming: Boolean, val supportsVision: Boolean, val supportsThinking: Boolean, val supportsStructuredOutput: Boolean, val supportsMultimodal: Boolean = false, val thinkingBudgetTokens: Int? = null, val reasoningEffortLevels: List<String> = emptyList())

interface Protocol {
    val type: ProtocolType
    fun buildRequest(model: String, messages: List<APIMessage>, tools: List<APITool>, systemPrompt: String, config: ProtocolConfig): APIRequest
    fun parseResponse(response: APIResponse): List<APIEvent>
    fun parseStreamChunk(chunk: String): List<APIEvent>
    fun formatToolResult(toolUseId: String, result: ToolResultBlockParam): APIMessage
    fun getEndpoint(provider: ModelProvider, model: String): String
    fun getHeaders(provider: ModelProvider, config: ProtocolConfig): Map<String, String>
}

data class ProtocolConfig(val temperature: Double? = null, val maxTokens: Int? = null, val topP: Double? = null, val stopSequences: List<String> = emptyList(), val thinkingBudget: Int? = null, val reasoningEffort: String? = null, val structuredOutputSchema: Map<String, Any>? = null, val timeoutMs: Long = 120_000, val extraParams: Map<String, Any> = emptyMap())
data class APIRequest(val url: String, val method: String = "POST", val headers: Map<String, String>, val body: String, val timeoutMs: Long = 120_000)
data class APIResponse(val statusCode: Int, val headers: Map<String, String>, val body: String)

sealed class APIEvent {
    data class MessageStart(val message: APIMessage) : APIEvent()
    data class ContentBlockStart(val index: Int, val block: APIContentBlock) : APIEvent()
    data class ContentBlockDelta(val index: Int, val delta: APIDelta) : APIEvent()
    data class ContentBlockStop(val index: Int) : APIEvent()
    data class MessageDelta(val delta: APIDelta, val usage: APIUsage?) : APIEvent()
    data class MessageStop : APIEvent()
    data class Error(val error: APIError) : APIEvent()
    data class Usage(val usage: APIUsage) : APIEvent()
}

data class APIMessage(val role: String, val content: List<APIContentBlock>, val model: String? = null, val stopReason: String? = null, val usage: APIUsage? = null)
sealed class APIContentBlock {
    data class Text(val text: String) : APIContentBlock()
    data class Thinking(val thinking: String, val signature: String? = null) : APIContentBlock()
    data class ToolUse(val id: String, val name: String, val input: Map<String, String>) : APIContentBlock()
    data class ToolResult(val toolUseId: String, val content: List<APIResultContent>, val isError: Boolean = false) : APIContentBlock()
    data class Image(val mediaType: String, val data: String) : APIContentBlock()
}
sealed class APIResultContent {
    data class Text(val text: String) : APIResultContent()
    data class Image(val mediaType: String, val data: String) : APIResultContent()
}
sealed class APIDelta {
    data class TextDelta(val text: String) : APIDelta()
    data class ThinkingDelta(val thinking: String) : APIDelta()
    data class ToolUseDelta(val id: String? = null, val name: String? = null, val input: Map<String, String>? = null) : APIDelta()
    data class StopReason(val reason: String) : APIDelta()
}
data class APIError(val type: String, val message: String, val statusCode: Int? = null, val retryable: Boolean = false)
data class APIUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheCreationInputTokens: Long = 0, val cacheReadInputTokens: Long = 0)
data class APITool(val name: String, val description: String, val inputSchema: Map<String, Any>)

interface ModelRouter {
    suspend fun route(task: RoutingTask): ModelRoute
    fun registerModel(model: ModelInfo, capabilities: ModelCapabilities)
    fun getModelsForCapability(capability: ModelCapability): List<ModelInfo>
}

data class RoutingTask(val type: TaskType, val description: String, val requiredCapabilities: List<ModelCapability>, val preferredModel: String? = null, val maxCostPerMillion: Double? = null, val contextSize: Int = 0)
data class ModelRoute(val modelId: String, val providerId: String, val protocol: ProtocolType, val reason: String)
enum class ModelCapability { CODE_GENERATION, CODE_MODIFICATION, CODE_REVIEW, TASK_PLANNING, TOOL_CALLING, RESULT_JUDGMENT, SCHEME_COMPARISON, DAILY_CONVERSATION, VISION, STRUCTURED_OUTPUT, THINKING, MULTIMODAL, LONG_CONTEXT }

class ModelRegistry {
    private val providers = ConcurrentHashMap<String, ModelProvider>()
    private val models = ConcurrentHashMap<String, ModelInfo>()
    private val capabilities = ConcurrentHashMap<String, ModelCapabilities>()
    private val router = DefaultModelRouter()

    fun registerProvider(provider: ModelProvider) { providers[provider.id] = provider }
    fun getProvider(id: String): ModelProvider? = providers[id]
    fun getAllProviders(): List<ModelProvider> = providers.values.toList()
    fun registerModel(model: ModelInfo, caps: ModelCapabilities) { models[model.id] = model; capabilities[model.id] = caps; router.registerModel(model, caps) }
    fun getModel(id: String): ModelInfo? = models[id]
    fun getCapabilities(modelId: String): ModelCapabilities? = capabilities[modelId]
    fun getRouter(): ModelRouter = router
    fun findModels(capability: ModelCapability): List<ModelInfo> = router.getModelsForCapability(capability)
}

class DefaultModelRouter : ModelRouter {
    private val modelCaps = ConcurrentHashMap<String, ModelCapabilities>()
    override fun registerModel(model: ModelInfo, capabilities: ModelCapabilities) { modelCaps[model.id] = capabilities }
    override fun getModelsForCapability(capability: ModelCapability): List<ModelInfo> = modelCaps.entries.filter { (_, caps) -> hasCapability(caps, capability) }.map { (id, _) -> ModelInfo(id = id, name = id, providerId = "unknown") }.toList()
    override suspend fun route(task: RoutingTask): ModelRoute {
        val candidates = task.requiredCapabilities.flatMap { getModelsForCapability(it) }.distinct()
        val selected = task.preferredModel?.let { id -> candidates.find { it.id == id } } ?: candidates.firstOrNull() ?: ModelInfo(id = "default", name = "Default", providerId = "default")
        return ModelRoute(selected.id, selected.providerId, ProtocolType.ANTHROPIC_MESSAGES, if (task.preferredModel != null) "user_preferred" else "default_route")
    }
    private fun hasCapability(caps: ModelCapabilities, capability: ModelCapability): Boolean = when (capability) {
        ModelCapability.CODE_GENERATION -> caps.supportsToolCalling
        ModelCapability.CODE_MODIFICATION -> caps.supportsToolCalling
        ModelCapability.CODE_REVIEW -> caps.supportsToolCalling
        ModelCapability.TASK_PLANNING -> caps.supportsThinking
        ModelCapability.TOOL_CALLING -> caps.supportsToolCalling
        ModelCapability.RESULT_JUDGMENT -> true
        ModelCapability.SCHEME_COMPARISON -> caps.supportsThinking
        ModelCapability.DAILY_CONVERSATION -> true
        ModelCapability.VISION -> caps.supportsVision
        ModelCapability.STRUCTURED_OUTPUT -> caps.supportsStructuredOutput
        ModelCapability.THINKING -> caps.supportsThinking
        ModelCapability.MULTIMODAL -> caps.supportsMultimodal
        ModelCapability.LONG_CONTEXT -> caps.maxContextTokens > 32_000
    }
}
