package com.mimicryos.agent.app

import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.events.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges tool permission requests to whichever frontend is attached.
 * Requests are real: a task suspends until the user approves, denies,
 * the request times out, or the session is non-interactive.
 */
class PermissionBroker(
    private val eventBus: EventBus,
    private val interactive: Boolean,
    private val timeoutMs: Long,
) {
    private data class Pending(val info: PermissionRequestInfo, val deferred: CompletableDeferred<Boolean>)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val counter = AtomicLong(0)

    suspend fun request(
        taskId: String,
        toolName: String,
        target: String,
        reason: String,
        scope: String = "tool",
    ): Boolean {
        val requestId = "perm-${counter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
        val info = PermissionRequestInfo(
            id = requestId,
            taskId = taskId,
            toolName = toolName,
            target = target,
            reason = reason,
            scope = scope,
        )
        eventBus.emit(AgentEvent.PermissionRequested(requestId, taskId, toolName, target, reason, scope))

        if (!interactive) {
            eventBus.emit(AgentEvent.PermissionResolved(requestId, taskId, false, "non-interactive session; use --permissions bypass or accept-edits"))
            return false
        }

        val deferred = CompletableDeferred<Boolean>()
        pending[requestId] = Pending(info, deferred)
        val approved = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        pending.remove(requestId)
        eventBus.emit(
            AgentEvent.PermissionResolved(
                requestId,
                taskId,
                approved,
                if (approved) "approved by user" else "denied or timed out",
            )
        )
        return approved
    }

    fun resolve(requestId: String, approved: Boolean): Boolean {
        val entry = pending[requestId] ?: return false
        return entry.deferred.complete(approved)
    }

    fun pendingRequests(): List<PermissionRequestInfo> =
        pending.values.map { it.info }.sortedBy { it.requestedAt }

    fun cancelTask(taskId: String) {
        pending.values.filter { it.info.taskId == taskId }.forEach { it.deferred.complete(false) }
    }

    fun shutdown() {
        pending.values.forEach { it.deferred.complete(false) }
        pending.clear()
    }
}
