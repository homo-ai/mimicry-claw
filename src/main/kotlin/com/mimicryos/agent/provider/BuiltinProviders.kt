package com.mimicryos.agent.provider

import com.mimicryos.agent.core.ValidationResult
import com.mimicryos.agent.protocol.AnthropicProtocol
import com.mimicryos.agent.protocol.GeminiProtocol
import com.mimicryos.agent.protocol.OpenAIChatProtocol

class AnthropicProvider(
    private val apiKey: String?,
    private val baseUrl: String = "https://api.anthropic.com",
) : ModelProvider {
    override val id = "anthropic"
    override val name = "Anthropic"
    override val type = ProviderType.ANTHROPIC
    override val authConfig = AuthConfig(AuthType.API_KEY, apiKey = apiKey)
    override val supportedProtocols = listOf(ProtocolType.ANTHROPIC_MESSAGES)
    override val defaultProtocol = ProtocolType.ANTHROPIC_MESSAGES

    override fun getProtocol(type: ProtocolType): Protocol = AnthropicProtocol(baseUrl = baseUrl)

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", id, contextWindow = 200_000, maxOutputTokens = 64_000, supportsToolCalling = true, supportsThinking = true, supportsVision = true, supportsStructuredOutput = true),
        ModelInfo("claude-opus-4-20250514", "Claude Opus 4", id, contextWindow = 200_000, maxOutputTokens = 32_000, supportsToolCalling = true, supportsThinking = true, supportsVision = true, supportsStructuredOutput = true),
    )

    override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null

    override fun validate(): ValidationResult =
        if (apiKey.isNullOrBlank()) ValidationResult.failure("ANTHROPIC_API_KEY is not configured") else ValidationResult.SUCCESS

    override suspend fun refreshAuth(): AuthResult = AuthResult(true)
}

class OpenAIProvider(
    private val apiKey: String?,
    private val baseUrl: String = "https://api.openai.com/v1",
) : ModelProvider {
    override val id = "openai"
    override val name = "OpenAI"
    override val type = ProviderType.OPENAI
    override val authConfig = AuthConfig(AuthType.BEARER_TOKEN, accessToken = apiKey)
    override val supportedProtocols = listOf(ProtocolType.OPENAI_CHAT_COMPLETIONS, ProtocolType.OPENAI_RESPONSES)
    override val defaultProtocol = ProtocolType.OPENAI_CHAT_COMPLETIONS

    override fun getProtocol(type: ProtocolType): Protocol = OpenAIChatProtocol(baseUrl = baseUrl)

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("gpt-4o", "GPT-4o", id, contextWindow = 128_000, maxOutputTokens = 16_384, supportsToolCalling = true, supportsVision = true, supportsStructuredOutput = true),
        ModelInfo("gpt-4o-mini", "GPT-4o mini", id, contextWindow = 128_000, maxOutputTokens = 16_384, supportsToolCalling = true, supportsVision = true, supportsStructuredOutput = true),
    )

    override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null

    override fun validate(): ValidationResult =
        if (apiKey.isNullOrBlank()) ValidationResult.failure("OPENAI_API_KEY is not configured") else ValidationResult.SUCCESS

    override suspend fun refreshAuth(): AuthResult = AuthResult(true)
}

class GoogleProvider(private val apiKey: String?) : ModelProvider {
    override val id = "google"
    override val name = "Google"
    override val type = ProviderType.GOOGLE
    override val authConfig = AuthConfig(AuthType.API_KEY, apiKey = apiKey)
    override val supportedProtocols = listOf(ProtocolType.GOOGLE_GEMINI)
    override val defaultProtocol = ProtocolType.GOOGLE_GEMINI

    override fun getProtocol(type: ProtocolType): Protocol = GeminiProtocol()

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", id, contextWindow = 1_000_000, maxOutputTokens = 8_192, supportsToolCalling = true, supportsVision = true, supportsThinking = true),
    )

    override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null

    override fun validate(): ValidationResult =
        if (apiKey.isNullOrBlank()) ValidationResult.failure("GOOGLE_API_KEY or GEMINI_API_KEY is not configured") else ValidationResult.SUCCESS

    override suspend fun refreshAuth(): AuthResult = AuthResult(true)
}

/**
 * OpenAI-compatible provider used by DeepSeek, Kimi, ZCode/Z.ai and MiMo.
 * Each provider keeps its own base URL and model catalog.
 */
class OpenAICompatibleProvider(
    override val id: String,
    override val name: String,
    override val type: ProviderType,
    private val baseUrl: String,
    private val apiKey: String?,
    private val models: List<ModelInfo>,
) : ModelProvider {
    override val authConfig = AuthConfig(AuthType.BEARER_TOKEN, accessToken = apiKey)
    override val supportedProtocols = listOf(ProtocolType.OPENAI_CHAT_COMPLETIONS)
    override val defaultProtocol = ProtocolType.OPENAI_CHAT_COMPLETIONS

    override fun getProtocol(type: ProtocolType): Protocol = OpenAIChatProtocol(baseUrl = baseUrl)

    override suspend fun listModels(): List<ModelInfo> = models

    override suspend fun getModelCapabilities(modelId: String): ModelCapabilities? = null

    override fun validate(): ValidationResult = when {
        apiKey.isNullOrBlank() -> ValidationResult.failure("$name API key is not configured")
        baseUrl.isBlank() -> ValidationResult.failure("$name base URL is not configured")
        else -> ValidationResult.SUCCESS
    }

    override suspend fun refreshAuth(): AuthResult = AuthResult(true)
}

object ProviderCatalog {
    data class Settings(val providerId: String, val apiKey: String?, val baseUrl: String?)

    fun normalize(providerId: String): String = when (providerId.lowercase()) {
        "zcode", "z.ai", "zai", "openai-zencode" -> "openai-zencode"
        "mimo", "xiaomi", "xiaomi-mimo" -> "xiaomi-mimo"
        "gemini", "google" -> "google"
        else -> providerId.lowercase()
    }

    fun knownProviders(): List<String> = listOf("anthropic", "openai", "google", "deepseek", "openai-zencode", "kimi", "xiaomi-mimo")

    fun defaultModel(providerId: String): String = when (normalize(providerId)) {
        "anthropic" -> "claude-sonnet-4-20250514"
        "openai" -> "gpt-4o"
        "google" -> "gemini-2.0-flash"
        "deepseek" -> "deepseek-chat"
        "openai-zencode" -> "glm-4.6"
        "kimi" -> "moonshot-v1-8k"
        "xiaomi-mimo" -> System.getenv("MIMO_MODEL") ?: ""
        else -> ""
    }

    fun defaultApiKey(providerId: String): String? = when (normalize(providerId)) {
        "anthropic" -> System.getenv("ANTHROPIC_API_KEY")
        "openai" -> System.getenv("OPENAI_API_KEY")
        "google" -> System.getenv("GOOGLE_API_KEY") ?: System.getenv("GEMINI_API_KEY")
        "deepseek" -> System.getenv("DEEPSEEK_API_KEY")
        "openai-zencode" -> System.getenv("ZAI_API_KEY") ?: System.getenv("ZCODE_API_KEY")
        "kimi" -> System.getenv("MOONSHOT_API_KEY") ?: System.getenv("KIMI_API_KEY")
        "xiaomi-mimo" -> System.getenv("MIMO_API_KEY")
        else -> null
    }

    fun defaultBaseUrl(providerId: String): String? = when (normalize(providerId)) {
        "anthropic" -> System.getenv("ANTHROPIC_BASE_URL")
        "openai" -> System.getenv("OPENAI_BASE_URL")
        "google" -> null
        "deepseek" -> System.getenv("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com/v1"
        "openai-zencode" -> System.getenv("ZAI_BASE_URL") ?: "https://api.z.ai/api/paas/v4"
        "kimi" -> System.getenv("MOONSHOT_BASE_URL") ?: "https://api.moonshot.cn/v1"
        "xiaomi-mimo" -> System.getenv("MIMO_BASE_URL")
        else -> null
    }

    fun createRegistry(settings: Settings): ModelRegistry {
        val selected = normalize(settings.providerId)
        fun key(id: String): String? = if (id == selected) (settings.apiKey ?: defaultApiKey(id)) else defaultApiKey(id)
        fun url(id: String, fallback: String): String =
            if (id == selected) (settings.baseUrl ?: defaultBaseUrl(id) ?: fallback) else (defaultBaseUrl(id) ?: fallback)

        val registry = ModelRegistry()
        registry.registerProvider(AnthropicProvider(key("anthropic"), url("anthropic", "https://api.anthropic.com")))
        registry.registerProvider(OpenAIProvider(key("openai"), url("openai", "https://api.openai.com/v1")))
        registry.registerProvider(GoogleProvider(key("google")))

        registry.registerProvider(
            OpenAICompatibleProvider(
                id = "deepseek",
                name = "DeepSeek",
                type = ProviderType.DEEPSEEK,
                baseUrl = url("deepseek", "https://api.deepseek.com/v1"),
                apiKey = key("deepseek"),
                models = listOf(
                    ModelInfo("deepseek-chat", "DeepSeek Chat", "deepseek", contextWindow = 64_000, maxOutputTokens = 8_192, supportsToolCalling = true),
                    ModelInfo("deepseek-reasoner", "DeepSeek Reasoner", "deepseek", contextWindow = 64_000, maxOutputTokens = 8_192, supportsToolCalling = true, supportsThinking = true),
                ),
            )
        )
        registry.registerProvider(
            OpenAICompatibleProvider(
                id = "openai-zencode",
                name = "ZCode / Z.ai",
                type = ProviderType.ZCODE,
                baseUrl = url("openai-zencode", "https://api.z.ai/api/paas/v4"),
                apiKey = key("openai-zencode"),
                models = listOf(
                    ModelInfo("glm-4.6", "GLM-4.6", "openai-zencode", contextWindow = 200_000, maxOutputTokens = 16_384, supportsToolCalling = true, supportsThinking = true),
                    ModelInfo("glm-4.5", "GLM-4.5", "openai-zencode", contextWindow = 128_000, maxOutputTokens = 16_384, supportsToolCalling = true, supportsThinking = true),
                ),
            )
        )
        registry.registerProvider(
            OpenAICompatibleProvider(
                id = "kimi",
                name = "Kimi / Moonshot",
                type = ProviderType.KIMI,
                baseUrl = url("kimi", "https://api.moonshot.cn/v1"),
                apiKey = key("kimi"),
                models = listOf(
                    ModelInfo("moonshot-v1-8k", "Moonshot v1 8K", "kimi", contextWindow = 8_000, maxOutputTokens = 4_096, supportsToolCalling = true),
                    ModelInfo("moonshot-v1-32k", "Moonshot v1 32K", "kimi", contextWindow = 32_000, maxOutputTokens = 4_096, supportsToolCalling = true),
                ),
            )
        )
        registry.registerProvider(
            OpenAICompatibleProvider(
                id = "xiaomi-mimo",
                name = "MiMo",
                type = ProviderType.XIAOMI_MIMO,
                baseUrl = url("xiaomi-mimo", ""),
                apiKey = key("xiaomi-mimo"),
                models = listOf(
                    ModelInfo(System.getenv("MIMO_MODEL") ?: "mimo", "MiMo", "xiaomi-mimo", supportsToolCalling = true),
                ),
            )
        )
        return registry
    }
}
