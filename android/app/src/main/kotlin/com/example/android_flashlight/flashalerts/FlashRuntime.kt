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
    val policy = TriggerPolicy { SystemClock.elapsedRealtime() }

    private var appCtx: Context? = null
    private var engineInst: FlashEngine? = null
    private var camera: CameraTorchDriver? = null
    private var dry: DryRunTorchDriver? = null
    private var storeInst: FlashSettingsStore? = null

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
                .also { it.onRunEnded = { policy.onFlashEnded() }; engineInst = it }
        }
    }

    fun deviceState(ctx: Context): DeviceState {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return DeviceState(pm.isInteractive, km.isKeyguardLocked, engine(ctx).currentKind)
    }

    fun notificationPattern(ctx: Context, s: FlashSettings = store(ctx).load()) =
        s.notificationPattern.copy(strengthLevel = s.strengthLevel).validated(store(ctx).calibratedMinMs)

    fun callPattern(ctx: Context, s: FlashSettings = store(ctx).load()) =
        s.callPattern.copy(strengthLevel = s.strengthLevel).validated(store(ctx).calibratedMinMs)

    fun flashCall(ctx: Context, source: String) {
        val s = store(ctx).load()
        if (!s.enabled || !s.callsEnabled) return
        FlashDiagnostics.log("CALL_RINGING", "source" to source)
        engine(ctx).start(callPattern(ctx, s), "call", FlashEngine.KIND_CALL)
    }

    fun stopCall(ctx: Context, reason: String) {
        policy.clearCall()
        engine(ctx).stop(reason, onlyKind = FlashEngine.KIND_CALL)
    }

    fun defaultDialer(ctx: Context): String? = try {
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
    } catch (_: Exception) { null }
}
