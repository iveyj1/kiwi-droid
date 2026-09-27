package org.kiwidroid.core

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Streaming linear interpolation. Retains phase and the boundary sample across packets. */
class PcmResampler(sourceRate: Double, outputRate: Int = Protocol.OUTPUT_RATE) {
    private val stride = sourceRate / outputRate
    private var position = 0.0
    private var previous: Short = 0

    init {
        require(sourceRate.isFinite() && sourceRate in 8000.0..96000.0)
        require(outputRate > 0)
    }

    fun convert(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val output = ShortArray(ceil((samples.size + 1) / stride).toInt() + 1)
        var count = 0
        while (position < samples.lastIndex) {
            val index = floor(position).toInt()
            val left = if (index == -1) previous.toDouble() else samples[index].toDouble()
            val right = samples[index + 1].toDouble()
            output[count++] = (left + (right - left) * (position - index)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            position += stride
        }
        position -= samples.size
        previous = samples.last()
        return output.copyOf(count)
    }
}

/** Non-blocking producer, blocking consumer, bounded in samples rather than packet count. */
class PcmBuffer(private val capacity: Int) : AutoCloseable {
    private val lock = Object()
    private val chunks = ArrayDeque<ShortArray>()
    private var size = 0
    private var closed = false
    var droppedSamples: Long = 0
        private set

    init { require(capacity > 0) }

    fun offer(samples: ShortArray) = synchronized(lock) {
        if (closed || samples.isEmpty()) return@synchronized
        val kept = if (samples.size > capacity) samples.takeLast(capacity).toShortArray() else samples
        droppedSamples += samples.size - kept.size
        while (size + kept.size > capacity) {
            val dropped = chunks.removeFirst().size
            size -= dropped
            droppedSamples += dropped
        }
        chunks.addLast(kept)
        size += kept.size
        lock.notifyAll()
    }

    fun take(minimumSamples: Int = 1): ShortArray? = synchronized(lock) {
        require(minimumSamples in 1..capacity)
        while (!closed && size < minimumSamples) lock.wait()
        if (closed) null else chunks.removeFirst().also { size -= it.size }
    }

    fun queuedSamples(): Int = synchronized(lock) { size }

    override fun close() = synchronized(lock) {
        closed = true
        chunks.clear()
        size = 0
        lock.notifyAll()
    }
}
