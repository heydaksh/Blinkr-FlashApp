package com.example.android_flashlight

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest

/** Self-update for sideloaded builds: verifies a downloaded APK and hands it to the system installer. */
class AppUpdater(private val ctx: Context) {
    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getVersionCode" -> {
                        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        result.success(if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
                    }
                    "updatesDir" -> result.success(File(ctx.cacheDir, "updates").apply { mkdirs() }.absolutePath)
                    "install" -> result.success(install(call.argument("path")!!, call.argument("sha256")))
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("UPDATE_ERROR", e.javaClass.simpleName, null)
            }
        }
    }

    /** Returns OK, NEEDS_PERMISSION (settings screen opened), BAD_HASH or BAD_FILE. */
    private fun install(path: String, sha256: String?): String {
        val file = File(path)
        val dir = File(ctx.cacheDir, "updates").canonicalPath
        if (!file.exists() || !file.canonicalPath.startsWith(dir)) return "BAD_FILE"
        if (!sha256.isNullOrBlank()) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256.trim(), ignoreCase = true)) { file.delete(); return "BAD_HASH" }
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "NEEDS_PERMISSION"
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return "OK"
    }

    companion object { const val CHANNEL = "com.example.android_flashlight/app_update" }
}
