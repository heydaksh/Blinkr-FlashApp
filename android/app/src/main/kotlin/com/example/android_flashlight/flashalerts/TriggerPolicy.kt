package com.example.android_flashlight.flashalerts

/** Privacy-safe notification description. Never contains title/text. */
data class NotificationDescriptor(
    val pkg: String,
    val category: String?,
    val flags: Int,
    val importance: Int,
    val matchesInterruptionFilter: Boolean,
    val keyHash: String,
    val postTime: Long,
    val hasFullScreenIntent: Boolean,
    val isDefaultDialer: Boolean = false,
)

data class DeviceState(
    val interactive: Boolean = true,
    val keyguardLocked: Boolean = false,
    /** null, "notification" or "call" */
    val engineKind: String? = null,
    val ringerMode: Int = RINGER_MODE_NORMAL,
) {
    companion object {
        const val RINGER_MODE_SILENT = 0
        const val RINGER_MODE_VIBRATE = 1
        const val RINGER_MODE_NORMAL = 2
    }
}

sealed class Decision {
    data class Ignore(val reason: String) : Decision()
    object FlashOnce : Decision()
    object FlashCall : Decision()
    object ReplaceWithCall : Decision()
    object StopCall : Decision()
}

/** Pure decision logic (Section 4.3). Not thread-safe by itself; callers synchronize. */
class TriggerPolicy(private val clock: () -> Long) {
    private val seen = HashMap<String, Long>()
    private var lastFlashStart: Long? = null
    private var lastFlashEnd: Long? = null
    var activeCallKey: String? = null
        private set

    @Synchronized
    fun decide(n: NotificationDescriptor, s: FlashSettings, state: DeviceState): Decision {
        val now = clock()
        if (!s.enabled) return ignore("DISABLED")

        when (state.ringerMode) {
            DeviceState.RINGER_MODE_SILENT -> if (!s.flashOnSilent) return ignore("RINGER_SILENT")
            DeviceState.RINGER_MODE_VIBRATE -> if (!s.flashOnVibrate) return ignore("RINGER_VIBRATE")
            DeviceState.RINGER_MODE_NORMAL -> if (!s.flashOnNormal) return ignore("RINGER_NORMAL")
        }

        if (isCall(n)) {
            if (activeCallKey == n.keyHash) {
                if (!isRinging(n)) {
                    activeCallKey = null
                    return Decision.StopCall
                }
                return ignore("DUPLICATE")
            }
            if (!s.callsEnabled) return ignore("DISABLED")
            if (!isRinging(n)) return ignore("ONGOING")
            activeCallKey = n.keyHash
            lastFlashStart = now
            return if (state.engineKind == "notification") Decision.ReplaceWithCall else Decision.FlashCall
        }

        if (!s.notificationsEnabled) return ignore("DISABLED")
        if (n.flags and FLAG_GROUP_SUMMARY != 0) return ignore("GROUP_SUMMARY")
        if (n.flags and (FLAG_ONGOING_EVENT or FLAG_FOREGROUND_SERVICE) != 0) return ignore("ONGOING")
        if (n.importance < IMPORTANCE_DEFAULT) return ignore("LOW_IMPORTANCE")
        if (s.respectDnd && !n.matchesInterruptionFilter) return ignore("DND")

        val prev = seen.put(n.keyHash, now)
        pruneSeen(now)
        if (prev != null && (now - prev < DEDUPE_MS || n.flags and FLAG_ONLY_ALERT_ONCE != 0)) return ignore("DUPLICATE")

        val listed = n.pkg in s.packages
        if ((s.packageFilterMode == FlashSettings.MODE_ALLOW && !listed) ||
            (s.packageFilterMode == FlashSettings.MODE_DENY && listed)
        ) return ignore("FILTERED_PKG")

        if (s.onlyWhenScreenOff && state.interactive && !state.keyguardLocked) return ignore("SCREEN_ON")

        val ref = listOfNotNull(lastFlashStart, lastFlashEnd).maxOrNull()
        if (ref != null && now - ref < s.cooldownMs) return ignore("COOLDOWN")

        lastFlashStart = now
        return Decision.FlashOnce
    }

    /** Returns true if [keyHash] was the active call notification. */
    @Synchronized
    fun onRemoved(keyHash: String): Boolean {
        if (activeCallKey == keyHash) {
            activeCallKey = null
            return true
        }
        return false
    }

    @Synchronized
    fun clearCall() { activeCallKey = null }

    @Synchronized
    fun onFlashEnded() { lastFlashEnd = clock() }

    private fun ignore(reason: String) = Decision.Ignore(reason)

    private fun pruneSeen(now: Long) {
        if (seen.size > 256) seen.entries.removeAll { now - it.value > 60_000 }
    }

    companion object {
        const val DEDUPE_MS = 5000L
        const val FLAG_ONGOING_EVENT = 0x2
        const val FLAG_ONLY_ALERT_ONCE = 0x8
        const val FLAG_FOREGROUND_SERVICE = 0x40
        const val FLAG_GROUP_SUMMARY = 0x200
        const val IMPORTANCE_DEFAULT = 3
        const val CATEGORY_CALL = "call"

        fun isCall(n: NotificationDescriptor) =
            n.category == CATEGORY_CALL || (n.isDefaultDialer && n.hasFullScreenIntent)

        /** Ringing heuristic: full-screen intent, or not an ongoing (in-call) notification. */
        fun isRinging(n: NotificationDescriptor) =
            n.hasFullScreenIntent || n.flags and FLAG_ONGOING_EVENT == 0
    }
}
