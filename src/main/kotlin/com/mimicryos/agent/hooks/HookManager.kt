package com.mimicryos.agent.hooks

import com.mimicryos.agent.core.*
import java.util.concurrent.ConcurrentHashMap

class HookManager {
    private val hooks = ConcurrentHashMap<String, MutableList<Hook>>()
    fun register(event: HookEvent, hook: Hook) { hooks.getOrPut(event.name) { mutableListOf() }.add(hook) }
    suspend fun executeHooks(event: HookEvent, context: HookContext): HookResult {
        val eventHooks = hooks[event.name] ?: return HookResult.continueChain()
        var currentContext = context
        for (hook in eventHooks) { when (val result = hook.execute(currentContext)) { is HookResult.Continue -> currentContext = result.context; is HookResultBlock -> { if (result.block) return result }; is HookResultReject -> return result } }
        return HookResult.continueChain(currentContext)
    }
    fun unregister(event: HookEvent, hook: Hook) { hooks[event.name]?.remove(hook) }
    fun clearHooks(event: HookEvent) { hooks.remove(event.name) }
    companion object { val PRE_TOOL_USE = HookEvent("pre_tool_use"); val POST_TOOL_USE = HookEvent("post_tool_use"); val SESSION_START = HookEvent("session_start"); val SESSION_END = HookEvent("session_end"); val PRE_COMPACT = HookEvent("pre_compact"); val POST_COMPACT = HookEvent("post_compact"); val PERMISSION_REQUEST = HookEvent("permission_request"); val MESSAGE_PROCESS = HookEvent("message_process") }
}

enum class HookEvent(val name: String)
interface Hook { val name: String; val priority: Int get() = 0; suspend fun execute(context: HookContext): HookResult }
sealed class HookResult { data class Continue(val context: HookContext) : HookResult(); data class Block(val reason: String, val context: HookContext) : HookResult(); data class Reject(val reason: String, val context: HookContext) : HookResult(); companion fun continueChain(context: HookContext = HookContext()) = Continue(context) }
data class HookContext(val toolName: String? = null, val toolInput: Map<String, Any>? = null, val toolResult: Any? = null, val message: Message? = null, val sessionId: String? = null, val metadata: Map<String, Any> = emptyMap())
