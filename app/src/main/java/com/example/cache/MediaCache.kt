package com.example.cache

import java.io.File

/**
 * Size-capped LRU housekeeping for the player's `signage_media_cache` directory.
 *
 * The cache is served cache-first and, before this, only ever shrank on an explicit
 * "clear cache" (local button or remote command). On an 8-16 GB Android box a video
 * playlist filled the disk. [trim] enforces a byte ceiling by deleting the least
 * recently *used* entries first — [touch] stamps a file every time it is served, so
 * media in the active rotation keeps itself alive while retired media ages out.
 *
 * Deliberately free of Android imports: the eviction logic is plain [File] work and
 * is unit-tested on the host JVM.
 */
class MediaCache(
    private val dir: File,
    private val maxBytesProvider: () -> Long
) {
    /** Total bytes on disk, including partial `.tmp` downloads. */
    fun currentSizeBytes(): Long = files().sumOf { it.length() }

    /**
     * Records [file] as just-used. Eviction orders by last-modified, so this is what
     * turns "oldest download" into "least recently used".
     *
     * Stamps that are already fresher than [TOUCH_THRESHOLD_MS] are left alone: a
     * playing video issues a range request per chunk, and rewriting the stamp on each
     * one would be pointless disk churn.
     */
    fun touch(file: File) {
        try {
            if (!file.isFile) return
            val now = System.currentTimeMillis()
            if (now - file.lastModified() < TOUCH_THRESHOLD_MS) return
            file.setLastModified(now)
        } catch (e: Exception) {
            // A cache stamp is best-effort; a read-only clock or FS must not break serving.
        }
    }

    /**
     * Deletes least-recently-used entries until the directory fits under the cap.
     * A non-positive cap means "unlimited" and is a no-op.
     *
     * `.tmp` files are counted (they occupy real disk) but never deleted — they are
     * downloads in flight, and removing one would corrupt the request being served.
     *
     * @return bytes freed.
     */
    @Synchronized
    fun trim(): Long {
        val maxBytes = maxBytesProvider()
        if (maxBytes <= 0) return 0L

        val all = files()
        var total = all.sumOf { it.length() }
        if (total <= maxBytes) return 0L

        val candidates = all
            .filter { !it.name.endsWith(TMP_SUFFIX) }
            .sortedBy { it.lastModified() }

        var freed = 0L
        for (file in candidates) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                freed += size
            }
        }
        return freed
    }

    private fun files(): List<File> =
        try {
            dir.listFiles()?.filter { it.isFile } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    private companion object {
        const val TMP_SUFFIX = ".tmp"

        /** Below this age a file already counts as "recently used"; skip the re-stamp. */
        const val TOUCH_THRESHOLD_MS = 60_000L
    }
}
