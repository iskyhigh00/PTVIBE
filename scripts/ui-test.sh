#!/usr/bin/env bash
set -u
mkdir -p ui-shots
( while true; do adb pull /sdcard/Android/data/com.glidelive/files/shots ui-shots/ >/dev/null 2>&1; sleep 1; done ) &
puller=$!
r=0
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 || r=$?
kill $puller 2>/dev/null
exit $r
