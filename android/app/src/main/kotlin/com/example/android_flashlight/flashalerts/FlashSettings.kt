package com.example.android_flashlight.flashalerts

data class FlashSettings(
    val enabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val callsEnabled: Boolean = true,
    val onlyWhenScreenOff: Boolean = false,
    val respectDnd: Boolean = true,
    val flashOnNormal: Boolean = true,
    val flashOnVibrate: Boolean = true,
    val flashOnSilent: Boolean = true,
    val notificationPattern: FlashPattern = FlashPattern(
        onMs = 60, offMs = 60, pulsesPerBurst = 3, burstGapMs = 400, bursts = 2, maxDurationMs = 3000,
    ),
    val callPattern: FlashPattern = FlashPattern(
        onMs = 60, offMs = 60, pulsesPerBurst = 3, burstGapMs = 500, bursts = Int.MAX_VALUE, maxDurationMs = 30_000,
    ),
    val strengthLevel: Int? = null,
    val packageFilterMode: String = MODE_ALL,
    val packages: Set<String> = emptySet(),
    val cooldownMs: Long = 2000,
    val dryRun: Boolean = false,
) {
    fun validated(minMs: Int): FlashSettings = copy(
        notificationPattern = notificationPattern.validated(minMs),
        callPattern = callPattern.validated(minMs),
        strengthLevel = strengthLevel?.takeIf { it >= 1 },
        packageFilterMode = if (packageFilterMode in MODES) packageFilterMode else MODE_ALL,
        packages = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        cooldownMs = cooldownMs.coerceIn(0, 60_000),
    )

    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "notificationsEnabled" to notificationsEnabled,
        "callsEnabled" to callsEnabled,
        "onlyWhenScreenOff" to onlyWhenScreenOff,
        "respectDnd" to respectDnd,
        "flashOnNormal" to flashOnNormal,
        "flashOnVibrate" to flashOnVibrate,
        "flashOnSilent" to flashOnSilent,
        "notificationPattern" to notificationPattern.toMap(),
        "callPattern" to callPattern.toMap(),
        "strengthLevel" to strengthLevel,
        "packageFilterMode" to packageFilterMode,
        "packages" to packages.toList().sorted(),
        "cooldownMs" to cooldownMs,
        "dryRun" to dryRun,
    )

    companion object {
        const val MODE_ALL = "all"
        const val MODE_ALLOW = "allowlist"
        const val MODE_DENY = "denylist"
        private val MODES = setOf(MODE_ALL, MODE_ALLOW, MODE_DENY)

        /** Merge a (possibly partial) map over [base]. Caller should then call validated(). */
        fun fromMap(map: Map<*, *>?, base: FlashSettings = FlashSettings()): FlashSettings {
            if (map == null) return base
            fun bool(k: String, d: Boolean) = map[k] as? Boolean ?: d
            return base.copy(
                enabled = bool("enabled", base.enabled),
                notificationsEnabled = bool("notificationsEnabled", base.notificationsEnabled),
                callsEnabled = bool("callsEnabled", base.callsEnabled),
                onlyWhenScreenOff = bool("onlyWhenScreenOff", base.onlyWhenScreenOff),
                respectDnd = bool("respectDnd", base.respectDnd),
                flashOnNormal = bool("flashOnNormal", base.flashOnNormal),
                flashOnVibrate = bool("flashOnVibrate", base.flashOnVibrate),
                flashOnSilent = bool("flashOnSilent", base.flashOnSilent),
                notificationPattern = FlashPattern.fromMap(map["notificationPattern"] as? Map<*, *>, base.notificationPattern),
                callPattern = FlashPattern.fromMap(map["callPattern"] as? Map<*, *>, base.callPattern),
                strengthLevel = if (map.containsKey("strengthLevel")) (map["strengthLevel"] as? Number)?.toInt() else base.strengthLevel,
                packageFilterMode = map["packageFilterMode"] as? String ?: base.packageFilterMode,
                packages = (map["packages"] as? Collection<*>)?.filterIsInstance<String>()?.toSet() ?: base.packages,
                cooldownMs = (map["cooldownMs"] as? Number)?.toLong() ?: base.cooldownMs,
                dryRun = bool("dryRun", base.dryRun),
            )
        }
    }
}
