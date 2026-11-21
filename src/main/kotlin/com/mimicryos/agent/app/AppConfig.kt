package com.mimicryos.agent.app

import com.mimicryos.agent.provider.ProviderCatalog
import java.io.File

data class AppConfig(
    val projectDir: String,
    val providerId: String = "anthropic",
    val model: String = "",
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val permissionMode: String = "default",
    val maxTurns: Int = 50,
    val maxBudgetUsd: Double = 100.0,
    val taskTimeoutMs: Long = 30 * 60 * 1000L,
    val permissionTimeoutMs: Long = 5 * 60 * 1000L,
    val skillsDir: String? = null,
    val verbose: Boolean = false,
    val interactivePermissions: Boolean = false,
) {
    fun normalized(): AppConfig {
        val provider = ProviderCatalog.normalize(providerId)
        val resolvedModel = model.ifBlank { ProviderCatalog.defaultModel(provider) }
        val dir = File(projectDir).absoluteFile.normalize().path
        return copy(projectDir = dir, providerId = provider, model = resolvedModel)
    }

    fun warnings(): List<String> {
        val result = mutableListOf<String>()
        if (model.isBlank()) result += "No model configured for provider '$providerId'. Use --model or MIMICRY_MODEL."
        if (apiKey == null && ProviderCatalog.defaultApiKey(providerId).isNullOrBlank()) {
            result += "No API key configured for provider '$providerId'. Set the provider's API key environment variable or use --api-key."
        }
        return result
    }

    companion object {
        fun fromEnv(
            projectDir: String? = null,
            providerId: String? = null,
            model: String? = null,
            apiKey: String? = null,
            baseUrl: String? = null,
            permissionMode: String? = null,
            maxTurns: Int? = null,
            maxBudgetUsd: Double? = null,
            taskTimeoutMs: Long? = null,
            permissionTimeoutMs: Long? = null,
            verbose: Boolean = false,
            interactivePermissions: Boolean = false,
        ): AppConfig {
            val provider = ProviderCatalog.normalize(
                providerId ?: System.getenv("MIMICRY_PROVIDER") ?: "anthropic"
            )
            val resolvedModel = model
                ?: System.getenv("MIMICRY_MODEL")?.takeIf { it.isNotBlank() }
                ?: ProviderCatalog.defaultModel(provider)
            return AppConfig(
                projectDir = projectDir ?: System.getenv("MIMICRY_CWD") ?: ".",
                providerId = provider,
                model = resolvedModel,
                apiKey = apiKey,
                baseUrl = baseUrl,
                permissionMode = permissionMode ?: System.getenv("MIMICRY_PERMISSIONS") ?: "default",
                maxTurns = maxTurns ?: System.getenv("MIMICRY_MAX_TURNS")?.toIntOrNull() ?: 50,
                maxBudgetUsd = maxBudgetUsd ?: System.getenv("MIMICRY_MAX_BUDGET_USD")?.toDoubleOrNull() ?: 100.0,
                taskTimeoutMs = taskTimeoutMs ?: System.getenv("MIMICRY_TASK_TIMEOUT_MS")?.toLongOrNull() ?: 30 * 60 * 1000L,
                permissionTimeoutMs = permissionTimeoutMs ?: System.getenv("MIMICRY_PERMISSION_TIMEOUT_MS")?.toLongOrNull() ?: 5 * 60 * 1000L,
                skillsDir = System.getenv("MIMICRY_SKILLS_DIR"),
                verbose = verbose,
                interactivePermissions = interactivePermissions,
            ).normalized()
        }
    }
}
