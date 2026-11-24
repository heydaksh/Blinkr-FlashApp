# LED Flash Alerts

Flutter app that blinks the rear camera LED when a notification or incoming call arrives
(Android only). Implementation follows `ANDROID_LED_FLASH_ALERTS_SPEC.md`.

* Native Kotlin (no Flutter engine needed in the background): `android/app/src/main/kotlin/.../flashalerts/`
* Dart UI + channel wrapper: `lib/features/flash_alerts/`
* Debug-only adb hooks: `android/app/src/debug/` (not present in release builds)

## Tests
```
flutter test                               # Dart
cd android && ./gradlew testDebugUnitTest  # Kotlin JVM
cd android && ./gradlew connectedDebugAndroidTest   # device/emulator (flash tests skip without a flash)
flutter build apk --debug && tool/flash_alerts/run_all.sh   # adb scenarios (emulator uses dry-run)
```

## Using it
1. Install, open the app, switch on **Flash for alerts**.
2. Tap **Notification access → Open** and enable the app. Sideloaded on Android 13+: App info → ⋮ → *Allow restricted settings* first.
3. Optional: allow phone state (precise call detection), set battery to *Unrestricted*.
4. **Test flash**, **Calibrate**, **Export diagnostics** are on the same screen.

Not supported by Android: flashing after Force Stop, or before first unlock after reboot.
