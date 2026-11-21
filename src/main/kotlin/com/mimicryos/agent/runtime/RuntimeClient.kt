package com.mimicryos.agent.runtime

import com.mimicryos.agent.app.*
import com.mimicryos.agent.events.EventEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the local Runtime HTTP/SSE API. Used by the CLI and TUI when
 * they attach to a running runtime, and by the Avalonia GUI.
 */
class RuntimeClient(val info: RuntimeInfo) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        prettyPrint = false
    }
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    suspend fun health(): Boolean = try {
        send("GET", "/health").statusCode() == 200
    } catch (e: Exception) {
        false
    }

    suspend fun snapshot(): AppSnapshot = decode(send("GET", "/v1/snapshot"))

    suspend fun eventHistory(since: Long = 0L): List<EventEnvelope> =
        decode(send("GET", "/v1/events/history?since=$since"))

    suspend fun submit(request: TaskRequest): TaskInfo =
        decode(send("POST", "/v1/tasks", json.encodeToString(TaskRequest.serializer(), request)))

    suspend fun task(taskId: String): TaskInfo? {
        val response = send("GET", "/v1/tasks/$taskId")
        if (response.statusCode() == 404) return null
        ensureOk(response)
        return json.decodeFromString(TaskInfo.serializer(), response.body())
    }

    suspend fun awaitTask(taskId: String, timeoutMs: Long = 0L): TaskInfo? {
        val deadline = if (timeoutMs > 0L) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
        while (true) {
            val current = task(taskId) ?: return null
            if (current.status.isTerminal) return current
            if (System.currentTimeMillis() >= deadline) return current
            delay(150)
        }
    }

    suspend fun interrupt(taskId: String? = null): Boolean {
        val id = taskId ?: snapshot().runningTaskId ?: return false
        val response = send("POST", "/v1/tasks/$id/interrupt")
        ensureOk(response)
        return json.decodeFromString(InterruptResponse.serializer(), response.body()).interrupted
    }

    suspend fun respondPermission(requestId: String, approved: Boolean, reason: String? = null): Boolean {
        val body = json.encodeToString(PermissionResponse.serializer(), PermissionResponse(approved, reason))
        val response = send("POST", "/v1/permissions/$requestId", body)
        ensureOk(response)
        return json.decodeFromString(PermissionResolvedResponse.serializer(), response.body()).resolved
    }

    suspend fun listSessions(): List<SessionSummary> = decode(send("GET", "/v1/sessions"))

    suspend fun createSession(): SessionSummary = decode(send("POST", "/v1/sessions"))

    suspend fun sessionTranscript(sessionId: String): SessionTranscript? {
        val response = send("GET", "/v1/sessions/$sessionId/messages")
        if (response.statusCode() == 404) return null
        return decode(response)
    }

    suspend fun resumeSession(sessionId: String): SessionSummary? {
        val response = send("POST", "/v1/sessions/$sessionId/resume")
        if (response.statusCode() == 404) return null
        ensureOk(response)
        return json.decodeFromString(SessionSummary.serializer(), response.body())
    }

    suspend fun setModel(model: String) {
        val body = json.encodeToString(ModelRequest.serializer(), ModelRequest(model))
        ensureOk(send("POST", "/v1/model", body))
    }

    suspend fun setProvider(providerId: String): Boolean {
        val body = json.encodeToString(ProviderRequest.serializer(), ProviderRequest(providerId))
        val response = send("POST", "/v1/provider", body)
        if (response.statusCode() == 400) return false
        ensureOk(response)
        return json.decodeFromString(OkResponse.serializer(), response.body()).ok
    }

    suspend fun runtimeConfig(): RuntimeConfig = decode(send("GET", "/v1/config"))

    suspend fun shutdown(): Boolean {
        val response = send("POST", "/v1/shutdown")
        return response.statusCode() == 200
    }

    /**
     * Server-sent event stream. The flow completes when the server closes
     * the connection or the caller cancels collection.
     */
    fun events(since: Long = 0L): Flow<EventEnvelope> = flow {
        val request = baseRequest("/v1/events?since=$since").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Runtime event stream failed: HTTP ${response.statusCode()}")
        }
        response.body().bufferedReader().use { reader ->
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        emit(json.decodeFromString(EventEnvelope.serializer(), data.toString()))
                        data.setLength(0)
                    }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun send(method: String, path: String, body: String? = null): HttpResponse<String> =
        withContext(Dispatchers.IO) {
            val builder = baseRequest(path)
                .timeout(Duration.ofSeconds(30))
            when (method.uppercase()) {
                "GET" -> builder.GET()
                "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
                else -> builder.method(method.uppercase(), HttpRequest.BodyPublishers.ofString(body ?: ""))
            }
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

    private fun baseRequest(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create(info.baseUrl + path))
            .header("Authorization", "Bearer ${info.token}")
            .header("Accept", "application/json")

    private fun ensureOk(response: HttpResponse<String>) {
        if (response.statusCode() !in 200..299) {
            val message = try {
                json.decodeFromString(ErrorResponse.serializer(), response.body()).error
            } catch (e: Exception) {
                response.body().take(500)
            }
            throw IllegalStateException("Runtime request failed (${response.statusCode()}): $message")
        }
    }

    private inline fun <reified T> decode(response: HttpResponse<String>): T {
        ensureOk(response)
        return json.decodeFromString(response.body())
    }
}
