package com.mimicryos.agent.tools

import com.mimicryos.agent.core.ToolUseContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TaskRecord(
    val id: String,
    val subject: String,
    val description: String = "",
    val activeForm: String = "",
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val todos: List<TodoItem> = emptyList(),
)

@Serializable
data class TaskStoreFile(
    val version: Int = 1,
    val todos: List<TodoItem> = emptyList(),
    val tasks: List<TaskRecord> = emptyList(),
)

/** Per-project task/todo state. Persisted so a restarted runtime does not lose it. */
class TaskStore internal constructor(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private var state: TaskStoreFile = load()

    @Synchronized
    fun replaceTodos(todos: List<TodoItem>) {
        state = state.copy(todos = todos.map { it.normalized() })
        persist()
    }

    @Synchronized
    fun todos(): List<TodoItem> = state.todos.toList()

    @Synchronized
    fun createTask(subject: String, description: String, activeForm: String): TaskRecord {
        require(subject.isNotBlank()) { "subject must not be blank" }
        val now = System.currentTimeMillis()
        val id = "T-" + UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        val task = TaskRecord(id, subject.trim(), description.trim(), activeForm.trim(), "pending", now, now, state.todos)
        state = state.copy(tasks = state.tasks + task)
        persist()
        return task
    }

    @Synchronized
    fun getTask(id: String): TaskRecord? = state.tasks.firstOrNull { it.id == id }

    @Synchronized
    fun updateTask(id: String, status: String, activeForm: String?): TaskRecord? {
        val normalized = status.trim().lowercase()
        require(normalized in setOf("pending", "in_progress", "completed", "cancelled")) {
            "status must be pending, in_progress, completed or cancelled"
        }
        val existing = getTask(id) ?: return null
        val updated = existing.copy(
            status = normalized,
            activeForm = activeForm?.trim()?.takeIf { it.isNotEmpty() } ?: existing.activeForm,
            updatedAt = System.currentTimeMillis(),
        )
        state = state.copy(tasks = state.tasks.map { if (it.id == id) updated else it })
        persist()
        return updated
    }

    @Synchronized
    fun listTasks(filter: String): List<TaskRecord> = when (filter.trim().lowercase()) {
        "", "all" -> state.tasks
        "active", "open" -> state.tasks.filter { it.status !in setOf("completed", "cancelled") }
        else -> state.tasks.filter { it.status == filter.trim().lowercase() }
    }.sortedByDescending { it.updatedAt }

    private fun TodoItem.normalized() = copy(
        content = content.trim(),
        status = status.trim().lowercase().ifBlank { "pending" },
        activeForm = activeForm.trim(),
    )

    private fun load(): TaskStoreFile = try {
        if (!file.isFile) TaskStoreFile()
        else json.decodeFromString(TaskStoreFile.serializer(), file.readText())
    } catch (_: Exception) {
        TaskStoreFile()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(TaskStoreFile.serializer(), state))
    }
}

object TaskStores {
    private val stores = ConcurrentHashMap<String, TaskStore>()

    fun forContext(context: ToolUseContext): TaskStore {
        val cwd = context.options.cwd ?: System.getProperty("user.dir")
        val key = File(cwd).canonicalPath
        return stores.computeIfAbsent(key) { TaskStore(File(File(it), ".mimicry/tasks.json")) }
    }
}
