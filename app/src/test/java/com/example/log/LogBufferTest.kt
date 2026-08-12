package com.example.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side unit tests for the log ring buffer. No Android dependencies, so
 * these run on plain JUnit.
 */
class LogBufferTest {

    @Test
    fun `add keeps everything under the cap`() {
        val buf = LogBuffer(maxSize = 5, maxBatch = 2)
        buf.add("a")
        buf.add("b")
        assertEquals(2, buf.size)
    }

    @Test
    fun `add drops the oldest line on overflow`() {
        val buf = LogBuffer(maxSize = 3, maxBatch = 10)
        listOf("1", "2", "3", "4", "5").forEach { buf.add(it) }
        // Cap is 3, so 1 and 2 were evicted; 3,4,5 remain in order.
        assertEquals(3, buf.size)
        assertEquals(listOf("3", "4", "5"), buf.drain())
    }

    @Test
    fun `drain returns at most maxBatch oldest lines and removes them`() {
        val buf = LogBuffer(maxSize = 10, maxBatch = 2)
        listOf("a", "b", "c").forEach { buf.add(it) }
        assertEquals(listOf("a", "b"), buf.drain())
        assertEquals(1, buf.size)
        assertEquals(listOf("c"), buf.drain())
        assertEquals(0, buf.size)
    }

    @Test
    fun `drain on empty buffer returns empty list`() {
        val buf = LogBuffer()
        assertTrue(buf.drain().isEmpty())
    }

    @Test
    fun `restore puts a failed batch back at the front in order`() {
        val buf = LogBuffer(maxSize = 10, maxBatch = 10)
        buf.add("new1")
        buf.add("new2")
        buf.restore(listOf("old1", "old2"))
        // Restored lines are the oldest, so they drain first, in original order.
        assertEquals(listOf("old1", "old2", "new1", "new2"), buf.drain())
    }

    @Test
    fun `restore drops the oldest when the batch overflows the cap`() {
        val buf = LogBuffer(maxSize = 3, maxBatch = 10)
        buf.add("n1")
        buf.add("n2")
        buf.add("n3")
        // Buffer is already full; restoring 2 older lines overflows to 5, and the
        // 2 oldest (the just-restored ones) are dropped to stay at the cap of 3.
        buf.restore(listOf("o1", "o2"))
        assertEquals(3, buf.size)
        assertEquals(listOf("n1", "n2", "n3"), buf.drain())
    }

    @Test
    fun `clear empties the buffer`() {
        val buf = LogBuffer()
        buf.add("x")
        buf.clear()
        assertEquals(0, buf.size)
    }
}
