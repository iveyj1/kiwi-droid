package org.kiwidroid.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class ReceiverHandoverTest {
    private val a = Receiver("A", "http://a.invalid")
    private val b = Receiver("B", "http://b.invalid")
    private val c = Receiver("C", "http://c.invalid")
    private inner class Harness(scope: CoroutineScope, keep: Int = 0) {
        val desired = MutableStateFlow(ReceiverSelection(a, Tuning(), keep))
        val state = MutableStateFlow<ReceiverPlayback?>(null)
        val held = ConcurrentHashMap.newKeySet<String>()
        val broken = ConcurrentHashMap.newKeySet<String>()
        val opened = ConcurrentHashMap<String, AtomicInteger>()
        val live = ConcurrentHashMap.newKeySet<String>()
        val maximum = AtomicInteger()
        val time = AtomicLong()
        val block = AtomicReference<ShortArray>()
        val mixer = ReceiverMixer(0) { SoundSettings() }
        private val engine = ReceiverHandover(mixer, session = { receiver, _, sink, report ->
            opened.computeIfAbsent(receiver.name) { AtomicInteger() }.incrementAndGet()
            live += receiver.name
            maximum.accumulateAndGet(live.size, ::maxOf)
            try {
                while (receiver.name in held) delay(10)
                sink.start(48000.0)
                while (currentCoroutineContext().isActive) {
                    if (receiver.name in broken) error("Failed ${receiver.name}")
                    sink.observeRssi(if (receiver == a) -50.0 else -80.0)
                    sink.offer(ShortArray(480) { if (receiver == a) 1000 else 2000 })
                    report(SessionMetrics(if (receiver == a) -50.0 else -80.0, false, 0, 0, 48000.0, sink.playbackDiagnostics()))
                    delay(10)
                }
            } finally { live -= receiver.name; sink.close() }
        }, clock = time::get)
        val playback = scope.launch { engine.run(desired) { state.value = it } }
        val audio = scope.launch(Dispatchers.Default) {
            while (isActive) { block.set(mixer.render(480)); delay(10) }
        }
        suspend fun await(predicate: () -> Boolean) { withTimeout(5000) { while (!predicate()) delay(10) } }
        suspend fun close() { playback.cancelAndJoin(); audio.cancelAndJoin(); assertTrue(live.isEmpty()) }
    }

    @Test fun `delayed handover keeps old sound metrics and socket then closes it with retention off`() = runBlocking {
        val h = Harness(this)
        try {
            h.await { h.state.value?.receiver == a }
            h.held += "B"
            h.desired.value = ReceiverSelection(b, Tuning())
            h.await { h.state.value?.connecting == b }
            delay(200)
            assertEquals(a, h.state.value!!.receiver)
            assertEquals(-50.0, h.state.value!!.metrics!!.rssiDbm)
            assertTrue(h.block.get().all { it > 0 })
            assertEquals(setOf("A", "B"), h.live.toSet())
            h.held -= "B"
            h.await { h.state.value?.receiver == b }
            h.time.addAndGet(200)
            h.await { "A" !in h.live }
            assertEquals(2, h.maximum.get())
        } finally { h.close() }
    }

    @Test fun `retained switchback reuses socket and expires from the most recent transfer`() = runBlocking {
        val h = Harness(this, 15)
        try {
            h.await { h.state.value?.receiver == a }
            h.desired.value = ReceiverSelection(b, Tuning(), 15)
            h.await { h.state.value?.receiver == b && h.state.value?.retained == a }
            h.time.set(10000)
            h.desired.value = ReceiverSelection(a, Tuning(), 15)
            h.await { h.state.value?.receiver == a && h.state.value?.retained == b }
            assertEquals(1, h.opened["A"]!!.get())
            assertEquals(1, h.opened["B"]!!.get())
            h.time.set(16000)
            delay(100)
            assertTrue("B" in h.live)
            h.time.set(26000)
            h.await { "B" !in h.live }
        } finally { h.close() }
    }

    @Test fun `retuning drops retained connection and third receiver never opens a third socket`() = runBlocking {
        val h = Harness(this, 60)
        try {
            h.await { h.state.value?.receiver == a }
            h.desired.value = ReceiverSelection(b, Tuning(), 60)
            h.await { h.state.value?.receiver == b }
            h.desired.value = ReceiverSelection(c, Tuning(), 60)
            h.await { h.state.value?.receiver == c }
            assertEquals(2, h.maximum.get())
            assertTrue("A" !in h.live)
            h.desired.value = ReceiverSelection(c, Tuning().step(100), 60)
            h.await { "B" !in h.live }
            assertEquals(1, h.opened["C"]!!.get())
        } finally { h.close() }
    }

    @Test fun `failed or timed out candidate leaves old receiver playing and can be retried`() = runBlocking {
        val h = Harness(this)
        try {
            h.await { h.state.value?.receiver == a }
            h.broken += "B"
            h.desired.value = ReceiverSelection(b, Tuning())
            h.await { h.state.value?.error != null }
            assertEquals(a, h.state.value!!.receiver)
            h.desired.value = ReceiverSelection(a, Tuning()) // UI rollback
            h.broken -= "B"
            h.held += "B"
            h.desired.value = ReceiverSelection(b, Tuning().step(1))
            h.await { h.state.value?.connecting == b }
            h.time.set(13000)
            h.await { h.state.value?.error?.startsWith("Timed out") == true }
            assertEquals(a, h.state.value!!.receiver)
        } finally { h.close() }
    }

    @Test fun `foreground failure uses a healthy retained stream but never reconnects failed sockets`() = runBlocking {
        val h = Harness(this, 60)
        try {
            h.await { h.state.value?.receiver == a }
            h.desired.value = ReceiverSelection(b, Tuning(), 60)
            h.await { h.state.value?.receiver == b }
            h.broken += "B"
            h.await { h.state.value?.receiver == a && h.state.value?.error != null }
            assertEquals(1, h.opened["A"]!!.get())
            assertEquals(1, h.opened["B"]!!.get())
            h.broken += "A"
            h.await { h.playback.isCompleted }
            assertNull(h.state.value!!.receiver)
            assertNotNull(h.state.value!!.error)
            assertEquals(1, h.opened["A"]!!.get())
        } finally { h.close() }
    }

    @Test fun `returning to audible receiver cancels pending attempt and background failure is harmless`() = runBlocking {
        val h = Harness(this, 60)
        try {
            h.await { h.state.value?.receiver == a }
            h.held += "B"
            h.desired.value = ReceiverSelection(b, Tuning(), 60)
            h.await { h.state.value?.connecting == b }
            h.desired.value = ReceiverSelection(a, Tuning(), 60)
            h.await { "B" !in h.live && h.state.value?.connecting == null }
            h.held -= "B"
            h.desired.value = ReceiverSelection(b, Tuning(), 60)
            h.await { h.state.value?.receiver == b }
            h.broken += "A"
            h.await { "A" !in h.live && h.state.value?.retained == null }
            assertEquals(b, h.state.value!!.receiver)
            assertNull(h.state.value!!.error)
        } finally { h.close() }
    }
}
