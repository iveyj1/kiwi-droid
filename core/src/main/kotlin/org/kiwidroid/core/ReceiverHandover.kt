package org.kiwidroid.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Desired controls, distinct from the receiver currently audible during connection setup. */
data class ReceiverSelection(val receiver: Receiver, val tuning: Tuning, val keepSeconds: Int = 0) {
    init { require(keepSeconds in 0..600) }
}
data class ReceiverPlayback(
    val receiver: Receiver?, val tuning: Tuning?, val metrics: SessionMetrics?,
    val connecting: Receiver? = null, val retained: Receiver? = null, val error: String? = null,
)

/** Serializes connection ownership. Cancellation joins each socket before its replacement is opened. */
class ReceiverHandover(
    private val mixer: ReceiverMixer,
    private val session: suspend (Receiver, StateFlow<Tuning>, AudioSink, (SessionMetrics) -> Unit) -> Unit =
        { receiver, tuning, sink, metrics -> KiwiSession().run(receiver, tuning, sink, metrics) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val setupTimeoutMs: Long = 12_000,
) {
    private class Slot(val receiver: Receiver, tune: Tuning, val input: ReceiverMixer.Input, val created: Long) {
        val tuning = MutableStateFlow(tune)
        lateinit var job: Job
        var metrics: SessionMetrics? = null
        var expires = Long.MAX_VALUE
        var waitingSince = created
    }
    private sealed interface Event {
        data class Metrics(val slot: Slot, val metrics: SessionMetrics) : Event
        data class Failed(val slot: Slot, val message: String) : Event
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(selections: Flow<ReceiverSelection>, checkOutput: () -> Unit = {},
                    onPlayback: (ReceiverPlayback) -> Unit): Unit = coroutineScope {
        val commands = Channel<ReceiverSelection>(Channel.CONFLATED)
        val events = Channel<Event>(Channel.UNLIMITED)
        val collector = launch { selections.distinctUntilChanged().collect { commands.send(it) } }
        var desired: ReceiverSelection? = null
        var active: Slot? = null
        var other: Slot? = null
        var pending = false
        var error: String? = null
        var previous: ReceiverPlayback? = null
        var finished = false
        var transferSettledAt = 0L

        suspend fun remove(slot: Slot) {
            mixer.remove(slot.input)
            slot.job.cancelAndJoin()
            if (active === slot) active = null
            if (other === slot) { other = null; pending = false }
        }
        fun create(want: ReceiverSelection): Slot {
            val slot = Slot(want.receiver, want.tuning, mixer.createInput(), clock())
            slot.job = launch(Dispatchers.IO) {
                try {
                    session(slot.receiver, slot.tuning, slot.input) { events.trySend(Event.Metrics(slot, it)) }
                    events.trySend(Event.Failed(slot, "Receiver stream ended"))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { events.trySend(Event.Failed(slot, e.message ?: "Receiver connection failed")) }
                finally { slot.input.close() }
            }
            return slot
        }
        fun promote(slot: Slot) {
            val old = active
            mixer.select(slot.input)
            transferSettledAt = clock() + (slot.metrics?.playback?.deviceBufferMs ?: 0) + 20
            active = slot
            other = old
            pending = false
            old?.expires = clock() + maxOf(100L, (desired?.keepSeconds ?: 0) * 1000L)
        }
        fun rollback(message: String) {
            error = message
            active?.let { desired = ReceiverSelection(it.receiver, it.tuning.value, desired?.keepSeconds ?: 0) }
        }
        try {
            while (isActive) {
                select<Unit> {
                    commands.onReceive { want ->
                        val prior = desired
                        if (prior != want) {
                            error = null
                            // Freeze the old audible source while a differently tuned candidate starts.
                            if (prior != null && prior.tuning.command() != want.tuning.command()) other?.let { remove(it) }
                            desired = want
                            if (other != null && !pending && prior?.keepSeconds != want.keepSeconds)
                                other!!.expires = clock() + maxOf(100L, want.keepSeconds * 1000L)
                        }
                    }
                    events.onReceive { event ->
                        when (event) {
                            is Event.Metrics -> if (event.slot === active || event.slot === other) event.slot.metrics = event.metrics
                            is Event.Failed -> {
                                if (event.slot === active) {
                                    remove(event.slot)
                                    if (other != null && other!!.input.ready) {
                                        val fallback = other!!
                                        promote(fallback)
                                        rollback(event.message)
                                    } else if (other == null || !pending) {
                                        other?.let { remove(it) }
                                        onPlayback(ReceiverPlayback(null, null, null, error = event.message))
                                        finished = true
                                    }
                                } else if (event.slot === other) {
                                    val wasPending = pending
                                    remove(event.slot)
                                    if (wasPending) {
                                        rollback(event.message)
                                        if (active == null) {
                                            onPlayback(ReceiverPlayback(null, null, null, error = event.message))
                                            finished = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    onTimeout(50) { }
                }
                if (finished) return@coroutineScope
                checkOutput()
                if (!pending && other != null && clock() >= other!!.expires) remove(other!!)
                val want = desired ?: continue
                if (active?.receiver == want.receiver) {
                    if (pending) other?.let { remove(it) }
                    val slot = active!!
                    if (slot.tuning.value.command() != want.tuning.command()) {
                        other?.let { remove(it) }
                        slot.input.retuned()
                        slot.metrics = null
                        slot.tuning.value = want.tuning
                    }
                } else {
                    val candidate = other
                    if (candidate != null && (candidate.receiver != want.receiver || candidate.tuning.value.command() != want.tuning.command())) {
                        // Finish the 20 ms fade before reusing its old input for a third receiver.
                        if (mixer.transitioning) continue
                        remove(candidate)
                    }
                    if (other == null) { other = create(want); pending = true }
                    val next = other!!
                    if (next.input.ready && next.metrics?.stale == false) promote(next)
                    else {
                        if (!pending) next.waitingSince = clock()
                        pending = true
                        if (clock() - next.waitingSince > setupTimeoutMs) {
                            remove(next)
                            rollback("Timed out buffering ${next.receiver.name}")
                            if (active == null) {
                                onPlayback(ReceiverPlayback(null, null, null, error = error))
                                return@coroutineScope
                            }
                        }
                    }
                }
                if (!pending && other != null && clock() >= other!!.expires) remove(other!!)
                val metrics = active?.metrics?.let { it.copy(settling = it.settling || clock() < transferSettledAt) }
                val state = ReceiverPlayback(active?.receiver, active?.tuning?.value, metrics,
                    connecting = other?.receiver?.takeIf { pending },
                    retained = other?.receiver?.takeIf { !pending && want.keepSeconds > 0 }, error = error)
                if (state != previous) { previous = state; onPlayback(state) }
            }
        } finally {
            collector.cancel()
            withContext(NonCancellable) {
                other?.let { remove(it) }
                active?.let { remove(it) }
            }
            events.close()
            commands.close()
        }
    }
}
