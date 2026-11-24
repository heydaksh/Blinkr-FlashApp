package com.example.android_flashlight.flashalerts

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

interface TorchDriver {
    val hasFlash: Boolean
    /** false while camera in use / thermal lockout */
    fun isAvailable(): Boolean
    /** torch is on and we did not turn it on */
    fun isUserTorchOn(): Boolean
    /** Never throws; false on failure. */
    fun set(on: Boolean, strength: Int? = null): Boolean
    fun maxStrengthLevel(): Int
    /** Called with (state, System.nanoTime()) when the framework confirms a torch change. */
    fun setConfirmListener(l: ((Boolean, Long) -> Unit)?)
}

class CameraTorchDriver(ctx: Context, private val diag: Diag) : TorchDriver {
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String?
    private val maxStrength: Int

    @Volatile private var available = true
    @Volatile private var torchOn = false
    @Volatile private var ourOn = false
    @Volatile private var lastOurSetOnMs = 0L
    @Volatile private var userTorchActive = false
    @Volatile private var listener: ((Boolean, Long) -> Unit)? = null

    override val hasFlash: Boolean get() = cameraId != null

    init {
        var id: String? = null
        var strength = 1
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                for (candidate in cm.cameraIdList) {
                    val c = cm.getCameraCharacteristics(candidate)
                    if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    ) {
                        id = candidate
                        if (Build.VERSION.SDK_INT >= 33) {
                            strength = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                diag.log("NO_FLASH_HARDWARE", "error" to e.javaClass.simpleName)
            }
            if (id != null) {
                val thread = HandlerThread("TorchCallbacks").apply { start() }
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId != id) return
                        available = true
                        torchOn = enabled
                        if (enabled) {
                            // Only flag user torch if WE did not command it within the last 1500ms
                            if (!ourOn && SystemClock.uptimeMillis() - lastOurSetOnMs > USER_TORCH_DETECTION_WINDOW_MS) {
                                userTorchActive = true
                            }
                        } else {
                            userTorchActive = false
                        }
                        listener?.invoke(enabled, System.nanoTime())
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId != id) return
                        available = false
                        // Camera taken by another client: the framework switched our torch off.
                        ourOn = false
                        diag.log("CAMERA_UNAVAILABLE", "cameraId" to cameraId, "source" to "callback")
                    }
                }, Handler(thread.looper))
            }
        }
        cameraId = id
        maxStrength = strength
    }

    override fun isAvailable() = cameraId != null && available

    override fun isUserTorchOn(): Boolean = userTorchActive

    override fun maxStrengthLevel() = maxStrength

    override fun setConfirmListener(l: ((Boolean, Long) -> Unit)?) { listener = l }

    override fun set(on: Boolean, strength: Int?): Boolean {
        val id = cameraId ?: return false
        if (Build.VERSION.SDK_INT < 23) return false
        ourOn = on
        if (on) {
            lastOurSetOnMs = SystemClock.uptimeMillis()
            userTorchActive = false
        }
        return try {
            if (on && Build.VERSION.SDK_INT >= 33 && strength != null && maxStrength > 1) {
                cm.turnOnTorchWithStrengthLevel(id, strength.coerceIn(1, maxStrength))
            } else {
                cm.setTorchMode(id, on)
            }
            true
        } catch (e: Exception) {
            if (on) ourOn = false
            diag.log("TOGGLE_ERROR", "cameraId" to id, "code" to (if (e is CameraAccessException) reasonName(e.reason) else e.javaClass.simpleName))
            if (!on) {
                try { cm.setTorchMode(id, false); true } catch (_: Exception) { false }
            } else {
                false
            }
        }
    }

    private fun reasonName(r: Int) = when (r) {
        CameraAccessException.CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraAccessException.CAMERA_DISCONNECTED -> "CAMERA_DISCONNECTED"
        CameraAccessException.CAMERA_ERROR -> "CAMERA_ERROR"
        CameraAccessException.CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        else -> "REASON_$r"
    }

    private companion object { const val USER_TORCH_DETECTION_WINDOW_MS = 1500L }
}

/** Emulator / CI driver: logs toggles, never touches hardware. */
class DryRunTorchDriver(private val diag: Diag) : TorchDriver {
    @Volatile var state = false
        private set
    @Volatile private var listener: ((Boolean, Long) -> Unit)? = null
    override val hasFlash = true
    override fun isAvailable() = true
    override fun isUserTorchOn() = false
    override fun maxStrengthLevel() = 1
    override fun setConfirmListener(l: ((Boolean, Long) -> Unit)?) { listener = l }
    override fun set(on: Boolean, strength: Int?): Boolean {
        state = on
        diag.log("DRYRUN_TORCH", "on" to on)
        listener?.invoke(on, System.nanoTime())
        return true
    }
}
