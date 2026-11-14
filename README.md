# wktalkclip

On-device Korean speech-to-text for Android. Audio never leaves the phone: no server traffic and no files written. Each finished sentence goes into its own clipboard entry, so the phone's clipboard history keeps the transcript.

## Rules the app follows

- Recognition runs on the device only (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, segmented session, API 33+).
- Shown text is never erased; a revision is shown as one diff line `± …[-old][+new]…`.
- Every recognizer error code is shown on screen.
- Media playback volume (STREAM_MUSIC) is never muted; only the start beep streams are.

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
4. Open wktalkclip, allow the microphone, and press the start button. Each finished sentence lands in the clipboard history.

Every push to `main` builds a new debug-signed APK in GitHub Actions and publishes it as the latest release. The signing key is created once and kept in the repository's Actions cache, so each new build installs over the previous one. If the cache is ever evicted (unused for 7 days) a new key is made and Android will refuse the update; then uninstall the old app once and install again.
