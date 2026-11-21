#!/usr/bin/env bash
set -u
mkdir -p ui-shots
( while true; do adb pull /sdcard/Android/data/com.glidelive/files/shots ui-shots/ >/dev/null 2>&1; sleep 1; done ) &
puller=$!
mic_feeder=
cleanup() {
    kill "$puller" 2>/dev/null || true
    if [[ -n "$mic_feeder" ]]; then kill "$mic_feeder" 2>/dev/null || true; fi
}
trap cleanup EXIT

runner_args=()
if [[ -n "${GLIDELIVE_TEST_MIC_SINK:-}" ]]; then
    # Solo en CI. Las muestras sintéticas entran por el micrófono virtual de Android.
    # El altavoz usa otro sink para que la reproducción no entre en la grabación.
    adb emu avd hostmicon || exit 1
    (
        shots=/sdcard/Android/data/com.glidelive/files/shots
        last=
        while true; do
            request=$(adb exec-out cat "$shots/mic-request" 2>/dev/null) || request=
            read -r voice token <<< "$request"
            if [[ ( "$voice" == narrador || "$voice" == rana ) && "$token" =~ ^[0-9]+$ && "$token" != "$last" ]]; then
                last=$token
                if paplay --device="$GLIDELIVE_TEST_MIC_SINK" "scripts/fixtures/story-voices/$voice.wav"; then
                    adb shell "printf '%s' '$token' > '$shots/mic-complete'"
                fi
            fi
            sleep 0.15
        done
    ) &
    mic_feeder=$!
    runner_args+=("-Pandroid.testInstrumentationRunnerArguments.storyMicInjected=true")
fi
r=0
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "${runner_args[@]}" || r=$?
exit $r
