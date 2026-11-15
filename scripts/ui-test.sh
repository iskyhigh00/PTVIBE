#!/usr/bin/env bash
set -u
r=0
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 || r=$?
mkdir -p ui-shots
adb pull /sdcard/Android/data/com.glidelive/files/. ui-shots/ || true
exit $r
