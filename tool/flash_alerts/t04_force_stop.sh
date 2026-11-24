#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; clear_log; adb shell am force-stop "$PKG"; sleep 2; post_notif
r=0; expect_no_log "FLASH_START" 6 || r=1   # force-stop is EXPECTED to be unsupported
clear_log; launch_app; sleep 4; post_notif
expect_log "FLASH_START" 10 || r=1; exit $r
