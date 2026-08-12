package com.example.log

import com.example.BuildConfig
import com.example.api.DeviceApiClient
import com.example.api.LogRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Buffers native lifecycle log lines in memory and ships them to
 * POST /api/device/log in batches. Object style mirrors [DeviceApiClient].
 *
 * Behaviour (device decides, the server has no gate):
 * - Ring buffer (~500 lines, drops oldest). Nothing is written to disk.
 * - Flush every 60s OR once 50 lines have accumulated, whichever comes first;
 *   at most 200 lines per request.
 * - Network error is silent: the lines stay buffered for the next attempt, with
 *   no retry loop.
 * - Respects logs_enabled from poll. Disabled means nothing is sent and the
 *   buffer does not grow.
 * - Hourly request cap so a noisy event source cannot hammer the endpoint (the
 *   old client had none — 11k lines in a few weeks).
 * - Only native lifecycle events are shipped (app_version like "1.0.5"). Web
 *   player logs are NOT relayed here; the web player posts them directly with a
 *   "web-" version prefix, so the two never duplicate.
 */
object DeviceLogger {
    private const val TICK_MS = 5_000L
    private const val FLUSH_INTERVAL_MS = 60_000L
    private const val FLUSH_THRESHOLD = 50
    private const val MAX_REQUESTS_PER_HOUR = 60
    private const val HOUR_MS = 3_600_000L

    private val buffer = LogBuffer(maxSize = 500, maxBatch = 200)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var enabled = false
    @Volatile private var token: String? = null
    @Volatile private var baseUrl: String = ""
    @Volatile private var started = false

    // "player started" is emitted once, on the first poll that reports logging
    // on — the buffer is disabled at process start (default off) so a line added
    // in onCreate would just be dropped.
    @Volatile private var startAnnounced = false

    private var windowStart = 0L
    private var requestsInWindow = 0
    private var lastFlush = 0L

    /** Idempotent. Call once from onCreate with the API base URL. */
    fun start(baseUrl: String) {
        this.baseUrl = baseUrl
        if (started) return
        started = true
        lastFlush = System.currentTimeMillis()
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (!enabled) continue
                val now = System.currentTimeMillis()
                val due = now - lastFlush >= FLUSH_INTERVAL_MS
                val full = buffer.size >= FLUSH_THRESHOLD
                if (due || full) {
                    flush()
                    lastFlush = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Apply the latest poll state. Turning logging off clears the buffer.
     *
     * Pairing is detected here rather than at the call site: logging only turns
     * on after a poll returns logs_enabled, so a "paired" line logged the moment
     * the token appears would be a no-op (enabled still false) and the first —
     * and most important — event would be lost.
     */
    fun updateConfig(enabled: Boolean, token: String?) {
        val tokenChanged = token != null && token != this.token
        this.token = token
        if (this.enabled && !enabled) {
            buffer.clear()
            startAnnounced = false
        }
        this.enabled = enabled
        if (enabled && !startAnnounced) {
            startAnnounced = true
            log("player started v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
        if (enabled && tokenChanged) {
            log("paired (device token acquired)")
        }
    }

    /** Record a lifecycle event. No-op while logging is disabled. */
    fun log(message: String) {
        if (!enabled) return
        buffer.add("${System.currentTimeMillis()}|$message")
    }

    private suspend fun flush() {
        val tok = token
        if (tok.isNullOrEmpty() || baseUrl.isEmpty()) return

        // Hourly request cap (fixed window).
        val now = System.currentTimeMillis()
        if (now - windowStart >= HOUR_MS) {
            windowStart = now
            requestsInWindow = 0
        }
        if (requestsInWindow >= MAX_REQUESTS_PER_HOUR) return

        val batch = buffer.drain()
        if (batch.isEmpty()) return

        requestsInWindow++ // count the attempt so failures can't dodge the cap
        try {
            DeviceApiClient.getService(baseUrl).log(
                tok,
                LogRequestBody(
                    logs = batch,
                    appVersion = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                ),
            )
        } catch (e: Exception) {
            // Silent: keep the lines for the next attempt, no retry storm.
            buffer.restore(batch)
        }
    }
}
