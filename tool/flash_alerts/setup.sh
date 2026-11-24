#!/usr/bin/env bash
# Installs the debug APK, grants notification access and enables the feature.
# Build first: flutter build apk --debug
set -e
cd "$(dirname "$0")"; source common.sh
adb install -r ../../build/app/outputs/flutter-apk/app-debug.apk
adb shell cmd notification allow_listener "$LISTENER"
adb shell pm grant "$PKG" android.permission.READ_PHONE_STATE || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true   # Android 13+, debug manifest only
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true       # Android 13+ sideload
launch_app
reset_config
sleep 2
echo "setup done (dryRun=$(is_emulator && echo on || echo off))"
