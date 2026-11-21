package com.mimicryos.agent.tools

import com.mimicryos.agent.core.*
import com.mimicryos.agent.provider.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap

private val toolJson = Json { ignoreUnknownKeys = true }

class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool<*, *>>()
    private val toolOrder = mutableListOf<String>()

    fun register(tool: Tool<*, *>) { tools[tool.name] = tool; if (!toolOrder.contains(tool.name)) toolOrder.add(tool.name) }
    fun unregister(name: String) { tools.remove(name); toolOrder.remove(name) }
    fun get(name: String): Tool<*, *>? = tools[name]
    fun getAll(): List<Tool<*, *>> = toolOrder.mapNotNull { tools[it] }

    /**
     * Only tools with a real implementation are exposed to the model.
     * Stubs stay registered so frontends can list them as unimplemented,
     * but the agent never advertises or calls them.
     */
    fun getEnabled(): List<Tool<*, *>> = getAll().filter { it.isEnabled() && it.isImplemented }
    fun filterByPermission(context: ToolPermissionContext): List<Tool<*, *>> = getAll().filter { !isDeniedByRules(it, context) }

    fun assembleToolPool(permissionContext: ToolPermissionContext, mcpTools: List<Tool<*, *>>): List<Tool<*, *>> {
        val builtIn = filterByPermission(permissionContext)
        val allowedMcp = mcpTools.filter { !isDeniedByRules(it, permissionContext) }
        val byName = { a: Tool<*, *>, b: Tool<*, *> -> a.name.compareTo(b.name) }
        return (builtIn.sortedWith(byName) + allowedMcp.sortedWith(byName)).distinctBy { it.name }
    }

    private fun isDeniedByRules(tool: Tool<*, *>, context: ToolPermissionContext): Boolean {
        val rules = context.alwaysDenyRules
        return rules.bash.any { matchesPattern(tool.name, it) } || rules.read.any { matchesPattern(tool.name, it) } || rules.write.any { matchesPattern(tool.name, it) } || rules.edit.any { matchesPattern(tool.name, it) } || rules.glob.any { matchesPattern(tool.name, it) } || rules.grep.any { matchesPattern(tool.name, it) } || rules.agent.any { matchesPattern(tool.name, it) } || rules.mcp.values.flatMap { it }.any { matchesPattern(tool.name, it) }
    }

    private fun matchesPattern(name: String, pattern: String): Boolean = when { pattern.endsWith("*") -> name.startsWith(pattern.dropLast(1)); pattern.contains("*") -> { val p = pattern.split("*"); p.size == 2 && name.startsWith(p[0]) && name.endsWith(p[1]) }; else -> name == pattern }

    companion object { fun createDefault(): ToolRegistry = ToolRegistry().apply {
        register(BashTool); register(FileReadTool); register(FileWriteTool); register(FileEditTool); register(GlobTool); register(GrepTool); register(AgentTool); register(TaskOutputTool); register(TaskStopTool); register(TodoWriteTool); register(SkillTool); register(EnterPlanModeTool); register(ExitPlanModeV2Tool); register(WebFetchTool); register(WebSearchTool); register(AskUserQuestionTool); register(BriefTool); register(NotebookEditTool); register(LSPTool); register(ConfigTool); register(TaskCreateTool); register(TaskGetTool); register(TaskUpdateTool); register(TaskListTool); register(ListMcpResourcesTool); register(ReadMcpResourceTool); register(ToolSearchTool); register(EnterWorktreeTool); register(ExitWorktreeTool)
    } }
}

private fun resolvePath(context: ToolUseContext, path: String): java.io.File {
    val file = java.io.File(path)
    return if (file.isAbsolute) file else java.io.File(context.options.cwd ?: System.getProperty("user.dir"), path)
}

object BashTool : Tool<BashInput, BashOutput> {
    override val name = "Bash"
    override val inputSchema = object : ToolInputSchema {}
    override val maxResultSizeChars = 80_000
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
            "description" to mapOf("type" to "string", "description" to "Short description of the command"),
            "timeout" to mapOf("type" to "integer", "description" to "Timeout in milliseconds"),
        ),
        required = listOf("command"),
    )

    override fun parseInput(input: Map<String, String>): BashInput = BashInput(
        command = input["command"] ?: error("Missing required field: command"),
        description = input["description"] ?: "",
        timeout = input["timeout"]?.toIntOrNull(),
    )

    override fun getToolUseSummary(input: BashInput): String = input.command.take(200)
    override suspend fun call(args: BashInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<BashOutput> {
        return try {
            val timeoutMs = args.timeout?.toLong() ?: 60_000L
            val process = ProcessBuilder(args.command.split(" "))
                .directory(java.io.File(context.options.cwd ?: System.getProperty("user.dir")))
                .redirectErrorStream(false)
                .start()

            val stdout = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderr = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                ToolResult(BashOutput("", "Timeout after ${timeoutMs}ms", -1, true))
            } else {
                val out = stdout.get()
                val err = stderr.get()
                ToolResult(BashOutput(out, err, process.exitValue()))
            }
        } catch (e: Exception) {
            ToolResult(BashOutput("", e.message ?: "Unknown error", -1, false))
        }
    }
    override suspend fun description(input: BashInput, options: DescriptionOptions): String = "Execute a bash command"
    override fun mapToolResultToToolResultBlockParam(content: BashOutput, toolUseID: String): ToolResultBlockParam {
        val failed = content.interrupted || content.exitCode != 0
        val text = buildString {
            if (content.stdout.isNotEmpty()) append(content.stdout)
            if (content.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(content.stderr)
            }
            if (isEmpty()) append(if (failed) "Command failed with exit code ${content.exitCode}" else "Command produced no output")
            if (failed && !content.interrupted) append("\n[exit code ${content.exitCode}]")
        }
        return ToolResultBlockParam(listOf(ResultContent.Text(text)), failed)
    }
    override fun prompt(options: PromptOptions): String = "Execute shell commands"
    override fun userFacingName(input: BashInput): String = "Bash"
    override fun toAutoClassifierInput(input: BashInput): Any = input.command
}

object FileReadTool : Tool<FileReadInput, FileReadOutput> {
    override val name = "Read"
    override val inputSchema = object : ToolInputSchema {}
    override val maxResultSizeChars = Int.MAX_VALUE
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "file_path" to mapOf("type" to "string", "description" to "Path to the file to read"),
            "offset" to mapOf("type" to "integer", "description" to "1-based line number to start from"),
            "limit" to mapOf("type" to "integer", "description" to "Maximum number of lines to read"),
        ),
        required = listOf("file_path"),
    )

    override fun parseInput(input: Map<String, String>): FileReadInput = FileReadInput(
        file_path = input["file_path"] ?: error("Missing required field: file_path"),
        offset = input["offset"]?.toIntOrNull(),
        limit = input["limit"]?.toIntOrNull(),
    )

    override fun getPath(input: FileReadInput): String? = input.file_path
    override fun getToolUseSummary(input: FileReadInput): String = input.file_path
    override suspend fun call(args: FileReadInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<FileReadOutput> {
        return try {
            val file = resolvePath(context, args.file_path)
            if (!file.exists()) {
                return ToolResult(FileReadOutput("Error: File not found: ${args.file_path}"))
            }
            if (!file.isFile) {
                return ToolResult(FileReadOutput("Error: Not a file: ${args.file_path}"))
            }
            val content = file.readText()
            val lines = content.lines()
            val startLine = (args.offset ?: 1).coerceAtLeast(1)
            val endLine = args.limit?.let { startLine + it - 1 } ?: lines.size
            val selectedLines = lines.drop(startLine - 1).take(endLine - startLine + 1)
            val numbered = selectedLines.mapIndexed { i, line -> "${startLine + i}: $line" }
            val result = numbered.joinToString("\n")
            ToolResult(FileReadOutput(result, numbered))
        } catch (e: Exception) {
            ToolResult(FileReadOutput("Error reading file: ${e.message}"))
        }
    }
    override suspend fun description(input: FileReadInput, options: DescriptionOptions): String = "Read a file"
    override fun mapToolResultToToolResultBlockParam(content: FileReadOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.content)))
    override fun prompt(options: PromptOptions): String = "Read files from the filesystem"
    override fun userFacingName(input: FileReadInput): String = "Read"
    override fun toAutoClassifierInput(input: FileReadInput): Any = input.file_path
    override fun isConcurrencySafe(input: FileReadInput): Boolean = true
    override fun isReadOnly(input: FileReadInput): Boolean = true
}

object FileWriteTool : Tool<FileWriteInput, FileWriteOutput> {
    override val name = "Write"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "file_path" to mapOf("type" to "string", "description" to "Path to the file to write"),
            "content" to mapOf("type" to "string", "description" to "Full file content"),
        ),
        required = listOf("file_path", "content"),
    )

    override fun parseInput(input: Map<String, String>): FileWriteInput = FileWriteInput(
        file_path = input["file_path"] ?: error("Missing required field: file_path"),
        content = input["content"] ?: error("Missing required field: content"),
    )

    override fun getPath(input: FileWriteInput): String? = input.file_path
    override fun getToolUseSummary(input: FileWriteInput): String = input.file_path
    override suspend fun call(args: FileWriteInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<FileWriteOutput> {
        return try {
            val file = resolvePath(context, args.file_path)
            file.parentFile?.mkdirs()
            file.writeText(args.content)
            ToolResult(FileWriteOutput(true))
        } catch (e: Exception) {
            ToolResult(FileWriteOutput(false))
        }
    }
    override suspend fun description(input: FileWriteInput, options: DescriptionOptions): String = "Write a file"
    override fun mapToolResultToToolResultBlockParam(content: FileWriteOutput, toolUseID: String): ToolResultBlockParam =
        ToolResultBlockParam(
            listOf(ResultContent.Text(if (content.success) "File written" else "Failed to write file")),
            isError = !content.success,
        )
    override fun prompt(options: PromptOptions): String = "Write files to the filesystem"
    override fun userFacingName(input: FileWriteInput): String = "Write"
    override fun toAutoClassifierInput(input: FileWriteInput): Any = "${input.file_path}: new content"
    override fun isDestructive(input: FileWriteInput): Boolean = true
}

object FileEditTool : Tool<FileEditInput, FileEditOutput> {
    override val name = "Edit"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "file_path" to mapOf("type" to "string", "description" to "Path to the file to edit"),
            "old_string" to mapOf("type" to "string", "description" to "Exact text to replace"),
            "new_string" to mapOf("type" to "string", "description" to "Replacement text"),
            "replace_all" to mapOf("type" to "boolean", "description" to "Replace every occurrence"),
        ),
        required = listOf("file_path", "old_string", "new_string"),
    )

    override fun parseInput(input: Map<String, String>): FileEditInput = FileEditInput(
        file_path = input["file_path"] ?: error("Missing required field: file_path"),
        old_string = input["old_string"] ?: error("Missing required field: old_string"),
        new_string = input["new_string"] ?: error("Missing required field: new_string"),
        replace_all = input["replace_all"]?.toBooleanStrictOrNull() ?: false,
    )

    override fun getPath(input: FileEditInput): String? = input.file_path
    override fun getToolUseSummary(input: FileEditInput): String = input.file_path
    override suspend fun call(args: FileEditInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<FileEditOutput> {
        return try {
            val file = resolvePath(context, args.file_path)
            if (!file.exists()) {
                return ToolResult(FileEditOutput(false, listOf("Error: File not found: ${args.file_path}")))
            }
            val content = file.readText()
            if (!content.contains(args.old_string)) {
                return ToolResult(FileEditOutput(false, listOf("Error: old_string not found in file")))
            }
            val newContent = if (args.replace_all) {
                content.replace(args.old_string, args.new_string)
            } else {
                content.replaceFirst(args.old_string, args.new_string)
            }
            file.writeText(newContent)
            val diffs = listOf("Replaced in ${args.file_path}")
            ToolResult(FileEditOutput(true, diffs))
        } catch (e: Exception) {
            ToolResult(FileEditOutput(false, listOf("Error: ${e.message}")))
        }
    }
    override suspend fun description(input: FileEditInput, options: DescriptionOptions): String = "Edit a file"
    override fun mapToolResultToToolResultBlockParam(content: FileEditOutput, toolUseID: String): ToolResultBlockParam {
        val text = if (content.success) {
            content.diffs.joinToString("\n").ifBlank { "File edited" }
        } else {
            content.diffs.joinToString("\n").ifBlank { "Failed to edit file" }
        }
        return ToolResultBlockParam(listOf(ResultContent.Text(text)), isError = !content.success)
    }
    override fun prompt(options: PromptOptions): String = "Edit files"
    override fun userFacingName(input: FileEditInput): String = "Edit"
    override fun toAutoClassifierInput(input: FileEditInput): Any = "${input.file_path}: edit"
    override fun isDestructive(input: FileEditInput): Boolean = true
}

object GlobTool : Tool<GlobInput, GlobOutput> {
    override val name = "Glob"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "pattern" to mapOf("type" to "string", "description" to "Glob pattern, e.g. **/*.kt"),
            "path" to mapOf("type" to "string", "description" to "Directory to search (default: .)"),
        ),
        required = listOf("pattern"),
    )

    override fun parseInput(input: Map<String, String>): GlobInput = GlobInput(
        pattern = input["pattern"] ?: error("Missing required field: pattern"),
        path = input["path"] ?: ".",
    )

    override fun getToolUseSummary(input: GlobInput): String = "${input.pattern} in ${input.path}"
    override suspend fun call(args: GlobInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<GlobOutput> {
        return try {
            val baseDir = resolvePath(context, args.path)
            if (!baseDir.exists() || !baseDir.isDirectory) {
                return ToolResult(GlobOutput(emptyList()))
            }
            val pattern = args.pattern
            val results = mutableListOf<String>()
            val regex = globToRegex(pattern)
            baseDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relPath = file.relativeTo(baseDir).path
                if (regex.matches(relPath)) {
                    results.add(file.absolutePath)
                }
            }
            ToolResult(GlobOutput(results.sorted()))
        } catch (e: Exception) {
            ToolResult(GlobOutput(emptyList()))
        }
    }
    override suspend fun description(input: GlobInput, options: DescriptionOptions): String = "Find files by pattern"
    override fun mapToolResultToToolResultBlockParam(content: GlobOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.files.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "Find files by glob pattern"
    override fun userFacingName(input: GlobInput): String = "Glob"
    override fun toAutoClassifierInput(input: GlobInput): Any = input.pattern
    override fun isConcurrencySafe(input: GlobInput): Boolean = true
    override fun isReadOnly(input: GlobInput): Boolean = true
    private fun globToRegex(pattern: String): Regex {
        val escaped = Regex.escape(pattern)
            .replace("\\*\\*", "§DOUBLESTAR§")
            .replace("\\*", "[^/]*")
            .replace("§DOUBLESTAR§", ".*")
            .replace("\\?", ".")
        return Regex("^$escaped$")
    }
}

object GrepTool : Tool<GrepInput, GrepOutput> {
    override val name = "Grep"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "pattern" to mapOf("type" to "string", "description" to "Regular expression to search for"),
            "path" to mapOf("type" to "string", "description" to "Directory to search (default: .)"),
            "include" to mapOf("type" to "string", "description" to "File name filter regex"),
        ),
        required = listOf("pattern"),
    )

    override fun parseInput(input: Map<String, String>): GrepInput = GrepInput(
        pattern = input["pattern"] ?: error("Missing required field: pattern"),
        path = input["path"] ?: ".",
        include = input["include"],
    )

    override fun getToolUseSummary(input: GrepInput): String = "${input.pattern} in ${input.path}"
    override suspend fun call(args: GrepInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<GrepOutput> {
        return try {
            val regex = try { Regex(args.pattern) } catch (e: Exception) { return ToolResult(GrepOutput(emptyList())) }
            val baseDir = resolvePath(context, args.path)
            if (!baseDir.exists() || !baseDir.isDirectory) {
                return ToolResult(GrepOutput(emptyList()))
            }
            val matches = mutableListOf<String>()
            val includeRegex = args.include?.let { Regex(it) }
            baseDir.walkTopDown().filter { it.isFile }.forEach { file ->
                if (includeRegex != null && !includeRegex.matches(file.name)) return@forEach
                try {
                    file.readLines().forEachIndexed { lineNum, line ->
                        if (regex.containsMatchIn(line)) {
                            matches.add("${file.absolutePath}:${lineNum + 1}:${line.trim()}")
                        }
                    }
                } catch (e: Exception) { /* skip binary files */ }
            }
            ToolResult(GrepOutput(matches))
        } catch (e: Exception) {
            ToolResult(GrepOutput(emptyList()))
        }
    }
    override suspend fun description(input: GrepInput, options: DescriptionOptions): String = "Search for a pattern"
    override fun mapToolResultToToolResultBlockParam(content: GrepOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.matches.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "Search for patterns in files"
    override fun userFacingName(input: GrepInput): String = "Grep"
    override fun toAutoClassifierInput(input: GrepInput): Any = "${input.pattern} in ${input.path}"
    override fun isConcurrencySafe(input: GrepInput): Boolean = true
    override fun isReadOnly(input: GrepInput): Boolean = true
}

object AgentTool : Tool<AgentInput, AgentOutput> {
    override val name = "Agent"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: AgentInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<AgentOutput> = ToolResult(AgentOutput("", "", ""))
    override suspend fun description(input: AgentInput, options: DescriptionOptions): String = "Launch a subagent"
    override fun mapToolResultToToolResultBlockParam(content: AgentOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.result)))
    override fun prompt(options: PromptOptions): String = "Launch subagents for complex tasks"
    override fun userFacingName(input: AgentInput): String = "Agent"
    override fun toAutoClassifierInput(input: AgentInput): Any = input.description
}

object TaskOutputTool : Tool<TaskOutputInput, TaskOutputOutput> {
    override val name = "TaskOutput"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: TaskOutputInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskOutputOutput> = ToolResult(TaskOutputOutput("", "", emptyList()))
    override suspend fun description(input: TaskOutputInput, options: DescriptionOptions): String = "Get task output"
    override fun mapToolResultToToolResultBlockParam(content: TaskOutputOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.output)))
    override fun prompt(options: PromptOptions): String = "Get output from background tasks"
    override fun userFacingName(input: TaskOutputInput): String = "TaskOutput"
    override fun toAutoClassifierInput(input: TaskOutputInput): Any = input.task_id
}

object TaskStopTool : Tool<TaskStopInput, TaskStopOutput> {
    override val name = "TaskStop"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: TaskStopInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskStopOutput> = ToolResult(TaskStopOutput(true))
    override suspend fun description(input: TaskStopInput, options: DescriptionOptions): String = "Stop a task"
    override fun mapToolResultToToolResultBlockParam(content: TaskStopOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("Task stopped")))
    override fun prompt(options: PromptOptions): String = "Stop running tasks"
    override fun userFacingName(input: TaskStopInput): String = "TaskStop"
    override fun toAutoClassifierInput(input: TaskStopInput): Any = input.task_id
}

object TodoWriteTool : Tool<TodoWriteInput, TodoWriteOutput> {
    override val name = "TodoWrite"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "todos" to mapOf(
                "type" to "array",
                "description" to "Complete todo list to store for this project",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "content" to mapOf("type" to "string"),
                        "status" to mapOf("type" to "string", "enum" to listOf("pending", "in_progress", "completed", "cancelled")),
                        "activeForm" to mapOf("type" to "string"),
                    ),
                ),
            ),
        ),
        required = listOf("todos"),
    )

    override fun parseInput(input: Map<String, String>): TodoWriteInput = TodoWriteInput(
        toolJson.decodeFromString(input["todos"] ?: error("Missing required field: todos")),
    )

    override suspend fun call(args: TodoWriteInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TodoWriteOutput> = try {
        TaskStores.forContext(context).replaceTodos(args.todos)
        ToolResult(TodoWriteOutput(true, args.todos.size))
    } catch (e: Exception) {
        ToolResult(TodoWriteOutput(false, error = e.message ?: "Failed to write todos"))
    }

    override suspend fun description(input: TodoWriteInput, options: DescriptionOptions): String = "Update todo list"
    override fun mapToolResultToToolResultBlockParam(content: TodoWriteOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.error ?: "Stored ${content.count} todo(s)")),
        isError = !content.success,
    )
    override fun prompt(options: PromptOptions): String = "Track task progress with todos"
    override fun userFacingName(input: TodoWriteInput): String = "TodoWrite"
    override fun getToolUseSummary(input: TodoWriteInput): String = "Write ${input.todos.size} todos"
    override fun toAutoClassifierInput(input: TodoWriteInput): Any = input.todos.map { it.content }
    override fun isConcurrencySafe(input: TodoWriteInput): Boolean = false
}

object SkillTool : Tool<SkillInput, SkillOutput> {
    override val name = "Skill"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: SkillInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<SkillOutput> = ToolResult(SkillOutput("", emptyList()))
    override suspend fun description(input: SkillInput, options: DescriptionOptions): String = "Use a skill"
    override fun mapToolResultToToolResultBlockParam(content: SkillOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.content)))
    override fun prompt(options: PromptOptions): String = "Access specialized skills"
    override fun userFacingName(input: SkillInput): String = "Skill"
    override fun toAutoClassifierInput(input: SkillInput): Any = input.skill_name
}

object EnterPlanModeTool : Tool<EnterPlanModeInput, EnterPlanModeOutput> {
    override val name = "EnterPlanMode"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: EnterPlanModeInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<EnterPlanModeOutput> = ToolResult(EnterPlanModeOutput(true))
    override suspend fun description(input: EnterPlanModeInput, options: DescriptionOptions): String = "Enter plan mode"
    override fun mapToolResultToToolResultBlockParam(content: EnterPlanModeOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("Entered plan mode")))
    override fun prompt(options: PromptOptions): String = "Enter plan mode for complex tasks"
    override fun userFacingName(input: EnterPlanModeInput): String = "EnterPlanMode"
    override fun toAutoClassifierInput(input: EnterPlanModeInput): Any = input.prompt
}

object ExitPlanModeV2Tool : Tool<ExitPlanModeInput, ExitPlanModeOutput> {
    override val name = "ExitPlanMode"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: ExitPlanModeInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ExitPlanModeOutput> = ToolResult(ExitPlanModeOutput(true, ""))
    override suspend fun description(input: ExitPlanModeInput, options: DescriptionOptions): String = "Exit plan mode"
    override fun mapToolResultToToolResultBlockParam(content: ExitPlanModeOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.plan)))
    override fun prompt(options: PromptOptions): String = "Exit plan mode"
    override fun userFacingName(input: ExitPlanModeInput): String = "ExitPlanMode"
    override fun toAutoClassifierInput(input: ExitPlanModeInput): Any = input.plan
}

object WebFetchTool : Tool<WebFetchInput, WebFetchOutput> {
    internal var transport: WebFetchTransport = JdkWebFetchTransport()

    override val name = "WebFetch"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val maxResultSizeChars = WebFetchPolicy.MAX_CHARS
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "url" to mapOf("type" to "string", "description" to "Public http/https URL to fetch"),
            "prompt" to mapOf("type" to "string", "description" to "Optional note recorded in metadata; no model-side summarization is performed"),
            "timeout" to mapOf("type" to "integer", "description" to "Timeout in milliseconds, clamped to 1000..60000"),
        ),
        required = listOf("url"),
    )

    override fun parseInput(input: Map<String, String>) = WebFetchInput(
        url = input["url"] ?: error("Missing required field: url"),
        prompt = input["prompt"] ?: "",
        timeout = input["timeout"]?.toIntOrNull() ?: 30_000,
    )

    override suspend fun validateInput(input: WebFetchInput, context: ToolUseContext): ValidationResult = try {
        WebFetchPolicy.validate(input.url)
        ValidationResult.SUCCESS
    } catch (e: Exception) {
        ValidationResult.failure(e.message ?: "Invalid WebFetch URL")
    }

    override suspend fun call(args: WebFetchInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<WebFetchOutput> = try {
        WebFetchPolicy.validate(args.url)
        val response = withContext(Dispatchers.IO) { transport.fetch(args.url, args.timeout) }
        val metadata = mutableMapOf(
            "status" to response.statusCode.toString(),
            "contentType" to response.contentType,
            "url" to response.finalUrl,
            "truncated" to response.truncated.toString(),
        )
        if (args.prompt.isNotBlank()) metadata["prompt"] = args.prompt
        ToolResult(WebFetchOutput(response.body, metadata))
    } catch (e: Exception) {
        ToolResult(WebFetchOutput("", error = e.message ?: "WebFetch failed"))
    }

    override suspend fun description(input: WebFetchInput, options: DescriptionOptions): String = "Fetch public web content"
    override fun mapToolResultToToolResultBlockParam(content: WebFetchOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.error ?: content.content)),
        isError = content.error != null,
    )
    override fun prompt(options: PromptOptions): String = "Fetch public HTTP and HTTPS pages as plain text"
    override fun userFacingName(input: WebFetchInput): String = "WebFetch"
    override fun getToolUseSummary(input: WebFetchInput): String = input.url
    override fun toAutoClassifierInput(input: WebFetchInput): Any = input.url
    override fun isOpenWorld(input: WebFetchInput): Boolean = true
    override fun isReadOnly(input: WebFetchInput): Boolean = true
    override fun isConcurrencySafe(input: WebFetchInput): Boolean = true
}

object WebSearchTool : Tool<WebSearchInput, WebSearchOutput> {
    override val name = "WebSearch"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: WebSearchInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<WebSearchOutput> = ToolResult(WebSearchOutput(emptyList()))
    override suspend fun description(input: WebSearchInput, options: DescriptionOptions): String = "Search the web"
    override fun mapToolResultToToolResultBlockParam(content: WebSearchOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.results.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "Search the web"
    override fun userFacingName(input: WebSearchInput): String = "WebSearch"
    override fun toAutoClassifierInput(input: WebSearchInput): Any = input.query
    override fun isOpenWorld(input: WebSearchInput): Boolean = true
}

object AskUserQuestionTool : Tool<AskUserQuestionInput, AskUserQuestionOutput> {
    override val name = "AskUserQuestion"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: AskUserQuestionInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<AskUserQuestionOutput> = ToolResult(AskUserQuestionOutput("", emptyList()))
    override suspend fun description(input: AskUserQuestionInput, options: DescriptionOptions): String = "Ask user a question"
    override fun mapToolResultToToolResultBlockParam(content: AskUserQuestionOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.answer)))
    override fun prompt(options: PromptOptions): String = "Ask questions to the user"
    override fun userFacingName(input: AskUserQuestionInput): String = "AskUserQuestion"
    override fun requiresUserInteraction(): Boolean = true
    override fun toAutoClassifierInput(input: AskUserQuestionInput): Any = input.question
}

object BriefTool : Tool<BriefInput, BriefOutput> {
    override val name = "Brief"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf("content" to mapOf("type" to "string", "description" to "Text to return as a brief response")),
        required = listOf("content"),
    )
    override fun parseInput(input: Map<String, String>) = BriefInput(input["content"] ?: error("Missing required field: content"))
    override suspend fun call(args: BriefInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<BriefOutput> = ToolResult(BriefOutput(args.content))
    override suspend fun description(input: BriefInput, options: DescriptionOptions): String = "Provide a brief response"
    override fun mapToolResultToToolResultBlockParam(content: BriefOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.content)))
    override fun prompt(options: PromptOptions): String = "Provide brief responses"
    override fun userFacingName(input: BriefInput): String = "Brief"
    override fun getToolUseSummary(input: BriefInput): String = input.content.take(120)
    override fun isReadOnly(input: BriefInput): Boolean = true
    override fun isConcurrencySafe(input: BriefInput): Boolean = true
    override fun toAutoClassifierInput(input: BriefInput): Any = input.content
}

object NotebookEditTool : Tool<NotebookEditInput, NotebookEditOutput> {
    override val name = "NotebookEdit"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "notebook_path" to mapOf("type" to "string", "description" to "Path to a .ipynb file"),
            "cell_id" to mapOf("type" to "string", "description" to "Cell id, or zero-based index; omit to append a cell"),
            "new_source" to mapOf("type" to "string", "description" to "Replacement cell source"),
        ),
        required = listOf("notebook_path", "new_source"),
    )

    override fun parseInput(input: Map<String, String>) = NotebookEditInput(
        notebook_path = input["notebook_path"] ?: error("Missing required field: notebook_path"),
        cell_id = input["cell_id"],
        new_source = input["new_source"] ?: error("Missing required field: new_source"),
    )

    override fun getPath(input: NotebookEditInput): String? = input.notebook_path
    override fun getToolUseSummary(input: NotebookEditInput): String = "${input.notebook_path}${input.cell_id?.let { " cell $it" } ?: " append"}"
    override fun isDestructive(input: NotebookEditInput): Boolean = true

    override suspend fun call(args: NotebookEditInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<NotebookEditOutput> = try {
        val file = resolvePath(context, args.notebook_path)
        if (!file.isFile) throw IllegalArgumentException("Notebook not found: ${args.notebook_path}")
        val root = toolJson.parseToJsonElement(file.readText()) as? JsonObject
            ?: throw IllegalArgumentException("Notebook root must be a JSON object")
        val cells = root["cells"] as? JsonArray
            ?: throw IllegalArgumentException("Notebook has no 'cells' array")
        val index = args.cell_id?.let { id ->
            id.toIntOrNull()?.takeIf { it in cells.indices }
                ?: cells.indexOfFirst { cell -> cell is JsonObject && (cell["id"] as? JsonPrimitive)?.content == id }
                    .takeIf { it >= 0 }
        }
        val source = JsonArray(args.new_source.lines().map { JsonPrimitive(it) })
        val updatedCells = if (index == null) {
            cells + buildJsonObject {
                put("cell_type", JsonPrimitive("code"))
                put("metadata", JsonObject(emptyMap()))
                put("execution_count", JsonNull)
                put("outputs", JsonArray(emptyList()))
                put("source", source)
                args.cell_id?.let { put("id", JsonPrimitive(it)) }
            }
        } else {
            cells.mapIndexed { i, cell ->
                if (i != index) cell else buildJsonObject {
                    (cell as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                    put("source", source)
                }
            }
        }
        val updated = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("cells", JsonArray(updatedCells))
        }
        file.writeText(updated.toString())
        ToolResult(NotebookEditOutput(true))
    } catch (e: Exception) {
        ToolResult(NotebookEditOutput(false))
    }

    override suspend fun description(input: NotebookEditInput, options: DescriptionOptions): String = "Edit a notebook"
    override fun mapToolResultToToolResultBlockParam(content: NotebookEditOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(if (content.success) "Notebook edited" else "Failed to edit notebook")),
        isError = !content.success,
    )
    override fun prompt(options: PromptOptions): String = "Edit Jupyter notebooks"
    override fun userFacingName(input: NotebookEditInput): String = "NotebookEdit"
    override fun toAutoClassifierInput(input: NotebookEditInput): Any = input.notebook_path
}

object LSPTool : Tool<LSPInput, LSPOutput> {
    override val name = "LSP"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: LSPInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<LSPOutput> = ToolResult(LSPOutput(emptyList()))
    override suspend fun description(input: LSPInput, options: DescriptionOptions): String = "LSP query"
    override fun mapToolResultToToolResultBlockParam(content: LSPOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.results.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "Language Server Protocol"
    override fun userFacingName(input: LSPInput): String = "LSP"
    override fun toAutoClassifierInput(input: LSPInput): Any = input.command
}

object ConfigTool : Tool<ConfigInput, ConfigOutput> {
    override val name = "Config"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: ConfigInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ConfigOutput> = ToolResult(ConfigOutput("", ""))
    override suspend fun description(input: ConfigInput, options: DescriptionOptions): String = "Update config"
    override fun mapToolResultToToolResultBlockParam(content: ConfigOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.value)))
    override fun prompt(options: PromptOptions): String = "Update configuration"
    override fun userFacingName(input: ConfigInput): String = "Config"
    override fun toAutoClassifierInput(input: ConfigInput): Any = "${input.key}=${input.value}"
}

object TaskCreateTool : Tool<TaskCreateInput, TaskCreateOutput> {
    override val name = "TaskCreate"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "subject" to mapOf("type" to "string", "description" to "Short task title"),
            "description" to mapOf("type" to "string"),
            "activeForm" to mapOf("type" to "string"),
        ),
        required = listOf("subject"),
    )
    override fun parseInput(input: Map<String, String>) = TaskCreateInput(
        subject = input["subject"] ?: error("Missing required field: subject"),
        description = input["description"] ?: "",
        activeForm = input["activeForm"] ?: input["active_form"] ?: "",
    )
    override suspend fun call(args: TaskCreateInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskCreateOutput> = try {
        val task = TaskStores.forContext(context).createTask(args.subject, args.description, args.activeForm)
        ToolResult(TaskCreateOutput(task.id, task.todos))
    } catch (e: Exception) {
        ToolResult(TaskCreateOutput("", error = e.message ?: "Failed to create task"))
    }
    override suspend fun description(input: TaskCreateInput, options: DescriptionOptions): String = "Create a task"
    override fun mapToolResultToToolResultBlockParam(content: TaskCreateOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.error ?: "Created task ${content.task_id}")),
        isError = content.task_id.isBlank(),
    )
    override fun prompt(options: PromptOptions): String = "Create tasks"
    override fun userFacingName(input: TaskCreateInput): String = "TaskCreate"
    override fun getToolUseSummary(input: TaskCreateInput): String = input.subject
    override fun toAutoClassifierInput(input: TaskCreateInput): Any = input.subject
}

object TaskGetTool : Tool<TaskGetInput, TaskGetOutput> {
    override val name = "TaskGet"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf("task_id" to mapOf("type" to "string", "description" to "Task id returned by TaskCreate")),
        required = listOf("task_id"),
    )
    override fun parseInput(input: Map<String, String>) = TaskGetInput(input["task_id"] ?: error("Missing required field: task_id"))
    override suspend fun call(args: TaskGetInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskGetOutput> {
        val task = TaskStores.forContext(context).getTask(args.task_id)
        return if (task == null) ToolResult(TaskGetOutput(args.task_id, "not_found", found = false))
        else ToolResult(TaskGetOutput(task.id, task.status, task.todos, task.subject, true))
    }
    override suspend fun description(input: TaskGetInput, options: DescriptionOptions): String = "Get task"
    override fun mapToolResultToToolResultBlockParam(content: TaskGetOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(buildString {
            append("${content.task_id}: ${content.status}")
            if (content.subject.isNotEmpty()) append(" · ${content.subject}")
            content.todos.forEach { append("\n- [${it.status}] ${it.content}") }
            content.error?.let { append("\n$it") }
        })),
        isError = !content.found,
    )
    override fun prompt(options: PromptOptions): String = "Get task status"
    override fun userFacingName(input: TaskGetInput): String = "TaskGet"
    override fun getToolUseSummary(input: TaskGetInput): String = input.task_id
    override fun isReadOnly(input: TaskGetInput): Boolean = true
    override fun isConcurrencySafe(input: TaskGetInput): Boolean = true
    override fun toAutoClassifierInput(input: TaskGetInput): Any = input.task_id
}

object TaskUpdateTool : Tool<TaskUpdateInput, TaskUpdateOutput> {
    override val name = "TaskUpdate"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf(
            "task_id" to mapOf("type" to "string"),
            "status" to mapOf("type" to "string", "enum" to listOf("pending", "in_progress", "completed", "cancelled")),
            "activeForm" to mapOf("type" to "string"),
        ),
        required = listOf("task_id", "status"),
    )
    override fun parseInput(input: Map<String, String>) = TaskUpdateInput(
        task_id = input["task_id"] ?: error("Missing required field: task_id"),
        status = input["status"] ?: error("Missing required field: status"),
        activeForm = input["activeForm"] ?: input["active_form"],
    )
    override suspend fun call(args: TaskUpdateInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskUpdateOutput> = try {
        val updated = TaskStores.forContext(context).updateTask(args.task_id, args.status, args.activeForm)
        ToolResult(
            if (updated == null) TaskUpdateOutput(false, "Task not found: ${args.task_id}")
            else TaskUpdateOutput(true, "Task ${updated.id} is ${updated.status}"),
        )
    } catch (e: Exception) {
        ToolResult(TaskUpdateOutput(false, e.message ?: "Failed to update task"))
    }
    override suspend fun description(input: TaskUpdateInput, options: DescriptionOptions): String = "Update task"
    override fun mapToolResultToToolResultBlockParam(content: TaskUpdateOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.message)), isError = !content.success,
    )
    override fun prompt(options: PromptOptions): String = "Update task status"
    override fun userFacingName(input: TaskUpdateInput): String = "TaskUpdate"
    override fun getToolUseSummary(input: TaskUpdateInput): String = "${input.task_id} → ${input.status}"
    override fun toAutoClassifierInput(input: TaskUpdateInput): Any = "${input.task_id}: ${input.status}"
}

object TaskListTool : Tool<TaskListInput, TaskListOutput> {
    override val name = "TaskList"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf("filter" to mapOf("type" to "string", "description" to "all, active, pending, in_progress, completed or cancelled")),
        required = listOf("filter"),
    )
    override fun parseInput(input: Map<String, String>) = TaskListInput(input["filter"] ?: "all")
    override suspend fun call(args: TaskListInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskListOutput> {
        val tasks = TaskStores.forContext(context).listTasks(args.filter).map {
            "${it.id} · ${it.status} · ${it.subject}" + if (it.activeForm.isNotEmpty()) " · ${it.activeForm}" else ""
        }
        return ToolResult(TaskListOutput(tasks))
    }
    override suspend fun description(input: TaskListInput, options: DescriptionOptions): String = "List tasks"
    override fun mapToolResultToToolResultBlockParam(content: TaskListOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.error ?: content.tasks.joinToString("\n").ifBlank { "No tasks" })),
        isError = content.error != null,
    )
    override fun prompt(options: PromptOptions): String = "List tasks"
    override fun userFacingName(input: TaskListInput): String = "TaskList"
    override fun getToolUseSummary(input: TaskListInput): String = "List ${input.filter} tasks"
    override fun isReadOnly(input: TaskListInput): Boolean = true
    override fun isConcurrencySafe(input: TaskListInput): Boolean = true
    override fun toAutoClassifierInput(input: TaskListInput): Any = input.filter
}

object ListMcpResourcesTool : Tool<ListMcpResourcesInput, ListMcpResourcesOutput> {
    override val name = "ListMcpResources"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf("server_name" to mapOf("type" to "string", "description" to "MCP server name, or * for all")),
        required = listOf("server_name"),
    )
    override fun parseInput(input: Map<String, String>) = ListMcpResourcesInput(input["server_name"] ?: "*")
    override suspend fun call(args: ListMcpResourcesInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ListMcpResourcesOutput> {
        val resources = context.options.mcpResources
            .filter { args.server_name.isBlank() || args.server_name == "*" || it.key == args.server_name }
            .flatMap { (server, list) -> list.map { "$server:${it.uri} · ${it.name}${it.mimeType?.let { mime -> " · $mime" } ?: ""}" } }
            .sorted()
        return ToolResult(ListMcpResourcesOutput(resources))
    }
    override suspend fun description(input: ListMcpResourcesInput, options: DescriptionOptions): String = "List MCP resources"
    override fun mapToolResultToToolResultBlockParam(content: ListMcpResourcesOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.error ?: content.resources.joinToString("\n").ifBlank { "No MCP resources configured" })),
        isError = content.error != null,
    )
    override fun prompt(options: PromptOptions): String = "List MCP resources"
    override fun userFacingName(input: ListMcpResourcesInput): String = "ListMcpResources"
    override fun getToolUseSummary(input: ListMcpResourcesInput): String = input.server_name
    override fun isReadOnly(input: ListMcpResourcesInput): Boolean = true
    override fun isConcurrencySafe(input: ListMcpResourcesInput): Boolean = true
    override fun toAutoClassifierInput(input: ListMcpResourcesInput): Any = input.server_name
}

object ReadMcpResourceTool : Tool<ReadMcpResourceInput, ReadMcpResourceOutput> {
    override val name = "ReadMcpResource"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf("uri" to mapOf("type" to "string", "description" to "Configured MCP resource URI")),
        required = listOf("uri"),
    )
    override fun parseInput(input: Map<String, String>) = ReadMcpResourceInput(input["uri"] ?: error("Missing required field: uri"))
    override suspend fun call(args: ReadMcpResourceInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ReadMcpResourceOutput> {
        val match = context.options.mcpResources.values.flatten().firstOrNull {
            it.uri == args.uri || it.name == args.uri
        }
        return when {
            match == null -> ToolResult(ReadMcpResourceOutput("", false, "MCP resource not configured: ${args.uri}"))
            match.text == null -> ToolResult(ReadMcpResourceOutput("", false, "MCP resource has no text content: ${args.uri}"))
            else -> ToolResult(ReadMcpResourceOutput(match.text, true))
        }
    }
    override suspend fun description(input: ReadMcpResourceInput, options: DescriptionOptions): String = "Read MCP resource"
    override fun mapToolResultToToolResultBlockParam(content: ReadMcpResourceOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.error ?: content.content)), isError = !content.found,
    )
    override fun prompt(options: PromptOptions): String = "Read MCP resources"
    override fun userFacingName(input: ReadMcpResourceInput): String = "ReadMcpResource"
    override fun getToolUseSummary(input: ReadMcpResourceInput): String = input.uri
    override fun isReadOnly(input: ReadMcpResourceInput): Boolean = true
    override fun isConcurrencySafe(input: ReadMcpResourceInput): Boolean = true
    override fun toAutoClassifierInput(input: ReadMcpResourceInput): Any = input.uri
}

object ToolSearchTool : Tool<ToolSearchInput, ToolSearchOutput> {
    override val name = "ToolSearch"
    override val inputSchema = object : ToolInputSchema {}
    override val isImplemented = true
    override val inputJSONSchema = ToolInputJSONSchema(
        properties = mapOf("query" to mapOf("type" to "string", "description" to "Tool name or capability text")),
        required = listOf("query"),
    )
    override fun parseInput(input: Map<String, String>) = ToolSearchInput(input["query"] ?: error("Missing required field: query"))
    override suspend fun call(args: ToolSearchInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ToolSearchOutput> {
        val query = args.query.trim().lowercase()
        val promptOptions = PromptOptions(
            { context.options.toolPermissionContext },
            context.options.tools,
            context.options.agentDefinitions.allAgents,
        )
        val matches = context.options.tools
            .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.prompt(promptOptions).lowercase().contains(query) }
            .take(20)
            .map { "${it.name} · ${it.prompt(promptOptions)}" }
        return ToolResult(ToolSearchOutput(matches))
    }
    override suspend fun description(input: ToolSearchInput, options: DescriptionOptions): String = "Search tools"
    override fun mapToolResultToToolResultBlockParam(content: ToolSearchOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(
        listOf(ResultContent.Text(content.tools.joinToString("\n").ifBlank { "No matching tools" })),
    )
    override fun prompt(options: PromptOptions): String = "Search for tools"
    override fun userFacingName(input: ToolSearchInput): String = "ToolSearch"
    override fun getToolUseSummary(input: ToolSearchInput): String = input.query
    override fun isReadOnly(input: ToolSearchInput): Boolean = true
    override fun isConcurrencySafe(input: ToolSearchInput): Boolean = true
    override fun toAutoClassifierInput(input: ToolSearchInput): Any = input.query
}

object EnterWorktreeTool : Tool<EnterWorktreeInput, EnterWorktreeOutput> {
    override val name = "EnterWorktree"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: EnterWorktreeInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<EnterWorktreeOutput> = ToolResult(EnterWorktreeOutput(true, ""))
    override suspend fun description(input: EnterWorktreeInput, options: DescriptionOptions): String = "Enter worktree"
    override fun mapToolResultToToolResultBlockParam(content: EnterWorktreeOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.path)))
    override fun prompt(options: PromptOptions): String = "Enter git worktree"
    override fun userFacingName(input: EnterWorktreeInput): String = "EnterWorktree"
    override fun toAutoClassifierInput(input: EnterWorktreeInput): Any = input.path
}

object ExitWorktreeTool : Tool<ExitWorktreeInput, ExitWorktreeOutput> {
    override val name = "ExitWorktree"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: ExitWorktreeInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ExitWorktreeOutput> = ToolResult(ExitWorktreeOutput(true))
    override suspend fun description(input: ExitWorktreeInput, options: DescriptionOptions): String = "Exit worktree"
    override fun mapToolResultToToolResultBlockParam(content: ExitWorktreeOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("Exited worktree")))
    override fun prompt(options: PromptOptions): String = "Exit worktree"
    override fun userFacingName(input: ExitWorktreeInput): String = "ExitWorktree"
    override fun toAutoClassifierInput(input: ExitWorktreeInput): Any = ""
}

data class BashInput(val command: String, val description: String = "", val timeout: Int? = null, val agentId: String? = null, val kind: String = "bash")
data class BashOutput(val stdout: String, val stderr: String = "", val exitCode: Int, val interrupted: Boolean = false)
data class FileReadInput(val file_path: String, val offset: Int? = null, val limit: Int? = null)
data class FileReadOutput(val content: String, val lines: List<String> = emptyList())
data class FileWriteInput(val file_path: String, val content: String)
data class FileWriteOutput(val success: Boolean)
data class FileEditInput(val file_path: String, val old_string: String, val new_string: String, val replace_all: Boolean = false)
data class FileEditOutput(val success: Boolean, val diffs: List<String> = emptyList())
data class GlobInput(val pattern: String, val path: String = ".")
data class GlobOutput(val files: List<String>)
data class GrepInput(val pattern: String, val path: String = ".", val include: String? = null)
data class GrepOutput(val matches: List<String>)
data class AgentInput(val description: String, val prompt: String, val model: String? = null, val agent_type: String? = null, val subagent_type: String? = null)
data class AgentOutput(val agentId: String, val model: String, val result: String, val usage: List<String> = emptyList())
data class TaskOutputInput(val task_id: String, val block: Boolean = false, val timeout: Int = 30000)
data class TaskOutputOutput(val output: String, val status: String, val messages: List<String> = emptyList())
data class TaskStopInput(val task_id: String, val reason: String = "")
data class TaskStopOutput(val success: Boolean)
data class TodoWriteInput(val todos: List<TodoItem>)
data class TodoWriteOutput(val success: Boolean, val count: Int = 0, val error: String? = null)
@Serializable
data class TodoItem(val content: String, val status: String = "pending", val activeForm: String = "")
data class SkillInput(val skill_name: String, val args: Map<String, String> = emptyMap())
data class SkillOutput(val content: String, val scripts: List<String> = emptyList())
data class EnterPlanModeInput(val prompt: String, val plan: String = "")
data class EnterPlanModeOutput(val entered: Boolean)
data class ExitPlanModeInput(val plan: String)
data class ExitPlanModeOutput(val success: Boolean, val plan: String)
data class WebFetchInput(val url: String, val prompt: String = "", val timeout: Int = 30000)
data class WebFetchOutput(val content: String, val metadata: Map<String, String> = emptyMap(), val error: String? = null)
data class WebSearchInput(val query: String, val max_results: Int = 10)
data class WebSearchOutput(val results: List<WebSearchResult>)
data class WebSearchResult(val title: String, val url: String, val snippet: String)
data class AskUserQuestionInput(val question: String, val options: List<String> = emptyList(), val header: String = "")
data class AskUserQuestionOutput(val answer: String, val answers: List<String> = emptyList())
data class BriefInput(val content: String)
data class BriefOutput(val content: String)
data class NotebookEditInput(val notebook_path: String, val cell_id: String? = null, val new_source: String)
data class NotebookEditOutput(val success: Boolean)
data class LSPInput(val command: String, val language: String, val path: String? = null, val pattern: String? = null)
data class LSPOutput(val results: List<String>)
data class ConfigInput(val key: String, val value: String)
data class ConfigOutput(val key: String, val value: String)
data class TaskCreateInput(val subject: String, val description: String = "", val activeForm: String = "")
data class TaskCreateOutput(val task_id: String, val todos: List<TodoItem> = emptyList(), val error: String? = null)
data class TaskGetInput(val task_id: String)
data class TaskGetOutput(
    val task_id: String,
    val status: String,
    val todos: List<TodoItem> = emptyList(),
    val subject: String = "",
    val found: Boolean = true,
    val error: String? = null,
)
data class TaskUpdateInput(val task_id: String, val status: String, val activeForm: String? = null)
data class TaskUpdateOutput(val success: Boolean, val message: String = "")
data class TaskListInput(val filter: String = "all")
data class TaskListOutput(val tasks: List<String>, val error: String? = null)
data class ListMcpResourcesInput(val server_name: String = "*")
data class ListMcpResourcesOutput(val resources: List<String>, val error: String? = null)
data class ReadMcpResourceInput(val uri: String)
data class ReadMcpResourceOutput(val content: String, val found: Boolean = true, val error: String? = null)
data class ToolSearchInput(val query: String)
data class ToolSearchOutput(val tools: List<String>)
data class EnterWorktreeInput(val path: String, val branch: String? = null)
data class EnterWorktreeOutput(val success: Boolean, val path: String)
data class ExitWorktreeInput(val confirm: Boolean = false)
data class ExitWorktreeOutput(val success: Boolean)
