#!/bin/bash
# Plain-Java checks for logic that does not need Android.
set -e
W="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$W/out/test"; mkdir -p "$W/out/test"
javac -encoding UTF-8 -d "$W/out/test" "$W/src/app/wktalkclip/ScreenClip.java" "$W/src/app/wktalkclip/LangChoice.java" "$W/test/ScreenClipTest.java" "$W/test/LangChoiceTest.java"
java -cp "$W/out/test" ScreenClipTest
java -cp "$W/out/test" LangChoiceTest
