package com.example.android_flashlight.flashalerts

data class CalibrationResult(val calibratedMinMs: Int, val medianLatencyMs: Int, val confirmedRatio: Double)

object Calibrator {
    data class IntervalStat(val intervalMs: Int, val confirmedRatio: Double, val medianLatencyMs: Double)

    val INTERVALS = listOf(100, 80, 60, 50, 40, 30, 20)
    private const val TOGGLES = 20

    /** Pure: stats ordered from slowest to fastest interval. */
    fun pick(stats: List<IntervalStat>): CalibrationResult {
        var best = 120
        var chosen: IntervalStat? = null
        for (s in stats.sortedByDescending { it.intervalMs }) {
            if (s.confirmedRatio >= 0.95 && s.medianLatencyMs < s.intervalMs / 2.0) {
                best = s.intervalMs; chosen = s
            } else break
        }
        val ref = chosen ?: stats.firstOrNull()
        return CalibrationResult(
            best.coerceIn(20, 120),
            (ref?.medianLatencyMs ?: 0.0).toInt(),
            ref?.confirmedRatio ?: 0.0,
        )
    }

    /** Blocking; run off the main thread. Returns null with [error] set if not possible. */
    fun run(driver: TorchDriver, diag: Diag): Pair<CalibrationResult?, String?> {
        if (!driver.hasFlash) return null to "NO_FLASH"
        if (!driver.isAvailable() || driver.isUserTorchOn()) return null to "CAMERA_UNAVAILABLE"

        val stats = ArrayList<IntervalStat>()
        val events = ArrayList<Pair<Boolean, Long>>()
        driver.setConfirmListener { on, t -> synchronized(events) { events += on to t } }
        try {
            for (interval in INTERVALS) {
                synchronized(events) { events.clear() }
                val sent = ArrayList<Triple<Boolean, Long, LongArray>>() // state, sentNs, [latencyNs or -1]
                val start = System.nanoTime()
                for (i in 0 until TOGGLES) {
                    val target = start + i * interval * 1_000_000L
                    while (System.nanoTime() < target) Thread.sleep(0, 200_000)
                    val on = i % 2 == 0
                    sent += Triple(on, System.nanoTime(), longArrayOf(-1))
                    driver.set(on, null)
                }
                Thread.sleep(150)
                val evs = synchronized(events) { events.toList() }
                for ((state, t) in evs) {
                    sent.firstOrNull { it.first == state && it.third[0] < 0 && it.second <= t }
                        ?.let { it.third[0] = t - it.second }
                }
                val lat = sent.map { it.third[0] }.filter { it >= 0 }.sorted()
                val median = if (lat.isEmpty()) Double.MAX_VALUE else lat[lat.size / 2] / 1e6
                stats += IntervalStat(interval, lat.size.toDouble() / TOGGLES, median)
            }
        } finally {
            driver.setConfirmListener(null)
            driver.set(false, null)
        }
        val result = pick(stats)
        diag.log("CALIBRATION_RESULT", "minMs" to result.calibratedMinMs,
            "medianLatencyMs" to result.medianLatencyMs, "confirmedRatio" to "%.2f".format(result.confirmedRatio))
        return result to null
    }
}
