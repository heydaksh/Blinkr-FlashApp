#!/usr/bin/env bash
cd "$(dirname "$0")"; source common.sh
reset_config; sleep 3; clear_log
for i in $(seq 10); do post_notif; done
sleep 4
s=$(count_log "FLASH_START"); i=$(count_log "NOTIF_IGNORED.*reason=(COOLDOWN|DUPLICATE)|FLASH_COALESCED")
echo "starts=$s ignored/coalesced=$i"
[ "$s" = "1" ] && [ "$i" -ge 9 ] && echo "PASS: burst" || { echo "FAIL: burst"; exit 1; }
