package com.mimicryos.agent.tools

import com.mimicryos.agent.core.AgentDefinitionsResult
import com.mimicryos.agent.core.AbortController
import com.mimicryos.agent.core.AppState
import com.mimicryos.agent.core.AssistantMessage
import com.mimicryos.agent.core.FileStateCache
import com.mimicryos.agent.core.Message
import com.mimicryos.agent.core.ServerResource
import com.mimicryos.agent.core.ThinkingConfig
import com.mimicryos.agent.core.Tool
import com.mimicryos.agent.core.ToolOptions
import com.mimicryos.agent.core.ToolUseContext
import com.mimicryos.agent.permission.PermissionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineToolsTest {
    private val allow = object : com.mimicryos.agent.core.CanUseToolFn {
        override suspend fun invoke(
            tool: Tool<*, *>,
            input: Any,
            context: ToolUseContext,
            assistantMessage: Message.Assistant,
            toolUseID: String,
            forceDecision: String?,
        ): PermissionResult = PermissionResult.allow(input)
    }
    private val parent = Message.Assistant(uuid = "parent", message = AssistantMessage("assistant", emptyList()))

    private fun context(
        dir: File,
        tools: List<Tool<*, *>> = ToolRegistry.createDefault().getAll(),
        mcpResources: Map<String, List<ServerResource>> = emptyMap(),
    ) = ToolUseContext(
        options = ToolOptions(
            mainLoopModel = "test-model",
            tools = tools,
            thinkingConfig = ThinkingConfig(),
            agentDefinitions = AgentDefinitionsResult(emptyList(), emptyList()),
            cwd = dir.path,
            mcpResources = mcpResources,
        ),
        abortController = AbortController(),
        readFileState = FileStateCache(),
        getAppState = { AppState() },
        setAppState = {},
    )

    @Test
    fun `todos and tasks persist through the task tool family`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-offline-tools").toFile()
        try {
            val context = context(dir)
            val todos = TodoWriteTool.call(
                TodoWriteTool.parseInput(mapOf("todos" to """[{"content":"Review","status":"in_progress","activeForm":"Reviewing"}]""")),
                context, allow, parent,
            )
            assertTrue(todos.data.success)
            assertEquals(1, TaskStores.forContext(context).todos().size)

            val created = TaskCreateTool.call(
                TaskCreateTool.parseInput(mapOf("subject" to "Ship feature", "description" to "Verify end to end")),
                context, allow, parent,
            ).data
            assertTrue(created.task_id.startsWith("T-"))
            assertEquals(1, created.todos.size)

            val fetched = TaskGetTool.call(TaskGetTool.parseInput(mapOf("task_id" to created.task_id)), context, allow, parent).data
            assertEquals("pending", fetched.status)
            assertEquals("Ship feature", fetched.subject)

            val updated = TaskUpdateTool.call(
                TaskUpdateTool.parseInput(mapOf("task_id" to created.task_id, "status" to "completed")),
                context, allow, parent,
            ).data
            assertTrue(updated.success)
            assertEquals("completed", TaskGetTool.call(TaskGetTool.parseInput(mapOf("task_id" to created.task_id)), context, allow, parent).data.status)

            val listed = TaskListTool.call(TaskListTool.parseInput(mapOf("filter" to "completed")), context, allow, parent).data
            assertTrue(listed.tasks.single().contains(created.task_id))

            val persisted = File(dir, ".mimicry/tasks.json")
            assertTrue(persisted.isFile)
            assertTrue(persisted.readText().contains("Ship feature"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid task status is reported as a tool error`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-task-update").toFile()
        try {
            val context = context(dir)
            val created = TaskCreateTool.call(TaskCreateInput("Subject"), context, allow, parent).data
            val failed = TaskUpdateTool.call(TaskUpdateInput(created.task_id, "unknown"), context, allow, parent)
            assertTrue(!failed.data.success)
            assertTrue(failed.data.message.contains("status", ignoreCase = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `notebook edit replaces an existing cell by id`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-notebook-edit").toFile()
        try {
            val notebook = File(dir, "demo.ipynb")
            notebook.writeText("""{"cells":[{"cell_type":"code","id":"c1","source":["old"]}],"metadata":{}}""")
            val context = context(dir)
            val result = NotebookEditTool.call(
                NotebookEditInput(notebook.path, "c1", "new line\nsecond line"),
                context, allow, parent,
            )
            assertTrue(result.data.success)
            val root = Json.parseToJsonElement(notebook.readText()).jsonObject
            val source = root["cells"]!!.jsonArray[0].jsonObject["source"]!!.jsonArray
            assertEquals("new line", source[0].toString().trim('"'))
            assertEquals("second line", source[1].toString().trim('"'))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tool search and configured MCP resource tools read real context data`() = runBlocking {
        val dir = Files.createTempDirectory("mimicry-tool-context").toFile()
        try {
            val tools = listOf(FileWriteTool, ToolSearchTool, ListMcpResourcesTool, ReadMcpResourceTool)
            val context = context(
                dir,
                tools,
                mapOf("docs" to listOf(ServerResource("file:///docs", "Docs", "text/plain", "hello resource"))),
            )
            val listed = ListMcpResourcesTool.call(ListMcpResourcesInput("docs"), context, allow, parent).data
            assertTrue(listed.resources.single().contains("docs:file:///docs"))

            val read = ReadMcpResourceTool.call(ReadMcpResourceInput("file:///docs"), context, allow, parent).data
            assertTrue(read.found)
            assertEquals("hello resource", read.content)

            val missing = ReadMcpResourceTool.call(ReadMcpResourceInput("file:///missing"), context, allow, parent).data
            assertTrue(!missing.found)

            val search = ToolSearchTool.call(ToolSearchInput("file"), context, allow, parent).data
            assertTrue(search.tools.any { it.startsWith("Write") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
