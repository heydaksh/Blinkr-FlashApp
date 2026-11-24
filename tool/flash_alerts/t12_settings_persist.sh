#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; debug_config --ei onMs 80 --ei offMs 80 --ei pulses 2 --ei gapMs 300; sleep 3
launch_app; adb shell input keyevent KEYCODE_HOME; adb shell am kill "$PKG"; sleep 8; clear_log; post_notif
expect_log "FLASH_START.*on=80 off=80 pulses=2 gap=300"
