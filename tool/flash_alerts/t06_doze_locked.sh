#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; clear_log
adb shell input keyevent KEYCODE_SLEEP; adb shell dumpsys deviceidle force-idle >/dev/null; sleep 2; post_notif
r=0; expect_log "FLASH_START" && expect_log "FLASH_STOP.*reason=COMPLETED" 8 || r=1
adb shell dumpsys deviceidle unforce >/dev/null; adb shell input keyevent KEYCODE_WAKEUP
# p95 scheduling drift must stay below 15 ms (FLASH_TOGGLE is logged in debug builds only)
p95=$(adb logcat -d -s FlashAlerts | grep FLASH_TOGGLE | sed -n "s/.*driftMs=\\([0-9-]*\\).*/\\1/p" | sort -n | awk "{a[NR]=\\$1} END{ if(NR) print a[int(NR*0.95+0.5)]; else print -1 }")
echo "p95 drift: ${p95}ms"; [ "$p95" -le 15 ] && echo "PASS: drift" || { echo "FAIL: drift"; r=1; }; exit $r
