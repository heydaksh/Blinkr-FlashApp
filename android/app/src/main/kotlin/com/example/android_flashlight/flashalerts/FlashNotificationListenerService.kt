package com.example.android_flashlight.flashalerts

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

/**
 * System-bound entry point. 100% native: no Flutter engine needed.
 * Privacy: only package, category, flags, importance and a hashed key are read.
 * Never touches notification extras (title / text).
 */
class FlashNotificationListenerService : NotificationListenerService() {
    private var callMonitor: CallStateMonitor? = null

    override fun onCreate() {
        super.onCreate()
        FlashRuntime.init(this)
    }

    override fun onListenerConnected() {
        connected = true
        FlashDiagnostics.log("LISTENER_CONNECTED", "sdk" to Build.VERSION.SDK_INT, "uptimeMs" to SystemClock.uptimeMillis())
        logBackgroundRestrictions()
        callMonitor = CallStateMonitor(this).also { it.start() }
    }

    override fun onListenerDisconnected() {
        connected = false
        FlashDiagnostics.log("LISTENER_DISCONNECTED", "sdk" to Build.VERSION.SDK_INT, "uptimeMs" to SystemClock.uptimeMillis())
        callMonitor?.stop()
        callMonitor = null
        if (Build.VERSION.SDK_INT >= 24) {
            FlashDiagnostics.log("LISTENER_REBIND_REQUESTED", "sdk" to Build.VERSION.SDK_INT, "uptimeMs" to SystemClock.uptimeMillis())
            try { requestRebind(ComponentName(this, FlashNotificationListenerService::class.java)) } catch (_: Exception) { }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        try {
            handlePosted(sbn, rankingMap)
        } catch (t: Throwable) {
            FlashDiagnostics.log("LISTENER_ERROR", "error" to t.javaClass.simpleName)
        }
    }

    private fun handlePosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val store = FlashRuntime.store(this)
        val settings = store.load()
        if (!settings.enabled) return

        val n = sbn.notification
        var importance = 3
        var matches = true
        val rank = Ranking()
        if (rankingMap.getRanking(sbn.key, rank)) {
            if (Build.VERSION.SDK_INT >= 24) importance = rank.importance
            matches = rank.matchesInterruptionFilter()
        }
        val dialer = FlashRuntime.defaultDialer(this)
        val d = NotificationDescriptor(
            pkg = sbn.packageName,
            category = n.category,
            flags = n.flags,
            importance = importance,
            matchesInterruptionFilter = matches,
            keyHash = hash(sbn.key),
            postTime = sbn.postTime,
            hasFullScreenIntent = n.fullScreenIntent != null,
            isDefaultDialer = dialer != null && dialer == sbn.packageName,
        )
        FlashDiagnostics.log("NOTIF_RECEIVED", "pkg" to d.pkg, "category" to d.category, "importance" to d.importance,
            "flags" to d.flags, "matchesFilter" to d.matchesInterruptionFilter, "keyHash" to d.keyHash)

        val engine = FlashRuntime.engine(this)
        when (val decision = FlashRuntime.policy.decide(d, settings, FlashRuntime.deviceState(this))) {
            is Decision.Ignore -> FlashDiagnostics.log("NOTIF_IGNORED", "pkg" to d.pkg, "reason" to decision.reason)
            Decision.FlashOnce -> engine.start(FlashRuntime.notificationPattern(this, settings), "notification:${d.pkg}",
                startDelayMs = FlashRuntime.NOTIFICATION_FLASH_DELAY_MS)
            Decision.FlashCall, Decision.ReplaceWithCall -> {
                FlashRuntime.beginCallFlash(this, settings, "notification")
            }
            Decision.StopCall -> {
                // With telephony permission the call state callback is authoritative; a dialer re-posting
                // its notification without a full-screen intent (common on a locked screen) must not
                // cut the flash short. Without it, fall back to the notification heuristic.
                if (FlashRuntime.telephonyActive) {
                    FlashDiagnostics.log("CALL_STOP_IGNORED", "source" to "notification")
                } else {
                    FlashDiagnostics.log("CALL_ENDED", "source" to "notification")
                    FlashRuntime.stopCall(this, "CALL_ANSWERED")
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            if (FlashRuntime.policy.onRemoved(hash(sbn.key))) {
                FlashDiagnostics.log("CALL_ENDED", "source" to "notification")
                FlashRuntime.stopCall(this, "CALL_NOTIFICATION_REMOVED")
            }
        } catch (_: Throwable) { }
    }

    private fun logBackgroundRestrictions() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
            var restricted: Boolean? = null
            var bucket: Int? = null
            if (Build.VERSION.SDK_INT >= 28) {
                restricted = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
                bucket = (getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
            }
            if (!ignoring || restricted == true) {
                FlashDiagnostics.log("BACKGROUND_RESTRICTED", "isIgnoringBatteryOptimizations" to ignoring,
                    "isBackgroundRestricted" to restricted, "standbyBucket" to bucket)
            }
        } catch (_: Exception) { }
    }

    companion object {
        @Volatile var connected = false

        fun hash(key: String?): String {
            val md = MessageDigest.getInstance("SHA-256").digest((key ?: "").toByteArray())
            return md.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
