package com.example.android_flashlight.flashalerts

import org.junit.Assert.*
import org.junit.Test

class FlashEngineTest {
    private val sched = FakeScheduler()
    private val torch = FakeTorch()
    private val diag = FakeDiag()
    private val wl = FakeWakeLock()
    private val engine = FlashEngine(sched, { torch }, diag, wl)
    private val pattern = FlashPattern(60, 60, 3, 400, 2, 3000)

    @Test fun endsOffAfterCompletion() {
        engine.start(pattern, "t")
        assertTrue(engine.isRunning); assertTrue(wl.held)
        sched.advanceTo(5000)
        assertFalse(engine.isRunning); assertFalse(torch.led); assertFalse(wl.held)
        assertEquals(12 + 1, torch.toggles.size) // 12 timeline steps + safety set(false) on every exit
        assertTrue(diag.has("FLASH_STOP reason=COMPLETED"))
    }

    @Test fun stopForcesOff() {
        engine.start(pattern, "t")
        sched.advanceTo(10) // LED on at t0+0
        assertTrue(torch.led)
        engine.stop("MANUAL")
        assertFalse(torch.led); assertFalse(engine.isRunning); assertFalse(wl.held)
        sched.advanceTo(5000)
        assertEquals(1, torch.toggles.count { it }.coerceAtMost(1)) // no further toggles after stop
        assertTrue(diag.has("FLASH_STOP reason=MANUAL"))
    }

    @Test fun toggleFailureEndsOff() {
        torch.failOn = true
        engine.start(pattern, "t")
        sched.advanceTo(100)
        assertFalse(engine.isRunning); assertFalse(torch.led)
        assertTrue(diag.has("FLASH_STOP reason=TOGGLE_FAILED"))
    }

    @Test fun secondStartCoalesced() {
        engine.start(pattern, "a")
        engine.start(pattern, "b")
        assertTrue(diag.has("FLASH_COALESCED"))
        assertEquals(1, diag.events.count { it.startsWith("FLASH_START") })
    }

    @Test fun callReplacesNotification() {
        engine.start(pattern, "n")
        sched.advanceTo(10)
        engine.start(pattern, "call", FlashEngine.KIND_CALL)
        assertTrue(diag.has("FLASH_STOP reason=REPLACED_BY_CALL"))
        assertEquals(FlashEngine.KIND_CALL, engine.currentKind)
        assertEquals(2, diag.events.count { it.startsWith("FLASH_START") })
    }

    @Test fun notificationDoesNotReplaceCall() {
        engine.start(pattern, "call", FlashEngine.KIND_CALL)
        engine.start(pattern, "n")
        assertTrue(diag.has("FLASH_COALESCED"))
        assertEquals(FlashEngine.KIND_CALL, engine.currentKind)
    }

    @Test fun stopOnlyKindLeavesOtherKindRunning() {
        engine.start(pattern, "n")
        engine.stop("CALL_ENDED", onlyKind = FlashEngine.KIND_CALL)
        assertTrue(engine.isRunning)
    }

    @Test fun userTorchOnMeansNoToggles() {
        torch.userOn = true
        engine.start(pattern, "t")
        assertTrue(torch.toggles.isEmpty()); assertFalse(engine.isRunning)
        assertTrue(diag.has("SKIP_USER_TORCH_ON"))
    }

    @Test fun unavailableMeansNoToggles() {
        torch.available = false
        engine.start(pattern, "t")
        assertTrue(torch.toggles.isEmpty()); assertFalse(engine.isRunning)
        assertTrue(diag.has("CAMERA_UNAVAILABLE"))
    }

    @Test fun noFlashHardware() {
        torch.hasFlash = false
        engine.start(pattern, "t")
        assertTrue(diag.has("NO_FLASH_HARDWARE")); assertFalse(engine.isRunning)
    }

    @Test fun userTakesOverMidRunIsNotSwitchedOff() {
        engine.start(pattern, "t")
        sched.advanceTo(70)
        torch.userOn = true; torch.led = true
        sched.advanceTo(200)
        assertFalse(engine.isRunning)
        assertTrue("user's torch must stay on", torch.led)
    }

    @Test fun runEndedCallbackFires() {
        var ended = 0
        engine.onRunEnded = { ended++ }
        engine.start(pattern, "t"); sched.advanceTo(5000)
        assertEquals(1, ended)
    }

    @Test fun canRunAgainAfterCompletion() {
        engine.start(pattern, "a"); sched.advanceTo(5000)
        engine.start(pattern, "b"); sched.advanceTo(10000)
        assertEquals(26, torch.toggles.size)
    }

    @Test fun burstOfStartsRunsOnceAndEndsOff() {
        repeat(6) { engine.start(pattern, "n$it") }
        assertEquals(1, diag.events.count { it.startsWith("FLASH_START") })
        assertEquals(5, diag.events.count { it.startsWith("FLASH_COALESCED") })
        sched.advanceTo(10_000)
        assertFalse(engine.isRunning); assertFalse(torch.led)
    }

    @Test fun staleUserTorchFlagStillTurnsOffOnCompletion() {
        engine.start(pattern, "t")
        sched.advanceTo(10)
        torch.userOn = true // false positive from a late callback; run was not aborted by it
        sched.advanceTo(5000)
        assertFalse(torch.led)
    }

    @Test fun cameraTakenMidRunStopsWithoutTouchingHardware() {
        engine.start(pattern, "t")
        sched.advanceTo(10)
        val before = torch.toggles.size
        torch.available = false
        sched.advanceTo(200)
        assertFalse(engine.isRunning)
        assertEquals(before, torch.toggles.size)
        assertTrue(diag.has("FLASH_STOP reason=CAMERA_UNAVAILABLE"))
    }

    @Test fun callRunEndedCallbackOnlyForCalls() {
        var calls = 0
        engine.onCallRunEnded = { calls++ }
        engine.start(pattern, "n"); sched.advanceTo(5000)
        assertEquals(0, calls)
        engine.start(pattern, "c", FlashEngine.KIND_CALL); engine.stop("CALL_SILENCED", FlashEngine.KIND_CALL)
        assertEquals(1, calls); assertFalse(torch.led)
    }
}
