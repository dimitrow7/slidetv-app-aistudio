package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.prefs.SignagePrefs

class BootCompletedReceiver : BroadcastReceiver() {
    private val tag = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(tag, "Received broadcast action: $action")
        
        val prefs = SignagePrefs(context)
        if (!prefs.isAutostartEnabled) {
            Log.d(tag, "Autostart is disabled in settings. Skipping launch.")
            return
        }
        
        if (Intent.ACTION_BOOT_COMPLETED == action || 
            "android.intent.action.QUICKBOOT_POWERON" == action ||
            "com.htc.intent.action.QUICKBOOT_POWERON" == action) {
            
            try {
                Log.d(tag, "Boot completed detected! Starting Digital Signage Player...")
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start MainActivity on boot: ${e.message}", e)
            }
        }
    }
}
