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
