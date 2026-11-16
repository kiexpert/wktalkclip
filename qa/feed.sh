#!/bin/bash
# Emulator run where the recognizer hears a prerecorded clip (FeedSource) instead of the microphone.
# Usage: feed.sh <apk> <wav> <en|ko> <model-wait-seconds> [expected-word ...]
# The wav must be PCM 16-bit mono 16000 Hz. Builds nothing.
set -u
[ $# -ge 4 ] || { echo "usage: $0 <apk> <wav> <en|ko> <model-wait-seconds> [expected-word ...]"; exit 2; }
APK="$(realpath "$1")"; WAV="$(realpath "$2")"; LANGSEL="$3"; WAIT="${4:-60}"
shift 4
WORDS=("$@")
PKG=app.wktalkclip
DEST=/sdcard/Android/data/$PKG/files
PY="python3 -I"
OUT="${FEED_OUT:-$PWD/feed-out}"; mkdir -p "$OUT"; OUT="$(realpath "$OUT")"
cd "$(dirname "$0")"
FAILS=0
res() { echo "RESULT $1"; }

launch() { adb shell am start -n $PKG/.MainActivity --ez feed true >/dev/null; sleep 4; }
screen() { $PY ui.py dump; }
signer() { "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools/34.0.0/apksigner" verify --print-certs "$1" 2>/dev/null | grep 'SHA-256 digest' | head -1 | awk '{print $NF}'; }

if [ -n "${OLD_APK:-}" ]; then
  # UPGRADE CASE: an old release is installed first and used once, then the new build is installed
  # over it with `adb install -r`, which keeps the data. What must survive: the permission, the
  # app's data (the pushed sound file) and the speech model. If the signing keys differ Android
  # refuses the update; that is reported by name and the run continues on a clean install.
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb install -r "$OLD_APK" >/dev/null || { res "FAIL could not install the old release ${OLD_TAG:-old}"; exit 1; }
  adb shell pm grant $PKG android.permission.RECORD_AUDIO
  adb shell mkdir -p "$DEST"
  adb push "$WAV" "$DEST/feed.wav" >/dev/null
  echo "[upgrade] old release ${OLD_TAG:-old} signer $(signer "$OLD_APK"), new build signer $(signer "$APK")"
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
  $PY ui.py tap "시작" >/dev/null; sleep 8
  echo "[upgrade] the old release after Start: $(screen)"
  $PY ui.py tap "멈추기" >/dev/null; sleep 1
  adb shell am force-stop $PKG; sleep 1
  UP=$(adb install -r "$APK" 2>&1 | tr -d '\r' | tail -2 | tr '\n' ' ')
  echo "[upgrade] adb install -r of the new build over ${OLD_TAG:-old}: $UP"
  if echo "$UP" | grep -q "Success"; then
    res "PASS upgrade install over ${OLD_TAG:-old} succeeded (same signing key)"
    if adb shell dumpsys package $PKG | grep -E "android.permission.RECORD_AUDIO" | grep -q "granted=true"; then res "PASS the microphone permission was kept by the upgrade";
    else res "FAIL the microphone permission was NOT kept by the upgrade"; FAILS=$((FAILS+1)); fi
    if adb shell "ls $DEST/feed.wav" 2>/dev/null | grep -q feed.wav; then res "PASS the app's data was kept by the upgrade (feed.wav still there)";
    else res "FAIL the app's data was NOT kept by the upgrade"; FAILS=$((FAILS+1)); fi
  else
    res "FAIL upgrade install over ${OLD_TAG:-old} refused: $UP (old signer $(signer "$OLD_APK"), new signer $(signer "$APK")); continuing on a clean install"
    FAILS=$((FAILS+1))
    adb uninstall $PKG >/dev/null 2>&1 || true
    adb install -r "$APK" >/dev/null
  fi
else
  adb install -r "$APK" >/dev/null
fi
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c
adb shell mkdir -p "$DEST"
adb push "$WAV" "$DEST/feed.wav" >/dev/null || { res "FAIL adb push of wav"; exit 1; }
# The transcript is one TextView with newlines, so split on both '|' (ui.py join) and newlines.
lines_in() { echo "$1" | tr '|' '\n' | grep -cE '^\[[0-9]{2}:[0-9]{2}\]'; }
has() { echo "$1" | grep -q "$2"; }

# Language: the app starts in Korean and the language button cycles ko -> en -> auto, so for English
# one tap on the button (whose label is then "한국어") is enough.
pick_lang() {
  [ "$LANGSEL" = en ] && { $PY ui.py tap "한국어" >/dev/null; sleep 1; }
  # simple mode: Start does only the recognizer (the way the very first build did)
  [ "${SIMPLE:-}" = 1 ] && { $PY ui.py tap "단순 모드: 꺼짐" >/dev/null; sleep 1; }
  return 0
}

# Accept the offline model download once, as voice.sh's warmup does (the system dialog says Download).
warmup_round() {
  launch
  pick_lang
  $PY ui.py tap "시작" >/dev/null; sleep 6
  if has "$(screen)" "음성 모델 받기"; then
    echo "[warmup] model missing, asking the system to download it"
    $PY ui.py tap "음성 모델 받기" >/dev/null
    for i in $(seq 1 "${DIALOG_POLLS:-12}"); do
      sleep 5
      D=$(screen); echo "[warmup] dialog check $i: $D"
      T=$($PY ui.py tap "Download")
      [ "$T" = tapped ] || T=$($PY ui.py tap "다운로드")
      if [ "$T" = tapped ]; then echo "[warmup] tapped download"; adb shell am force-stop $PKG; return 0; fi
    done
  fi
  adb shell am force-stop $PKG; sleep 2
  return 1
}

# Reads the installed on-device languages from the app's diagnostics screen.
installed_models() {
  launch
  $PY ui.py tap "진단" >/dev/null; sleep 5
  D=$(screen)
  adb shell am force-stop $PKG
  echo "$D" | tr '|' '\n' | grep -oE '설치됨=\[[^]]*\]' | head -1
}

# The system asks for the download at the second or third try, so repeat the whole round.
warmup() {
  for r in 1 2 3; do
    echo "[warmup] round $r"
    if warmup_round; then
      echo "[warmup] waiting ${WAIT}s for the download"
      sleep "$WAIT"
      break
    fi
  done
  if [ "${REQUIRE_MODEL:-}" = 1 ]; then
    NEED=en-US; [ "$LANGSEL" = ko ] && NEED=ko-KR
    INST=$(installed_models)
    echo "MODEL CHECK: need $NEED, the phone reports: ${INST:-nothing}"
    if ! echo "$INST" | grep -q "$NEED"; then
      echo "RESULT FAIL MISSING MODEL: on-device speech model $NEED is not installed (${INST:-no report}); the on-device path cannot be proven on this emulator image"
      echo "$INST" > "$OUT/model-missing-$LANGSEL.txt"
      exit 1
    fi
    echo "RESULT PASS on-device model $NEED is installed"
  fi
}

warmup
launch
pick_lang
ROUNDS="${ROUNDS:-1}"
CLIP_SECS=$(( ( $(stat -c %s "$WAV") - 44 ) / 32000 ))
transcript_lines() { echo "$1" | tr '|' '\n' | grep -E '^\[[0-9]{2}:[0-9]{2}\]'; }
PREV=0
for R in $(seq 1 "$ROUNDS"); do
  # One app session: Start, the sound file, Stop, and again without leaving the app.
  T0=$(date +%s)
  $PY ui.py tap "시작" >/dev/null; sleep 6
  S=$(screen)
  [ "$R" = 1 ] && { echo "[start] right after the first Start: $S"; adb logcat -d | grep -E "FATAL|AndroidRuntime|app.wktalkclip|SpeechRecogn|RecognitionService" | tail -25 | sed 's/^/LOG: /'; }
  # After the model is ready the app may still show 시작 (the earlier try ended); press it again.
  if ! has "$S" "멈추기"; then $PY ui.py tap "시작" >/dev/null; sleep 6; fi
  # The clip loops; give the recognizer up to ~90 s to commit a transcript line of its own.
  sleep 20
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do
    S=$(screen)
    [ "$(lines_in "$S")" -gt "$PREV" ] && break
    sleep 5
  done
  sleep 5
  S=$(screen)
  echo "$S" > "$OUT/screen-$LANGSEL-round$R.txt"
  [ "$R" = "$ROUNDS" ] && { adb logcat -d > "$OUT/logcat-$LANGSEL.txt" 2>&1; }
  echo "$S" | tr '|' '\n' | sed "s/^/SCREEN r$R: /"
  TOTAL=$(lines_in "$S")
  NEW=$((TOTAL - PREV))
  ROUND_TEXT=$(transcript_lines "$S" | sed -n "$((PREV + 1)),\$p")
  ERRNAME=$(echo "$S" | grep -oE 'err[0-9]+' | head -1)
  T1=$(date +%s)
  LOOPS=$(( (T1 - T0) * 10 / ( CLIP_SECS * 10 + 15 ) ))
  LIMIT=$(( LOOPS + 2 ))
  if [ "$NEW" -gt 0 ]; then res "PASS round $R: its own transcript has $NEW new line(s)"; else res "FAIL round $R: no transcript of its own (new lines: 0, recognizer error: ${ERRNAME:-none})"; FAILS=$((FAILS+1)); fi
  # A sentence is read at most once per loop of the sound file; many more lines means one sentence
  # is shown as several lines (for example a case-only change). Limit = loops played + 2.
  if [ "$NEW" -le "$LIMIT" ]; then res "PASS round $R: display lines for the sound file: $NEW (limit $LIMIT = $LOOPS loops played + 2)";
  else res "FAIL round $R: display lines for the sound file: $NEW (limit $LIMIT = $LOOPS loops played + 2): one sentence is shown as several lines"; FAILS=$((FAILS+1)); fi
  if [ ${#WORDS[@]} -gt 0 ]; then
    FOUND=""
    for w in "${WORDS[@]}"; do
      if echo "$ROUND_TEXT" | grep -qiF -- "$w"; then FOUND="$w"; break; fi
    done
    if [ -n "$FOUND" ]; then res "PASS round $R: its own lines contain the expected word '$FOUND'";
    else res "FAIL round $R: its own lines contain none of: ${WORDS[*]}"; FAILS=$((FAILS+1)); fi
  fi
  echo "ROUND $R: new_lines=$NEW total_lines=$TOTAL recognizer_error=${ERRNAME:-none}"
  $PY ui.py tap "멈추기" >/dev/null; sleep 2
  S=$(screen)
  echo "$S" > "$OUT/screen-$LANGSEL-after-stop$R.txt"
  C=$(echo "$S" | grep -oE 'clips=[0-9]+' | head -1)
  # Each Stop writes the day's one entry once, so after round R the counter is R.
  if [ "$C" = "clips=$R" ]; then res "PASS round $R: clipboard counter after Stop: $C (one write per stop)";
  else res "FAIL round $R: clipboard counter after Stop: ${C:-none} (expected clips=$R)"; FAILS=$((FAILS+1)); fi
  PREV=$TOTAL
done
N=$PREV
# The day's text goes to the share sheet: KakaoTalk is not on the emulator, so the button must fall back to it.
if [ "$N" -gt 0 ]; then
  $PY ui.py tap "카톡으로" >/dev/null; sleep 3
  SH=$(screen); echo "SHARE: $SH"
  echo "$SH" > "$OUT/screen-$LANGSEL-share.txt"
  if has "$SH" "회의 내용 공유" || has "$SH" "Share"; then res "PASS 카톡으로 without KakaoTalk opens the share sheet";
  else res "FAIL 카톡으로 without KakaoTalk did not open the share sheet"; FAILS=$((FAILS+1)); fi
  if has "$SH" "wktalkclip 20"; then res "PASS shared text starts with the day header";
  else res "NA day header not visible in the share sheet preview"; fi
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi
# Update banner from the real release check.
if echo "$S" | grep -q "새 버전 v0.1."; then res "PASS update banner is shown"; else res "NA update banner not seen on this screen"; fi
adb logcat -d > "$OUT/logcat-$LANGSEL.txt" 2>&1
adb shell am force-stop $PKG; sleep 2
exit $FAILS
