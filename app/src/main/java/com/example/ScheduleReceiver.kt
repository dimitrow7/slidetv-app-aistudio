package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

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

                // 1. Direct App Start (Works instantly if the app has draw-over-apps overlay permission)
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("schedule_action", scheduleAction)
                    }
                    context.startActivity(launchIntent)
                    Log.d(tag, "Dispatched direct intent to MainActivity with schedule_action=$scheduleAction")
                } catch (e: Exception) {
                    Log.e(tag, "Direct launch failed (common in background restricts): ${e.message}")
                }

                // 2. High-Priority FullScreenIntent Notification (Can physically wake up locked screen and force background launch)
                showFullScreenNotification(context, scheduleAction)
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

    private fun showFullScreenNotification(context: Context, scheduleAction: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "slidetv_schedule_channel"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId, 
                    "Управление на Екрана (График)", 
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Използва се за автоматично събуждане и заспиване на плеъра"
                    enableLights(true)
                    enableVibration(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("schedule_action", scheduleAction)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val actionTitle = if (scheduleAction == "wake") "График: Събуждане" else "График: Сън"
            val actionText = if (scheduleAction == "wake") "Плеърът преминава в АКТИВЕН режим" else "Плеърът преминава в режим СЪН"

            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(actionTitle)
                .setContentText(actionText)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .setSilent(true)

            notificationManager.notify(1001, notificationBuilder.build())
            Log.d(tag, "Successfully issued FullScreenIntent wake notification")
        } catch (e: Exception) {
            Log.e(tag, "Failed to present full-screen wake notification: ${e.message}", e)
        }
    }
}
