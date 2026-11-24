package com.example.android_flashlight.flashalerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * DEBUG BUILDS ONLY (src/debug). Drives the adb test scripts.
 *
 *  DEBUG_CONFIG  --ez enabled --ez dryRun --ez calls --ez notifications --ez respectDnd
 *                --ei cooldownMs --ei onMs --ei offMs --ei pulses --ei gapMs --ei bursts
 *  DEBUG_POST    posts a high-importance notification from this app
 */
class DebugConfigReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        FlashRuntime.init(ctx)
        when (intent.action?.substringAfterLast('.')) {
            "DEBUG_CONFIG" -> config(ctx, intent)
            "DEBUG_POST" -> post(ctx)
        }
    }

    private fun config(ctx: Context, i: Intent) {
        val store = FlashRuntime.store(ctx)
        val m = HashMap<String, Any?>()
        val e = i.extras
        fun bool(extra: String, key: String) { if (e?.containsKey(extra) == true) m[key] = i.getBooleanExtra(extra, false) }
        bool("enabled", "enabled"); bool("dryRun", "dryRun"); bool("calls", "callsEnabled")
        bool("notifications", "notificationsEnabled"); bool("respectDnd", "respectDnd")
        bool("onlyWhenScreenOff", "onlyWhenScreenOff")
        if (e?.containsKey("cooldownMs") == true) m["cooldownMs"] = i.getIntExtra("cooldownMs", 2000).toLong()
        val p = HashMap<String, Any?>()
        for ((extra, key) in listOf("onMs" to "onMs", "offMs" to "offMs", "pulses" to "pulsesPerBurst",
            "gapMs" to "burstGapMs", "bursts" to "bursts")) {
            if (e?.containsKey(extra) == true) p[key] = i.getIntExtra(extra, 0)
        }
        if (p.isNotEmpty()) m["notificationPattern"] = p
        val s = FlashSettings.fromMap(m, store.load()).validated(store.calibratedMinMs)
        store.save(s)
        FlashDiagnostics.log("DEBUG_CONFIG", "enabled" to s.enabled, "dryRun" to s.dryRun)
    }

    private fun post(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("flash_debug", "Flash debug", NotificationManager.IMPORTANCE_HIGH))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "flash_debug") else @Suppress("DEPRECATION") Notification.Builder(ctx)
        val n = b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("QA").setContentText("debug").build()
        nm.notify((System.nanoTime() % 100000).toInt(), n)
    }
}
