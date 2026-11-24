package com.example.android_flashlight.flashalerts

import org.junit.Assert.*
import org.junit.Test

class FlashPatternTest {
    @Test fun defaultTimelineMatchesExpected() {
        val t = FlashPattern(60, 60, 3, 400, 2, 3000).toTimeline()
        val expected = listOf(
            0L to true, 60L to false, 120L to true, 180L to false, 240L to true, 300L to false,
            760L to true, 820L to false, 880L to true, 940L to false, 1000L to true, 1060L to false,
        )
        assertEquals(expected, t.map { it.offsetMs to it.on })
        assertFalse(t.last().on)
    }

    @Test fun clampsToCalibratedMin() {
        val p = FlashPattern(onMs = 5, offMs = 5).validated(40)
        assertEquals(40, p.onMs); assertEquals(40, p.offMs)
        assertEquals(20, FlashPattern(onMs = 1, offMs = 1).validated(0).onMs)
    }

    @Test fun truncatesAtMaxDurationEndingOff() {
        val p = FlashPattern(60, 60, 3, 500, Int.MAX_VALUE, 1000)
        val t = p.toTimeline()
        assertTrue(t.all { it.offsetMs <= 1000 })
        assertFalse(t.last().on)
        assertEquals(1000L, t.last().offsetMs.coerceAtMost(1000))
        // on/off strictly alternate
        t.forEachIndexed { i, s -> assertEquals(i % 2 == 0, s.on) }
    }

    @Test fun midPulseTruncationEndsOffAtLimit() {
        val t = FlashPattern(onMs = 60, offMs = 60, pulsesPerBurst = 1, burstGapMs = 0, bursts = 100, maxDurationMs = 150).toTimeline()
        assertEquals(150L, t.last().offsetMs)
        assertFalse(t.last().on)
    }

    @Test fun invalidValuesNormalized() {
        val p = FlashPattern(-5, 0, 0, -1, 0, -1, 0).validated(0)
        assertTrue(p.onMs >= 20 && p.offMs >= 20)
        assertEquals(1, p.pulsesPerBurst); assertEquals(0, p.burstGapMs); assertEquals(1, p.bursts)
        assertEquals(100, p.maxDurationMs); assertNull(p.strengthLevel)
        assertTrue(p.toTimeline().isNotEmpty())
    }

    @Test fun totalCappedAt30s() {
        assertEquals(30_000, FlashPattern(maxDurationMs = 999_999).validated().maxDurationMs)
        assertTrue(FlashPattern(60, 60, 3, 500, Int.MAX_VALUE, 99_999).totalMs() <= 30_000)
    }
}
