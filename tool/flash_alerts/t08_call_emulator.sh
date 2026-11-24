#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
is_emulator || { echo "SKIP (emulator only)"; exit 0; }
reset_config; clear_log
adb emu gsm call 5551234; sleep 4
r=0; expect_log "CALL_RINGING" && expect_log "FLASH_START.*reason=call" || r=1
adb emu gsm accept 5551234
expect_log "FLASH_STOP.*reason=CALL_ANSWERED" 2 || r=1
adb emu gsm cancel 5551234; exit $r
