package com.example.android_flashlight.flashalerts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

/** Thin MethodChannel adapter; business logic lives in the other classes. */
class FlashAlertsChannelHandler(
    private val ctx: Context,
    private val activity: Activity?,
) : MethodChannel.MethodCallHandler {
    private val main = Handler(Looper.getMainLooper())
    private val store = FlashRuntime.store(ctx)
    private var pendingPermission: MethodChannel.Result? = null
    @Volatile private var calibrating = false

    fun register(messenger: BinaryMessenger) {
        FlashRuntime.init(ctx)
        MethodChannel(messenger, CHANNEL).setMethodCallHandler(this)
        ListenerHealth.scheduleCheck(ctx)
        maybeCalibrate(store.load())
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getStatus" -> result.success(DeviceStatus.snapshot(ctx))
                "getSettings" -> result.success(store.load().toMap())
                "updateSettings" -> {
                    val args = call.arguments as? Map<*, *> ?: return result.error("INVALID_ARGS", "settings map expected", null)
                    val old = store.load()
                    val merged = FlashSettings.fromMap(args, old).validated(store.calibratedMinMs)
                    store.save(merged)
                    if (merged.enabled && !old.enabled) maybeCalibrate(merged)
                    result.success(store.load().toMap())
                }
                "openNotificationAccessSettings" -> result.success(openNotificationAccess())
                "openBatteryOptimizationSettings" -> result.success(launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                "openAppDetailsSettings" -> result.success(launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))))
                "requestPhoneStatePermission" -> requestPhoneState(result)
                "testFlash" -> testFlash(call, result)
                "stopFlash" -> {
                    FlashRuntime.engine(ctx).stop("MANUAL")
                    result.success(null)
                }
                "calibrate" -> calibrate(result)
                "trigger" -> trigger(call, result)
                "getInstalledApps" -> getInstalledApps(result)
                "getDiagnostics" -> result.success(FlashDiagnostics.read())
                "clearDiagnostics" -> { FlashDiagnostics.clear(); result.success(null) }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("INVALID_ARGS", e.javaClass.simpleName, null)
        }
    }

    private fun failureFor(code: String): Pair<String, String>? = when (code) {
        "NO_FLASH_HARDWARE" -> "NO_FLASH" to "This device has no flash hardware"
        "CAMERA_UNAVAILABLE" -> "CAMERA_UNAVAILABLE" to "Torch unavailable (camera in use or thermal lockout)"
        "SKIP_USER_TORCH_ON" -> null // not an error; reported as started=false
        else -> null
    }

    private fun testFlash(call: MethodCall, result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < 23) return result.error("UNSUPPORTED", "Android 6+ required", null)
        val engine = FlashRuntime.engine(ctx)
        val settings = store.load()
        val base = FlashRuntime.notificationPattern(ctx, settings)
        val pattern = FlashPattern.fromMap(call.arguments as? Map<*, *>, base).validated(store.calibratedMinMs)
        val why = engine.preflight()
        if (why != null) {
            FlashDiagnostics.log(why, "reason" to "test")
            val err = failureFor(why)
            return if (err != null) result.error(err.first, err.second, null) else result.success(false)
        }
        engine.start(pattern, "test")
        result.success(true)
    }

    private fun trigger(call: MethodCall, result: MethodChannel.Result) {
        val reason = (call.arguments as? Map<*, *>)?.get("reason") as? String ?: "foreground"
        val s = store.load()
        // Only a fallback: when the listener is enabled it already sees the notification.
        if (!s.enabled || !s.notificationsEnabled || DeviceStatus.isListenerEnabled(ctx)) return result.success(false)
        val engine = FlashRuntime.engine(ctx)
        if (engine.preflight() != null) return result.success(false)
        engine.start(FlashRuntime.notificationPattern(ctx, s), "foreground:$reason")
        result.success(true)
    }

    private fun calibrate(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < 23) return result.error("UNSUPPORTED", "Android 6+ required", null)
        if (calibrating || FlashRuntime.engine(ctx).isRunning) return result.error("CAMERA_UNAVAILABLE", "Busy", null)
        calibrating = true
        Thread {
            val (res, err) = try { Calibrator.run(FlashRuntime.driver(ctx), FlashDiagnostics) } catch (e: Exception) { null to "CAMERA_UNAVAILABLE" }
            calibrating = false
            if (res != null) {
                store.calibratedMinMs = res.calibratedMinMs
                store.calibrationFingerprint = Build.FINGERPRINT
                store.save(store.load())
            }
            main.post {
                if (res != null) result.success(mapOf(
                    "calibratedMinMs" to res.calibratedMinMs,
                    "medianLatencyMs" to res.medianLatencyMs,
                    "confirmedRatio" to res.confirmedRatio))
                else result.error(err ?: "CAMERA_UNAVAILABLE", "Calibration not possible", null)
            }
        }.start()
    }

    /** First enable, or after an OS update (fingerprint change). Silent. */
    private fun maybeCalibrate(s: FlashSettings) {
        if (!s.enabled || Build.VERSION.SDK_INT < 23 || calibrating) return
        if (store.calibrationFingerprint == Build.FINGERPRINT) return
        calibrating = true
        Thread {
            try {
                val (res, _) = Calibrator.run(FlashRuntime.driver(ctx), FlashDiagnostics)
                if (res != null) {
                    store.calibratedMinMs = res.calibratedMinMs
                    store.calibrationFingerprint = Build.FINGERPRINT
                    store.save(store.load())
                }
            } catch (_: Exception) {
            } finally { calibrating = false }
        }.start()
    }

    private fun openNotificationAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    DeviceStatus.listenerComponent(ctx).flattenToString())
            if (launch(detail)) return true
        }
        return launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun launch(intent: Intent): Boolean = try {
        if (activity != null) activity.startActivity(intent)
        else ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    private fun requestPhoneState(result: MethodChannel.Result) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            return result.success(true)
        val a = activity ?: return result.error("NO_ACTIVITY", "No foreground activity", null)
        pendingPermission?.success(false)
        pendingPermission = result
        a.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
    }

    fun onRequestPermissionsResult(code: Int, grantResults: IntArray) {
        if (code != REQ_PHONE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        pendingPermission?.success(granted)
        pendingPermission = null
        if (granted) FlashDiagnostics.log("PERMISSION_GRANTED", "which" to "read_phone_state")
    }

    private fun getInstalledApps(result: MethodChannel.Result) {
        Thread {
            try {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                val seen = HashSet<String>()
                val list = ArrayList<Map<String, Any?>>()

                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == ctx.packageName || !seen.add(pkg)) continue
                    val name = ri.loadLabel(pm).toString()
                    val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val iconBytes = drawableToByteArray(ri.loadIcon(pm))
                    list.add(mapOf(
                        "name" to name,
                        "packageName" to pkg,
                        "isSystemApp" to isSystem,
                        "iconBytes" to iconBytes
                    ))
                }
                list.sortWith(compareBy({ it["isSystemApp"] as Boolean }, { (it["name"] as String).lowercase() }))
                main.post { result.success(list) }
            } catch (e: Exception) {
                main.post { result.error("QUERY_FAILED", e.message, null) }
            }
        }.start()
    }

    private fun drawableToByteArray(drawable: Drawable): ByteArray? {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val b = drawable.bitmap
                if (b.width > 96 || b.height > 96) Bitmap.createScaledBitmap(b, 72, 72, true) else b
            } else {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceAtMost(72) else 72
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceAtMost(72) else 72
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val CHANNEL = "com.example.android_flashlight/flash_alerts"
        private const val REQ_PHONE = 4711
    }
}
