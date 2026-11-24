package com.example.android_flashlight.flashalerts

import org.junit.Assert.*
import org.junit.Test

class CalibratorAndSettingsTest {
    private fun stat(i: Int, r: Double, l: Double) = Calibrator.IntervalStat(i, r, l)

    @Test fun picksSmallestPassingContiguousInterval() {
        val r = Calibrator.pick(listOf(stat(100, 1.0, 10.0), stat(80, 1.0, 10.0), stat(60, 0.97, 20.0),
            stat(50, 0.8, 20.0), stat(40, 1.0, 5.0)))
        assertEquals(60, r.calibratedMinMs)
    }

    @Test fun failsAtSlowestGives120() =
        assertEquals(120, Calibrator.pick(listOf(stat(100, 0.5, 90.0), stat(80, 1.0, 5.0))).calibratedMinMs)

    @Test fun latencyMustBeBelowHalfInterval() =
        assertEquals(120, Calibrator.pick(listOf(stat(100, 1.0, 50.0))).calibratedMinMs)

    @Test fun allPassClampsTo20() =
        assertEquals(20, Calibrator.pick(Calibrator.INTERVALS.map { stat(it, 1.0, 1.0) }).calibratedMinMs)

    @Test fun settingsPartialMergeAndValidation() {
        val s = FlashSettings.fromMap(mapOf(
            "enabled" to true, "cooldownMs" to 999_999,
            "notificationPattern" to mapOf("onMs" to 5L), "packageFilterMode" to "bogus",
            "packages" to listOf(" a ", "", "b"),
        )).validated(40)
        assertTrue(s.enabled)
        assertEquals(60_000L, s.cooldownMs)
        assertEquals(40, s.notificationPattern.onMs)
        assertEquals(3, s.notificationPattern.pulsesPerBurst) // untouched default
        assertEquals("all", s.packageFilterMode)
        assertEquals(setOf("a", "b"), s.packages)
    }

    @Test fun settingsRoundTripThroughMap() {
        val s = FlashSettings(enabled = true, strengthLevel = 2, packages = setOf("x"), packageFilterMode = "allowlist")
        assertEquals(s, FlashSettings.fromMap(s.toMap()))
    }
}
