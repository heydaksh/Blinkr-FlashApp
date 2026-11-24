#!/usr/bin/env bash
# Runs all scenario scripts on the connected device and writes a report.
cd "$(dirname "$0")"; source common.sh
mfr=$(adb shell getprop ro.product.manufacturer | tr -d '\r'); model=$(adb shell getprop ro.product.model | tr -d '\r')
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
report="report_${model// /_}_$(date +%Y%m%d).txt"
{ echo "Device: $mfr $model (SDK $sdk) emulator=$(is_emulator && echo yes || echo no)"; date; echo; } | tee "$report"
./setup.sh | tee -a "$report"
fail=0
for t in t0[1-4]_*.sh t06*.sh t07*.sh t08*.sh t09*.sh t10*.sh t11*.sh t12*.sh t05*.sh; do
  echo "== $t" | tee -a "$report"
  ./"$t" 2>&1 | tee -a "$report"; rc=${PIPESTATUS[0]}
  [ $rc -ne 0 ] && { echo "!! $t FAILED" | tee -a "$report"; fail=1; }
done
adb logcat -d -s FlashAlerts > "log_${model// /_}.txt"
[ $fail -eq 0 ] && echo "ALL PASSED" | tee -a "$report" || echo "SOME FAILED" | tee -a "$report"
exit $fail
