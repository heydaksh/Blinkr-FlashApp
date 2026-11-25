package com.example.android_flashlight.flashalerts

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager

class HandlerScheduler : Scheduler {
    private val thread = HandlerThread("FlashEngine", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    override fun now() = SystemClock.uptimeMillis()
    override fun post(r: Runnable) { handler.post(r) }
    override fun postAt(token: Any, atMs: Long, r: Runnable) { handler.postAtTime(r, token, atMs) }
    override fun cancel(token: Any) = handler.removeCallbacksAndMessages(token)
}

private class PmWakeLock(ctx: Context) : WakeLockHolder {
    private val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${ctx.packageName}:FlashAlerts").apply { setReferenceCounted(false) }
    override fun acquire(timeoutMs: Long) = wl.acquire(timeoutMs)
    override fun release() { if (wl.isHeld) wl.release() }
}

/** Process-wide singletons, usable without Flutter. */
object FlashRuntime {
    /** Notification flashes start this long after the notification is posted (banner/sound/vibration lag). */
    const val NOTIFICATION_FLASH_DELAY_MS = 110L

    val policy = TriggerPolicy { SystemClock.elapsedRealtime() }

    private var appCtx: Context? = null
    private var engineInst: FlashEngine? = null
    private var camera: CameraTorchDriver? = null
    private var dry: DryRunTorchDriver? = null
    private var storeInst: FlashSettingsStore? = null
    private var silenceWatcher: RingSilenceWatcher? = null

    /** True while the telephony callback is registered: it is then the source of truth for call end. */
    @Volatile var telephonyActive = false
    /** The current ringing call was silenced by the user; do not restart flashing until it ends. */
    @Volatile var callSilenced = false

    @Synchronized
    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        FlashDiagnostics.init(ctx)
    }

    @Synchronized
    fun store(ctx: Context): FlashSettingsStore =
        storeInst ?: FlashSettingsStore(ctx.applicationContext).also { storeInst = it }

    fun isDebuggable(ctx: Context) = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @Synchronized
    fun driver(ctx: Context): TorchDriver {
        val useDry = isDebuggable(ctx) && store(ctx).load().dryRun
        if (useDry) return dry ?: DryRunTorchDriver(FlashDiagnostics).also { dry = it }
        return camera ?: CameraTorchDriver(ctx.applicationContext, FlashDiagnostics).also { camera = it }
    }

    @Synchronized
    fun engine(ctx: Context): FlashEngine {
        init(ctx)
        return engineInst ?: run {
            val c = ctx.applicationContext
            FlashEngine(HandlerScheduler(), { driver(c) }, FlashDiagnostics, PmWakeLock(c), verbose = isDebuggable(c))
                .also {
                    it.onRunEnded = { policy.onFlashEnded() }
                    it.onCallRunEnded = { stopSilenceWatcher() }
                    engineInst = it
                }
        }
    }

    fun deviceState(ctx: Context): DeviceState {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val ringer = am?.ringerMode ?: DeviceState.RINGER_MODE_NORMAL
        return DeviceState(pm.isInteractive, km.isKeyguardLocked, engine(ctx).currentKind, ringer)
    }

    fun notificationPattern(ctx: Context, s: FlashSettings = store(ctx).load()) =
        s.notificationPattern.copy(strengthLevel = s.strengthLevel).validated(store(ctx).calibratedMinMs)

    fun callPattern(ctx: Context, s: FlashSettings = store(ctx).load()) =
        s.callPattern.copy(strengthLevel = s.strengthLevel).validated(store(ctx).calibratedMinMs)

    fun flashCall(ctx: Context, source: String) {
        val s = store(ctx).load()
        if (!s.enabled || !s.callsEnabled) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val ringer = am?.ringerMode ?: DeviceState.RINGER_MODE_NORMAL
        when (ringer) {
            DeviceState.RINGER_MODE_SILENT -> if (!s.flashOnSilent) return
            DeviceState.RINGER_MODE_VIBRATE -> if (!s.flashOnVibrate) return
            DeviceState.RINGER_MODE_NORMAL -> if (!s.flashOnNormal) return
        }
        beginCallFlash(ctx, s, source)
    }

    /** Starts the repeating call flash plus the silence watcher. Ignored if the user already silenced this call. */
    fun beginCallFlash(ctx: Context, s: FlashSettings, source: String) {
        if (callSilenced) return
        FlashDiagnostics.log("CALL_RINGING", "source" to source)
        startSilenceWatcher(ctx)
        engine(ctx).start(callPattern(ctx, s), "call", FlashEngine.KIND_CALL)
    }

    /** Call answered / rejected / disconnected / notification removed. */
    fun stopCall(ctx: Context, reason: String) {
        callSilenced = false
        policy.clearCall()
        engine(ctx).stop(reason, onlyKind = FlashEngine.KIND_CALL)
    }

    /** Call is still ringing but the user silenced it: stop now, and keep it from restarting. */
    fun silenceCall(ctx: Context, reason: String) {
        if (engine(ctx).currentKind != FlashEngine.KIND_CALL) return
        callSilenced = true
        FlashDiagnostics.log("CALL_SILENCED", "reason" to reason)
        engine(ctx).stop("CALL_SILENCED", onlyKind = FlashEngine.KIND_CALL)
    }

    /** Telephony IDLE/OFFHOOK with no flash running: forget silenced state for the next call. */
    fun callFinished() {
        callSilenced = false
        policy.clearCall()
    }

    @Synchronized
    private fun startSilenceWatcher(ctx: Context) {
        if (silenceWatcher != null) return
        val app = ctx.applicationContext
        silenceWatcher = RingSilenceWatcher(app) { silenceCall(app, it) }.also { it.start() }
    }

    @Synchronized
    private fun stopSilenceWatcher() {
        silenceWatcher?.stop()
        silenceWatcher = null
    }

    fun defaultDialer(ctx: Context): String? = try {
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
    } catch (_: Exception) { null }
}
