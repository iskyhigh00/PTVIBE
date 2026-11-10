#!/usr/bin/env bash
set -u
viewport_result=0
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 || viewport_result=$?
mkdir -p viewport-screenshots
if ! adb pull /sdcard/Android/data/com.glidelive/files/. viewport-screenshots/; then
    if [ "$viewport_result" -eq 0 ]; then viewport_result=1; fi
fi
exit "$viewport_result"
