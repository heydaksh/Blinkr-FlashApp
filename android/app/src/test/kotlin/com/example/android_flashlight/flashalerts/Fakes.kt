package com.example.android_flashlight.flashalerts

class FakeScheduler : Scheduler {
    var time = 0L
    private val queue = ArrayList<Triple<Long, Any, Runnable>>()
    override fun now() = time
    override fun post(r: Runnable) = r.run()
    override fun postAt(token: Any, atMs: Long, r: Runnable) { queue += Triple(atMs, token, r) }
    override fun cancel(token: Any) { queue.removeAll { it.second === token } }
    /** Runs everything due up to and including [untilMs], in time order (stable for ties). */
    fun advanceTo(untilMs: Long) {
        while (true) {
            val next = queue.withIndex().filter { it.value.first <= untilMs }.minWithOrNull(compareBy({ it.value.first }, { it.index })) ?: break
            queue.removeAt(next.index)
            time = maxOf(time, next.value.first)
            next.value.third.run()
        }
        time = untilMs
    }
}

class FakeWakeLock : WakeLockHolder {
    var held = false
    override fun acquire(timeoutMs: Long) { held = true }
    override fun release() { held = false }
}

class FakeDiag : Diag {
    val events = ArrayList<String>()
    override fun log(event: String, vararg fields: Pair<String, Any?>) { events += event + fields.joinToString("") { " ${it.first}=${it.second}" } }
    fun has(prefix: String) = events.any { it.startsWith(prefix) }
}

class FakeTorch(
    override var hasFlash: Boolean = true,
    var available: Boolean = true,
    var userOn: Boolean = false,
    var failOn: Boolean = false,
) : TorchDriver {
    var led = false
    val toggles = ArrayList<Boolean>()
    override fun isAvailable() = available
    override fun isUserTorchOn() = userOn
    override fun maxStrengthLevel() = 1
    override fun setConfirmListener(l: ((Boolean, Long) -> Unit)?) {}
    override fun set(on: Boolean, strength: Int?): Boolean {
        if (on && failOn) return false
        led = on; toggles += on
        return true
    }
}
