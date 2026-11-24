package com.example.android_flashlight.flashalerts

import org.junit.Assert.*
import org.junit.Test

class TriggerPolicyTest {
    private var now = 100_000L
    private val policy = TriggerPolicy { now }
    private val on = FlashSettings(enabled = true)
    private val state = DeviceState()
    private var seq = 0

    private fun n(
        pkg: String = "com.app", flags: Int = 0, importance: Int = 4, dnd: Boolean = true,
        key: String = "k${seq++}", category: String? = null, fsi: Boolean = false,
    ) = NotificationDescriptor(pkg, category, flags, importance, dnd, key, now, fsi)

    private fun ignored(d: Decision, reason: String) = assertEquals(Decision.Ignore(reason), d)

    @Test fun disabled() = ignored(policy.decide(n(), on.copy(enabled = false), state), "DISABLED")
    @Test fun groupSummary() = ignored(policy.decide(n(flags = TriggerPolicy.FLAG_GROUP_SUMMARY), on, state), "GROUP_SUMMARY")
    @Test fun ongoing() = ignored(policy.decide(n(flags = TriggerPolicy.FLAG_ONGOING_EVENT), on, state), "ONGOING")
    @Test fun foregroundService() = ignored(policy.decide(n(flags = TriggerPolicy.FLAG_FOREGROUND_SERVICE), on, state), "ONGOING")
    @Test fun lowImportance() = ignored(policy.decide(n(importance = 2), on, state), "LOW_IMPORTANCE")
    @Test fun dndBlocked() = ignored(policy.decide(n(dnd = false), on, state), "DND")
    @Test fun dndIgnoredWhenNotRespected() =
        assertEquals(Decision.FlashOnce, policy.decide(n(dnd = false), on.copy(respectDnd = false), state))

    @Test fun filteredPackages() {
        ignored(policy.decide(n("a"), on.copy(packageFilterMode = "allowlist", packages = setOf("b")), state), "FILTERED_PKG")
        now += 10_000
        ignored(policy.decide(n("b"), on.copy(packageFilterMode = "denylist", packages = setOf("b")), state), "FILTERED_PKG")
        now += 10_000
        assertEquals(Decision.FlashOnce, policy.decide(n("b"), on.copy(packageFilterMode = "allowlist", packages = setOf("b")), state))
    }

    @Test fun sameKeyWithin5sFlashesOnce() {
        val s = on.copy(cooldownMs = 0)
        assertEquals(Decision.FlashOnce, policy.decide(n(key = "x"), s, state))
        now += 1000
        ignored(policy.decide(n(key = "x"), s, state), "DUPLICATE")
        now += 6000
        assertEquals(Decision.FlashOnce, policy.decide(n(key = "x"), s, state))
    }

    @Test fun onlyAlertOnceFlagIgnoresUpdatesForever() {
        val s = on.copy(cooldownMs = 0)
        assertEquals(Decision.FlashOnce, policy.decide(n(key = "y", flags = TriggerPolicy.FLAG_ONLY_ALERT_ONCE), s, state))
        now += 60_000
        ignored(policy.decide(n(key = "y", flags = TriggerPolicy.FLAG_ONLY_ALERT_ONCE), s, state), "DUPLICATE")
    }

    @Test fun burstOf10GivesOneFlash() {
        val results = (0 until 10).map { now += 100; policy.decide(n(), on, state) }
        assertEquals(1, results.count { it == Decision.FlashOnce })
        assertEquals(9, results.count { it == Decision.Ignore("COOLDOWN") })
    }

    @Test fun cooldownMeasuredFromRunEnd() {
        assertEquals(Decision.FlashOnce, policy.decide(n(), on, state))
        now += 3000; policy.onFlashEnded()
        now += 1500
        ignored(policy.decide(n(), on, state), "COOLDOWN")
        now += 600
        assertEquals(Decision.FlashOnce, policy.decide(n(), on, state))
    }

    @Test fun callCategoryFlashesCall() =
        assertEquals(Decision.FlashCall, policy.decide(n(category = "call", importance = 4), on, state))

    @Test fun callDuringNotificationFlashReplaces() =
        assertEquals(Decision.ReplaceWithCall, policy.decide(n(category = "call"), on, state.copy(engineKind = "notification")))

    @Test fun callsToggleOff() = ignored(policy.decide(n(category = "call"), on.copy(callsEnabled = false), state), "DISABLED")

    @Test fun callUpdatedToOngoingStops() {
        assertEquals(Decision.FlashCall, policy.decide(n(key = "c", category = "call", fsi = true), on, state))
        assertEquals(Decision.StopCall, policy.decide(n(key = "c", category = "call", flags = TriggerPolicy.FLAG_ONGOING_EVENT), on, state))
        assertNull(policy.activeCallKey)
    }

    @Test fun callRemovedClearsActive() {
        policy.decide(n(key = "c2", category = "call"), on, state)
        assertTrue(policy.onRemoved("c2"))
        assertFalse(policy.onRemoved("c2"))
    }

    @Test fun defaultDialerFullScreenIntentIsCall() {
        val d = n(fsi = true).copy(isDefaultDialer = true)
        assertEquals(Decision.FlashCall, policy.decide(d, on, state))
    }

    @Test fun screenOnIgnoredWhenOnlyScreenOff() {
        val s = on.copy(onlyWhenScreenOff = true)
        ignored(policy.decide(n(), s, DeviceState(interactive = true)), "SCREEN_ON")
        now += 10_000
        assertEquals(Decision.FlashOnce, policy.decide(n(), s, DeviceState(interactive = false)))
        now += 10_000
        assertEquals(Decision.FlashOnce, policy.decide(n(), s, DeviceState(interactive = true, keyguardLocked = true)))
    }

    @Test fun ringerModesRespected() {
        // Normal mode
        val stateNormal = DeviceState(ringerMode = DeviceState.RINGER_MODE_NORMAL)
        assertEquals(Decision.FlashOnce, policy.decide(n(), on.copy(flashOnNormal = true), stateNormal))
        now += 10_000
        ignored(policy.decide(n(), on.copy(flashOnNormal = false), stateNormal), "RINGER_NORMAL")

        // Vibrate mode
        now += 10_000
        val stateVibrate = DeviceState(ringerMode = DeviceState.RINGER_MODE_VIBRATE)
        assertEquals(Decision.FlashOnce, policy.decide(n(), on.copy(flashOnVibrate = true), stateVibrate))
        now += 10_000
        ignored(policy.decide(n(), on.copy(flashOnVibrate = false), stateVibrate), "RINGER_VIBRATE")

        // Silent mode
        now += 10_000
        val stateSilent = DeviceState(ringerMode = DeviceState.RINGER_MODE_SILENT)
        assertEquals(Decision.FlashOnce, policy.decide(n(), on.copy(flashOnSilent = true), stateSilent))
        now += 10_000
        ignored(policy.decide(n(), on.copy(flashOnSilent = false), stateSilent), "RINGER_SILENT")
    }
}
