package com.mimicryos.agent.tools

import com.mimicryos.agent.core.*
import com.mimicryos.agent.provider.*
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool<*, *>>()
    private val toolOrder = mutableListOf<String>()

    fun register(tool: Tool<*, *>) { tools[tool.name] = tool; if (!toolOrder.contains(tool.name)) toolOrder.add(tool.name) }
    fun unregister(name: String) { tools.remove(name); toolOrder.remove(name) }
    fun get(name: String): Tool<*, *>? = tools[name]
    fun getAll(): List<Tool<*, *>> = toolOrder.mapNotNull { tools[it] }
    fun getEnabled(): List<Tool<*, *>> = getAll().filter { it.isEnabled() }
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

object BashTool : Tool<BashInput, BashOutput> {
    override val name = "Bash"
    override val inputSchema = object : ToolInputSchema {}
    override val maxResultSizeChars = 80_000
    override suspend fun call(args: BashInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<BashOutput> = ToolResult(BashOutput("", 0, false))
    override suspend fun description(input: BashInput, options: DescriptionOptions): String = "Execute a bash command"
    override fun mapToolResultToToolResultBlockParam(content: BashOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.stdout)))
    override fun prompt(options: PromptOptions): String = "Execute shell commands"
    override fun userFacingName(input: BashInput): String = "Bash"
    override fun toAutoClassifierInput(input: BashInput): Any = input.command
}

object FileReadTool : Tool<FileReadInput, FileReadOutput> {
    override val name = "Read"
    override val inputSchema = object : ToolInputSchema {}
    override val maxResultSizeChars = Int.MAX_VALUE
    override suspend fun call(args: FileReadInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<FileReadOutput> = ToolResult(FileReadOutput("", emptyList()))
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
    override suspend fun call(args: FileWriteInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<FileWriteOutput> = ToolResult(FileWriteOutput(true))
    override suspend fun description(input: FileWriteInput, options: DescriptionOptions): String = "Write a file"
    override fun mapToolResultToToolResultBlockParam(content: FileWriteOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("File written")))
    override fun prompt(options: PromptOptions): String = "Write files to the filesystem"
    override fun userFacingName(input: FileWriteInput): String = "Write"
    override fun toAutoClassifierInput(input: FileWriteInput): Any = "${input.file_path}: new content"
    override fun isDestructive(input: FileWriteInput): Boolean = true
}

object FileEditTool : Tool<FileEditInput, FileEditOutput> {
    override val name = "Edit"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: FileEditInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<FileEditOutput> = ToolResult(FileEditOutput(true, emptyList()))
    override suspend fun description(input: FileEditInput, options: DescriptionOptions): String = "Edit a file"
    override fun mapToolResultToToolResultBlockParam(content: FileEditOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("File edited")))
    override fun prompt(options: PromptOptions): String = "Edit files"
    override fun userFacingName(input: FileEditInput): String = "Edit"
    override fun toAutoClassifierInput(input: FileEditInput): Any = "${input.file_path}: edit"
    override fun isDestructive(input: FileEditInput): Boolean = true
}

object GlobTool : Tool<GlobInput, GlobOutput> {
    override val name = "Glob"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: GlobInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<GlobOutput> = ToolResult(GlobOutput(emptyList()))
    override suspend fun description(input: GlobInput, options: DescriptionOptions): String = "Find files by pattern"
    override fun mapToolResultToToolResultBlockParam(content: GlobOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.files.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "Find files by glob pattern"
    override fun userFacingName(input: GlobInput): String = "Glob"
    override fun toAutoClassifierInput(input: GlobInput): Any = input.pattern
    override fun isConcurrencySafe(input: GlobInput): Boolean = true
    override fun isReadOnly(input: GlobInput): Boolean = true
}

object GrepTool : Tool<GrepInput, GrepOutput> {
    override val name = "Grep"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: GrepInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<GrepOutput> = ToolResult(GrepOutput(emptyList()))
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
    override suspend fun call(args: AgentInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<AgentOutput> = ToolResult(AgentOutput("", "", emptyList()))
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
    override suspend fun call(args: TodoWriteInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TodoWriteOutput> = ToolResult(TodoWriteOutput(true))
    override suspend fun description(input: TodoWriteInput, options: DescriptionOptions): String = "Update todo list"
    override fun mapToolResultToToolResultBlockParam(content: TodoWriteOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(emptyList())
    override fun prompt(options: PromptOptions): String = "Track task progress with todos"
    override fun userFacingName(input: TodoWriteInput): String = "TodoWrite"
    override fun toAutoClassifierInput(input: TodoWriteInput): Any = ""
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
    override val name = "WebFetch"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: WebFetchInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<WebFetchOutput> = ToolResult(WebFetchOutput("", emptyMap()))
    override suspend fun description(input: WebFetchInput, options: DescriptionOptions): String = "Fetch web content"
    override fun mapToolResultToToolResultBlockParam(content: WebFetchOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.content)))
    override fun prompt(options: PromptOptions): String = "Fetch content from URLs"
    override fun userFacingName(input: WebFetchInput): String = "WebFetch"
    override fun toAutoClassifierInput(input: WebFetchInput): Any = input.url
    override fun isOpenWorld(input: WebFetchInput): Boolean = true
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
    override suspend fun call(args: BriefInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<BriefOutput> = ToolResult(BriefOutput(""))
    override suspend fun description(input: BriefInput, options: DescriptionOptions): String = "Provide a brief response"
    override fun mapToolResultToToolResultBlockParam(content: BriefOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.content)))
    override fun prompt(options: PromptOptions): String = "Provide brief responses"
    override fun userFacingName(input: BriefInput): String = "Brief"
    override fun toAutoClassifierInput(input: BriefInput): Any = input.content
}

object NotebookEditTool : Tool<NotebookEditInput, NotebookEditOutput> {
    override val name = "NotebookEdit"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: NotebookEditInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<NotebookEditOutput> = ToolResult(NotebookEditOutput(true))
    override suspend fun description(input: NotebookEditInput, options: DescriptionOptions): String = "Edit a notebook"
    override fun mapToolResultToToolResultBlockParam(content: NotebookEditOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("Notebook edited")))
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
    override suspend fun call(args: TaskCreateInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskCreateOutput> = ToolResult(TaskCreateOutput("", emptyList()))
    override suspend fun description(input: TaskCreateInput, options: DescriptionOptions): String = "Create a task"
    override fun mapToolResultToToolResultBlockParam(content: TaskCreateOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.task_id)))
    override fun prompt(options: PromptOptions): String = "Create tasks"
    override fun userFacingName(input: TaskCreateInput): String = "TaskCreate"
    override fun toAutoClassifierInput(input: TaskCreateInput): Any = input.subject
}

object TaskGetTool : Tool<TaskGetInput, TaskGetOutput> {
    override val name = "TaskGet"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: TaskGetInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskGetOutput> = ToolResult(TaskGetOutput("", "", emptyList()))
    override suspend fun description(input: TaskGetInput, options: DescriptionOptions): String = "Get task"
    override fun mapToolResultToToolResultBlockParam(content: TaskGetOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.status)))
    override fun prompt(options: PromptOptions): String = "Get task status"
    override fun userFacingName(input: TaskGetInput): String = "TaskGet"
    override fun toAutoClassifierInput(input: TaskGetInput): Any = input.task_id
}

object TaskUpdateTool : Tool<TaskUpdateInput, TaskUpdateOutput> {
    override val name = "TaskUpdate"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: TaskUpdateInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskUpdateOutput> = ToolResult(TaskUpdateOutput(true))
    override suspend fun description(input: TaskUpdateInput, options: DescriptionOptions): String = "Update task"
    override fun mapToolResultToToolResultBlockParam(content: TaskUpdateOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text("Task updated")))
    override fun prompt(options: PromptOptions): String = "Update task status"
    override fun userFacingName(input: TaskUpdateInput): String = "TaskUpdate"
    override fun toAutoClassifierInput(input: TaskUpdateInput): Any = "${input.task_id}: ${input.status}"
}

object TaskListTool : Tool<TaskListInput, TaskListOutput> {
    override val name = "TaskList"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: TaskListInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<TaskListOutput> = ToolResult(TaskListOutput(emptyList()))
    override suspend fun description(input: TaskListInput, options: DescriptionOptions): String = "List tasks"
    override fun mapToolResultToToolResultBlockParam(content: TaskListOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.tasks.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "List tasks"
    override fun userFacingName(input: TaskListInput): String = "TaskList"
    override fun toAutoClassifierInput(input: TaskListInput): Any = ""
}

object ListMcpResourcesTool : Tool<ListMcpResourcesInput, ListMcpResourcesOutput> {
    override val name = "ListMcpResources"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: ListMcpResourcesInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ListMcpResourcesOutput> = ToolResult(ListMcpResourcesOutput(emptyList()))
    override suspend fun description(input: ListMcpResourcesInput, options: DescriptionOptions): String = "List MCP resources"
    override fun mapToolResultToToolResultBlockParam(content: ListMcpResourcesOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.resources.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "List MCP resources"
    override fun userFacingName(input: ListMcpResourcesInput): String = "ListMcpResources"
    override fun toAutoClassifierInput(input: ListMcpResourcesInput): Any = input.server_name
}

object ReadMcpResourceTool : Tool<ReadMcpResourceInput, ReadMcpResourceOutput> {
    override val name = "ReadMcpResource"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: ReadMcpResourceInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ReadMcpResourceOutput> = ToolResult(ReadMcpResourceOutput(""))
    override suspend fun description(input: ReadMcpResourceInput, options: DescriptionOptions): String = "Read MCP resource"
    override fun mapToolResultToToolResultBlockParam(content: ReadMcpResourceOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.content)))
    override fun prompt(options: PromptOptions): String = "Read MCP resources"
    override fun userFacingName(input: ReadMcpResourceInput): String = "ReadMcpResource"
    override fun toAutoClassifierInput(input: ReadMcpResourceInput): Any = input.uri
}

object ToolSearchTool : Tool<ToolSearchInput, ToolSearchOutput> {
    override val name = "ToolSearch"
    override val inputSchema = object : ToolInputSchema {}
    override suspend fun call(args: ToolSearchInput, context: ToolUseContext, canUseTool: CanUseToolFn, parentMessage: Message.Assistant, onProgress: ToolCallProgress<ToolProgressData>?): ToolResult<ToolSearchOutput> = ToolResult(ToolSearchOutput(emptyList()))
    override suspend fun description(input: ToolSearchInput, options: DescriptionOptions): String = "Search tools"
    override fun mapToolResultToToolResultBlockParam(content: ToolSearchOutput, toolUseID: String): ToolResultBlockParam = ToolResultBlockParam(listOf(ResultContent.Text(content.tools.joinToString("\n"))))
    override fun prompt(options: PromptOptions): String = "Search for tools"
    override fun userFacingName(input: ToolSearchInput): String = "ToolSearch"
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
data class TodoWriteOutput(val success: Boolean)
data class TodoItem(val content: String, val status: String = "pending", val activeForm: String = "")
data class SkillInput(val skill_name: String, val args: Map<String, String> = emptyMap())
data class SkillOutput(val content: String, val scripts: List<String> = emptyList())
data class EnterPlanModeInput(val prompt: String, val plan: String = "")
data class EnterPlanModeOutput(val entered: Boolean)
data class ExitPlanModeInput(val plan: String)
data class ExitPlanModeOutput(val success: Boolean, val plan: String)
data class WebFetchInput(val url: String, val prompt: String = "", val timeout: Int = 30000)
data class WebFetchOutput(val content: String, val metadata: Map<String, String> = emptyMap())
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
data class TaskCreateOutput(val task_id: String, val todos: List<TodoItem> = emptyList())
data class TaskGetInput(val task_id: String)
data class TaskGetOutput(val task_id: String, val status: String, val todos: List<TodoItem> = emptyList())
data class TaskUpdateInput(val task_id: String, val status: String, val activeForm: String? = null)
data class TaskUpdateOutput(val success: Boolean)
data class TaskListInput(val filter: String = "all")
data class TaskListOutput(val tasks: List<String>)
data class ListMcpResourcesInput(val server_name: String)
data class ListMcpResourcesOutput(val resources: List<String>)
data class ReadMcpResourceInput(val uri: String)
data class ReadMcpResourceOutput(val content: String)
data class ToolSearchInput(val query: String)
data class ToolSearchOutput(val tools: List<String>)
data class EnterWorktreeInput(val path: String, val branch: String? = null)
data class EnterWorktreeOutput(val success: Boolean, val path: String)
data class ExitWorktreeInput(val confirm: Boolean = false)
data class ExitWorktreeOutput(val success: Boolean)
