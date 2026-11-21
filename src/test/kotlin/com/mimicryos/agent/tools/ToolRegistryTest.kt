package com.mimicryos.agent.tools

import com.mimicryos.agent.core.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRegistryTest {

    private val registry = ToolRegistry.createDefault()

    @Test
    fun `registers the full tool catalogue but only exposes implemented tools`() {
        assertEquals(29, registry.getAll().size)

        val enabled = registry.getEnabled()
        assertEquals(
            setOf(
                "Bash", "Read", "Write", "Edit", "Glob", "Grep",
                "TodoWrite", "TaskCreate", "TaskGet", "TaskUpdate", "TaskList",
                "NotebookEdit", "ToolSearch", "ListMcpResources", "ReadMcpResource", "Brief",
            ),
            enabled.map { it.name }.toSet(),
        )
        assertTrue(enabled.all { it.isImplemented })
        assertTrue(registry.getAll().filterNot { it.isImplemented }.isNotEmpty())
    }

    @Test
    fun `every implemented tool publishes a usable schema`() {
        registry.getEnabled().forEach { tool ->
            val schema = assertNotNull(tool.inputJSONSchema, "${tool.name} must expose inputJSONSchema")
            assertEquals("object", schema.type)
            assertTrue(schema.properties?.isNotEmpty() == true, "${tool.name} schema has no properties")
            assertTrue(schema.required?.isNotEmpty() == true, "${tool.name} schema has no required fields")
            schema.required!!.forEach { field ->
                assertTrue(
                    schema.properties!!.containsKey(field),
                    "${tool.name} requires '$field' but does not declare it",
                )
            }
        }
    }

    @Test
    fun `implemented tools decode model input into typed input`() {
        assertDecodes("Bash", mapOf("command" to "echo hi"), "echo hi")
        assertDecodes("Read", mapOf("file_path" to "/tmp/a.txt"), "/tmp/a.txt")
        assertDecodes("Write", mapOf("file_path" to "a.txt", "content" to "hi"), "a.txt")
        assertDecodes("Edit", mapOf("file_path" to "a.txt", "old_string" to "a", "new_string" to "b"), "a.txt")
        assertDecodes("Glob", mapOf("pattern" to "**/*.kt"), "**/*.kt")
        assertDecodes("Grep", mapOf("pattern" to "TODO"), "TODO")
        assertDecodes("TaskCreate", mapOf("subject" to "Ship it"), "Ship it")
        assertDecodes("TaskGet", mapOf("task_id" to "T-ABC123"), "T-ABC123")
        assertDecodes("TaskList", mapOf("filter" to "active"), "active")
        assertDecodes("ToolSearch", mapOf("query" to "notebook"), "notebook")
        assertDecodes("ReadMcpResource", mapOf("uri" to "file:///docs"), "file:///docs")
        assertDecodes("ListMcpResources", mapOf("server_name" to "docs"), "docs")
        assertDecodes("Brief", mapOf("content" to "summary"), "summary")
    }

    @Test
    fun `rejects input that misses a required field`() {
        val bash = registry.get("Bash")!!
        assertFailsWith<IllegalStateException> { bash.parseInput(emptyMap()) }

        val write = registry.get("Write")!!
        assertFailsWith<IllegalStateException> { write.parseInput(mapOf("file_path" to "a.txt")) }
    }

    @Test
    fun `stub tools refuse to accept agent input`() {
        val stub = registry.get("WebSearch")!!
        assertTrue(!stub.isImplemented)
        assertFailsWith<UnsupportedOperationException> { stub.parseInput(mapOf("query" to "kotlin")) }
    }

    @Test
    fun `implemented tools report paths for permission targeting`() {
        val read = registry.get("Read")!!
        val input = read.parseInput(mapOf("file_path" to "/tmp/x.txt"))
        @Suppress("UNCHECKED_CAST")
        assertEquals("/tmp/x.txt", (read as Tool<Any, Any>).getPath(input))

        val bash = registry.get("Bash")!!
        val bashInput = bash.parseInput(mapOf("command" to "ls"))
        @Suppress("UNCHECKED_CAST")
        assertNull((bash as Tool<Any, Any>).getPath(bashInput))
    }

    private fun assertDecodes(toolName: String, input: Map<String, String>, expectedSummaryPart: String) {
        val tool = assertNotNull(registry.get(toolName), "missing tool $toolName")
        @Suppress("UNCHECKED_CAST")
        val typed = tool as Tool<Any, Any>
        val decoded = typed.parseInput(input)
        val summary = typed.getToolUseSummary(decoded) ?: typed.userFacingName(decoded)
        assertTrue(
            summary.contains(expectedSummaryPart),
            "$toolName summary '$summary' should mention '$expectedSummaryPart'",
        )
    }
}
