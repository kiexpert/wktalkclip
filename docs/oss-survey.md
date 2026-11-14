# Open-source continuous on-device dictation: logic survey

Scope: how open-source projects run continuous on-device dictation, and what our plain-Java app (android.speech.SpeechRecognizer, ko-KR) should copy. Items marked "not verified" were not opened or did not contain the claimed detail.

## 1. sherpa-onnx (k2-fsa) — strongest candidate

Repo: https://github.com/k2-fsa/sherpa-onnx

Kotlin API, file `sherpa-onnx/kotlin-api/OnlineRecognizer.kt`
- Source: https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
- Classes: `EndpointRule`, `EndpointConfig`, `OnlineRecognizerConfig`, `OnlineRecognizerResult`, `OnlineRecognizer`.
- Loop methods: `createStream(hotwords)`, `isReady(stream)`, `decode(stream)`, `isEndpoint(stream)`, `getResult(stream)`, `reset(stream)`, `release()`.
- Endpoint defaults: `rule1MinTrailingSilence = 2.4f`, `rule2MinTrailingSilence = 1.4f`, `rule3MinUtteranceLength = 20.0f`. Rule 2 sets `mustContainNonSilence = true`; rules 1 and 3 set it false.
- `enable_endpoint` defaults to `true` on `OnlineRecognizerConfig`.
- Native methods are private `external` JNI functions (`newFromAsset`, `newFromFile`, `decode`, `isEndpoint`, `getResult`, and others).
- C++ header `sherpa-onnx/csrc/online-recognizer.h` (https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/csrc/online-recognizer.h) lists the same methods (`IsEndpoint`, `DecodeStreams`, `GetResult`, `Reset`) and `enable_endpoint` default true. The `EndpointRule` struct lives in `csrc/endpoint.h`, which was not opened, so its numbers are cross-checked only through the Kotlin defaults.

Android projects, directory listing: https://github.com/k2-fsa/sherpa-onnx/tree/master/android
- Includes `SherpaOnnx` (streaming ASR), `SherpaOnnxSimulateStreamingAsr`, `SherpaOnnx2Pass`, `SherpaOnnxVadAsr`, `SherpaOnnxJavaDemo`, `SherpaOnnxAar`.
- `SherpaOnnxJavaDemo` README: models go in `app/src/main/assets/`, using the example folder `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` with `encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.onnx`, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt`. Source: https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxJavaDemo
- Exact Java source file names, and how the `.so` files are packaged (jniLibs or AAR): not verified. The `SherpaOnnxAar` project suggests a prebuilt AAR path, but its contents were not opened.

Korean model: `k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16`
- https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16
- Page says it is converted from `johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12`, "uses zipformer v1", and points to icefall PR 1651 for training. The `ksponspeech` name suggests the KsponSpeech corpus, but the page does not confirm it.
- File sizes, WER/CER numbers, and license: not verified (the page's file list was not rendered and the repo card has no YAML metadata).
- Listed in the sherpa-onnx pretrained-models docs: https://k2-fsa.github.io/sherpa/onnx/pretrained_models
- Do not confuse with `sherpa-onnx-zipformer-korean-2024-06-24`, which the docs list as non-streaming.

Copy from sherpa-onnx: the `isReady`/`decode`/`isEndpoint`/`getResult`/`reset` loop, which keeps one stream open across utterances instead of restarting a recognizer. Endpointing is done by the model's own rules, not by a restart. Partial text comes from `getResult` while the stream is open. Verify the emit logic in `SherpaOnnx/.../MainActivity` before copying it, because that file was not opened.

## 2. futo-org/voice-input

Repo: https://github.com/futo-org/voice-input (official GitHub mirror; primary source is GitLab https://gitlab.futo.org/keyboard/voiceinput)
- README (https://raw.githubusercontent.com/futo-org/voice-input/master/README.md) names Whisper as the multilingual model, credits `android-vad` and WebRTC VAD, and says the build is `./gradlew assembleStandaloneRelease`. Whisper-acft fine-tunes are mentioned for low latency on short dictations.
- Chunk length, overlap, and boundary handling: not verified. The README does not describe them, and the source under `app/` was not opened.
- A GitLab commit mentions a `USE_VAD_AUTOSTOP` setting (search result only, not opened).
- Copy: nothing concrete yet. Treat as a reference for VAD-driven endpointing, pending a source read.

## 3. soupslurpr/Transcribro

Repo: https://github.com/soupslurpr/Transcribro
- README: "It uses whisper.cpp to run the OpenAI Whisper family of models and Silero VAD for voice activity detection." Source: https://raw.githubusercontent.com/soupslurpr/Transcribro/main/README.md
- Chunk length, overlap, and VAD thresholds: not verified (README gives none; source not opened).
- Language support: English only per a mirror, Korean tracked in issue 18 (search-result claim, not verified on GitHub).
- Copy: nothing verified yet.

## 4. alphacep/vosk-android-demo

Repo: https://github.com/alphacep/vosk-android-demo
- File `app/src/main/java/org/vosk/demo/VoskActivity.java` (https://raw.githubusercontent.com/alphacep/vosk-android-demo/master/app/src/main/java/org/vosk/demo/VoskActivity.java):
  - `onPartialResult` and `onResult` both append to the view; `onFinalResult` sets the UI to done.
  - Microphone path: `new Recognizer(model, 16000.0f)`, `new SpeechService(rec, 16000.0f)`, `speechService.startListening(this)`, `speechService.stop()`.
  - Model folder is `model-en-us`, unpacked via `StorageService.unpack`. The demo is English-only.
- Korean model availability and size: not verified. Vosk's Android page says models must be small and suggests checking sizes in the demo (https://alphacephei.com/vosk/android). The Korean entry on https://alphacephei.com/vosk/models was not confirmed in the fetched excerpt.
- Copy: the partial/final callback split and the `SpeechService` continuous-listen pattern are simple to reproduce in Java without NDK work if a Vosk Korean model fits the APK budget. Not verified for Korean accuracy.

## 5. Continuous android.speech.SpeechRecognizer without gaps

- Search returned RecognitionListener docs only: https://developer.android.com/reference/android/speech/RecognitionListener
- `EXTRA_SEGMENTED_SESSION` requests segmented results; `onSegmentResults` may fire several times between `onReadyForSpeech` and `onEndOfSegmentedSession`. `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` controls end-of-input silence, and the docs say these extras may have no effect depending on the recognizer implementation. Source: https://developer.android.com/reference/android/speech/RecognizerIntent
- No open-source project with a verified gap-free SpeechRecognizer restart loop was found. Not verified.
- Note: the measured fast-speech loss and ERROR_CLIENT on the audio-source pipe are not explained by any source found here.

## Ranked recommendation

1. Top path: sherpa-onnx streaming zipformer Korean, through its Kotlin/JNI API (`OnlineRecognizer` loop above). It is the only candidate with an explicit continuous stream API, endpointing, and a Korean streaming model. It fixes the restart-per-utterance problem by design.
   - Integration cost: prebuilt `.so` plus a Kotlin wrapper or direct JNI. Not verified whether the NDK is required: the `SherpaOnnxAar` project suggests a prebuilt AAR, so an NDK build may not be needed. Java calls need Kotlin interop or a small JNI shim; plain Java can call the Kotlin classes if the wrapper is compiled into the app.
   - APK size: not verified. The Korean model files and the `.so` add size; measure by building the `SherpaOnnxAar` sample.
   - Accuracy on fast speech: not verified; this is the test to run first on a real phone.
2. Fallback: Vosk `SpeechService` with a Korean model, if it exists and fits the size budget. Pure Java, prebuilt libs, simpler build. Korean availability not verified.
3. Keep `SpeechRecognizer` only as a short-term patch. No verified gap-free restart pattern exists.

## Not verified (summary)

- sherpa-onnx `endpoint.h` rule struct, the emit logic in the streaming Android app, the JNI library name, and APK size.
- Korean zipformer file sizes, WER, and license.
- FUTO and Transcribro chunk length, overlap, and VAD thresholds.
- Vosk Korean model existence and size.
- Any open-source gap-free `SpeechRecognizer` restart pattern.
