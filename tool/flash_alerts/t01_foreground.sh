#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; launch_app; clear_log; post_notif
expect_log "NOTIF_RECEIVED" && expect_log "FLASH_START" && expect_log "FLASH_STOP.*reason=COMPLETED" 8
