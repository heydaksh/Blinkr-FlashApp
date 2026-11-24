#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; clear_log
adb shell cmd notification disallow_listener "$LISTENER"; sleep 2; post_notif
r=0; expect_no_log "FLASH_START" 5 || r=1
adb shell cmd notification allow_listener "$LISTENER"; sleep 2; exit $r
