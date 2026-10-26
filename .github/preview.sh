#!/usr/bin/env bash
# Installs the APK on the emulator, walks the remote through the screens and saves screenshots,
# a short screen recording of the motion, and frame timings (old release vs this build).
set -x
mkdir -p shots
key() { adb shell input keyevent "$1"; sleep "${2:-1.2}"; }
shot() { adb exec-out screencap -p > "shots/$1.png"; }

# Frame timings for one fixed walk through the dock and the grid. The emulator draws with a
# software GPU, so compare the two builds with each other, not with a real TV.
bench() {
  adb shell am force-stop tv.glasshome
  adb shell am start -W -n tv.glasshome/.Home
  sleep 6
  adb shell dumpsys gfxinfo tv.glasshome reset >/dev/null
  for i in 1 2 3; do
    adb shell input keyevent DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_DOWN DPAD_DOWN
    sleep 1.5
    adb shell input keyevent DPAD_LEFT DPAD_LEFT DPAD_UP DPAD_UP DPAD_UP DPAD_LEFT DPAD_LEFT
    sleep 1.5
  done
  adb shell dumpsys gfxinfo tv.glasshome | sed -n '/Stats since/,/HISTOGRAM/p' > "shots/gfxinfo-$1.txt"
}

if [ -f apk-old/glass-home.apk ]; then
  adb install -r apk-old/glass-home.apk && bench old
fi
adb install -r apk/glass-home.apk
bench new

adb shell am force-stop tv.glasshome
adb shell am start -W -n tv.glasshome/.Home
sleep 6
shot 01-home
key DPAD_RIGHT; shot 02-dock-right
key DPAD_UP; shot 03-controls
key DPAD_DOWN; key DPAD_DOWN 1.5; shot 04-all-apps
key DPAD_RIGHT; key DPAD_DOWN 1.5; shot 05-all-apps-row2
key MENU 1.5; shot 06-app-menu
key BACK; key BACK 1.5; shot 07-back-home

# The glide, recorded: along the dock, down into the grid and back up.
adb shell screenrecord --time-limit 9 --bit-rate 8000000 /sdcard/motion.mp4 &
rec=$!
sleep 1
for k in DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_RIGHT DPAD_DOWN DPAD_LEFT DPAD_UP DPAD_UP DPAD_LEFT DPAD_LEFT; do
  key $k 0.6
done
wait $rec
adb pull /sdcard/motion.mp4 shots/motion.mp4

key DPAD_UP; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT; key DPAD_CENTER 1.5; shot 08-inputs
key BACK; key MENU 1.5; shot 09-edit
adb logcat -d | grep -iE "glasshome|AndroidRuntime|FATAL" | tail -80 > shots/logcat.txt
adb shell dumpsys meminfo tv.glasshome | head -40 > shots/meminfo.txt
true
