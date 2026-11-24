package com.example.android_flashlight.flashalerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Android sends third-party apps no "ringer silenced" event: telephony stays RINGING after the
 * user presses volume/power. This watcher infers it, only while a call flash is running:
 *  - ringer mode change / ring-stream volume change (volume button)
 *  - screen on/off toggle after the call started (power button)
 *  - ringtone playback that was audible and then stops (any silencing, when a sound was playing)
 * Best effort: OEMs differ, and vibrate-only ringing has no audio signal.
 */
class RingSilenceWatcher(private val ctx: Context, private val onSilenced: (String) -> Unit) {
    private var receiver: BroadcastReceiver? = null
    private var playbackCb: AudioManager.AudioPlaybackCallback? = null
    private var startedAt = 0L
    private var sawRingtone = false
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start() {
        if (receiver != null) return
        startedAt = SystemClock.elapsedRealtime()
        sawRingtone = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val age = SystemClock.elapsedRealtime() - startedAt
                when (i.action) {
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> if (age > VOLUME_GRACE_MS) fire("RINGER_MODE_CHANGED")
                    VOLUME_CHANGED_ACTION -> {
                        val stream = i.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                        if (stream == AudioManager.STREAM_RING && age > VOLUME_GRACE_MS) fire("VOLUME_BUTTON")
                    }
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> if (age > SCREEN_GRACE_MS) fire("POWER_BUTTON")
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(VOLUME_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(r, f)
            receiver = r
        } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val cb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                        val ringing = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
                        if (ringing) sawRingtone = true
                        else if (sawRingtone && SystemClock.elapsedRealtime() - startedAt > VOLUME_GRACE_MS) fire("RINGTONE_STOPPED")
                    }
                }
                am.registerAudioPlaybackCallback(cb, main)
                playbackCb = cb
            } catch (_: Exception) { }
        }
    }

    @Synchronized
    fun stop() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        playbackCb?.let {
            try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioPlaybackCallback(it) } catch (_: Exception) { }
        }
        playbackCb = null
    }

    private fun fire(reason: String) { stop(); onSilenced(reason) }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val VOLUME_GRACE_MS = 1000L
        const val SCREEN_GRACE_MS = 3000L
    }
}
