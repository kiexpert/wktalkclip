#!/bin/bash
# Emulator run with real speech played into the virtual microphone.
# Usage: voice.sh <apk> ; prints RESULT lines for each measured step.
set -u
APK="$(realpath "$1")"
PKG=app.wktalkclip
PY="python3 -I"
cd "$(dirname "$0")"
FAILS=0
res() { echo "RESULT $1 $2"; }

adb install -r "$APK" >/dev/null
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb logcat -c
echo "model packages:"; adb shell pm list packages | grep -Ei "speech|googlequicksearch|tts" | head
launch() { adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4; }
screen() { $PY ui.py dump; }
transcript_lines() { screen | tr '|' '\n' | grep -E '^\[[0-9]{2}:[0-9]{2}\]' | wc -l; }

cycle() {  # $1 = label
  launch
  $PY ui.py tap "시작" >/dev/null
  # one-time model download attempt when the recognizer asks for it
  if screen | grep -q "한국어 음성 모델 받기"; then
    $PY ui.py tap "한국어 음성 모델 받기" >/dev/null; sleep 60
    $PY ui.py tap "시작" >/dev/null
  fi
  sleep 25
  S=$(screen); echo "[$1] screen: $S"
  N=$(transcript_lines)
  if [ "$N" -gt 0 ]; then res "PASS $1 transcript has $N line(s)" ""; else res "FAIL $1 transcript empty" ""; FAILS=$((FAILS+1)); fi
  $PY ui.py tap "멈추기" >/dev/null; sleep 2
  adb shell am force-stop $PKG; sleep 2
}
cycle first
cycle second
adb logcat -d | grep -iE "setPrimaryClip|ClipboardService" | head -5
exit $FAILS
