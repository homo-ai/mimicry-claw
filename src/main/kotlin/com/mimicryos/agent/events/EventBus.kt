package com.mimicryos.agent.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide event bus with a bounded replay history.
 *
 * History lets a frontend attach after a task has already started and still
 * reconstruct recent activity. Live subscribers receive the same envelopes.
 */
class EventBus(private val maxHistory: Int = 5_000) {
    private val live = MutableSharedFlow<EventEnvelope>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<EventEnvelope> = live.asSharedFlow()

    private val history = ArrayDeque<EventEnvelope>()
    private val lock = Any()
    private var sequence = 0L

    fun emit(event: AgentEvent): EventEnvelope {
        val envelope = synchronized(lock) {
            sequence += 1
            EventEnvelope(seq = sequence, event = event).also {
                history.addLast(it)
                while (history.size > maxHistory) history.removeFirst()
            }
        }
        live.tryEmit(envelope)
        return envelope
    }

    fun history(sinceSeq: Long = 0L): List<EventEnvelope> = synchronized(lock) {
        if (sinceSeq <= 0L) history.toList() else history.filter { it.seq > sinceSeq }
    }

    fun lastSeq(): Long = synchronized(lock) { sequence }
}
