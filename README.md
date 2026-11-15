# wktalkclip

On-device Korean speech-to-text for Android. Audio never leaves the phone: no server traffic and no files written. One day is one clipboard entry: every meeting of the same calendar day is merged into a single entry (a time header per meeting, a new day starts a fresh entry), written when the app is paused or stopped, so the clipboard history is not filled with one entry per sentence or per meeting. The entry starts with a line like `[wktalkclip 2026-11-16]`; after the app was killed, the next Start on the same day continues from that entry if it is still in the clipboard.

## Rules the app follows

- The language button cycles Korean, English and Auto (Korean and English mixed, Android 14+, depends on the phone's on-device engine).
- Recognition runs on the device only (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, segmented session, API 33+).
- Shown text is never erased; a revision is shown as one diff line `± …[-old][+new]…`.
- Every recognizer error code is shown on screen.
- Media playback volume (STREAM_MUSIC) is never muted; only the start beep streams are.

## Long meetings

- Recording runs as a microphone foreground service with a notification and a CPU wake lock, so it keeps going with the screen off or the phone in a pocket.
- A recognizer error never ends the meeting: it is retried with a growing pause (200 ms up to 30 s) and the error code stays visible.
- The screen draws each finished line once and only redraws the small grey composing line, so hour 3 is as fast as minute 1.
- While the screen is off the single clipboard entry is refreshed every 15 minutes (replaced, not added to). The clipboard has a size limit (200000 characters): if the day is longer, the oldest text of the day is dropped first and the current meeting is kept; the screen keeps everything.
- Final recognizer text goes to the clipboard; superseded partial guesses are shown on screen but not copied.

## Build (no Gradle)

Needs JDK 11+, Android SDK platform 34 and build-tools 34.0.0 (set `ANDROID_SDK`, default `./sdk`).

    ./build.sh

Output: `out/wktalkclip.apk`, signed with a local debug keystore that the script creates once (never committed).

## Next engine

The SpeechRecognizer engine restarts between utterances and drops speech. The planned replacement is the sherpa-onnx streaming Korean zipformer, which runs continuously on device. Models are downloaded at build or first run from https://github.com/k2-fsa/sherpa-onnx/releases (tag `asr-models`) and are never committed. See `docs/oss-survey.md`.

## Install on a phone

1. On the phone, open this repository on GitHub, tap Releases, and open the latest release.
2. Under Assets, tap `wktalkclip.apk` to download it.
3. Open the downloaded file. When Android asks, allow installs from this source (Settings > Apps > Special access > Install unknown apps > your browser), then tap Install.
4. Open wktalkclip, allow the microphone, and press the start button. The day's text lands in the clipboard as one entry when you leave the app or press stop.

Every push to `main` builds a new debug-signed APK in GitHub Actions and publishes it as the latest release. The signing key is created once and kept in the repository's Actions cache, so each new build installs over the previous one. If the cache is ever evicted (unused for 7 days) a new key is made and Android will refuse the update; then uninstall the old app once and install again (the cache key is apk-signing-v1).
