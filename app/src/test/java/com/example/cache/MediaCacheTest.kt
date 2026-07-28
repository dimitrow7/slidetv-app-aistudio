package com.example.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-side unit tests for the media cache LRU eviction. No Android dependencies,
 * so these run on plain JUnit.
 */
class MediaCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tmp.newFolder("signage_media_cache")
    }

    /** Writes a file of [sizeBytes] and stamps it [ageSeconds] seconds in the past. */
    private fun writeFile(name: String, sizeBytes: Int, ageSeconds: Long = 0): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(sizeBytes))
        file.setLastModified(BASE_TIME - ageSeconds * 1000L)
        return file
    }

    private fun cache(maxBytes: Long) = MediaCache(dir) { maxBytes }

    @Test
    fun `trim keeps everything when total is under the cap`() {
        val a = writeFile("a.jpg", 100, ageSeconds = 300)
        val b = writeFile("b.jpg", 100, ageSeconds = 100)

        cache(maxBytes = 1000).trim()

        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun `trim keeps everything when total exactly equals the cap`() {
        val a = writeFile("a.jpg", 100, ageSeconds = 300)
        val b = writeFile("b.jpg", 100, ageSeconds = 100)

        cache(maxBytes = 200).trim()

        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun `trim evicts the least recently used files first`() {
        val oldest = writeFile("oldest.mp4", 100, ageSeconds = 900)
        val middle = writeFile("middle.mp4", 100, ageSeconds = 600)
        val newest = writeFile("newest.mp4", 100, ageSeconds = 300)

        cache(maxBytes = 250).trim()

        assertFalse("oldest must be evicted", oldest.exists())
        assertTrue("middle must survive", middle.exists())
        assertTrue("newest must survive", newest.exists())
    }

    @Test
    fun `trim keeps evicting until the total is at or below the cap`() {
        writeFile("a.mp4", 100, ageSeconds = 900)
        writeFile("b.mp4", 100, ageSeconds = 800)
        writeFile("c.mp4", 100, ageSeconds = 700)
        val newest = writeFile("d.mp4", 100, ageSeconds = 100)

        cache(maxBytes = 150).trim()

        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(newest.exists())
    }

    @Test
    fun `trim never deletes in-flight tmp downloads`() {
        val inFlight = writeFile("downloading.mp4.tmp", 500, ageSeconds = 999)
        val complete = writeFile("done.mp4", 100, ageSeconds = 1)

        cache(maxBytes = 100).trim()

        assertTrue("a .tmp download in progress must survive eviction", inFlight.exists())
    }

    @Test
    fun `tmp bytes count towards the cap`() {
        writeFile("downloading.mp4.tmp", 400, ageSeconds = 999)
        val complete = writeFile("done.mp4", 100, ageSeconds = 1)

        cache(maxBytes = 450).trim()

        assertFalse("complete file must be evicted because the .tmp fills the cap", complete.exists())
    }

    @Test
    fun `trim terminates when only tmp files remain over the cap`() {
        val inFlight = writeFile("downloading.mp4.tmp", 900, ageSeconds = 999)

        cache(maxBytes = 100).trim()

        assertTrue(inFlight.exists())
    }

    @Test
    fun `trim ignores subdirectories`() {
        File(dir, "nested").mkdirs()
        val file = writeFile("a.jpg", 100, ageSeconds = 1)

        cache(maxBytes = 1000).trim()

        assertTrue(file.exists())
    }

    @Test
    fun `trim on a missing directory does not throw`() {
        dir.deleteRecursively()

        cache(maxBytes = 100).trim()
    }

    @Test
    fun `trim is a no-op when the cap is zero or negative`() {
        val file = writeFile("a.jpg", 100, ageSeconds = 900)

        cache(maxBytes = 0).trim()

        assertTrue("a non-positive cap means unlimited, not wipe-everything", file.exists())
    }

    @Test
    fun `touch marks a file as recently used so it survives eviction`() {
        val stale = writeFile("stale.mp4", 100, ageSeconds = 900)
        val recentlyDownloaded = writeFile("recent.mp4", 100, ageSeconds = 100)

        MediaCache(dir) { 150 }.apply {
            touch(stale)
            trim()
        }

        assertTrue("touched file must survive", stale.exists())
        assertFalse("untouched older-by-access file must be evicted", recentlyDownloaded.exists())
    }

    @Test
    fun `touch on a missing file does not throw`() {
        MediaCache(dir) { 100 }.touch(File(dir, "gone.mp4"))
    }

    @Test
    fun `trim reports how many bytes it freed`() {
        writeFile("a.mp4", 100, ageSeconds = 900)
        writeFile("b.mp4", 100, ageSeconds = 800)
        writeFile("c.mp4", 100, ageSeconds = 100)

        assertEquals(200L, cache(maxBytes = 150).trim())
    }

    @Test
    fun `trim reports zero when nothing needed evicting`() {
        writeFile("a.mp4", 100, ageSeconds = 900)

        assertEquals(0L, cache(maxBytes = 1000).trim())
    }

    @Test
    fun `currentSizeBytes sums every file including tmp`() {
        writeFile("a.jpg", 100)
        writeFile("b.mp4.tmp", 50)

        assertEquals(150L, cache(maxBytes = 1000).currentSizeBytes())
    }

    private companion object {
        /** Fixed "now" so ages are deterministic and comfortably in the past. */
        const val BASE_TIME = 1_700_000_000_000L
    }
}
