#!/bin/bash
# Gradle-free build of the throwaway mic probe APK (out/micprobe.apk). Runnable from any cwd.
set -e
W="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$W"
B="${ANDROID_SDK:-$W/sdk}/build-tools/34.0.0"
P="${ANDROID_SDK:-$W/sdk}/platforms/android-34/android.jar"
SRC="$W/qa/probe/src"

rm -rf out/probe_classes out/probe_dex gen_probe
mkdir -p out out/probe_classes out/probe_dex gen_probe
rm -f out/probe_unsigned.apk out/probe_aligned.apk out/micprobe.apk

# Link manifest (no resources to compile)
"$B/aapt2" link -I "$P" --manifest qa/probe/AndroidManifest.xml -o out/probe_unsigned.apk \
    --java gen_probe --min-sdk-version 31 --target-sdk-version 34

# Compile Java (R.java only if generated)
JAVA_FILES=$(find "$SRC/app/micprobe" -name '*.java')
if [ -f gen_probe/app/micprobe/R.java ]; then
    JAVA_FILES="gen_probe/app/micprobe/R.java $JAVA_FILES"
fi
javac --release 11 -classpath "$P" -d out/probe_classes $JAVA_FILES

# Dex and add to the APK
"$B/d8" --min-api 31 --lib "$P" --output out/probe_dex $(find out/probe_classes -name '*.class')
(cd out/probe_dex && zip -u ../probe_unsigned.apk classes.dex)

# Align
"$B/zipalign" -f 4 out/probe_unsigned.apk out/probe_aligned.apk

# Throwaway keystore (created once, never committed)
if [ ! -f out/probe.jks ]; then
    keytool -genkeypair -keystore out/probe.jks -storepass android -keypass android \
        -alias probe -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=micprobe"
fi

# Sign
"$B/apksigner" sign --ks out/probe.jks --ks-pass pass:android --key-pass pass:android \
    --out out/micprobe.apk out/probe_aligned.apk

echo "BUILD OK"
