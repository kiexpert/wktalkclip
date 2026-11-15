#!/bin/bash
# Emulator run with real speech played into the virtual microphone.
# Usage: voice.sh <apk> <en|ko> <model-wait-seconds>
set -u
APK="$(realpath "$1")"; LANGSEL="$2"; WAIT="${3:-60}"
PKG=app.wktalkclip
PY="python3 -I"
cd "$(dirname "$0")"
FAILS=0
res() { echo "RESULT $1"; }

adb install -r "$APK" >/dev/null
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb logcat -c
launch() { adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4; }
screen() { $PY ui.py dump; }
lines_in() { echo "$1" | tr '|' '\n' | grep -cE '^\[[0-9]{2}:[0-9]{2}\]'; }
has() { echo "$1" | grep -q "$2"; }

cycle() {  # $1 = label
  launch
  if [ "$LANGSEL" = en ]; then $PY ui.py tap "한국어" >/dev/null; sleep 1; fi
  $PY ui.py tap "시작" >/dev/null; sleep 6
  S=$(screen)
  if has "$S" "음성 모델 받기"; then
    echo "[$1] model missing, download wait ${WAIT}s"
    $PY ui.py tap "음성 모델 받기" >/dev/null; sleep "$WAIT"
    S=$(screen); has "$S" "멈추기" || { $PY ui.py tap "시작" >/dev/null; sleep 6; }
  fi
  sleep 30
  S=$(screen); echo "[$1] screen: $S"
  N=$(lines_in "$S")
  if [ "$N" -gt 0 ]; then res "PASS $1 transcript has $N line(s)"; else res "FAIL $1 transcript empty"; FAILS=$((FAILS+1)); fi
  $PY ui.py tap "멈추기" >/dev/null; sleep 2
  S=$(screen)
  C=$(echo "$S" | grep -oE 'clips=[0-9]+' | head -1)
  if [ -z "$C" ]; then res "NA $1 clipboard writes (build has no counter)";
  elif [ "$C" = "clips=1" ] || [ "$C" = "clips=2" ]; then res "PASS $1 clipboard writes after $N sentences: $C";
  else res "FAIL $1 clipboard writes after $N sentences: $C"; FAILS=$((FAILS+1)); fi
  adb shell am force-stop $PKG; sleep 2
}
cycle first
cycle second
exit $FAILS
