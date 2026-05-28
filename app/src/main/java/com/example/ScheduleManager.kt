package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.prefs.SignagePrefs
import java.util.Calendar

object ScheduleManager {
    private const val TAG = "ScheduleManager"
    const val ACTION_SLEEP = "com.example.ACTION_SLEEP"
    const val ACTION_WAKE = "com.example.ACTION_WAKE"

    fun updateAlarms(context: Context) {
        val prefs = SignagePrefs(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create Intents
        val sleepIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SLEEP
        }
        val wakeIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_WAKE
        }

        // Cancel previous alarms
        val sleepPendingIntent = PendingIntent.getBroadcast(
            context,
            101,
            sleepIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val wakePendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(sleepPendingIntent)
        alarmManager.cancel(wakePendingIntent)

        if (!prefs.isScheduleEnabled) {
            Log.d(TAG, "Schedule is disabled. Existing alarms cancelled.")
            return
        }

        val now = Calendar.getInstance()

        // Calculate next sleep time
        val sleepCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.sleepHour)
            set(Calendar.MINUTE, prefs.sleepMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (sleepCal.before(now)) {
            sleepCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Calculate next wake time
        val wakeCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.wakeHour)
            set(Calendar.MINUTE, prefs.wakeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (wakeCal.before(now)) {
            wakeCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        Log.d(TAG, "Next Sleep Time: ${sleepCal.time}")
        Log.d(TAG, "Next Wake Time: ${wakeCal.time}")

        // Schedule alarms (using RTC_WAKEUP to wake the device)
        scheduleAlarm(context, alarmManager, sleepCal.timeInMillis, sleepPendingIntent)
        scheduleAlarm(context, alarmManager, wakeCal.timeInMillis, wakePendingIntent)
    }

    fun isCurrentlyInSleepSchedule(context: Context): Boolean {
        val prefs = SignagePrefs(context)
        if (!prefs.isScheduleEnabled) return false

        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val sleepTimeStart = prefs.sleepHour * 60 + prefs.sleepMinute
        val wakeTimeStart = prefs.wakeHour * 60 + prefs.wakeMinute
        val currentTimeVal = currentHour * 60 + currentMinute

        return if (sleepTimeStart < wakeTimeStart) {
            currentTimeVal in sleepTimeStart until wakeTimeStart
        } else if (sleepTimeStart > wakeTimeStart) {
            currentTimeVal >= sleepTimeStart || currentTimeVal < wakeTimeStart
        } else {
            false
        }
    }

    private fun scheduleAlarm(context: Context, alarmManager: AlarmManager, triggerTimeMs: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
                        Log.d(TAG, "Scheduled exact alarm with allow while idle (canScheduleExactAlarms = true)")
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
                        Log.d(TAG, "Scheduled alarm via setAndAllowWhileIdle (canScheduleExactAlarms = false)")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
                    Log.d(TAG, "Scheduled exact alarm with allow while idle (M+ to R)")
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
                Log.d(TAG, "Scheduled exact alarm (pre-M)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact alarm, falling back to setAndAllowWhileIdle: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Critical: Failed all attempts to schedule alarm", ex)
            }
        }
    }
}
