# wktalkclip

On-device Korean speech-to-text for Android. Audio and text never leave the phone and no files are written. The only network use is the update check: the app reads the latest release of this public repository and downloads its APK from github.com, nothing else. One day is one clipboard entry: every meeting of the same calendar day is merged into a single entry (a time header per meeting, a new day starts a fresh entry), written when the app is paused or stopped, so the clipboard history is not filled with one entry per sentence or per meeting. The entry starts with a line like `[wktalkclip 2026-11-16]`; after the app was killed, the next Start on the same day continues from that entry if it is still in the clipboard.

## Permissions

- Microphone: dictation.
- Notifications and foreground service: the "recording" notification that keeps a long meeting alive with the screen off.
- Internet (`INTERNET`): only to check the latest release on github.com and download the new APK. Audio and transcript are never sent.
- Install packages (`REQUEST_INSTALL_PACKAGES`): so the app can hand the downloaded update to the system installer; Android asks you to allow it once. The update is signed with the same key, so it installs over the current app and keeps its settings. The APK is streamed straight to the installer, no file is created.

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
- The share buttons send the day's whole text as plain text: `공유` opens the system share sheet, `카톡으로` goes straight to KakaoTalk if it is installed (otherwise the share sheet). No file, no Kakao SDK, no extra permission.
- Final recognizer text goes to the clipboard; superseded partial guesses are shown on screen but not copied.

## Signing key

Updates only install over the installed app when the signing key stays the same. CI signs with the repository secrets `APK_KEYSTORE_B64` (the keystore, base64) and `APK_KEYSTORE_PASS`; when they are not set it falls back to a key kept in the Actions cache. `signing-fingerprint.txt` holds the expected certificate fingerprint: a build that would be signed with a different key stops before it publishes a release. The key itself is never committed, printed or uploaded.

Releases v0.1.1 to v0.1.3 were signed with one-off keys made on each CI run (the stable key first appears in v0.1.4), so Android refuses to update those over any later build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, measured in the emulator feed QA run for v0.1.1): uninstall the old app once, then every later build installs over the previous one.

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

Every push to `main` builds a new debug-signed APK in GitHub Actions and publishes it as the latest release. The signing key is stored as repository secrets (certificate fingerprint `44b2e00e…6bfb`, pinned in `signing-fingerprint.txt`; a build signed with another key stops before publishing), so every release from v0.1.4 on installs over the previous one without uninstalling. Only v0.1.1, v0.1.2 and v0.1.3, signed with one-off keys, need one uninstall.
