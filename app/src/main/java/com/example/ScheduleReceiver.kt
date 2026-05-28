package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class ScheduleReceiver : BroadcastReceiver() {
    private val tag = "ScheduleReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(tag, "Received schedule alarm action: $action")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SlideTVApp:ScheduleReceiverWakeLock"
        )
        
        try {
            // Acquire partial wake lock for up to 10 seconds to ensure the CPU stays fully awake while we start the activity
            wakeLock.acquire(10000)
            Log.d(tag, "Acquired partial WakeLock for launching MainActivity")

            val scheduleAction = when (action) {
                ScheduleManager.ACTION_SLEEP -> "sleep"
                ScheduleManager.ACTION_WAKE -> "wake"
                else -> null
            }

            if (scheduleAction != null) {
                // Reschedule next occurrences of sleep and wake alarms for the next cyclical period
                ScheduleManager.updateAlarms(context)

                // Start or bring MainActivity to the foreground
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("schedule_action", scheduleAction)
                }
                context.startActivity(launchIntent)
                Log.d(tag, "Dispatched intent to MainActivity with schedule_action=$scheduleAction")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing schedule alarm: ${e.message}", e)
        } finally {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }
}
