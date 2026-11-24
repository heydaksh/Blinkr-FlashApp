package com.example.android_flashlight.flashalerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/** Optional precise call state; active only while the listener is connected. */
class CallStateMonitor(private val ctx: Context) {
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var callback: Any? = null
    private var legacy: PhoneStateListener? = null

    fun start() {
        if (callback != null || legacy != null) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            FlashDiagnostics.log("PERMISSION_MISSING", "which" to "read_phone_state")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) registerNew() else registerLegacy()
            FlashRuntime.telephonyActive = true
        } catch (e: SecurityException) {
            FlashDiagnostics.log("PERMISSION_MISSING", "which" to "read_phone_state")
        }
    }

    @RequiresApi(31)
    private fun registerNew() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = handle(state)
        }
        callback = cb
        tm.registerTelephonyCallback(ctx.mainExecutor, cb)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
        }
        legacy = l
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= 31) (callback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            else @Suppress("DEPRECATION") legacy?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        } catch (_: Exception) { }
        callback = null
        legacy = null
        FlashRuntime.telephonyActive = false
    }

    private fun handle(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> FlashRuntime.flashCall(ctx, "telephony")
            TelephonyManager.CALL_STATE_OFFHOOK -> endCall("CALL_ANSWERED")
            TelephonyManager.CALL_STATE_IDLE -> endCall("CALL_ENDED")
        }
    }

    private fun endCall(reason: String) {
        val engine = FlashRuntime.engine(ctx)
        if (engine.currentKind == FlashEngine.KIND_CALL) {
            FlashDiagnostics.log("CALL_ENDED", "source" to "telephony", "state" to reason)
            FlashRuntime.stopCall(ctx, reason)
        } else {
            FlashRuntime.callFinished()
        }
    }
}
