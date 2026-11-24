#!/usr/bin/env bash
# Shared helpers. Usage: source common.sh
PKG="${PKG:-com.example.android_flashlight}"
LISTENER="$PKG/$PKG.flashalerts.FlashNotificationListenerService"
EMU_PREFIX="$PKG.flashalerts"

is_emulator() { [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1" ] || adb shell getprop ro.product.model | grep -qi sdk; }

clear_log() { adb logcat -c; }
# expect_log <regex> [timeout seconds]: polls logcat -d so it never blocks on the stream.
expect_log() {
  local end=$((SECONDS + ${2:-10}))
  while [ $SECONDS -lt $end ]; do
    adb logcat -d -s FlashAlerts | grep -qE "$1" && { echo "PASS: $1"; return 0; }
    sleep 0.5
  done
  echo "FAIL: $1"; return 1
}
expect_no_log() { # <regex> [wait seconds]
  sleep "${2:-6}"
  if adb logcat -d -s FlashAlerts | grep -qE "$1"; then echo "FAIL (unexpected): $1"; return 1; fi
  echo "PASS (absent): $1"
}
count_log() { adb logcat -d -s FlashAlerts | grep -cE "$1"; }

# Debug-only receiver (src/debug). Posts a high-importance notification from the app itself.
post_notif() { adb shell am broadcast -a "$EMU_PREFIX.DEBUG_POST" -p "$PKG" >/dev/null; }
# cmd notification posts as com.android.shell, often LOW importance; kept for ad-hoc use.
post_shell_notif() { adb shell cmd notification post -S bigtext -t "QA" "qa_tag_$RANDOM" "flash test"; }

# config --ez enabled true ...
debug_config() { adb shell am broadcast -a "$EMU_PREFIX.DEBUG_CONFIG" -p "$PKG" "$@" >/dev/null; }

launch_app() { adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3; }
# Flash-free defaults so back-to-back tests aren't blocked by the cooldown.
reset_config() { debug_config --ez enabled true --ez dryRun "$(is_emulator && echo true || echo false)" --ei cooldownMs 500; }
