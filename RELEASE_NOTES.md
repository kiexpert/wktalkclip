Debug-signed APK (app 1.18). Installs over earlier builds (same signing key).

What is in this build
- One clipboard entry per calendar day: every meeting of the day is merged into one entry with a time header per meeting; the app creates no files. Tests: Build APK run 43709558197.
- Korean, English and Auto (mixed) recognition, on-device only.
- Long meetings: microphone foreground service with wake lock, recognizer errors never end the meeting, calmer watchdog, incremental screen drawing. Simulated 3 hour restart soak in CI: run 43709558197 (RestartSoakTest).
- Calls: the meeting pauses during a phone call or a voice-call app and resumes by itself. Audio focus is deliberately not used: a permanent focus loss may never send a gain (the meeting would stay paused), it would stop other media, and the microphone does not need it. Real-phone check that covers it: receive a call during a meeting, then look for "다시 시작" in the diagnostics screen's recent events.
- Diagnostics screen (진단) and a one-tap Share of the day's text.

Proof by run id
- Item 1, recognition without a phone: Emulator feed QA run 43707801543, job "feed en online" passed: the app's sound-in to text-out path produced "hello everyone let us start the meeting the first item is the schedule". This used a CI-only copy of the app with the online recognizer; the shipped app is on-device only. The on-device engine could not be tested: the emulator has no speech model (job "feed en ondevice" failed, no download dialog in 5 minutes). Korean: job "feed ko online" failed with an empty transcript.
- Item 2, calls and 3 hour restarts: unit tests MeetingPause (21) and the simulated soak, both in run 43709558197.
- Item 4, one-minute check: on the app's first screen and on the download page.

Not verified on a phone: dictation itself, screen-off recording, mixed-language accuracy, call pause. Open the diagnostics screen and send a screenshot if nothing is printed.
