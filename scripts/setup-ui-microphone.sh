#!/usr/bin/env bash
set -euo pipefail

if ! command -v pactl >/dev/null || ! command -v paplay >/dev/null || ! command -v pulseaudio >/dev/null; then
    sudo apt-get update
    sudo apt-get install -y pulseaudio pulseaudio-utils
fi

if ! pactl info >/dev/null 2>&1; then
    pulseaudio --start --exit-idle-time=-1
fi

test_input=glidelive_voice_test
test_output=glidelive_speaker_test
pactl load-module module-null-sink "sink_name=$test_input" rate=44100 channels=1
pactl load-module module-null-sink "sink_name=$test_output"
pactl set-default-source "$test_input.monitor"
pactl set-default-sink "$test_output"
pactl set-source-mute "$test_input.monitor" 0

{
    echo "GLIDELIVE_TEST_MIC_SINK=$test_input"
    echo "PULSE_SOURCE=$test_input.monitor"
    echo "PULSE_SINK=$test_output"
    echo "QEMU_AUDIO_DRV=pa"
    echo "QEMU_PA_SOURCE=$test_input.monitor"
    echo "QEMU_PA_SINK=$test_output"
} >> "$GITHUB_ENV"
