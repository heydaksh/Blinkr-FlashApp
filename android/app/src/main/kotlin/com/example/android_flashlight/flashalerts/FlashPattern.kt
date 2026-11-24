package com.example.android_flashlight.flashalerts

data class Step(val offsetMs: Long, val on: Boolean)

/** Immutable blink pattern. Pure Kotlin. */
data class FlashPattern(
    val onMs: Int = 60,
    val offMs: Int = 60,
    val pulsesPerBurst: Int = 3,
    val burstGapMs: Int = 400,
    val bursts: Int = 2,
    val maxDurationMs: Int = 3000,
    val strengthLevel: Int? = null,
) {
    /** Clamp everything into sane ranges; on/off are never below max(20, minMs). */
    fun validated(minMs: Int = ABS_MIN_MS): FlashPattern {
        val floor = maxOf(ABS_MIN_MS, minMs)
        return FlashPattern(
            onMs = onMs.coerceIn(floor, MAX_PULSE_MS),
            offMs = offMs.coerceIn(floor, MAX_PULSE_MS),
            pulsesPerBurst = pulsesPerBurst.coerceIn(1, 50),
            burstGapMs = burstGapMs.coerceIn(0, 5000),
            bursts = bursts.coerceAtLeast(1),
            maxDurationMs = maxDurationMs.coerceIn(100, MAX_TOTAL_MS),
            strengthLevel = strengthLevel?.takeIf { it >= 1 },
        )
    }

    /** Timeline of toggles; always ends with an OFF step. Truncated at maxDurationMs. */
    fun toTimeline(): List<Step> {
        val p = validated(ABS_MIN_MS)
        val limit = p.maxDurationMs.toLong()
        val steps = ArrayList<Step>()
        var t = 0L
        var burst = 0
        loop@ while (burst < p.bursts) {
            for (i in 0 until p.pulsesPerBurst) {
                if (t >= limit) break@loop
                steps += Step(t, true)
                steps += Step(minOf(t + p.onMs, limit), false)
                t += p.onMs + p.offMs
            }
            t += p.burstGapMs
            burst++
        }
        return steps
    }

    fun totalMs(): Long = toTimeline().lastOrNull()?.offsetMs ?: 0L

    fun summary(): String =
        "on=$onMs off=$offMs pulses=$pulsesPerBurst gap=$burstGapMs bursts=$bursts max=$maxDurationMs"

    fun toMap(): Map<String, Any?> = mapOf(
        "onMs" to onMs, "offMs" to offMs, "pulsesPerBurst" to pulsesPerBurst,
        "burstGapMs" to burstGapMs, "bursts" to bursts, "maxDurationMs" to maxDurationMs,
        "strengthLevel" to strengthLevel,
    )

    companion object {
        const val ABS_MIN_MS = 20
        const val MAX_PULSE_MS = 2000
        const val MAX_TOTAL_MS = 30_000

        private fun Any?.int(fallback: Int): Int =
            (this as? Number)?.toLong()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: fallback

        /** Partial maps are merged over [base]. Result is NOT clamped; call validated(). */
        fun fromMap(map: Map<*, *>?, base: FlashPattern): FlashPattern {
            if (map == null) return base
            return FlashPattern(
                onMs = map["onMs"].int(base.onMs),
                offMs = map["offMs"].int(base.offMs),
                pulsesPerBurst = map["pulsesPerBurst"].int(base.pulsesPerBurst),
                burstGapMs = map["burstGapMs"].int(base.burstGapMs),
                bursts = map["bursts"].int(base.bursts),
                maxDurationMs = map["maxDurationMs"].int(base.maxDurationMs),
                strengthLevel = if (map.containsKey("strengthLevel"))
                    (map["strengthLevel"] as? Number)?.toInt() else base.strengthLevel,
            )
        }
    }
}
