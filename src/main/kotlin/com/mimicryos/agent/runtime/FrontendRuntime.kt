package com.mimicryos.agent.runtime

import com.mimicryos.agent.app.*
import com.mimicryos.agent.events.EventEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.runBlocking

/**
 * The small surface a frontend needs. Both the embedded runtime and a
 * remote runtime expose the same operations, so the TUI and CLI contain no
 * agent-loop code of their own.
 */
interface FrontendRuntime : AutoCloseable {
    fun isRemote(): Boolean
    fun snapshot(): AppSnapshot
    fun eventHistory(since: Long = 0L): List<EventEnvelope>
    fun events(since: Long = 0L): Flow<EventEnvelope>

    suspend fun submit(prompt: String, sessionId: String? = null): TaskInfo
    suspend fun awaitTask(taskId: String, timeoutMs: Long = 0L): TaskInfo?
    suspend fun interrupt(taskId: String? = null): Boolean
    suspend fun respondPermission(requestId: String, approved: Boolean, reason: String? = null): Boolean

    fun listSessions(): List<SessionSummary>
    suspend fun resumeSession(sessionId: String): SessionSummary?

    fun setModel(model: String)
    fun setProvider(providerId: String): Boolean
    fun runtimeConfig(): RuntimeConfig

    override fun close()
}

class LocalFrontendRuntime(private val service: MimicryApplicationService) : FrontendRuntime {
    override fun isRemote(): Boolean = false
    override fun snapshot(): AppSnapshot = service.snapshot()
    override fun eventHistory(since: Long): List<EventEnvelope> = service.eventHistory(since)
    override fun events(since: Long): Flow<EventEnvelope> = service.events.dropWhile { it.seq <= since }

    override suspend fun submit(prompt: String, sessionId: String?): TaskInfo =
        service.submit(TaskRequest(prompt = prompt, sessionId = sessionId))

    override suspend fun awaitTask(taskId: String, timeoutMs: Long): TaskInfo? =
        service.awaitTask(taskId, timeoutMs)

    override suspend fun interrupt(taskId: String?): Boolean = service.interrupt(taskId)
    override suspend fun respondPermission(requestId: String, approved: Boolean, reason: String?): Boolean =
        service.respondToPermission(requestId, approved, reason)

    override fun listSessions(): List<SessionSummary> = service.listSessions()
    override suspend fun resumeSession(sessionId: String): SessionSummary? = service.resumeSession(sessionId)
    override fun setModel(model: String) = service.setModel(model)
    override fun setProvider(providerId: String): Boolean = service.setProvider(providerId)
    override fun runtimeConfig(): RuntimeConfig = service.runtimeConfig()

    override fun close() {
        service.shutdown()
    }
}

class RemoteFrontendRuntime(private val client: RuntimeClient) : FrontendRuntime {
    override fun isRemote(): Boolean = true
    override fun snapshot(): AppSnapshot = runBlocking { client.snapshot() }
    override fun eventHistory(since: Long): List<EventEnvelope> = runBlocking { client.eventHistory(since) }
    override fun events(since: Long): Flow<EventEnvelope> = client.events(since)

    override suspend fun submit(prompt: String, sessionId: String?): TaskInfo =
        client.submit(TaskRequest(prompt = prompt, sessionId = sessionId))

    override suspend fun awaitTask(taskId: String, timeoutMs: Long): TaskInfo? =
        client.awaitTask(taskId, timeoutMs)

    override suspend fun interrupt(taskId: String?): Boolean = client.interrupt(taskId)
    override suspend fun respondPermission(requestId: String, approved: Boolean, reason: String?): Boolean =
        client.respondPermission(requestId, approved, reason)

    override fun listSessions(): List<SessionSummary> = runBlocking { client.listSessions() }
    override suspend fun resumeSession(sessionId: String): SessionSummary? = client.resumeSession(sessionId)
    override fun setModel(model: String) = runBlocking { client.setModel(model) }
    override fun setProvider(providerId: String): Boolean = runBlocking { client.setProvider(providerId) }
    override fun runtimeConfig(): RuntimeConfig = runBlocking { client.runtimeConfig() }

    override fun close() {
        // Detaching from a runtime must not stop the runtime or the agent.
    }
}

object RuntimeConnections {
    fun local(config: AppConfig): FrontendRuntime {
        val normalized = config.normalized()
        val components = AppFactory.create(normalized)
        return LocalFrontendRuntime(MimicryApplicationService(components, normalized))
    }

    suspend fun attach(): FrontendRuntime? {
        val info = RuntimeDiscovery.find() ?: return null
        return RemoteFrontendRuntime(RuntimeClient(info))
    }

    suspend fun attachOrLocal(config: AppConfig, preferAttach: Boolean): Pair<FrontendRuntime, Boolean> {
        if (preferAttach) {
            attach()?.let { return it to true }
        }
        return local(config) to false
    }
}
