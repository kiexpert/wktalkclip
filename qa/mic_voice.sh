#!/bin/bash
# S1: prove audio reaches the guest with a plain recorder (qa/probe), then S2: warm up the English
# model and run the voice cycles. Usage: mic_voice.sh <apk> <en|ko> <model-wait-seconds>
set -u
cd "$(dirname "$0")"
echo "== host audio =="
pactl info | grep -E "Default (Sink|Source)"
pactl list short sources
echo "== emulator host mic =="
adb emu avd hostmicon 2>&1 | head -3
adb shell settings put global heads_up_enabled 0 >/dev/null 2>&1
adb install -r ../out/micprobe.apk >/dev/null
adb shell pm grant app.micprobe android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n app.micprobe/.ProbeActivity >/dev/null
sleep 12
P=$(adb logcat -d -s MICPROBE:I | grep -E "MICPROBE" | tail -1)
echo "$P"
PEAK=$(echo "$P" | grep -oE 'peak=[0-9]+' | cut -d= -f2)
if [ -n "${PEAK:-}" ] && [ "$PEAK" -gt 500 ]; then
  echo "RESULT PASS guest hears the null-sink audio ($P)"
else
  echo "RESULT FAIL guest mic is silent or unavailable ($P)"
  exit 1
fi
exec ./voice.sh "$@"
