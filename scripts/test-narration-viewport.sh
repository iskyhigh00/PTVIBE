#!/usr/bin/env bash
set -u
viewport_result=0
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 || viewport_result=$?
mkdir -p viewport-screenshots
viewport_output=app/build/outputs/connected_android_test_additional_output
if [ -d "$viewport_output" ]; then
    find "$viewport_output" -name '*.png' -exec cp '{}' viewport-screenshots/ \;
fi
# AGP collects these files before uninstalling the tested app.
if ! compgen -G 'viewport-screenshots/*.png' > /dev/null; then
    if [ "$viewport_result" -eq 0 ]; then viewport_result=1; fi
fi
exit "$viewport_result"
