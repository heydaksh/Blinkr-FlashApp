package com.example.android_flashlight.flashalerts

interface Scheduler {
    fun now(): Long
    fun post(r: Runnable)
    fun postAt(token: Any, atMs: Long, r: Runnable)
    fun cancel(token: Any)
}

interface WakeLockHolder {
    fun acquire(timeoutMs: Long)
    fun release()
}

interface Diag {
    fun log(event: String, vararg fields: Pair<String, Any?>)
}

/**
 * Single-flight flash engine. All state is touched only on the scheduler thread
 * (Handler thread in production). The LED is forced off on every exit path.
 */
class FlashEngine(
    private val scheduler: Scheduler,
    private val driverProvider: () -> TorchDriver,
    private val diag: Diag,
    private val wakeLock: WakeLockHolder,
    private val verbose: Boolean = false,
) {
    @Volatile var isRunning = false
        private set
    @Volatile var currentKind: String? = null
        private set
    @Volatile var onRunEnded: (() -> Unit)? = null
    /** Fires (in addition to onRunEnded) when a KIND_CALL run ends for any reason. */
    @Volatile var onCallRunEnded: (() -> Unit)? = null

    private var token: Any? = null
    private var startedAt = 0L
    private var ok = 0
    private var failed = 0

    /** Null if a run could start now, else the reason code. Safe to call from any thread. */
    fun preflight(): String? = preflight(driverProvider())

    private fun preflight(d: TorchDriver): String? = when {
        !d.hasFlash -> "NO_FLASH_HARDWARE"
        !d.isAvailable() -> "CAMERA_UNAVAILABLE"
        d.isUserTorchOn() -> "SKIP_USER_TORCH_ON"
        else -> null
    }

    fun start(pattern: FlashPattern, reason: String, kind: String = KIND_NOTIFICATION) {
        scheduler.post { safely { startInternal(pattern, reason, kind) } }
    }

    fun stop(reason: String, onlyKind: String? = null) {
        scheduler.post {
            safely {
                if (isRunning && (onlyKind == null || currentKind == onlyKind)) stopInternal(reason)
            }
        }
    }

    private fun startInternal(pattern: FlashPattern, reason: String, kind: String) {
        if (isRunning) {
            if (kind == KIND_CALL && currentKind != KIND_CALL) stopInternal("REPLACED_BY_CALL")
            else {
                diag.log("FLASH_COALESCED", "reason" to reason)
                return
            }
        }
        val driver = driverProvider()
        preflight(driver)?.let {
            diag.log(it, "reason" to reason)
            return
        }
        val timeline = pattern.toTimeline()
        if (timeline.isEmpty()) return
        val total = timeline.last().offsetMs
        val run = Any()
        token = run
        isRunning = true
        currentKind = kind
        ok = 0; failed = 0
        startedAt = scheduler.now()
        runCatching { wakeLock.acquire(total + 1000) }
        val t0 = startedAt + 5
        for (step in timeline) {
            val at = t0 + step.offsetMs
            scheduler.postAt(run, at) {
                if (token !== run) return@postAt
                safely { toggle(driver, step, pattern.strengthLevel, at) }
            }
        }
        scheduler.postAt(run, t0 + total) { if (token === run) safely { stopInternal("COMPLETED") } }
        scheduler.postAt(run, t0 + total + 500L) { if (token === run) safely { stopInternal("WATCHDOG_TIMEOUT") } }
        diag.log("FLASH_START", "reason" to reason, "kind" to kind, "pattern" to pattern.summary(),
            "strength" to pattern.strengthLevel)
    }

    private fun toggle(driver: TorchDriver, step: Step, strength: Int?, scheduledAt: Long) {
        // Camera taken by another app (on or off step): stop without touching the hardware.
        if (!driver.isAvailable()) return stopInternal("CAMERA_UNAVAILABLE")
        if (step.on && driver.isUserTorchOn()) return stopInternal("USER_TORCH_ON")
        val success = driver.set(step.on, strength)
        if (success) ok++ else failed++
        if (verbose) {
            val executed = scheduler.now()
            diag.log("FLASH_TOGGLE", "on" to step.on, "scheduledAt" to scheduledAt,
                "executedAt" to executed, "driftMs" to (executed - scheduledAt))
        }
        if (!success) {
            if (step.on) {
                stopInternal("TOGGLE_FAILED")
            } else {
                stopInternal("OFF_FAILED")
            }
        }
    }

    private fun stopInternal(reason: String) {
        token?.let { scheduler.cancel(it) }
        token = null
        val was = isRunning
        val endedKind = currentKind
        if (was) {
            try {
                val d = driverProvider()
                // Never switch off a torch the user turned on themselves (FR-6).
                // In all other cases (COMPLETED, ERROR, TIMEOUT, CANCELLED, TOGGLE_FAILED), force torch off!
                // If another app owns the camera the framework already turned our torch off and any
                // set() would only raise CAMERA_IN_USE, so leave the hardware alone.
                val cameraTaken = reason == "CAMERA_UNAVAILABLE" || !d.isAvailable()
                if (!cameraTaken && (reason != "USER_TORCH_ON" || !d.isUserTorchOn())) {
                    d.set(false, null)
                }
            } catch (_: Throwable) {
            }
            diag.log("FLASH_STOP", "reason" to reason, "durationMs" to (scheduler.now() - startedAt),
                "togglesOk" to ok, "togglesFailed" to failed)
        }
        isRunning = false
        currentKind = null
        runCatching { wakeLock.release() }
        if (was) runCatching { onRunEnded?.invoke() }
        if (was && endedKind == KIND_CALL) runCatching { onCallRunEnded?.invoke() }
    }

    /** Last-resort guard: an unexpected exception must never leave the LED on. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            diag.log("ENGINE_ERROR", "error" to t.javaClass.simpleName)
            try { stopInternal("ERROR") } catch (_: Throwable) { }
        }
    }

    companion object {
        const val KIND_NOTIFICATION = "notification"
        const val KIND_CALL = "call"
    }
}
