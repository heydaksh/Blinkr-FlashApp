#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; debug_config --ez respectDnd true; sleep 3; clear_log
adb shell cmd notification set_dnd priority; sleep 1; post_notif
r=0; expect_log "NOTIF_IGNORED.*reason=DND" || r=1
adb shell cmd notification set_dnd off; exit $r
