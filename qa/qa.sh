#!/bin/bash
# Emulator check for the start -> stop -> force-stop -> relaunch -> start path.
# The emulator has no Korean on-device recognizer, so both starts must end in the SAME visible
# state and the app must never crash. This catches a second Start that does nothing or dies.
set -u
PKG=app.wktalkclip
PY="python3 -I"
cd "$(dirname "$0")"
FAILS=0
check() { if [ "$2" = ok ]; then echo "RESULT PASS $1"; else echo "RESULT FAIL $1"; FAILS=$((FAILS+1)); fi; }

adb install -r ../out/wktalkclip.apk >/dev/null
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

launch() { adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4; }
snapshot() { $PY ui.py dump | tr '|' '\n' | grep -vE '^[0-9]{2}:[0-9]{2}' | tr '\n' '|'; }

# first run
launch
S0=$(snapshot); echo "initial: $S0"

# In-app update check: this build has the manifest's versionCode, far below the latest release's
# 100 + N, so the app must show the "새 버전 v0.1.N" banner after it asked github.com.
BANNER=""
for i in 1 2 3 4 5 6 7 8; do
  if $PY ui.py dump | grep -q "새 버전 v0.1."; then BANNER=yes; break; fi
  sleep 3
done
[ -n "$BANNER" ] && check "update banner appears (real check against the latest release)" ok || check "update banner appears (real check against the latest release)" bad
$PY ui.py tap "새 버전" >/dev/null; sleep 2
echo "after the 새 버전 button: $(snapshot)"
# nothing was dictated, so there is nothing to share: the app must say so and not crash
$PY ui.py tap "카톡으로" >/dev/null; sleep 1
$PY ui.py dump | grep -q "공유할 내용이 없어요" && check "kakao button with nothing dictated says there is nothing to share" ok || check "kakao button with nothing dictated says there is nothing to share" bad
$PY ui.py tap "시작"; sleep 4
S1=$(snapshot); echo "after first start: $S1"
$PY ui.py tap "멈추기" >/dev/null; sleep 2
$PY ui.py tap "멈췄어요" >/dev/null
adb shell am force-stop $PKG; sleep 2

# relaunch, start again
launch
S2=$(snapshot); echo "relaunched: $S2"
$PY ui.py tap "시작"; sleep 4
S3=$(snapshot); echo "after second start: $S3"

# state fields change with timing, so compare the status sentence only
status() { echo "$1" | tr '|' '\n' | grep -E '요|없|오류|이전' | head -1; }
[ -n "$(adb shell pidof $PKG)" ] && check "app alive after second start" ok || check "app alive after second start" bad
[ "$(status "$S1")" = "$(status "$S3")" ] && check "second start reaches the same state as the first" ok || check "second start reaches the same state as the first" bad
adb logcat -d | grep -q "FATAL EXCEPTION" && check "no crash in logcat" bad || check "no crash in logcat" ok
adb logcat -d -s AndroidRuntime:E | head -20
exit $FAILS
