package com.example.android_flashlight.flashalerts

import android.content.Context
import android.content.SharedPreferences

/** Native source of truth (readable by the listener with no Flutter engine). */
class FlashSettingsStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("flash_alerts_prefs", Context.MODE_PRIVATE)

    var calibratedMinMs: Int
        get() = prefs.getInt(KEY_CAL, 40)
        set(v) { prefs.edit().putInt(KEY_CAL, v).apply() }

    var calibrationFingerprint: String?
        get() = prefs.getString(KEY_FP, null)
        set(v) { prefs.edit().putString(KEY_FP, v).apply() }

    fun load(): FlashSettings {
        val nested = HashMap<String, Any?>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith(PREFIX)) continue
            val parts = k.removePrefix(PREFIX).split(".")
            var cur = nested
            for (p in parts.dropLast(1)) {
                @Suppress("UNCHECKED_CAST")
                cur = cur.getOrPut(p) { HashMap<String, Any?>() } as HashMap<String, Any?>
            }
            cur[parts.last()] = if (v is Set<*>) v.toList() else v
        }
        return FlashSettings.fromMap(nested).validated(calibratedMinMs)
    }

    fun save(s: FlashSettings) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { e.remove(it) }
        put(e, PREFIX.trimEnd('.'), s.toMap(), top = true)
        e.apply()
    }

    private fun put(e: SharedPreferences.Editor, prefix: String, map: Map<String, Any?>, top: Boolean = false) {
        for ((k, v) in map) {
            val key = if (top) "$PREFIX$k" else "$prefix.$k"
            when (v) {
                null -> Unit
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    put(e, key, v as Map<String, Any?>)
                }
                is Boolean -> e.putBoolean(key, v)
                is Int -> e.putInt(key, v)
                is Long -> e.putLong(key, v)
                is String -> e.putString(key, v)
                is Collection<*> -> e.putStringSet(key, v.filterIsInstance<String>().toSet())
            }
        }
    }

    private companion object {
        const val PREFIX = "s."
        const val KEY_CAL = "cal_min_ms"
        const val KEY_FP = "cal_fingerprint"
    }
}
