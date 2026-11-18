package com.mimicryos.agent.core

import java.util.UUID

@JvmInline
value class AgentId(val value: String) {
    companion object {
        fun generate(): AgentId = AgentId(UUID.randomUUID().toString())
        fun of(value: String): AgentId = AgentId(value)
    }
    override fun toString(): String = value
}

@JvmInline
value class SessionId(val value: String) {
    companion object {
        fun generate(): SessionId = SessionId(UUID.randomUUID().toString())
        fun of(value: String): SessionId = SessionId(value)
    }
    override fun toString(): String = value
}

@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun generate(type: TaskType): TaskId {
            val prefix = type.prefix
            val bytes = UUID.randomUUID().toString().toByteArray()
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
            val id = StringBuilder(prefix)
            for (i in 0 until 8) {
                val idx = (bytes[i].toInt() and 0xFF) % alphabet.length
                id.append(alphabet[idx])
            }
            return TaskId(id.toString())
        }
        fun of(value: String): TaskId = TaskId(value)
    }
    override fun toString(): String = value
}

@JvmInline
value class ToolUseId(val value: String) {
    companion object {
        fun generate(): ToolUseId = ToolUseId(UUID.randomUUID().toString())
        fun of(value: String): ToolUseId = ToolUseId(value)
    }
    override fun toString(): String = value
}

enum class TaskType(val prefix: String) {
    LOCAL_BASH("b"),
    LOCAL_AGENT("a"),
    REMOTE_AGENT("r"),
    IN_PROCESS_TEAMMATE("t"),
    LOCAL_WORKFLOW("w"),
    MONITOR_MCP("m"),
    DREAM("d");
}

enum class TaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, KILLED;
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == KILLED
}
