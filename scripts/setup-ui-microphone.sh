#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
sudo apt-get install -y pulseaudio pulseaudio-utils xvfb libxcb-cursor0 libxkbcommon-x11-0

# Android's headless emulator replaces this audio backend with stubs. Run the
# windowed emulator on a private display so the guest microphone can receive PCM.
export DISPLAY=:99
Xvfb "$DISPLAY" -nolisten tcp -screen 0 1920x1080x24 > "$RUNNER_TEMP/glidelive-xvfb.log" 2>&1 &
for _ in {1..50}; do
    if [[ -S /tmp/.X11-unix/X99 ]]; then break; fi
    sleep 0.1
done
test -S /tmp/.X11-unix/X99

# QEMU's PA backend needs to find the daemon's pid file and UNIX socket. A
# daemon started with the runner's implicit runtime directory can be invisible
# to the emulator even when pactl works.
export XDG_RUNTIME_DIR="$(mktemp -d "$RUNNER_TEMP/glidelive-audio.XXXXXX")"
export PULSE_RUNTIME_PATH="$XDG_RUNTIME_DIR/pulse"
pulseaudio --start --exit-idle-time=-1
test -s "$PULSE_RUNTIME_PATH/pid"
test -S "$PULSE_RUNTIME_PATH/native"
export PULSE_SERVER="unix:$PULSE_RUNTIME_PATH/native"
pactl info >/dev/null

test_input=glidelive_voice_test
test_output=glidelive_speaker_test
pactl load-module module-null-sink "sink_name=$test_input" rate=44100 channels=1
pactl load-module module-null-sink "sink_name=$test_output"
pactl set-default-source "$test_input.monitor"
pactl set-default-sink "$test_output"
pactl set-source-mute "$test_input.monitor" 0

{
    echo "DISPLAY=$DISPLAY"
    echo "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"
    echo "PULSE_RUNTIME_PATH=$PULSE_RUNTIME_PATH"
    echo "PULSE_SERVER=$PULSE_SERVER"
    echo "QEMU_PA_SERVER=$PULSE_SERVER"
    echo "GLIDELIVE_TEST_MIC_SINK=$test_input"
    echo "PULSE_SOURCE=$test_input.monitor"
    echo "PULSE_SINK=$test_output"
    echo "QEMU_AUDIO_DRV=pa"
    echo "QEMU_PA_SOURCE=$test_input.monitor"
    echo "QEMU_PA_SINK=$test_output"
} >> "$GITHUB_ENV"
