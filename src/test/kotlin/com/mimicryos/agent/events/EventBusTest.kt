package com.mimicryos.agent.events

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBusTest {

    @Test
    fun `assigns monotonic sequence numbers and trims history`() {
        val bus = EventBus(maxHistory = 3)

        repeat(5) { index -> bus.emit(AgentEvent.LogEvent("info", "message-$index")) }

        val history = bus.history()
        assertEquals(listOf(3L, 4L, 5L), history.map { it.seq })
        assertEquals(5L, bus.lastSeq())

        val since = bus.history(2)
        assertEquals(listOf(3L, 4L, 5L), since.map { it.seq })
        assertTrue(bus.history(5).isEmpty())
    }

    @Test
    fun `keeps event payloads intact`() {
        val bus = EventBus()
        bus.emit(AgentEvent.TaskCompleted("task-1", "session-1", "all good", turns = 2))

        val event = bus.history().single().event as AgentEvent.TaskCompleted
        assertEquals("task-1", event.taskId)
        assertEquals("all good", event.result)
        assertEquals(2, event.turns)
    }

    @Test
    fun `delivers live events to subscribers`() = runBlocking {
        val bus = EventBus()
        val received = mutableListOf<EventEnvelope>()
        val job = launch { bus.events.collect { received += it } }
        delay(200)

        bus.emit(AgentEvent.RuntimeStatus("ready", "started"))
        delay(200)
        job.cancel()

        assertEquals(1, received.size)
        assertTrue(received.single().event is AgentEvent.RuntimeStatus)
    }
}
