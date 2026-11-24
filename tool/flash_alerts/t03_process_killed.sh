#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; launch_app; adb shell input keyevent KEYCODE_HOME; sleep 2; clear_log
adb shell am kill "$PKG"; sleep 10; post_notif
expect_log "PROCESS_START" && expect_log "LISTENER_CONNECTED" && expect_log "FLASH_START"
