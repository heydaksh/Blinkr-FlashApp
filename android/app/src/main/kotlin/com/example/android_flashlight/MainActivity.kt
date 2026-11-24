package com.example.android_flashlight

import com.example.android_flashlight.flashalerts.FlashAlertsChannelHandler
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var flashHandler: FlashAlertsChannelHandler? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flashHandler = FlashAlertsChannelHandler(applicationContext, this)
            .also { it.register(flutterEngine.dartExecutor.binaryMessenger) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        flashHandler?.onRequestPermissionsResult(requestCode, grantResults)
    }
}
