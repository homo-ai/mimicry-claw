package com.mimicryos.agent.cli

import com.mimicryos.agent.app.*
import com.mimicryos.agent.events.AgentEvent
import com.mimicryos.agent.events.EventEnvelope
import com.mimicryos.agent.provider.ProviderCatalog
import com.mimicryos.agent.runtime.FrontendRuntime
import com.mimicryos.agent.runtime.RuntimeClient
import com.mimicryos.agent.runtime.RuntimeConnections
import com.mimicryos.agent.runtime.RuntimeInfo
import com.mimicryos.agent.runtime.RuntimeServer
import com.mimicryos.agent.session.SessionManager
import com.mimicryos.agent.tools.ToolRegistry
import com.mimicryos.agent.tui.TuiApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
    prettyPrint = true
}

private val VALUE_OPTIONS = setOf(
    "cwd", "provider", "model", "api-key", "base-url", "permissions",
    "max-turns", "max-budget", "timeout", "session", "mode", "skills-dir", "port",
)

private data class ParsedArgs(
    val options: Map<String, String>,
    val flags: Set<String>,
    val positionals: List<String>,
)

fun main(args: Array<String>) {
    val code = try {
        dispatch(args.toList())
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e.toString()}")
        if (System.getenv("MIMICRY_DEBUG") != null) e.printStackTrace()
        1
    }
    if (code != 0) kotlin.system.exitProcess(code)
}

private fun dispatch(args: List<String>): Int {
    if (args.isEmpty()) {
        return if (System.console() != null) runTui(emptyList()) else {
            printHelp()
            0
        }
    }
    return when (args.first()) {
        "-h", "--help", "help" -> { printHelp(); 0 }
        "--version", "version" -> { println("mimicry-claw 1.0.0"); 0 }
        "tui" -> runTui(args.drop(1))
        "exec" -> runExec(args.drop(1))
        "serve" -> runServe(args.drop(1))
        "status" -> runStatus(args.drop(1))
        "sessions" -> runSessions(args.drop(1))
        "resume" -> runResume(args.drop(1))
        "providers" -> runProviders(args.drop(1))
        "tools" -> runTools(args.drop(1))
        "gui" -> runGui(args.drop(1))
        else -> {
            System.err.println("Unknown command: ${args.first()}")
            printHelp()
            2
        }
    }
}

private fun parseArgs(args: List<String>): ParsedArgs {
    val options = linkedMapOf<String, String>()
    val flags = linkedSetOf<String>()
    val positionals = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--" -> {
                positionals.addAll(args.drop(i + 1))
                break
            }
            arg.startsWith("--") && arg.length > 2 -> {
                val eq = arg.indexOf('=')
                if (eq > 2) {
                    options[arg.substring(2, eq)] = arg.substring(eq + 1)
                } else {
                    val key = arg.substring(2)
                    if (key in VALUE_OPTIONS && i + 1 < args.size && !args[i + 1].startsWith("-")) {
                        options[key] = args[++i]
                    } else {
                        flags.add(key)
                    }
                }
            }
            arg.startsWith("-") && arg.length > 1 && arg != "-" -> {
                val key = when (arg) {
                    "-v" -> "verbose"
                    "-m" -> "model"
                    "-c" -> "cwd"
                    "-s" -> "session"
                    "-h" -> "help"
                    else -> arg.removePrefix("-")
                }
                if (key in VALUE_OPTIONS && i + 1 < args.size) options[key] = args[++i] else flags.add(key)
            }
            else -> positionals.add(arg)
        }
        i++
    }
    return ParsedArgs(options, flags, positionals)
}

private fun configFrom(parsed: ParsedArgs, interactivePermissions: Boolean): AppConfig =
    AppConfig.fromEnv(
        projectDir = parsed.options["cwd"],
        providerId = parsed.options["provider"],
        model = parsed.options["model"],
        apiKey = parsed.options["api-key"],
        baseUrl = parsed.options["base-url"],
        permissionMode = parsed.options["permissions"],
        maxTurns = parsed.options["max-turns"]?.toIntOrNull(),
        maxBudgetUsd = parsed.options["max-budget"]?.toDoubleOrNull(),
        taskTimeoutMs = parsed.options["timeout"]?.toLongOrNull()?.times(1000),
        verbose = parsed.flags.contains("verbose"),
        interactivePermissions = interactivePermissions,
    )

private fun runTui(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) { printTuiHelp(); return 0 }
    val config = configFrom(parsed, interactivePermissions = true)
    val mode = parsed.options["mode"]
    val fullScreen = parsed.flags.contains("fullscreen") || mode.equals("full", ignoreCase = true)
    val forceNormal = parsed.flags.contains("normal") || mode.equals("normal", ignoreCase = true)
    val preferAttach = !parsed.flags.contains("no-attach")
    val (runtime, remote) = runBlocking { RuntimeConnections.attachOrLocal(config, preferAttach) }
    return try {
        TuiApp(
            runtime = runtime,
            fullScreen = fullScreen,
            forceNormal = forceNormal,
            initialSessionId = parsed.options["session"],
            attached = remote,
        ).run()
    } finally {
        runtime.close()
    }
}

private fun runExec(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) { printExecHelp(); return 0 }
    val prompt = if (parsed.positionals == listOf("-")) {
        System.`in`.bufferedReader().readText().trim()
    } else {
        parsed.positionals.joinToString(" ").trim()
    }
    if (prompt.isBlank()) {
        System.err.println("No prompt provided. Usage: mimicry exec \"your prompt\" (or: echo \"prompt\" | mimicry exec -)")
        return 2
    }
    val interactive = parsed.flags.contains("interactive-permissions")
    val config = configFrom(parsed, interactivePermissions = interactive)
    val attach = parsed.flags.contains("attach")
    val (runtime, _) = runBlocking { RuntimeConnections.attachOrLocal(config, attach) }
    return try {
        runTask(runtime, prompt, parsed, parsed.options["session"], interactive)
    } finally {
        runtime.close()
    }
}

private fun runTask(
    runtime: FrontendRuntime,
    prompt: String,
    parsed: ParsedArgs,
    sessionId: String?,
    interactive: Boolean,
): Int {
    val jsonMode = parsed.flags.contains("json")
    val timeoutSeconds = parsed.options["timeout"]?.toLongOrNull() ?: 0L
    var lastSeq = runtime.snapshot().lastEventSeq
    val task = runBlocking { runtime.submit(prompt, sessionId) }
    val startedAt = System.currentTimeMillis()
    var textActive = false

    while (true) {
        val envelopes = runtime.eventHistory(lastSeq)
        for (envelope in envelopes) {
            lastSeq = envelope.seq
            if (jsonMode) {
                println(json.encodeToString(EventEnvelope.serializer(), envelope))
            } else {
                textActive = printEvent(envelope.event, runtime, interactive, textActive)
            }
        }

        val current = runBlocking { runtime.awaitTask(task.id, 250) } ?: task
        if (current.status.isTerminal) {
            for (envelope in runtime.eventHistory(lastSeq)) {
                lastSeq = envelope.seq
                if (jsonMode) println(json.encodeToString(EventEnvelope.serializer(), envelope))
                else textActive = printEvent(envelope.event, runtime, interactive, textActive)
            }
            if (!jsonMode) {
                if (textActive) println()
                when (current.status) {
                    TaskRunStatus.COMPLETED -> {
                        val result = current.result.orEmpty()
                        if (!textActive && result.isNotBlank()) println(result)
                        if (current.inputTokens > 0 || current.outputTokens > 0) {
                            println("[usage] input=${current.inputTokens} output=${current.outputTokens} turns=${current.turns}")
                        }
                    }
                    TaskRunStatus.CANCELLED -> System.err.println("Task cancelled: ${current.error ?: "cancelled"}")
                    else -> System.err.println("Task ${current.status}: ${current.error ?: "unknown error"}")
                }
            }
            return if (current.status == TaskRunStatus.COMPLETED) 0 else 1
        }

        if (timeoutSeconds > 0 && System.currentTimeMillis() - startedAt > timeoutSeconds * 1000) {
            runBlocking { runtime.interrupt(task.id) }
            System.err.println("Task timed out after ${timeoutSeconds}s")
            return 1
        }
        Thread.sleep(100)
    }
}

private fun printEvent(
    event: AgentEvent,
    runtime: FrontendRuntime,
    interactive: Boolean,
    textActive: Boolean,
): Boolean {
    var active = textActive
    when (event) {
        is AgentEvent.TextDelta -> {
            print(event.text)
            System.out.flush()
            active = true
        }
        is AgentEvent.MessageEvent -> {
            if (event.role == "assistant" && event.text.isNotBlank() && !active) {
                println(event.text)
            }
        }
        is AgentEvent.ToolStarted -> {
            if (active) println()
            println("[tool] ${event.toolName}: ${event.summary}")
            active = false
        }
        is AgentEvent.ToolCompleted -> {
            if (active) println()
            val firstLine = event.output.lineSequence().firstOrNull().orEmpty().take(300)
            println("[tool] ${event.toolName} ${if (event.isError) "failed" else "ok"}: $firstLine")
            active = false
        }
        is AgentEvent.ToolFailed -> {
            if (active) println()
            println("[tool] ${event.toolName} failed: ${event.error}")
            active = false
        }
        is AgentEvent.PermissionRequested -> {
            if (active) println()
            println("[permission] ${event.toolName}: ${event.reason} (${event.target})")
            if (interactive && System.console() != null) {
                print("Approve? [y/N] ")
                System.out.flush()
                val answer = readLine()?.trim()?.lowercase()
                val approved = answer == "y" || answer == "yes"
                runBlocking { runtime.respondPermission(event.requestId, approved) }
                println(if (approved) "[permission] approved" else "[permission] denied")
            } else {
                println("[permission] non-interactive; the request will be denied unless a GUI/TUI approves it")
            }
            active = false
        }
        is AgentEvent.FileChanged -> {
            if (active) println()
            println("[file] ${event.operation}: ${event.path}")
            active = false
        }
        is AgentEvent.TaskFailed -> {
            if (active) println()
            System.err.println("[task] failed: ${event.error}")
            active = false
        }
        is AgentEvent.TaskCancelled -> {
            if (active) println()
            println("[task] cancelled: ${event.reason}")
            active = false
        }
        is AgentEvent.LogEvent -> {
            if (active) println()
            println("[log:${event.level}] ${event.message}")
            active = false
        }
        is AgentEvent.ErrorEvent -> {
            if (active) println()
            System.err.println("[error] ${event.message}")
            active = false
        }
        is AgentEvent.SessionCreated -> {
            if (active) println()
            println("[session] ${event.sessionId} (${event.projectDir})")
            active = false
        }
        is AgentEvent.SessionResumed -> {
            if (active) println()
            println("[session] resumed ${event.sessionId} (${event.messageCount} messages)")
            active = false
        }
        else -> {}
    }
    return active
}

private fun runServe(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) { printServeHelp(); return 0 }
    val interactive = !parsed.flags.contains("no-interactive-permissions")
    val config = configFrom(parsed, interactivePermissions = interactive)
    val components = AppFactory.create(config)
    val service = MimicryApplicationService(components, config)
    val server = RuntimeServer(
        application = service,
        port = parsed.options["port"]?.toIntOrNull() ?: 0,
    )
    val info = server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    if (parsed.flags.contains("json")) {
        println(json.encodeToString(RuntimeInfo.serializer(), info))
    } else {
        println("Mimicry runtime listening on ${info.baseUrl}")
        println("runtime file: ${RuntimeInfo.path()}")
        println("pid: ${info.pid}  runtimeId: ${info.runtimeId}")
        println("project: ${info.projectDir}")
        println("permissions: ${config.permissionMode} (interactive=$interactive)")
        println("Attach with: mimicry tui   or   mimicry exec --attach \"...\"")
        println("Press Ctrl+C to stop.")
    }
    server.await()
    return 0
}

@Serializable
private data class StatusOutput(
    val running: Boolean,
    val host: String? = null,
    val port: Int? = null,
    val pid: Long? = null,
    val projectDir: String? = null,
    val runtimeId: String? = null,
    val providerId: String? = null,
    val model: String? = null,
    val sessions: Int = 0,
    val tasks: Int = 0,
    val pendingPermissions: Int = 0,
    val lastEventSeq: Long = 0,
)

private fun runStatus(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) {
        println("Usage: mimicry status [--json]")
        return 0
    }
    val info = RuntimeInfo.read()
    var running = false
    var snapshot: AppSnapshot? = null
    if (info != null && info.isProcessAlive()) {
        running = runBlocking { RuntimeClient(info).health() }
        if (running) snapshot = runBlocking { runCatching { RuntimeClient(info).snapshot() }.getOrNull() }
    }
    val output = StatusOutput(
        running = running,
        host = info?.host,
        port = info?.port,
        pid = info?.pid,
        projectDir = info?.projectDir,
        runtimeId = info?.runtimeId,
        providerId = snapshot?.providerId,
        model = snapshot?.model,
        sessions = snapshot?.sessions?.size ?: 0,
        tasks = snapshot?.tasks?.size ?: 0,
        pendingPermissions = snapshot?.pendingPermissions?.size ?: 0,
        lastEventSeq = snapshot?.lastEventSeq ?: 0,
    )
    if (parsed.flags.contains("json")) {
        println(json.encodeToString(StatusOutput.serializer(), output))
    } else if (running) {
        println("Runtime: running")
        println("  endpoint: ${info?.baseUrl}")
        println("  pid: ${info?.pid}  runtimeId: ${info?.runtimeId}")
        println("  project: ${info?.projectDir}")
        println("  provider: ${output.providerId}  model: ${output.model}")
        println("  sessions: ${output.sessions}  tasks: ${output.tasks}  pending permissions: ${output.pendingPermissions}")
        println("  last event: ${output.lastEventSeq}")
    } else {
        println("Runtime: not running")
        println("Start one with: mimicry serve")
    }
    return 0
}

private fun runSessions(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) {
        println("Usage: mimicry sessions [--cwd DIR] [--json]")
        return 0
    }
    val config = configFrom(parsed, interactivePermissions = false)
    val sessions = SessionManager(config.projectDir).listSessions().map {
        SessionSummary(it.id, it.createdAt, it.lastActiveAt, it.messageCount, config.projectDir)
    }
    if (parsed.flags.contains("json")) {
        println(json.encodeToString(ListSerializer(SessionSummary.serializer()), sessions))
    } else if (sessions.isEmpty()) {
        println("No sessions in ${config.projectDir}")
    } else {
        sessions.forEach { session ->
            println("${session.id}  messages=${session.messageCount}  lastActive=${java.time.Instant.ofEpochMilli(session.lastActiveAt)}")
        }
    }
    return 0
}

private fun runResume(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) {
        println("Usage: mimicry resume <session-id> [prompt] [--json]")
        return 0
    }
    val sessionId = parsed.options["session"] ?: parsed.positionals.firstOrNull()
    if (sessionId == null) {
        System.err.println("Session id is required")
        return 2
    }
    val prompt = if (parsed.options["session"] != null) {
        parsed.positionals.joinToString(" ").trim()
    } else {
        parsed.positionals.drop(1).joinToString(" ").trim()
    }
    val config = configFrom(parsed, interactivePermissions = parsed.flags.contains("interactive-permissions"))
    val (runtime, _) = runBlocking { RuntimeConnections.attachOrLocal(config, parsed.flags.contains("attach")) }
    return try {
        val session = runBlocking { runtime.resumeSession(sessionId) }
        if (session == null) {
            System.err.println("Session not found: $sessionId")
            1
        } else if (prompt.isBlank()) {
            if (parsed.flags.contains("json")) {
                println(json.encodeToString(SessionSummary.serializer(), session))
            } else {
                println("Resumed session ${session.id} (${session.messageCount} messages)")
                println("Continue with: mimicry exec --session ${session.id} \"...\"")
            }
            0
        } else {
            runTask(runtime, prompt, parsed, session.id, parsed.flags.contains("interactive-permissions"))
        }
    } finally {
        runtime.close()
    }
}

private fun runProviders(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) {
        println("Usage: mimicry providers [--provider ID] [--json]")
        return 0
    }
    val config = configFrom(parsed, interactivePermissions = false)
    val registry = ProviderCatalog.createRegistry(
        ProviderCatalog.Settings(config.providerId, config.apiKey, config.baseUrl)
    )
    val providers = registry.getAllProviders().sortedBy { it.id }.map { provider ->
        ProviderInfo(
            id = provider.id,
            name = provider.name,
            configured = provider.validate().result,
            defaultProtocol = provider.defaultProtocol.name,
            supportedProtocols = provider.supportedProtocols.map { it.name },
        )
    }
    if (parsed.flags.contains("json")) {
        println(json.encodeToString(ListSerializer(ProviderInfo.serializer()), providers))
    } else {
        providers.forEach { provider ->
            val status = if (provider.configured) "configured" else "missing credentials"
            println("${provider.id.padEnd(16)} ${provider.name.padEnd(20)} $status  (${provider.defaultProtocol})")
        }
    }
    return 0
}

private fun runTools(args: List<String>): Int {
    val parsed = parseArgs(args)
    if (parsed.flags.contains("help")) {
        println("Usage: mimicry tools [--json]")
        return 0
    }
    val registry = ToolRegistry.createDefault()
    val tools = registry.getAll().sortedBy { it.name }.map { tool ->
        ToolInfo(
            name = tool.name,
            description = try {
                tool.prompt(PromptOptions({ ToolPermissionContext() }, registry.getAll(), emptyList()))
            } catch (e: Exception) {
                ""
            },
            implemented = tool.isImplemented,
        )
    }
    if (parsed.flags.contains("json")) {
        println(json.encodeToString(ListSerializer(ToolInfo.serializer()), tools))
    } else {
        tools.forEach { tool ->
            val status = if (tool.implemented) "implemented" else "stub"
            println("${tool.name.padEnd(18)} $status")
        }
    }
    return 0
}

private fun runGui(args: List<String>): Int {
    if (args.contains("--help") || args.contains("-h")) {
        println("Usage: mimicry gui")
        println("The Avalonia GUI is Stage B and is not part of this build yet.")
        return 0
    }
    System.err.println("The Avalonia GUI frontend is Stage B and is not included in this build.")
    System.err.println("The shared runtime is available now:")
    System.err.println("  1. mimicry serve")
    System.err.println("  2. read ~/.mimicry/runtime.json for the endpoint and bearer token")
    System.err.println("  3. GET /v1/snapshot and GET /v1/events (SSE) for live state")
    return 1
}

private fun printHelp() {
    println(
        """
        Mimicry Claw - AI coding agent

        Usage:
          mimicry [tui] [options]             Interactive TUI (Normal or Full Screen)
          mimicry exec [options] <prompt>     Run one task and print the result
          mimicry serve [options]             Start the local runtime (HTTP + SSE)
          mimicry status [--json]             Show whether a runtime is running
          mimicry sessions [--json]           List persisted sessions
          mimicry resume <id> [prompt]        Resume a session, optionally run a prompt
          mimicry providers [--json]          List providers and credential status
          mimicry tools [--json]              List tools (implemented vs stub)
          mimicry gui                         GUI status (Stage B)

        Common options:
          --cwd DIR              Working directory (default: current directory)
          --provider ID          anthropic, openai, google, deepseek, openai-zencode,
                                 kimi, xiaomi-mimo
          --model NAME           Model id (default: provider default)
          --api-key KEY          Provider API key (otherwise environment variable)
          --base-url URL         Custom provider base URL
          --permissions MODE     default, bypass, auto, plan, accept-edits
          --max-turns N          Maximum agent turns (default: 50)
          --session ID           Continue a persisted session
          --json                 Machine-readable event/output stream
          --attach               Attach to a running runtime instead of embedding one

        TUI options:
          --fullscreen           Full Screen Mode (alternate screen, keyboard driven)
          --normal               Force Normal Mode (SSH/small terminals)
          --mode normal|full     Select TUI mode
          --no-attach            Always start an embedded runtime

        Examples:
          mimicry tui --provider deepseek --model deepseek-chat
          mimicry exec --provider anthropic "Explain this repository"
          mimicry serve --port 0
          mimicry exec --attach --json "Run the tests"
        """.trimIndent()
    )
}

private fun printTuiHelp() {
    println("Usage: mimicry tui [--fullscreen|--normal|--mode normal|full] [--no-attach] [common options]")
    println("Normal Mode is line-based and works over SSH without mouse or full ANSI support.")
    println("Full Screen Mode uses the alternate screen and keyboard navigation.")
}

private fun printExecHelp() {
    println("Usage: mimicry exec [options] <prompt>")
    println("Use '-' to read the prompt from stdin: echo \"prompt\" | mimicry exec -")
    println("Options: --attach, --json, --session ID, --interactive-permissions, --timeout SECONDS")
}

private fun printServeHelp() {
    println("Usage: mimicry serve [--port N] [--no-interactive-permissions] [--json]")
    println("Writes ~/.mimicry/runtime.json with host, port, bearer token, pid and runtime id.")
}
