#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
is_emulator || { echo "SKIP (emulator only)"; exit 0; }
reset_config; clear_log
adb emu gsm call 5551234; sleep 4
expect_log "FLASH_START.*reason=call" || exit 1
adb emu gsm cancel 5551234
expect_log "FLASH_STOP.*reason=(CALL_NOTIFICATION_REMOVED|CALL_ENDED)" 3
