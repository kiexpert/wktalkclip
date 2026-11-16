#!/bin/bash
# Plain-Java checks for logic that does not need Android.
set -e
W="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$W/out/test"; mkdir -p "$W/out/test"
javac -encoding UTF-8 -d "$W/out/test" "$W/src/app/wktalkclip/LangChoice.java" "$W/src/app/wktalkclip/Transcript.java" "$W/src/app/wktalkclip/Backoff.java" "$W/src/app/wktalkclip/ErrorNames.java" "$W/src/app/wktalkclip/EventLog.java" "$W/test/DiagTest.java" "$W/src/app/wktalkclip/MeetingPause.java" "$W/test/MeetingPauseTest.java" "$W/test/RestartSoakTest.java" "$W/src/app/wktalkclip/DayClip.java" "$W/test/DayClipTest.java" "$W/src/app/wktalkclip/ShareRoute.java" "$W/test/ShareRouteTest.java" "$W/src/app/wktalkclip/UpdateCheck.java" "$W/test/UpdateCheckTest.java" "$W/src/app/wktalkclip/StartPlan.java" "$W/test/StartPlanTest.java" "$W/test/LangChoiceTest.java" "$W/test/BackoffTest.java" "$W/test/TranscriptSoakTest.java"
java -cp "$W/out/test" LangChoiceTest
java -cp "$W/out/test" StartPlanTest
java -cp "$W/out/test" UpdateCheckTest
java -cp "$W/out/test" ShareRouteTest
java -cp "$W/out/test" DayClipTest
java -cp "$W/out/test" MeetingPauseTest
java -cp "$W/out/test" RestartSoakTest
java -cp "$W/out/test" DiagTest
java -cp "$W/out/test" BackoffTest
java -cp "$W/out/test" TranscriptSoakTest
