package com.example.log

/**
 * In-memory ring buffer for device log lines. Pure Kotlin, no Android
 * dependencies, so the eviction / drain / restore logic is unit-testable
 * off-device (plain JUnit).
 *
 * - [add] appends and caps at [maxSize], dropping the OLDEST line on overflow.
 * - [drain] removes and returns up to [maxBatch] oldest lines, in chronological
 *   order, ready to send.
 * - [restore] puts a failed batch back at the FRONT, preserving order. If the
 *   buffer overflowed while the send was in flight, the oldest lines are dropped
 *   to stay within the cap (ring semantics: oldest go first).
 *
 * All operations are synchronized so the flush coroutine and the logging call
 * sites can touch it concurrently.
 */
class LogBuffer(
    private val maxSize: Int = 500,
    private val maxBatch: Int = 200,
) {
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    val size: Int
        get() = synchronized(lock) { lines.size }

    fun add(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > maxSize) lines.removeFirst()
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    /** Remove and return up to [maxBatch] oldest lines. Empty list if none. */
    fun drain(): List<String> = synchronized(lock) {
        if (lines.isEmpty()) return emptyList()
        val count = minOf(maxBatch, lines.size)
        ArrayList<String>(count).also { out ->
            repeat(count) { out.add(lines.removeFirst()) }
        }
    }

    /** Put a failed batch back at the front, keeping order and the size cap. */
    fun restore(batch: List<String>) {
        if (batch.isEmpty()) return
        synchronized(lock) {
            for (i in batch.indices.reversed()) lines.addFirst(batch[i])
            while (lines.size > maxSize) lines.removeFirst()
        }
    }
}
