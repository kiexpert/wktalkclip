#!/bin/bash
# Gradle-free build of the meeting dictation APK, following the spec steps 1-7 in order.
set -e
W="$(cd "$(dirname "$0")" && pwd)"
cd "$W"
B="${ANDROID_SDK:-$W/sdk}/build-tools/34.0.0"
P="${ANDROID_SDK:-$W/sdk}/platforms/android-34/android.jar"
SRC="$W/src"

rm -rf out/classes out/dex gen
mkdir -p out out/classes out/dex gen
rm -f out/res.zip out/unsigned.apk out/aligned.apk out/wktalkclip.apk

# Step 1: compile resources
"$B/aapt2" compile --dir res -o out/res.zip

# Step 2: link manifest and resources, generate R.java
"$B/aapt2" link -I "$P" --manifest AndroidManifest.xml -o out/unsigned.apk \
    --java gen out/res.zip --min-sdk-version 31 --target-sdk-version 34

# Step 3: compile Java
javac --release 11 -classpath "$P" -d out/classes \
    gen/app/wktalkclip/R.java \
    $(find "$SRC/app/wktalkclip" -name '*.java')

# Step 4: dex and add to the APK
"$B/d8" --min-api 31 --lib "$P" --output out/dex $(find out/classes -name '*.class')
(cd out/dex && zip -u ../unsigned.apk classes.dex)

# Step 5: align
"$B/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# Step 6: keystore (created once)
if [ ! -f "$W/wktalkclip.jks" ]; then
    keytool -genkeypair -keystore "$W/wktalkclip.jks" -storepass android -keypass android \
        -alias wktalkclip -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=wktalkclip"
fi

# Step 7: sign
"$B/apksigner" sign --ks "$W/wktalkclip.jks" --ks-pass pass:android --key-pass pass:android \
    --out out/wktalkclip.apk out/aligned.apk

echo "BUILD OK"
