package com.mimicryos.agent.app

import com.mimicryos.agent.core.*
import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.events.EventBus
import com.mimicryos.agent.events.EventEnvelope
import com.mimicryos.agent.events.PlanStepInfo
import com.mimicryos.agent.permission.PermissionResult
import com.mimicryos.agent.provider.APIRequest
import com.mimicryos.agent.provider.APIResponse
import com.mimicryos.agent.provider.ModelProvider
import com.mimicryos.agent.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MimicryApplicationService(
    private val components: AppComponents,
    initialConfig: AppConfig,
    private val eventBus: EventBus = EventBus(),
    private val permissionBroker: PermissionBroker = PermissionBroker(
        eventBus,
        initialConfig.interactivePermissions,
        initialConfig.permissionTimeoutMs,
    ),
    private val requestExecutor: (suspend (APIRequest, ModelProvider) -> APIResponse)? = null,
) : ApplicationService {
    private val config = initialConfig.normalized()
    private val runtimeId = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tasks = ConcurrentHashMap<String, TaskInfo>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<TaskInfo>>()
    private val abortControllers = ConcurrentHashMap<String, AbortController>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    @Volatile private var currentProviderId = config.providerId
    @Volatile private var currentModel = config.model
    @Volatile private var activeSessionId: String? = null
    @Volatile private var closed = false

    override val events: SharedFlow<EventEnvelope> = eventBus.events

    init {
        eventBus.emit(AgentEvent.RuntimeStatus("ready", "Mimicry runtime started"))
    }

    override fun snapshot(): AppSnapshot {
        val running = tasks.values.firstOrNull {
            it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.WAITING_PERMISSION
        }
        val allTools = components.toolRegistry.getAll()
        return AppSnapshot(
            runtimeId = runtimeId,
            startedAt = startedAt,
            projectDir = config.projectDir,
            providerId = currentProviderId,
            model = currentModel,
            permissionMode = config.permissionMode,
            sessions = listSessions(),
            tasks = tasks.values.sortedByDescending { it.createdAt },
            agents = listOf(
                AgentInfo(
                    id = "main",
                    name = "Mimicry Agent",
                    status = if (running != null) "running" else "idle",
                    currentTaskId = running?.id,
                )
            ),
            plan = currentPlan(),
            providers = components.registry.getAllProviders()
                .map { provider ->
                    ProviderInfo(
                        id = provider.id,
                        name = provider.name,
                        configured = provider.validate().result,
                        defaultProtocol = provider.defaultProtocol.name,
                        supportedProtocols = provider.supportedProtocols.map { it.name },
                    )
                }
                .sortedBy { it.id },
            tools = allTools
                .map { tool ->
                    ToolInfo(
                        name = tool.name,
                        description = try {
                            tool.prompt(PromptOptions({ ToolPermissionContext() }, allTools, emptyList()))
                        } catch (e: Exception) {
                            ""
                        },
                        implemented = tool.isImplemented,
                    )
                }
                .sortedBy { it.name },
            pendingPermissions = permissionBroker.pendingRequests(),
            lastEventSeq = eventBus.lastSeq(),
            runningTaskId = running?.id,
        )
    }

    override fun eventHistory(since: Long): List<EventEnvelope> = eventBus.history(since)

    override fun task(taskId: String): TaskInfo? = tasks[taskId]

    override suspend fun submit(request: TaskRequest): TaskInfo {
        check(!closed) { "Runtime is shut down" }
        val prompt = request.prompt.trim()
        require(prompt.isNotEmpty()) { "Prompt must not be empty" }

        val providerId = request.providerId?.let { ProviderCatalog.normalize(it) } ?: currentProviderId
        val provider = components.registry.getProvider(providerId)
            ?: throw IllegalArgumentException("Unknown provider: $providerId")
        val validation = provider.validate()
        if (!validation.result) {
            throw IllegalStateException(validation.message ?: "Provider '$providerId' is not configured")
        }

        val model = request.model?.takeIf { it.isNotBlank() }
            ?: currentModel.ifBlank { ProviderCatalog.defaultModel(providerId) }
        if (model.isBlank()) {
            throw IllegalStateException("No model configured for provider '$providerId'")
        }

        request.sessionId?.let { id ->
            require(validSessionId(id)) { "Invalid session id" }
            require(components.sessionManager.getSessionInfo(id) != null) { "Session not found: $id" }
        }
        val hadSession = request.sessionId != null || activeSessionId != null
        val sessionId = request.sessionId
            ?: activeSessionId
            ?: components.sessionManager.createSession().also { activeSessionId = it }
        activeSessionId = sessionId

        val taskId = "task-${UUID.randomUUID().toString().take(12)}"
        val info = TaskInfo(
            id = taskId,
            sessionId = sessionId,
            prompt = prompt,
            status = TaskRunStatus.QUEUED,
            providerId = providerId,
            model = model,
            createdAt = System.currentTimeMillis(),
        )
        tasks[taskId] = info
        completions[taskId] = CompletableDeferred()
        eventBus.emit(AgentEvent.TaskCreated(taskId, sessionId, prompt))
        if (!hadSession) {
            eventBus.emit(AgentEvent.SessionCreated(sessionId, config.projectDir))
        }

        val job = scope.launch { executeTask(taskId) }
        jobs[taskId] = job
        return info
    }

    override suspend fun awaitTask(taskId: String, timeoutMs: Long): TaskInfo? {
        val deferred = completions[taskId] ?: return tasks[taskId]
        return if (timeoutMs > 0L) {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: tasks[taskId]
        } else {
            deferred.await()
        }
    }

    override fun interrupt(taskId: String?): Boolean {
        val id = taskId
            ?: tasks.values.firstOrNull {
                it.status == TaskRunStatus.RUNNING ||
                    it.status == TaskRunStatus.WAITING_PERMISSION ||
                    it.status == TaskRunStatus.QUEUED
            }?.id
            ?: return false
        val task = tasks[id] ?: return false
        if (task.status.isTerminal) return false
        permissionBroker.cancelTask(id)
        abortControllers[id]?.abort()
        jobs[id]?.cancel(CancellationException("Interrupted by user"))
        return true
    }

    override suspend fun respondToPermission(requestId: String, approved: Boolean, reason: String?): Boolean =
        permissionBroker.resolve(requestId, approved)

    override suspend fun createSession(): SessionSummary {
        check(!closed) { "Runtime is shut down" }
        val id = components.sessionManager.createSession()
        components.sessionManager.saveMessages(id, emptyList())
        activeSessionId = id
        eventBus.emit(AgentEvent.SessionCreated(id, config.projectDir))
        val info = requireNotNull(components.sessionManager.getSessionInfo(id))
        return SessionSummary(id, info.createdAt, info.lastActiveAt, 0, config.projectDir)
    }

    override fun sessionTranscript(sessionId: String): SessionTranscript? {
        if (!validSessionId(sessionId)) return null
        // Read the watermark before the transcript; replay after this point may
        // overlap persisted messages, which clients deduplicate by message id.
        val watermark = eventBus.lastSeq()
        components.sessionManager.getSessionInfo(sessionId) ?: return null
        val result = mutableListOf<TranscriptMessage>()
        components.sessionManager.loadMessages(sessionId).forEach { msg ->
            when (msg) {
                is Message.User -> when (val content = msg.message.content) {
                    is Content.Text -> result.add(TranscriptMessage(msg.uuid, "user", content.text, msg.timestamp))
                    is Content.ToolResult -> result.add(TranscriptMessage(
                        msg.uuid, "tool", content.content.filterIsInstance<ResultContent.Text>().joinToString("\n") { it.text },
                        msg.timestamp, content.toolUseId, isError = content.isError,
                    ))
                    else -> Unit
                }
                is Message.Assistant -> {
                    val text = msg.message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
                    if (text.isNotEmpty()) result.add(TranscriptMessage(msg.uuid, "assistant", text, msg.timestamp))
                    msg.message.content.filterIsInstance<ContentBlock.ToolUse>().forEach { tool ->
                        result.add(TranscriptMessage(tool.id, "tool_call", tool.input.toString(), msg.timestamp, tool.id, tool.name))
                    }
                }
                else -> Unit
            }
        }
        // Include in-flight events, so closing/reopening the GUI mid-task does
        // not lose already emitted output. Persisted entries win for final text.
        val sessionTasks = tasks.values.filter { it.sessionId == sessionId }.map { it.id }.toSet()
        eventBus.history().forEach { envelope ->
            when (val event = envelope.event) {
                is AgentEvent.MessageEvent -> if (event.taskId in sessionTasks && result.none { it.id == event.messageId })
                    result.add(TranscriptMessage(event.messageId, event.role, event.text, envelope.timestamp))
                is AgentEvent.ToolStarted -> if (event.taskId in sessionTasks && result.none { it.id == event.toolUseId })
                    result.add(TranscriptMessage(event.toolUseId, "tool_call", event.input.toString(), envelope.timestamp, event.toolUseId, event.toolName))
                is AgentEvent.ToolCompleted -> if (event.taskId in sessionTasks && result.none { it.toolUseId == event.toolUseId && it.role == "tool" })
                    result.add(TranscriptMessage("result-${event.toolUseId}", "tool", event.output, envelope.timestamp, event.toolUseId, event.toolName, event.isError))
                is AgentEvent.ToolFailed -> if (event.taskId in sessionTasks && result.none { it.toolUseId == event.toolUseId && it.role == "tool" })
                    result.add(TranscriptMessage("result-${event.toolUseId}", "tool", event.error, envelope.timestamp, event.toolUseId, event.toolName, true))
                else -> Unit
            }
        }
        return SessionTranscript(sessionId, result.sortedBy { it.timestamp }, watermark)
    }

    private fun validSessionId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,100}"))

    override suspend fun resumeSession(sessionId: String): SessionSummary? {
        if (!validSessionId(sessionId)) return null
        val info = components.sessionManager.getSessionInfo(sessionId) ?: return null
        val messages = components.sessionManager.loadMessages(sessionId)
        activeSessionId = sessionId
        eventBus.emit(AgentEvent.SessionResumed(sessionId, messages.size))
        return SessionSummary(info.id, info.createdAt, info.lastActiveAt, info.messageCount, config.projectDir)
    }

    override fun listSessions(): List<SessionSummary> = components.sessionManager.listSessions().map {
        SessionSummary(it.id, it.createdAt, it.lastActiveAt, it.messageCount, config.projectDir)
    }

    override fun setModel(model: String) {
        require(model.isNotBlank()) { "Model must not be blank" }
        currentModel = model
    }

    override fun setProvider(providerId: String): Boolean {
        val normalized = ProviderCatalog.normalize(providerId)
        val provider = components.registry.getProvider(normalized) ?: return false
        currentProviderId = normalized
        currentModel = ProviderCatalog.defaultModel(normalized).ifBlank { currentModel }
        eventBus.emit(AgentEvent.LogEvent("info", "Provider changed to ${provider.name}", null))
        return true
    }

    override fun runtimeConfig(): RuntimeConfig {
        val provider = components.registry.getProvider(currentProviderId)
        return RuntimeConfig(
            runtimeId = runtimeId,
            projectDir = config.projectDir,
            providerId = currentProviderId,
            model = currentModel,
            permissionMode = config.permissionMode,
            maxTurns = config.maxTurns,
            interactivePermissions = config.interactivePermissions,
            apiKeyConfigured = provider?.validate()?.result == true,
        )
    }

    override fun shutdown() {
        if (closed) return
        closed = true
        permissionBroker.shutdown()
        jobs.values.forEach { it.cancel(CancellationException("Runtime shutting down")) }
        scope.cancel()
        eventBus.emit(AgentEvent.RuntimeStatus("stopped", "Mimicry runtime stopped"))
    }

    private suspend fun executeTask(taskId: String) {
        val initial = tasks[taskId] ?: return
        val mutex = sessionMutexes.computeIfAbsent(initial.sessionId) { Mutex() }
        try {
            mutex.withLock {
                if (jobs[taskId]?.isCancelled == true) return
                val now = System.currentTimeMillis()
                updateTask(taskId) {
                    it.copy(status = TaskRunStatus.RUNNING, startedAt = now)
                }
                eventBus.emit(AgentEvent.TaskStarted(taskId, initial.sessionId))
                eventBus.emit(AgentEvent.AgentStateChanged(taskId, "starting"))

                val abort = AbortController()
                abortControllers[taskId] = abort
                val current = tasks[taskId] ?: initial
                val engine = buildEngine(current, abort)
                val userMessageId = UUID.randomUUID().toString()
                eventBus.emit(AgentEvent.MessageEvent(taskId, userMessageId, "user", current.prompt))
                val stream = engine.submitMessage(current.prompt, QueryEngine.SubmitOptions(uuid = userMessageId))
                val messages = withTimeout(config.taskTimeoutMs) { stream.run() }
                components.sessionManager.saveMessages(current.sessionId, messages)

                val text = extractFinalText(messages)
                val usage = stream.lastResult?.totalUsage
                val turns = stream.lastResult?.turnCount ?: 0
                updateTask(taskId) {
                    it.copy(
                        status = TaskRunStatus.COMPLETED,
                        completedAt = System.currentTimeMillis(),
                        result = text,
                        turns = turns,
                        inputTokens = usage?.inputTokens ?: 0L,
                        outputTokens = usage?.outputTokens ?: 0L,
                    )
                }
                eventBus.emit(
                    AgentEvent.TaskCompleted(
                        taskId,
                        current.sessionId,
                        text,
                        turns,
                        usage?.inputTokens ?: 0L,
                        usage?.outputTokens ?: 0L,
                    )
                )
                eventBus.emit(AgentEvent.AgentStateChanged(taskId, "idle"))
            }
        } catch (e: TimeoutCancellationException) {
            updateTask(taskId) {
                it.copy(
                    status = TaskRunStatus.FAILED,
                    completedAt = System.currentTimeMillis(),
                    error = "Task timed out after ${config.taskTimeoutMs} ms",
                )
            }
            eventBus.emit(AgentEvent.TaskFailed(taskId, initial.sessionId, "Task timed out"))
            eventBus.emit(AgentEvent.AgentStateChanged(taskId, "idle"))
        } catch (e: CancellationException) {
            updateTask(taskId) {
                it.copy(
                    status = TaskRunStatus.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    error = e.message ?: "cancelled",
                )
            }
            eventBus.emit(AgentEvent.TaskCancelled(taskId, initial.sessionId, e.message ?: "cancelled by user"))
            eventBus.emit(AgentEvent.AgentStateChanged(taskId, "idle"))
        } catch (e: Exception) {
            updateTask(taskId) {
                it.copy(
                    status = TaskRunStatus.FAILED,
                    completedAt = System.currentTimeMillis(),
                    error = e.message ?: e.toString(),
                )
            }
            eventBus.emit(AgentEvent.TaskFailed(taskId, initial.sessionId, e.message ?: e.toString()))
            eventBus.emit(AgentEvent.AgentStateChanged(taskId, "idle"))
        } finally {
            abortControllers.remove(taskId)
            jobs.remove(taskId)
            tasks[taskId]?.let { completions[taskId]?.complete(it) }
        }
    }

    private fun buildEngine(task: TaskInfo, abort: AbortController): QueryEngine {
        val tools = components.toolRegistry.getEnabled()
        val permissionContext = permissionContextFor(config.permissionMode)
        val appState = AppState(
            toolPermissionContext = permissionContext,
            settings = Settings(model = task.model, verbose = config.verbose),
        )
        val canUseTool = object : CanUseToolFn {
            override suspend fun invoke(
                tool: Tool<*, *>,
                input: Any,
                context: ToolUseContext,
                assistantMessage: Message.Assistant,
                toolUseID: String,
                forceDecision: String?,
            ): PermissionResult {
                val result = components.permissionSystem.canUseTool(
                    tool, input, context, assistantMessage, toolUseID, forceDecision
                )
                if (result !is PermissionResult.Ask) return result
                if (isReadOnly(tool, input)) return PermissionResult.allow(input)

                val target = describeTarget(tool, input)
                updateTask(task.id) { it.copy(status = TaskRunStatus.WAITING_PERMISSION) }
                val approved = try {
                    permissionBroker.request(
                        taskId = task.id,
                        toolName = tool.name,
                        target = target,
                        reason = result.message,
                        scope = "tool:${tool.name}",
                    )
                } finally {
                    updateTask(task.id) { if (it.status == TaskRunStatus.WAITING_PERMISSION) it.copy(status = TaskRunStatus.RUNNING) else it }
                }
                return if (approved) {
                    PermissionResult.allow(input)
                } else {
                    PermissionResult.deny("Permission denied: ${result.message}")
                }
            }
        }

        return QueryEngine(
            QueryEngine.QueryEngineConfig(
                sessionId = task.sessionId,
                cwd = config.projectDir,
                tools = tools,
                canUseTool = canUseTool,
                getAppState = { appState },
                setAppState = { },
                mainLoopModel = task.model,
                providerId = task.providerId,
                registry = components.registry,
                readFileCache = FileStateCache(),
                abortController = abort,
                userSpecifiedModel = task.model,
                maxTurns = config.maxTurns,
                maxBudgetUsd = config.maxBudgetUsd,
                initialMessages = components.sessionManager.loadMessages(task.sessionId),
                eventListener = { event -> eventBus.emit(event) },
                eventTaskId = task.id,
                eventSessionId = task.sessionId,
                requestExecutor = requestExecutor,
            )
        )
    }

    private fun isReadOnly(tool: Tool<*, *>, input: Any): Boolean = try {
        @Suppress("UNCHECKED_CAST")
        val typed = tool as Tool<Any, Any>
        typed.isReadOnly(input) && !typed.isDestructive(input)
    } catch (e: Exception) {
        false
    }

    private fun describeTarget(tool: Tool<*, *>, input: Any): String = try {
        @Suppress("UNCHECKED_CAST")
        val typed = tool as Tool<Any, Any>
        typed.getPath(input) ?: typed.getToolUseSummary(input) ?: input.toString().take(200)
    } catch (e: Exception) {
        input.toString().take(200)
    }

    private fun extractFinalText(messages: List<Message>): String {
        val assistant = messages.asReversed().firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return ""
        return assistant.message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    }

    private fun currentPlan(): PlanSnapshot? {
        val plan = components.planModeManager.getActivePlan() ?: return null
        return PlanSnapshot(
            planId = plan.id,
            prompt = plan.prompt,
            status = plan.status.name,
            steps = plan.steps.map { PlanStepInfo(it.description, it.status.name) },
        )
    }

    private fun updateTask(taskId: String, transform: (TaskInfo) -> TaskInfo) {
        tasks.computeIfPresent(taskId) { _, old -> transform(old) }
    }

    private fun permissionContextFor(mode: String): ToolPermissionContext = when (mode) {
        "bypass" -> ToolPermissionContext(mode = "bypass", isBypassPermissionsModeAvailable = true)
        "accept-edits" -> ToolPermissionContext(
            mode = "accept_edits",
            alwaysAllowRules = ToolPermissionRules(edit = listOf("*"), write = listOf("*")),
        )
        "auto" -> ToolPermissionContext(mode = "auto", isAutoModeAvailable = true)
        "plan" -> ToolPermissionContext(mode = "plan")
        else -> ToolPermissionContext()
    }
}
