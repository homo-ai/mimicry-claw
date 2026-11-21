package com.mimicryos.agent.runtime

import com.mimicryos.agent.app.*
import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.events.EventEnvelope
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class HealthResponse(val ok: Boolean, val runtimeId: String, val version: String = "1.0.0")

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class ModelRequest(val model: String)

@Serializable
data class ProviderRequest(val providerId: String)

@Serializable
data class OkResponse(val ok: Boolean, val error: String? = null)

@Serializable
data class InterruptResponse(val interrupted: Boolean)

@Serializable
data class PermissionResolvedResponse(val resolved: Boolean)

/**
 * Local HTTP + SSE runtime.
 *
 * The runtime is the single owner of the ApplicationService. GUI, TUI and
 * CLI attach to it instead of creating their own agent loop. The server
 * binds to loopback only and requires a per-process bearer token.
 */
class RuntimeServer(
    private val application: ApplicationService,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val token: String = RuntimeInfo.generateToken(),
    private val shutdownApplication: Boolean = true,
    private val publishRuntimeInfo: Boolean = true,
    private val checkExisting: Boolean = true,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        prettyPrint = false
    }
    private val closed = AtomicBoolean(false)
    private val stopped = CountDownLatch(1)
    private var server: HttpServer? = null
    private var info: RuntimeInfo? = null

    fun start(): RuntimeInfo {
        check(server == null) { "Runtime server already started" }
        if (checkExisting) {
            val existing = runBlocking { RuntimeDiscovery.find() }
            if (existing != null && existing.pid != ProcessHandle.current().pid()) {
                error("A Mimicry runtime is already running at ${existing.baseUrl} (pid ${existing.pid})")
            }
        }

        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext("/") { exchange -> handle(exchange) }
        http.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mimicry-runtime-http").apply { isDaemon = true }
        }
        http.start()
        server = http

        val config = application.runtimeConfig()
        val runtimeInfo = RuntimeInfo(
            host = host,
            port = http.address.port,
            token = token,
            pid = ProcessHandle.current().pid(),
            startedAt = System.currentTimeMillis(),
            projectDir = config.projectDir,
            runtimeId = config.runtimeId,
        )
        info = runtimeInfo
        if (publishRuntimeInfo) RuntimeInfo.write(runtimeInfo)
        return runtimeInfo
    }

    fun await() = stopped.await()

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        server?.stop(0)
        server = null
        if (publishRuntimeInfo) {
            val current = RuntimeInfo.read()
            if (current != null && current.runtimeId == info?.runtimeId) RuntimeInfo.delete()
        }
        if (shutdownApplication) application.shutdown()
        stopped.countDown()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "authorization,content-type")
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, ErrorResponse("Missing or invalid bearer token"))
                return
            }

            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path
            val query = parseQuery(exchange.requestURI.rawQuery)

            when {
                path == "/health" && method == "GET" -> {
                    val config = application.runtimeConfig()
                    respond(exchange, 200, HealthResponse(true, config.runtimeId))
                }

                path == "/v1/snapshot" && method == "GET" -> respond(exchange, 200, application.snapshot())

                path == "/v1/events/history" && method == "GET" -> {
                    val since = query["since"]?.toLongOrNull() ?: 0L
                    respond(exchange, 200, application.eventHistory(since))
                }

                path == "/v1/events" && method == "GET" -> streamEvents(exchange, query["since"]?.toLongOrNull() ?: 0L)

                path == "/v1/tasks" && method == "POST" -> {
                    val request = json.decodeFromString<TaskRequest>(readBody(exchange))
                    val task = runBlocking { application.submit(request) }
                    val wait = query["wait"].let { it == "1" || it.equals("true", ignoreCase = true) }
                    val timeout = query["timeoutMs"]?.toLongOrNull() ?: 0L
                    val result = if (wait) runBlocking { application.awaitTask(task.id, timeout) } ?: task else task
                    respond(exchange, if (wait) 200 else 202, result)
                }

                path.startsWith("/v1/tasks/") && method == "POST" && path.endsWith("/interrupt") -> {
                    val taskId = path.removePrefix("/v1/tasks/").removeSuffix("/interrupt")
                    respond(exchange, 200, InterruptResponse(application.interrupt(taskId)))
                }

                path.startsWith("/v1/tasks/") && method == "GET" -> {
                    val taskId = path.removePrefix("/v1/tasks/")
                    val task = application.task(taskId)
                    if (task == null) respond(exchange, 404, ErrorResponse("Task not found: $taskId"))
                    else respond(exchange, 200, task)
                }

                path.startsWith("/v1/permissions/") && method == "POST" -> {
                    val requestId = path.removePrefix("/v1/permissions/")
                    val response = json.decodeFromString<PermissionResponse>(readBody(exchange))
                    val resolved = runBlocking {
                        application.respondToPermission(requestId, response.approved, response.reason)
                    }
                    respond(exchange, 200, PermissionResolvedResponse(resolved))
                }

                path == "/v1/sessions" && method == "GET" -> respond(exchange, 200, application.listSessions())

                path == "/v1/sessions" && method == "POST" ->
                    respond(exchange, 201, runBlocking { application.createSession() })

                path.startsWith("/v1/sessions/") && path.endsWith("/messages") && method == "GET" -> {
                    val sessionId = path.removePrefix("/v1/sessions/").removeSuffix("/messages")
                    val transcript = application.sessionTranscript(sessionId)
                    if (transcript == null) respond(exchange, 404, ErrorResponse("Session not found"))
                    else respond(exchange, 200, transcript)
                }

                path.startsWith("/v1/sessions/") && path.endsWith("/resume") && method == "POST" -> {
                    val sessionId = path.removePrefix("/v1/sessions/").removeSuffix("/resume")
                    val session = runBlocking { application.resumeSession(sessionId) }
                    if (session == null) respond(exchange, 404, ErrorResponse("Session not found: $sessionId"))
                    else respond(exchange, 200, session)
                }

                path == "/v1/model" && method == "POST" -> {
                    val request = json.decodeFromString<ModelRequest>(readBody(exchange))
                    application.setModel(request.model)
                    respond(exchange, 200, application.runtimeConfig())
                }

                path == "/v1/provider" && method == "POST" -> {
                    val request = json.decodeFromString<ProviderRequest>(readBody(exchange))
                    val ok = application.setProvider(request.providerId)
                    respond(exchange, if (ok) 200 else 400, OkResponse(ok, if (ok) null else "Unknown provider"))
                }

                path == "/v1/config" && method == "GET" -> respond(exchange, 200, application.runtimeConfig())

                path == "/v1/shutdown" && method == "POST" -> {
                    respond(exchange, 200, OkResponse(true))
                    Thread {
                        Thread.sleep(150)
                        stop()
                    }.apply { isDaemon = true }.start()
                }

                else -> respond(exchange, 404, ErrorResponse("Unknown endpoint: $method $path"))
            }
        } catch (e: Exception) {
            try {
                respond(exchange, 500, ErrorResponse(e.message ?: e.toString()))
            } catch (ignored: Exception) {
                // Client may already be gone.
            }
        }
    }

    private fun streamEvents(exchange: HttpExchange, since: Long) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, 0)

        var lastSeq = since
        var lastHeartbeat = System.currentTimeMillis()
        try {
            exchange.responseBody.use { output ->
                output.write("retry: 1000\n\n".toByteArray(Charsets.UTF_8))
                output.flush()
                while (!closed.get()) {
                    val events = application.eventHistory(lastSeq)
                    for (envelope in events) {
                        val name = envelope.event::class.simpleName ?: "event"
                        val payload = "id: ${envelope.seq}\nevent: $name\ndata: ${json.encodeToString(envelope)}\n\n"
                        output.write(payload.toByteArray(Charsets.UTF_8))
                        lastSeq = envelope.seq
                    }
                    if (events.isNotEmpty()) {
                        output.flush()
                        lastHeartbeat = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastHeartbeat >= 15_000L) {
                        output.write(": keepalive\n\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                        lastHeartbeat = System.currentTimeMillis()
                    }
                    Thread.sleep(150)
                }
            }
        } catch (ignored: Exception) {
            // Client disconnected.
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return header == "Bearer $token"
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun respond(exchange: HttpExchange, status: Int, value: Any) {
        val body = when (value) {
            is String -> value
            is HealthResponse -> json.encodeToString(HealthResponse.serializer(), value)
            is ErrorResponse -> json.encodeToString(ErrorResponse.serializer(), value)
            is OkResponse -> json.encodeToString(OkResponse.serializer(), value)
            is InterruptResponse -> json.encodeToString(InterruptResponse.serializer(), value)
            is PermissionResolvedResponse -> json.encodeToString(PermissionResolvedResponse.serializer(), value)
            is AppSnapshot -> json.encodeToString(AppSnapshot.serializer(), value)
            is RuntimeConfig -> json.encodeToString(RuntimeConfig.serializer(), value)
            is TaskInfo -> json.encodeToString(TaskInfo.serializer(), value)
            is SessionSummary -> json.encodeToString(SessionSummary.serializer(), value)
            is SessionTranscript -> json.encodeToString(SessionTranscript.serializer(), value)
            is EventEnvelope -> json.encodeToString(EventEnvelope.serializer(), value)
            is List<*> -> {
                if (value.isEmpty()) {
                    "[]"
                } else {
                    val first = value.firstOrNull()
                    when (first) {
                        is EventEnvelope -> json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(EventEnvelope.serializer()),
                            value.filterIsInstance<EventEnvelope>(),
                        )
                        is SessionSummary -> json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(SessionSummary.serializer()),
                            value.filterIsInstance<SessionSummary>(),
                        )
                        is TaskInfo -> json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(TaskInfo.serializer()),
                            value.filterIsInstance<TaskInfo>(),
                        )
                        else -> error("Unsupported list payload")
                    }
                }
            }
            else -> error("Unsupported payload type: ${value::class.qualifiedName}")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null
            else URLDecoder.decode(part.substring(0, index), "UTF-8") to
                URLDecoder.decode(part.substring(index + 1), "UTF-8")
        }.toMap()
    }
}
