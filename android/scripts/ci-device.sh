#!/usr/bin/env bash
set -euo pipefail

export ANDROID_AVD_HOME="$RUNNER_TEMP/castor-avds"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --name castor-ci --path "$ANDROID_AVD_HOME/castor-ci.avd" --package 'system-images;android-37.2;google_apis_ps16k;x86_64' --device pixel_6
config="$ANDROID_AVD_HOME/castor-ci.avd/config.ini"
sed -i '/^disk\.dataPartition\.size=/d' "$config"
printf '\ndisk.dataPartition.size=8G\n' >> "$config"
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fx castor-ci
mkdir -p android/build
log="$PWD/android/build/emulator.log"
export ANDROID_SERIAL=emulator-5554
"$ANDROID_HOME/emulator/emulator" -avd castor-ci -port 5554 -memory 4096 -no-window -no-audio -no-boot-anim -no-snapshot -gpu software > "$log" 2>&1 &
emulator_pid=$!
logcat_pid=
cleanup() {
    local result=$?
    if [ -n "$logcat_pid" ]; then kill "$logcat_pid" 2>/dev/null || true; fi
    if [ "$result" -ne 0 ]; then
        "$ANDROID_HOME/platform-tools/adb" shell cat /proc/meminfo || true
        free -h
        tail -n 200 android/build/device-logcat.txt 2>/dev/null || true
    fi
    if [ "$result" -ne 0 ]; then cat "$log"; fi
    "$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" emu kill || true
    if kill -0 "$emulator_pid" 2>/dev/null; then kill "$emulator_pid"; fi
}
trap cleanup EXIT
timeout 90s "$ANDROID_HOME/platform-tools/adb" wait-for-device
"$ANDROID_HOME/platform-tools/adb" logcat -b all -v threadtime > android/build/device-logcat.txt 2>&1 &
logcat_pid=$!
for attempt in $(seq 1 120); do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo 'Android emulator exited before boot completion' >&2
        exit 1
    fi
    if [ "$("$ANDROID_HOME/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
    sleep 3
done
test "$("$ANDROID_HOME/platform-tools/adb" shell getprop sys.boot_completed | tr -d '\r')" = 1
page_size="$("$ANDROID_HOME/platform-tools/adb" shell getconf PAGE_SIZE | tr -d '\r')"
echo "Android emulator page size: $page_size"
test "$page_size" = 16384
"$ANDROID_HOME/platform-tools/adb" shell df -h /data
"$ANDROID_HOME/platform-tools/adb" shell cat /proc/meminfo | grep -E 'MemTotal|MemAvailable'
(cd android && bash gradlew :app:connectedDebugAndroidTest --console=plain)
python3 android/scripts/check-device-results.py
