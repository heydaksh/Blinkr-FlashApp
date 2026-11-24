#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; adb reboot; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d "\r")" = "1" ]; do sleep 2; done
adb shell input keyevent 82; sleep 20; clear_log; post_notif
expect_log "LISTENER_CONNECTED|NOTIF_RECEIVED" && expect_log "FLASH_START"   # device must have no secure lock
