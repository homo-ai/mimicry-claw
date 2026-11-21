package com.mimicryos.agent.tui

import com.mimicryos.agent.app.AppSnapshot
import com.mimicryos.agent.app.TaskRunStatus
import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.events.EventEnvelope
import com.mimicryos.agent.runtime.FrontendRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Two-mode TUI.
 *
 * Normal Mode is line based and deliberately avoids alternate-screen,
 * mouse and heavy ANSI requirements, so it remains usable over SSH, in
 * small terminals and with no mouse support.
 *
 * Full Screen Mode uses the alternate screen and a self-drawn layout. If
 * the terminal cannot support it, the TUI falls back to Normal Mode and
 * says so instead of pretending the mode worked.
 */
class TuiApp(
    private val runtime: FrontendRuntime,
    private val fullScreen: Boolean = false,
    private val forceNormal: Boolean = false,
    private val initialSessionId: String? = null,
    private val attached: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var activeSessionId: String? = initialSessionId
    @Volatile private var activeTaskId: String? = null
    @Volatile private var cachedSnapshot: AppSnapshot? = null
    @Volatile private var usageInput = 0L
    @Volatile private var usageOutput = 0L
    @Volatile private var dirty = true
    @Volatile private var running = true

    private val transcript = mutableListOf<Entry>()
    private val pendingPermissions = linkedMapOf<String, AgentEvent.PermissionRequested>()
    private val seenMessages = mutableSetOf<String>()
    private var taskHadText = false
    private var scrollOffset = 0
    private var lastMaxScroll = 0
    private var input = ""

    private data class Entry(val role: String, var text: String, var streaming: Boolean = false)

    fun run(): Int {
        val sessionToResume = initialSessionId
        if (sessionToResume != null) {
            runCatching {
                kotlinx.coroutines.runBlocking { runtime.resumeSession(sessionToResume) }
            }.onSuccess { session ->
                if (session != null) {
                    activeSessionId = session.id
                    synchronized(lock) {
                        add("system", "Resumed session ${session.id} (${session.messageCount} messages)")
                    }
                }
            }
        }

        val terminal = try {
            TerminalBuilder.builder().system(true).build()
        } catch (e: Exception) {
            null
        }
        if (terminal == null) {
            return runPlain()
        }
        return try {
            if (fullScreen && !forceNormal && canUseFullScreen(terminal)) {
                runFullScreen(terminal)
            } else {
                if (fullScreen && !forceNormal) {
                    terminal.writer().println("This terminal cannot run Full Screen Mode; using Normal Mode.")
                    terminal.writer().flush()
                }
                runNormal(terminal)
            }
        } finally {
            try {
                terminal.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun canUseFullScreen(terminal: Terminal): Boolean {
        if (System.getenv("TERM")?.equals("dumb", ignoreCase = true) == true) return false
        if (terminal.type.equals("dumb", ignoreCase = true)) return false
        return terminal.width >= 60 && terminal.height >= 14
    }

    // ------------------------------------------------------------------
    // Normal Mode
    // ------------------------------------------------------------------

    private fun runNormal(terminal: Terminal): Int {
        val reader = try {
            LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(DefaultParser())
                .build()
        } catch (e: Exception) {
            return runPlain()
        }
        val writer = terminal.writer()
        val readLine: (String) -> String? = { prompt ->
            try {
                reader.readLine(prompt)
            } catch (e: UserInterruptException) {
                null
            } catch (e: EndOfFileException) {
                null
            }
        }
        return normalLoop(readLine, writer, ansi = !terminal.type.equals("dumb", ignoreCase = true))
    }

    private fun runPlain(): Int {
        val writer = PrintWriter(System.out, true)
        val input = BufferedReader(InputStreamReader(System.`in`))
        val readLine: (String) -> String? = { prompt ->
            if (prompt.isNotEmpty()) {
                System.out.print(prompt)
                System.out.flush()
            }
            input.readLine()
        }
        return normalLoop(readLine, writer, ansi = false)
    }

    private fun normalLoop(readLine: (String) -> String?, writer: PrintWriter, ansi: Boolean): Int {
        writer.println("Mimicry Claw - Normal Mode (${if (attached) "attached runtime" else "embedded runtime"})")
        writer.println("Type a prompt, /help for commands, exit to quit.")
        writer.flush()
        while (true) {
            val line = readLine("mimicry> ") ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.equals("exit", true) || trimmed.equals("quit", true)) break
            if (trimmed.startsWith("/")) {
                if (handleNormalSlash(trimmed, writer, ansi, readLine)) break
                continue
            }
            executeTaskNormal(trimmed, writer, readLine)
        }
        return 0
    }

    private fun executeTaskNormal(
        prompt: String,
        writer: PrintWriter,
        readLine: (String) -> String?,
    ) {
        val startSeq = try {
            runtime.snapshot().lastEventSeq
        } catch (e: Exception) {
            0L
        }
        val task = try {
            kotlinx.coroutines.runBlocking { runtime.submit(prompt, activeSessionId) }
        } catch (e: Exception) {
            writer.println("Error: ${e.message ?: e.toString()}")
            writer.flush()
            return
        }
        activeSessionId = task.sessionId
        activeTaskId = task.id
        var lastSeq = startSeq
        var textActive = false
        while (true) {
            val events = runCatching { runtime.eventHistory(lastSeq) }.getOrDefault(emptyList())
            for (envelope in events) {
                lastSeq = envelope.seq
                textActive = printNormalEvent(envelope.event, writer, textActive, readLine)
            }
            val current = runCatching { kotlinx.coroutines.runBlocking { runtime.awaitTask(task.id, 200) } }
                .getOrNull() ?: task
            if (current.status.isTerminal) {
                for (envelope in runCatching { runtime.eventHistory(lastSeq) }.getOrDefault(emptyList())) {
                    lastSeq = envelope.seq
                    textActive = printNormalEvent(envelope.event, writer, textActive, readLine)
                }
                if (textActive) writer.println()
                when (current.status) {
                    TaskRunStatus.COMPLETED -> {
                        val result = current.result.orEmpty()
                        if (!textActive && result.isNotBlank()) writer.println(result)
                        if (current.inputTokens > 0 || current.outputTokens > 0) {
                            writer.println("[usage] input=${current.inputTokens} output=${current.outputTokens} turns=${current.turns}")
                        }
                    }
                    TaskRunStatus.CANCELLED -> writer.println("Task cancelled: ${current.error ?: "cancelled"}")
                    else -> writer.println("Task ${current.status}: ${current.error ?: "unknown error"}")
                }
                writer.flush()
                activeTaskId = null
                return
            }
            Thread.sleep(100)
        }
    }

    private fun printNormalEvent(
        event: AgentEvent,
        writer: PrintWriter,
        textActive: Boolean,
        readLine: (String) -> String?,
    ): Boolean {
        var active = textActive
        when (event) {
            is AgentEvent.TextDelta -> {
                writer.print(event.text)
                writer.flush()
                active = true
            }
            is AgentEvent.MessageEvent -> {
                if (event.role == "assistant" && event.text.isNotBlank() && !active) {
                    writer.println(event.text)
                    writer.flush()
                }
            }
            is AgentEvent.ToolStarted -> {
                if (active) writer.println()
                writer.println("[tool] ${event.toolName}: ${event.summary}")
                writer.flush()
                active = false
            }
            is AgentEvent.ToolCompleted -> {
                if (active) writer.println()
                val first = event.output.lineSequence().firstOrNull().orEmpty().take(300)
                writer.println("[tool] ${event.toolName} ${if (event.isError) "failed" else "ok"}: $first")
                writer.flush()
                active = false
            }
            is AgentEvent.ToolFailed -> {
                if (active) writer.println()
                writer.println("[tool] ${event.toolName} failed: ${event.error}")
                writer.flush()
                active = false
            }
            is AgentEvent.PermissionRequested -> {
                if (active) writer.println()
                writer.print("[permission] ${event.toolName}: ${event.reason} (${event.target}) - approve? [y/N] ")
                writer.flush()
                val answer = readLine("")?.trim()?.lowercase()
                val approved = answer == "y" || answer == "yes"
                val resolved = runCatching {
                    kotlinx.coroutines.runBlocking { runtime.respondPermission(event.requestId, approved) }
                }.getOrDefault(false)
                writer.println(if (resolved) (if (approved) "approved" else "denied") else "permission request no longer pending")
                writer.flush()
                active = false
            }
            is AgentEvent.FileChanged -> {
                if (active) writer.println()
                writer.println("[file] ${event.operation}: ${event.path}")
                writer.flush()
                active = false
            }
            is AgentEvent.TaskFailed -> {
                if (active) writer.println()
                writer.println("[task] failed: ${event.error}")
                writer.flush()
                active = false
            }
            is AgentEvent.TaskCancelled -> {
                if (active) writer.println()
                writer.println("[task] cancelled: ${event.reason}")
                writer.flush()
                active = false
            }
            is AgentEvent.SessionCreated -> {
                if (active) writer.println()
                writer.println("[session] ${event.sessionId}")
                writer.flush()
                active = false
            }
            is AgentEvent.SessionResumed -> {
                if (active) writer.println()
                writer.println("[session] resumed ${event.sessionId} (${event.messageCount} messages)")
                writer.flush()
                active = false
            }
            is AgentEvent.LogEvent -> {
                if (active) writer.println()
                writer.println("[log:${event.level}] ${event.message}")
                writer.flush()
                active = false
            }
            is AgentEvent.ErrorEvent -> {
                if (active) writer.println()
                writer.println("[error] ${event.message}")
                writer.flush()
                active = false
            }
            else -> {}
        }
        return active
    }

    private fun handleNormalSlash(
        line: String,
        writer: PrintWriter,
        ansi: Boolean,
        readLine: (String) -> String?,
    ): Boolean {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase()
        val argument = parts.getOrNull(1)?.trim()
        when (command) {
            "/help" -> {
                writer.println(NORMAL_HELP)
            }
            "/status" -> {
                val snapshot = runCatching { runtime.snapshot() }.getOrNull()
                writer.println(statusText(snapshot))
            }
            "/sessions" -> {
                val sessions = runCatching { kotlinx.coroutines.runBlocking { runtime.listSessions() } }
                    .getOrDefault(emptyList())
                if (sessions.isEmpty()) writer.println("No sessions")
                else sessions.take(15).forEach { writer.println("${it.id}  messages=${it.messageCount}  lastActive=${java.time.Instant.ofEpochMilli(it.lastActiveAt)}") }
            }
            "/resume" -> {
                if (argument.isNullOrBlank()) {
                    writer.println("Usage: /resume <session-id>")
                } else {
                    val session = runCatching { kotlinx.coroutines.runBlocking { runtime.resumeSession(argument) } }.getOrNull()
                    if (session == null) writer.println("Session not found: $argument")
                    else {
                        activeSessionId = session.id
                        writer.println("Resumed ${session.id} (${session.messageCount} messages)")
                    }
                }
            }
            "/tools" -> {
                val tools = runCatching { runtime.snapshot() }.getOrNull()?.tools.orEmpty()
                if (tools.isEmpty()) writer.println("No snapshot available")
                else tools.forEach { writer.println("${it.name.padEnd(18)} ${if (it.implemented) "implemented" else "stub"}") }
            }
            "/providers" -> {
                val providers = runCatching { runtime.snapshot() }.getOrNull()?.providers.orEmpty()
                if (providers.isEmpty()) writer.println("No snapshot available")
                else providers.forEach { writer.println("${it.id.padEnd(16)} ${it.name.padEnd(20)} ${if (it.configured) "configured" else "missing credentials"}") }
            }
            "/permissions" -> {
                val pending = runCatching { runtime.snapshot() }.getOrNull()?.pendingPermissions.orEmpty()
                if (pending.isEmpty()) writer.println("No pending permission requests")
                else pending.forEach { writer.println("${it.id}  ${it.toolName}: ${it.reason} (${it.target})") }
            }
            "/approve", "/deny" -> {
                val approved = command == "/approve"
                val pending = runCatching { runtime.snapshot() }.getOrNull()?.pendingPermissions.orEmpty()
                val requestId = argument ?: pending.firstOrNull()?.id
                if (requestId == null) writer.println("No pending permission request")
                else {
                    val resolved = runCatching {
                        kotlinx.coroutines.runBlocking { runtime.respondPermission(requestId, approved) }
                    }.getOrDefault(false)
                    writer.println(if (resolved) "Permission ${if (approved) "approved" else "denied"}" else "Request not found (already resolved?)")
                }
            }
            "/interrupt" -> {
                val interrupted = runCatching { kotlinx.coroutines.runBlocking { runtime.interrupt(activeTaskId) } }.getOrDefault(false)
                writer.println(if (interrupted) "Interrupt requested" else "No running task")
            }
            "/clear" -> {
                if (ansi) writer.print("\u001B[2J\u001B[H") else repeat(40) { writer.println() }
            }
            "/model" -> {
                if (argument.isNullOrBlank()) writer.println("Model: ${cachedSnapshot?.model ?: "unknown"}")
                else {
                    runCatching { runtime.setModel(argument) }
                    writer.println("Model set to $argument")
                }
            }
            "/provider" -> {
                if (argument.isNullOrBlank()) writer.println("Provider: ${cachedSnapshot?.providerId ?: "unknown"}")
                else writer.println(if (runCatching { runtime.setProvider(argument) }.getOrDefault(false)) "Provider set to $argument" else "Unknown provider: $argument")
            }
            "/quit", "/exit" -> return true
            else -> writer.println("Unknown command: $command (try /help)")
        }
        writer.flush()
        return false
    }

    // ------------------------------------------------------------------
    // Full Screen Mode
    // ------------------------------------------------------------------

    private fun runFullScreen(terminal: Terminal): Int {
        val original = try {
            terminal.enterRawMode()
        } catch (e: Exception) {
            terminal.writer().println("Full Screen Mode is not supported by this terminal; using Normal Mode.")
            terminal.writer().flush()
            return runNormal(terminal)
        }

        val startSeq = try {
            runtime.snapshot().lastEventSeq
        } catch (e: Exception) {
            0L
        }
        var lastSeq = (startSeq - 200).coerceAtLeast(0L)
        val initialEvents = runCatching { runtime.eventHistory(lastSeq) }.getOrDefault(emptyList())
        synchronized(lock) {
            consume(initialEvents)
            if (initialEvents.isNotEmpty()) lastSeq = initialEvents.last().seq
        }
        cachedSnapshot = runCatching { runtime.snapshot() }.getOrNull()

        val eventPump = scope.launch {
            while (isActive) {
                val events = runCatching { runtime.eventHistory(lastSeq) }.getOrDefault(emptyList())
                if (events.isNotEmpty()) {
                    synchronized(lock) {
                        consume(events)
                        lastSeq = events.last().seq
                        dirty = true
                    }
                }
                delay(150)
            }
        }
        val statusPump = scope.launch {
            while (isActive) {
                val snapshot = runCatching { runtime.snapshot() }.getOrNull()
                if (snapshot != null) {
                    cachedSnapshot = snapshot
                    dirty = true
                }
                delay(1000)
            }
        }

        try {
            terminal.writer().write("\u001B[?1049h\u001B[?25l\u001B[2J\u001B[H")
            terminal.writer().flush()
            val reader = terminal.reader()
            var lastRender = 0L
            while (running) {
                val now = System.currentTimeMillis()
                if (dirty || now - lastRender >= 1000) {
                    render(terminal)
                    dirty = false
                    lastRender = now
                }
                if (!reader.ready()) {
                    Thread.sleep(60)
                    continue
                }
                val code = try {
                    reader.read()
                } catch (e: Exception) {
                    break
                }
                if (code < 0) break
                when (code) {
                    3 -> { // Ctrl+C
                        if (activeTaskId != null) {
                            scope.launch { runCatching { runtime.interrupt(activeTaskId) } }
                            synchronized(lock) { add("system", "Interrupt requested") }
                            dirty = true
                        } else {
                            running = false
                        }
                    }
                    4 -> if (input.isEmpty()) running = false // Ctrl+D
                    12 -> { // Ctrl+L
                        synchronized(lock) { transcript.clear() }
                        scrollOffset = 0
                        dirty = true
                    }
                    13, 10 -> {
                        val line = input.trim()
                        input = ""
                        dirty = true
                        if (line.isNotEmpty()) {
                            if (line.startsWith("/")) {
                                if (handleFullSlash(line)) break
                            } else {
                                submitPrompt(line)
                            }
                        }
                    }
                    127, 8 -> {
                        if (input.isNotEmpty()) input = input.dropLast(1)
                        dirty = true
                    }
                    27 -> { // escape sequence
                        if (reader.ready()) {
                            val second = reader.read()
                            if (second == '['.code && reader.ready()) {
                                when (val third = reader.read()) {
                                    'A'.code -> scrollOffset = (scrollOffset + 1).coerceAtMost(lastMaxScroll)
                                    'B'.code -> scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
                                    '5'.code -> {
                                        if (reader.ready() && reader.read() == '~'.code) scrollOffset = (scrollOffset + mainHeight(terminal)).coerceAtMost(lastMaxScroll)
                                    }
                                    '6'.code -> {
                                        if (reader.ready() && reader.read() == '~'.code) scrollOffset = (scrollOffset - mainHeight(terminal)).coerceAtLeast(0)
                                    }
                                    'H'.code -> scrollOffset = lastMaxScroll
                                    'F'.code -> scrollOffset = 0
                                }
                            } else {
                                input = ""
                            }
                        } else {
                            input = ""
                        }
                        dirty = true
                    }
                    in 32..126 -> {
                        val hasPending = synchronized(lock) { pendingPermissions.isNotEmpty() }
                        if (hasPending && input.isEmpty() && (code == 'y'.code || code == 'n'.code)) {
                            resolvePermission(null, code == 'y'.code)
                        } else {
                            input += code.toChar()
                        }
                        dirty = true
                    }
                }
            }
        } finally {
            eventPump.cancel()
            statusPump.cancel()
            scope.cancel()
            try {
                terminal.writer().write("\u001B[?25h\u001B[?1049l")
                terminal.writer().flush()
                terminal.setAttributes(original)
            } catch (ignored: Exception) {
            }
        }
        return 0
    }

    private fun handleFullSlash(line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val command = parts[0].lowercase()
        val argument = parts.getOrNull(1)?.trim()
        when (command) {
            "/help" -> synchronized(lock) { add("system", FULL_HELP); dirty = true }
            "/status" -> synchronized(lock) { add("system", statusText(cachedSnapshot)); dirty = true }
            "/sessions" -> {
                scope.launch {
                    val sessions = runCatching { runtime.listSessions() }.getOrDefault(emptyList())
                    synchronized(lock) {
                        if (sessions.isEmpty()) add("system", "No sessions")
                        else sessions.take(12).forEach { add("system", "${it.id} (${it.messageCount} messages)") }
                        dirty = true
                    }
                }
            }
            "/resume" -> {
                if (argument.isNullOrBlank()) {
                    synchronized(lock) { add("system", "Usage: /resume <session-id>"); dirty = true }
                } else {
                    scope.launch {
                        val session = runCatching { runtime.resumeSession(argument) }.getOrNull()
                        synchronized(lock) {
                            if (session == null) add("error", "Session not found: $argument")
                            else {
                                activeSessionId = session.id
                                add("system", "Resumed ${session.id} (${session.messageCount} messages)")
                            }
                            dirty = true
                        }
                    }
                }
            }
            "/tools" -> {
                val tools = cachedSnapshot?.tools.orEmpty()
                synchronized(lock) {
                    if (tools.isEmpty()) add("system", "No snapshot available yet")
                    else tools.forEach { add("system", "${it.name}${if (it.implemented) "" else " (stub)"}") }
                    dirty = true
                }
            }
            "/providers" -> {
                val providers = cachedSnapshot?.providers.orEmpty()
                synchronized(lock) {
                    if (providers.isEmpty()) add("system", "No snapshot available yet")
                    else providers.forEach { add("system", "${it.id}: ${if (it.configured) "configured" else "missing credentials"}") }
                    dirty = true
                }
            }
            "/permissions" -> synchronized(lock) {
                if (pendingPermissions.isEmpty()) add("system", "No pending permission requests")
                else pendingPermissions.values.forEach { add("permission", "${it.requestId} ${it.toolName}: ${it.reason} (${it.target})") }
                dirty = true
            }
            "/approve", "/deny" -> resolvePermission(argument, command == "/approve")
            "/interrupt" -> {
                scope.launch {
                    val interrupted = runCatching { runtime.interrupt(activeTaskId) }.getOrDefault(false)
                    synchronized(lock) {
                        add("system", if (interrupted) "Interrupt requested" else "No running task")
                        dirty = true
                    }
                }
            }
            "/clear" -> {
                synchronized(lock) { transcript.clear() }
                scrollOffset = 0
                dirty = true
            }
            "/model" -> {
                if (argument.isNullOrBlank()) synchronized(lock) { add("system", "Model: ${cachedSnapshot?.model ?: "unknown"}"); dirty = true }
                else scope.launch {
                    runCatching { runtime.setModel(argument) }
                    synchronized(lock) { add("system", "Model set to $argument"); dirty = true }
                }
            }
            "/provider" -> {
                if (argument.isNullOrBlank()) synchronized(lock) { add("system", "Provider: ${cachedSnapshot?.providerId ?: "unknown"}"); dirty = true }
                else scope.launch {
                    val ok = runCatching { runtime.setProvider(argument) }.getOrDefault(false)
                    synchronized(lock) {
                        add("system", if (ok) "Provider set to $argument" else "Unknown provider: $argument")
                        dirty = true
                    }
                }
            }
            "/quit", "/exit" -> return true
            else -> synchronized(lock) { add("error", "Unknown command: $command (try /help)"); dirty = true }
        }
        return false
    }

    private fun submitPrompt(prompt: String) {
        synchronized(lock) {
            if (activeTaskId != null) {
                add("system", "A task is already running. Use /interrupt first.")
                dirty = true
                return
            }
            add("user", prompt)
            taskHadText = false
            seenMessages.clear()
            dirty = true
        }
        val sessionId = activeSessionId
        scope.launch {
            try {
                val task = runtime.submit(prompt, sessionId)
                synchronized(lock) {
                    activeTaskId = task.id
                    activeSessionId = task.sessionId
                    dirty = true
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    add("error", e.message ?: e.toString())
                    dirty = true
                }
            }
        }
    }

    private fun resolvePermission(requestId: String?, approved: Boolean) {
        val id = requestId ?: synchronized(lock) { pendingPermissions.keys.firstOrNull() }
        if (id == null) {
            synchronized(lock) { add("system", "No pending permission request"); dirty = true }
            return
        }
        scope.launch {
            val resolved = runCatching { runtime.respondPermission(id, approved) }.getOrDefault(false)
            synchronized(lock) {
                add("system", if (resolved) "Permission ${if (approved) "approved" else "denied"}" else "Request not found (already resolved?)")
                dirty = true
            }
        }
    }

    private fun consume(envelopes: List<EventEnvelope>) {
        for (envelope in envelopes) {
            when (val event = envelope.event) {
                is AgentEvent.TextDelta -> {
                    taskHadText = true
                    seenMessages.add(event.messageId)
                    val last = transcript.lastOrNull()
                    if (last?.role == "assistant" && last.streaming) {
                        last.text += event.text
                    } else {
                        transcript.add(Entry("assistant", event.text, streaming = true))
                    }
                }
                is AgentEvent.MessageEvent -> {
                    if (event.role == "assistant" && event.text.isNotBlank() && !seenMessages.contains(event.messageId)) {
                        add("assistant", event.text)
                    }
                }
                is AgentEvent.ToolStarted -> {
                    transcript.lastOrNull()?.streaming = false
                    add("tool", "${event.toolName}: ${event.summary}")
                }
                is AgentEvent.ToolCompleted -> {
                    transcript.lastOrNull()?.streaming = false
                    val first = event.output.lineSequence().firstOrNull().orEmpty().take(300)
                    add("tool", "${event.toolName} ${if (event.isError) "failed" else "done"}: $first")
                }
                is AgentEvent.ToolFailed -> {
                    transcript.lastOrNull()?.streaming = false
                    add("tool", "${event.toolName} failed: ${event.error}")
                }
                is AgentEvent.PermissionRequested -> {
                    pendingPermissions[event.requestId] = event
                    add("permission", "${event.toolName}: ${event.reason} (${event.target})")
                }
                is AgentEvent.PermissionResolved -> {
                    pendingPermissions.remove(event.requestId)
                    add("permission", if (event.approved) "approved" else "denied: ${event.reason ?: ""}")
                }
                is AgentEvent.FileChanged -> add("file", "${event.operation}: ${event.path}")
                is AgentEvent.TaskCreated -> {
                    activeTaskId = event.taskId
                    activeSessionId = event.sessionId
                    add("system", "Task ${event.taskId} created")
                }
                is AgentEvent.TaskStarted -> {
                    activeTaskId = event.taskId
                    add("system", "Task ${event.taskId} started")
                }
                is AgentEvent.TaskCompleted -> {
                    activeTaskId = null
                    transcript.lastOrNull()?.streaming = false
                    if (!taskHadText && event.result.isNotBlank()) add("assistant", event.result)
                    add("system", "Task completed: ${event.turns} turns, ${event.inputTokens}/${event.outputTokens} tokens")
                    taskHadText = false
                }
                is AgentEvent.TaskFailed -> {
                    activeTaskId = null
                    transcript.lastOrNull()?.streaming = false
                    add("error", "Task failed: ${event.error}")
                }
                is AgentEvent.TaskCancelled -> {
                    activeTaskId = null
                    transcript.lastOrNull()?.streaming = false
                    add("system", "Task cancelled: ${event.reason}")
                }
                is AgentEvent.UsageUpdated -> {
                    usageInput = event.inputTokens
                    usageOutput = event.outputTokens
                }
                is AgentEvent.SessionCreated -> {
                    activeSessionId = event.sessionId
                    add("system", "Session ${event.sessionId} created")
                }
                is AgentEvent.SessionResumed -> {
                    activeSessionId = event.sessionId
                    add("system", "Session ${event.sessionId} resumed (${event.messageCount} messages)")
                }
                is AgentEvent.LogEvent -> add("log", event.message)
                is AgentEvent.ErrorEvent -> add("error", event.message)
                else -> {}
            }
        }
    }

    private fun render(terminal: Terminal) {
        val width = terminal.width.coerceAtLeast(40)
        val height = terminal.height.coerceAtLeast(10)
        val mainHeight = (height - 5).coerceAtLeast(3)
        val leftWidth = ((width * 2) / 3).coerceAtLeast(20)
        val rightWidth = (width - leftWidth - 1).coerceAtLeast(10)

        var leftLines: List<String> = emptyList()
        var rightLines: List<String> = emptyList()
        var header: String = ""
        var inputDisplay: String = ""
        synchronized(lock) {
            val all = buildTranscriptLines(leftWidth - 1)
            lastMaxScroll = (all.size - mainHeight).coerceAtLeast(0)
            scrollOffset = scrollOffset.coerceIn(0, lastMaxScroll)
            val end = (all.size - scrollOffset).coerceAtLeast(0)
            val start = (end - mainHeight).coerceAtLeast(0)
            leftLines = all.subList(start, end).map { fit(it, leftWidth) }
            rightLines = buildInspector(rightWidth)
            header = headerText()
            inputDisplay = if (input.length > width - 2) input.takeLast(width - 2) else input
        }

        val out = terminal.writer()
        out.write("\u001B[H")
        val rows = mutableListOf<String>()
        rows += fit(header, width)
        rows += "-".repeat(width)
        for (i in 0 until mainHeight) {
            val left = leftLines.getOrElse(i) { "" }
            val right = rightLines.getOrElse(i) { "" }
            rows += fit(left, leftWidth) + "|" + fit(right, rightWidth)
        }
        rows += "> " + fit(inputDisplay, width - 2)
        rows += fit(footerText(), width)
        rows.forEachIndexed { index, row ->
            out.write(fit(row, width))
            out.write("\u001B[K")
            if (index < rows.size - 1) out.write("\r\n")
        }
        val inputRow = 3 + mainHeight
        val cursorColumn = (3 + inputDisplay.length).coerceAtMost(width)
        out.write("\u001B[${inputRow};${cursorColumn}H")
        out.flush()
    }

    private fun buildTranscriptLines(width: Int): List<String> {
        if (transcript.isEmpty()) {
            return wrapLine("Mimicry Claw", width) +
                wrapLine("Type a prompt and press Enter. /help lists real commands.", width) +
                wrapLine("Normal Mode is available with: mimicry tui --normal", width)
        }
        val lines = mutableListOf<String>()
        transcript.forEach { entry ->
            val prefix = when (entry.role) {
                "user" -> "> "
                "tool" -> "[tool] "
                "permission" -> "[permission] "
                "file" -> "[file] "
                "system" -> "· "
                "error" -> "! "
                "log" -> "· "
                else -> ""
            }
            lines += wrapLine(prefix + entry.text, width)
        }
        return lines
    }

    private fun buildInspector(width: Int): List<String> {
        val snapshot = cachedSnapshot
        val task = snapshot?.tasks?.firstOrNull { it.id == activeTaskId }
        val lines = mutableListOf<String>()
        lines += "INSPECTOR"
        lines += "status   ${task?.status?.name ?: if (activeTaskId != null) "RUNNING" else "IDLE"}"
        lines += "session  ${activeSessionId?.take(12) ?: "new"}"
        lines += "provider ${snapshot?.providerId ?: "?"}"
        lines += "model    ${snapshot?.model ?: "?"}"
        lines += "usage    ${usageInput}/${usageOutput}"
        lines += "events   ${snapshot?.lastEventSeq ?: 0}"
        lines += ""
        lines += "PENDING PERMISSIONS"
        if (pendingPermissions.isEmpty()) lines += "  none"
        else pendingPermissions.values.take(6).forEach {
            lines += "  ! ${it.toolName}: ${it.reason}"
            lines += "    ${it.target}"
        }
        lines += ""
        lines += "TOOLS"
        snapshot?.tools?.take(8)?.forEach { tool ->
            lines += "  ${if (tool.implemented) "[ok]" else "[--]"} ${tool.name}"
        } ?: run { lines += "  no snapshot yet" }
        return lines.map { fit(it, width) }
    }

    private fun headerText(): String {
        val snapshot = cachedSnapshot
        val status = when {
            pendingPermissions.isNotEmpty() -> "WAITING PERMISSION"
            activeTaskId != null -> "RUNNING"
            else -> "IDLE"
        }
        val mode = if (attached) "attached" else "embedded"
        return " MIMICRY CLAW  ${snapshot?.providerId ?: "?"}/${snapshot?.model ?: "?"}  " +
            "session=${activeSessionId?.take(8) ?: "new"}  task=$status  $mode"
    }

    private fun footerText(): String =
        " Ctrl+C ${if (activeTaskId != null) "interrupt" else "quit"}  Enter send  /help  PgUp/PgDn scroll  Ctrl+L clear"

    private fun mainHeight(terminal: Terminal): Int = (terminal.height - 5).coerceAtLeast(3)

    private fun statusText(snapshot: AppSnapshot?): String {
        if (snapshot == null) return "No runtime snapshot available"
        val running = snapshot.tasks.firstOrNull {
            it.status == TaskRunStatus.RUNNING || it.status == TaskRunStatus.WAITING_PERMISSION
        }
        return buildString {
            appendLine("runtime: ${snapshot.runtimeId}")
            appendLine("project: ${snapshot.projectDir}")
            appendLine("provider/model: ${snapshot.providerId}/${snapshot.model}")
            appendLine("permissions: ${snapshot.permissionMode}")
            appendLine("session: ${activeSessionId ?: "new"}")
            appendLine("task: ${running?.id ?: "idle"} ${running?.status?.name ?: ""}")
            appendLine("sessions: ${snapshot.sessions.size}  tasks: ${snapshot.tasks.size}")
            appendLine("pending permissions: ${snapshot.pendingPermissions.size}")
            append("last event: ${snapshot.lastEventSeq}")
        }
    }

    private fun add(role: String, text: String) {
        transcript.add(Entry(role, text))
        while (transcript.size > 500) transcript.removeAt(0)
    }

    private fun wrapLine(text: String, width: Int): List<String> {
        if (width <= 0) return emptyList()
        val result = mutableListOf<String>()
        text.split("\n").forEach { raw ->
            if (raw.isEmpty()) {
                result.add("")
            } else {
                var rest = raw
                while (rest.length > width) {
                    result.add(rest.substring(0, width))
                    rest = rest.substring(width)
                }
                result.add(rest)
            }
        }
        return result
    }

    private fun fit(text: String, width: Int): String = when {
        width <= 0 -> ""
        text.length >= width -> text.substring(0, width)
        else -> text.padEnd(width)
    }

    companion object {
        private val NORMAL_HELP = """
            Commands (all have real behavior):
              /help                 Show this help
              /status               Runtime and task status
              /sessions             List persisted sessions
              /resume <id>          Continue a session
              /tools                List implemented and stub tools
              /providers            Provider credential status
              /permissions          List pending permission requests
              /approve [id]         Approve a permission request
              /deny [id]            Deny a permission request
              /interrupt            Interrupt the running task
              /clear                Clear the local transcript
              /model <name>         Change model
              /provider <id>        Change provider
              /quit                 Exit the TUI (runtime keeps running)
        """.trimIndent()

        private val FULL_HELP = """
            Commands: /help /status /sessions /resume /tools /providers
            /permissions /approve /deny /interrupt /clear /model /provider /quit
            Keys: Enter send, Ctrl+C interrupt/quit, Ctrl+L clear,
            Up/Down scroll, PgUp/PgDn page, y/n resolve permission.
        """.trimIndent()
    }
}
