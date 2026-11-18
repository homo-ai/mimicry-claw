package com.mimicryos.agent.context

import com.mimicryos.agent.core.Message
import com.mimicryos.agent.core.Content
import com.mimicryos.agent.core.ContentBlock
import java.util.concurrent.ConcurrentHashMap

class ContextManager(private val cwd: String) {
    private val tokenCache = ConcurrentHashMap<String, Int>()
    private var totalTokens = 0
    private val maxContextTokens = 180_000

    fun addMessages(messages: List<Message>) { messages.forEach { msg -> val tokens = estimateTokens(msg); totalTokens += tokens; tokenCache[msg.uuid] = tokens } }
    fun needsCompaction(): Boolean = totalTokens > maxContextTokens * 0.8
    suspend fun compact(): CompactResult { val before = totalTokens; totalTokens = (totalTokens * 0.3).toInt(); return CompactResult(before, totalTokens, 0, 0) }
    fun getTokenUsage(): Int = totalTokens
    fun getMaxContextTokens(): Int = maxContextTokens
    private fun estimateTokens(message: Message): Int = when (message) { is Message.User -> { val content = (message.message.content as? Content.Text)?.text ?: ""; content.length / 4 }; is Message.Assistant -> message.message.content.sumOf { block -> when (block) { is ContentBlock.Text -> block.text.length / 4; is ContentBlock.Thinking -> block.thinking.length / 4; is ContentBlock.ToolUse -> (block.id + block.name + block.input.toString()).length / 4; else -> 10 } }; else -> 10 }
    data class CompactResult(val beforeTokens: Int, val afterTokens: Int, val messagesBefore: Int, val messagesAfter: Int)
}
