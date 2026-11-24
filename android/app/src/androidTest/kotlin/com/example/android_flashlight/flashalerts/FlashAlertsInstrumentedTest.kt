package com.example.android_flashlight.flashalerts

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

private fun hasFlash() = ctx().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

private fun awaitStop(engine: FlashEngine, timeoutMs: Long = 6000): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (engine.isRunning && System.currentTimeMillis() < end) Thread.sleep(20)
    return !engine.isRunning
}

/** Physical-device tests: skipped automatically when there is no flash. */
@RunWith(AndroidJUnit4::class)
class CameraTorchDriverTest {
    @Before fun setUp() { FlashRuntime.init(ctx()); assumeTrue(hasFlash() && Build.VERSION.SDK_INT >= 23) }

    @Test fun twentyTogglesAt100msMostlyConfirmed() {
        val d = CameraTorchDriver(ctx(), FlashDiagnostics)
        val confirmed = java.util.concurrent.atomic.AtomicInteger()
        d.setConfirmListener { _, _ -> confirmed.incrementAndGet() }
        repeat(20) { i -> d.set(i % 2 == 0); Thread.sleep(100) }
        d.set(false); Thread.sleep(300)
        d.setConfirmListener(null)
        assertTrue("confirmed=${confirmed.get()}", confirmed.get() >= 19)
    }

    @Test fun calibrationInRange() {
        val (res, err) = Calibrator.run(CameraTorchDriver(ctx(), FlashDiagnostics), FlashDiagnostics)
        assertNull(err)
        assertTrue(res!!.calibratedMinMs in 20..120)
    }
}

@RunWith(AndroidJUnit4::class)
class FlashEngineTimingTest {
    @Test fun defaultPatternRunsToCompletionAndEndsOff() {
        assumeTrue(hasFlash() && Build.VERSION.SDK_INT >= 23)
        val engine = FlashRuntime.engine(ctx())
        val p = FlashPattern(60, 60, 3, 400, 2, 3000)
        val t0 = System.currentTimeMillis()
        engine.start(p, "instrumented")
        Thread.sleep(100)
        assertTrue(engine.isRunning)
        assertTrue(awaitStop(engine))
        val took = System.currentTimeMillis() - t0
        val expected = p.totalMs()
        assertTrue("took=$took expected=$expected", took in (expected * 85 / 100)..(expected * 115 / 100 + 300))
    }
}

@RunWith(AndroidJUnit4::class)
class NoFlashTest {
    @Test fun noFlashDeviceNeverCrashes() {
        assumeTrue(!hasFlash())
        val driver = CameraTorchDriver(ctx(), FlashDiagnostics)
        assertFalse(driver.hasFlash)
        val engine = FlashRuntime.engine(ctx())
        engine.start(FlashPattern(), "instrumented")
        Thread.sleep(200)
        assertFalse(engine.isRunning)
        assertTrue(FlashDiagnostics.read().contains("NO_FLASH_HARDWARE"))
    }
}

@RunWith(AndroidJUnit4::class)
class UserTorchOnTest {
    @Test fun userTorchIsNeverSwitchedOff() {
        assumeTrue(hasFlash() && Build.VERSION.SDK_INT >= 23)
        val cm = ctx().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        val engine = FlashRuntime.engine(ctx())
        FlashRuntime.driver(ctx()) // register torch callback first
        Thread.sleep(300)
        var torchState = false
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) torchState = enabled }
        }
        cm.registerTorchCallback(cb, null)
        try {
            cm.setTorchMode(id, true) // "user" turns it on from a separate path
            Thread.sleep(600)
            engine.start(FlashPattern(), "instrumented")
            Thread.sleep(500)
            assertFalse(engine.isRunning)
            assertTrue("user torch must remain on", torchState)
            assertTrue(FlashDiagnostics.read().contains("SKIP_USER_TORCH_ON"))
        } finally {
            cm.setTorchMode(id, false)
            cm.unregisterTorchCallback(cb)
        }
    }
}

/** Needs CAMERA granted to the *test* APK: `adb shell pm grant <pkg>.test android.permission.CAMERA` (or GrantPermissionRule). */
@RunWith(AndroidJUnit4::class)
class CameraInUseTest {
    @Test @Suppress("MissingPermission")
    fun cameraInUseSkipsCleanlyAndRecovers() {
        assumeTrue(hasFlash() && Build.VERSION.SDK_INT >= 23)
        assumeTrue(ctx().checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val cm = ctx().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
        val engine = FlashRuntime.engine(ctx())
        FlashRuntime.driver(ctx())
        val thread = android.os.HandlerThread("cam").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val opened = java.util.concurrent.CountDownLatch(1)
        var device: android.hardware.camera2.CameraDevice? = null
        cm.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
            override fun onOpened(camera: android.hardware.camera2.CameraDevice) { device = camera; opened.countDown() }
            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { camera.close() }
            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) { camera.close(); opened.countDown() }
        }, handler)
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(500)
        engine.start(FlashPattern(), "instrumented")
        awaitStop(engine, 3000)
        assertFalse(engine.isRunning)
        val log = FlashDiagnostics.read()
        assertTrue(log.contains("CAMERA_UNAVAILABLE") || log.contains("TOGGLE_FAILED") || log.contains("TOGGLE_ERROR"))
        device?.close(); thread.quitSafely()
        Thread.sleep(1500)
        engine.start(FlashPattern(), "recovery")
        Thread.sleep(150)
        assertTrue(engine.isRunning)
        awaitStop(engine)
    }
}
