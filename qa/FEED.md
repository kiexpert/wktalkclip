# Feed hook (QA only)

`FeedSource` lets the on-device recognizer hear a prerecorded clip instead of the microphone.

Wiring in MainActivity:
- Read the intent extra `feed` (boolean) once at start.
- In ensureRecognizer, when `feed` is true and `FeedSource.clipFile(this) != null`, build the
  recognizer intent with `EXTRA_AUDIO_SOURCE` = `FeedSource.open(file, onDone)` (plus
  `_CHANNEL_COUNT`=1, `_ENCODING`=ENCODING_PCM_16BIT, `_SAMPLING_RATE`=16000) instead of the mic.
- If `open` returns null (bad format), fall back to the microphone.
- Each (re)start of the recognizer needs a fresh `open`; call `FeedSource.stop()` on Stop/destroy.
- `onDone` runs on the feeder thread; post to the main thread if it touches UI.

Run: `qa/feed.sh <apk> <wav> <en|ko> <model-wait-seconds> [expected-word ...]`
The wav must be PCM 16-bit mono 16000 Hz, else the hook is ignored.

Why it is safe:
- It only reads `feed.wav` from the app's own external files dir
  (`/sdcard/Android/data/app.wktalkclip/files`), which other apps cannot write.
- It creates no files, opens no network, and logs nothing.
- Without the `feed` extra and the file, behaviour is unchanged.
