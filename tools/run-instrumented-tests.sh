#!/usr/bin/env sh
# Runs the instrumented suite on a physical device.
#
# On some Chinese OEM builds - vivo/OriginOS in particular - the app process is
# frozen by the vendor's own "fast_freezer" a few seconds after it starts, while
# AndroidJUnitRunner is still bringing up the test host activity. The run then
# hangs forever: every thread sleeps, the process uses no CPU, no activity ever
# appears and logcat stays empty, so it looks like a broken test rather than a
# frozen process. The evidence is one line in the events buffer:
#
#   am_app_frozen: [0,<uid>,com.imankoppai.mediaanvil.debug,from fast_freezer]
#
# Setting `cached_apps_freezer` to `disabled` does NOT help; fast_freezer is the
# vendor's own mechanism and ignores that AOSP setting.
#
# Launching the test host activity first puts the process in the foreground, and
# a foreground process is not frozen. That is all this script does before
# starting the run.
#
# Usage:
#   tools/run-instrumented-tests.sh                 # whole suite
#   tools/run-instrumented-tests.sh <class>[#method]
set -eu

PKG="${PKG:-com.imankoppai.mediaanvil.debug}"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
HOST_ACTIVITY="androidx.activity.ComponentActivity"
SERIAL="${SERIAL:-}"
OUT="${OUT:-/sdcard/_instrumented.txt}"

if [ -n "$SERIAL" ]; then
    adb() { command adb -s "$SERIAL" "$@"; }
else
    adb() { command adb "$@"; }
fi

# Wake and unlock: an install or an activity launch is refused while the keyguard
# is up, and the screen sleeping mid-run produces confusing failures.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell input swipe 720 2400 720 900 300 >/dev/null 2>&1 || true

# Bring the test process up in the foreground so the vendor freezer leaves it be.
adb shell "am force-stop $PKG" >/dev/null 2>&1 || true
sleep 2
adb shell "am start -n $PKG/$HOST_ACTIVITY" >/dev/null 2>&1 || true
sleep 4

CLASS_FILTER=""
if [ "$#" -gt 0 ]; then
    CLASS_FILTER="-e class $1"
fi

adb shell "rm -f $OUT" >/dev/null 2>&1 || true
adb shell "am instrument -w -r $CLASS_FILTER $RUNNER > $OUT 2>&1"

adb shell "cat $OUT" | grep -E 'OK \(|Tests run|FAILURES|INSTRUMENTATION_CODE|main-thread' || true
