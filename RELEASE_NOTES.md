Debug-signed APK (app 1.18). Installs over earlier builds (same signing key).

What is in this build
- One clipboard entry per calendar day: every meeting of the day is merged into one entry with a time header per meeting; the app creates no files. Tests: Build APK run 43709558197.
- Korean, English and Auto (mixed) recognition, on-device only.
- Long meetings: microphone foreground service with wake lock, recognizer errors never end the meeting, calmer watchdog, incremental screen drawing. Simulated 3 hour restart soak in CI: run 43709558197 (RestartSoakTest).
- Calls: the meeting pauses during a phone call or a voice-call app and resumes by itself. Audio focus is deliberately not used: a permanent focus loss may never send a gain (the meeting would stay paused), it would stop other media, and the microphone does not need it. Real-phone check that covers it: receive a call during a meeting, then look for "다시 시작" in the diagnostics screen's recent events.
- In-app update: a banner appears when a newer release exists (checked at start, at most every 6 hours, and with the 새 버전 button); tapping it downloads the APK from github.com straight into the system installer. New permissions: INTERNET and REQUEST_INSTALL_PACKAGES. Audio and text are never sent. Unit checks: UpdateCheck (16). The real install is a phone check.
- Updating in place: every CI build has versionCode 100 + the build number (release v0.1.N has versionCode 100 + N) and the same signing key, so a new build installs over the old one without uninstalling; the build log prints both (VERSION line and the certificate SHA-256).
- Diagnostics screen (진단) and one-tap sharing of the day's text: 공유 (share sheet) and 카톡으로 (straight to KakaoTalk, share sheet if it is missing). 지우기 only clears the screen; the day's clipboard entry stays.
- KakaoTalk share: tests ShareRoute (7) in the Build APK run of this release; the real Intent and KakaoTalk opening are phone checks.

Proof by run id
- Item 1, recognition without a phone: Emulator feed QA run 43707801543, job "feed en online" passed: the app's sound-in to text-out path produced "hello everyone let us start the meeting the first item is the schedule". This used a CI-only copy of the app with the online recognizer; the shipped app is on-device only. The on-device engine could not be tested: the emulator has no speech model (job "feed en ondevice" failed, no download dialog in 5 minutes). Korean: job "feed ko online" failed (the Google app on the emulator image has no Korean recognition, error 12). Run 43709442230 also shows clips=1 after 5 sentences (one clipboard write per session) and the on-device failure reason (language pack not downloaded, error 13).
- Item 2, calls and 3 hour restarts: unit tests MeetingPause (21) and the simulated soak, both in run 43709558197.
- Item 4, one-minute check: on the app's first screen and on the download page.

Not verified on a phone: dictation itself, screen-off recording, mixed-language accuracy, call pause. Open the diagnostics screen and send a screenshot if nothing is printed.
