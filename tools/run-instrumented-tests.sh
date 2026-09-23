#!/usr/bin/env sh
# Runs the instrumented suite on a physical device.
#
# On some Chinese OEM builds - vivo/OriginOS in particular - the app process is
# suspended by the vendor's own "fast_freezer" about five seconds after it starts,
# before AndroidJUnitRunner has managed to launch the test host activity. The run then
# hangs forever: every thread sleeps, the process uses no CPU, no activity ever
# appears and logcat stays empty, so it reads as a broken test rather than a frozen
# process. The evidence is one line in the events buffer:
#
#   am_app_frozen: [0,<uid>,com.imankoppai.mediaanvil.debug,from fast_freezer]
#
# Things that do NOT work (all tried on the device):
#   * settings put global cached_apps_freezer disabled - that is the AOSP mechanism,
#     fast_freezer is the vendor's own and ignores it
#   * am set-standby-bucket <pkg> active, appops RUN_IN_BACKGROUND allow
#   * starting a foreground service - this app's service only becomes foreground
#     while it is playing, so the process stays a freeze candidate
#   * launching the host activity *before* the run - `am instrument` kills and
#     restarts the process, so the warmed activity is gone by the time it matters
#
# What works is repeating the launch *during* the startup window:
#
#   am start -f 0x20000000 -n <pkg>/androidx.activity.ComponentActivity
#
# FLAG_ACTIVITY_SINGLE_TOP matters. Without it every poke stacks another
# ComponentActivity and the test that uses StateRestorationTester fails with
# "No compose hierarchies found in the app". With it, one instance is reused - the
# same one the test framework ends up driving. Verified 4 runs in a row.
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
# How long to keep poking. The suite needs cover for roughly its first ten seconds;
# after that the tests are running well inside the freeze timeout.
POKES="${POKES:-12}"
# How long to wait for INSTRUMENTATION_CODE before giving up.
WAIT_SECONDS="${WAIT_SECONDS:-300}"

if [ -n "$SERIAL" ]; then
    adb() { command adb -s "$SERIAL" "$@"; }
else
    adb() { command adb "$@"; }
fi

# Wake and unlock first: while the screen is asleep the host activity never receives a
# window (the log shows "Focus leaving ... reason=NO_WINDOW"), so the process is
# backgrounded and frozen regardless of the pokes.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell input swipe 720 2400 720 900 300 >/dev/null 2>&1 || true
sleep 2

CLASS_FILTER=""
if [ "$#" -gt 0 ]; then
    CLASS_FILTER="-e class $1"
fi

adb shell "rm -f $OUT" >/dev/null 2>&1 || true
adb shell "am force-stop $PKG" >/dev/null 2>&1 || true
sleep 2

# Start the run detached, so the pokes below can happen while it is still starting up.
adb shell "nohup sh -c 'am instrument -w -r $CLASS_FILTER $RUNNER > $OUT 2>&1' >/dev/null 2>&1 &" \
    >/dev/null 2>&1 || true

i=0
while [ "$i" -lt "$POKES" ]; do
    sleep 1
    adb shell "am start -f 0x20000000 -n $PKG/$HOST_ACTIVITY" >/dev/null 2>&1 || true
    i=$((i + 1))
done

# The runner writes INSTRUMENTATION_CODE as its last line.
waited=0
while [ "$waited" -lt "$WAIT_SECONDS" ]; do
    if adb shell "grep -q INSTRUMENTATION_CODE $OUT" >/dev/null 2>&1; then
        break
    fi
    sleep 2
    waited=$((waited + 2))
done

adb shell "cat $OUT" | grep -E 'OK \(|Tests run|FAILURES|INSTRUMENTATION_CODE|main-thread|No compose' || true
