package com.example.android_flashlight.flashalerts

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

object DeviceStatus {
    fun listenerComponent(ctx: Context) = ComponentName(ctx, FlashNotificationListenerService::class.java)

    fun isListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }

    fun snapshot(ctx: Context): Map<String, Any?> {
        val store = FlashRuntime.store(ctx)
        val driver = FlashRuntime.driver(ctx)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        } catch (_: Exception) { null }
        val map = mapOf(
            "supported" to (Build.VERSION.SDK_INT >= 23),
            "hasFlash" to driver.hasFlash,
            "sdkInt" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "listenerEnabled" to isListenerEnabled(ctx),
            "listenerConnected" to FlashNotificationListenerService.connected,
            "phoneStatePermission" to (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED),
            "batteryOptimized" to !pm.isIgnoringBatteryOptimizations(ctx.packageName),
            "maxStrengthLevel" to driver.maxStrengthLevel(),
            "calibratedMinMs" to store.calibratedMinMs,
            "systemFlashNotificationsLikelyOn" to systemFlashLikelyOn(ctx),
            "sideloaded" to (installer == null),
            "debuggable" to FlashRuntime.isDebuggable(ctx),
        )
        FlashDiagnostics.log("STATUS_SNAPSHOT", *map.map { it.key to it.value }.toTypedArray())
        return map
    }

    /** Best effort: AOSP key (Android 14+) and a couple of OEM guesses. */
    private fun systemFlashLikelyOn(ctx: Context): Boolean {
        for (key in listOf("camera_flash_notification", "flash_notification")) {
            try {
                if (Settings.System.getInt(ctx.contentResolver, key, 0) == 1) return true
            } catch (_: Exception) { }
        }
        return false
    }
}

object ListenerHealth {
    private var checked = false

    /** OEM quirk: listener enabled but never rebound after an update. Force a rebind once per process. */
    fun scheduleCheck(ctx: Context) {
        if (checked) return
        checked = true
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            if (DeviceStatus.isListenerEnabled(app) && !FlashNotificationListenerService.connected) {
                try {
                    val cn = DeviceStatus.listenerComponent(app)
                    val pm = app.packageManager
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    FlashDiagnostics.log("LISTENER_REBIND_FORCED", "sdk" to Build.VERSION.SDK_INT,
                        "uptimeMs" to android.os.SystemClock.uptimeMillis())
                } catch (_: Exception) { }
            }
        }, 3000)
    }
}
