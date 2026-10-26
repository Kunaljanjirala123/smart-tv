#!/usr/bin/env bash
# Installs the APK on the emulator, measures frame timings against the previous release, then
# walks the remote through every screen and saves screenshots and a recording of the motion.
set -x
mkdir -p shots
key() { adb shell input keyevent "$1"; sleep "${2:-1.2}"; }
shot() { adb exec-out screencap -p > "shots/$1.png"; }
home() { adb shell am start -W -n tv.glasshome/.Home "$@"; }

# Frame timings for one fixed walk through the dock and the grid. The emulator draws with a
# software GPU, so compare the two builds with each other, not with a real TV.
bench() {
  adb shell am force-stop tv.glasshome
  home --ez skip_setup true
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
adb shell appops set tv.glasshome GET_USAGE_STATS allow
bench new

# A fresh start: first-run setup.
adb shell am force-stop tv.glasshome
adb shell pm clear tv.glasshome
adb shell appops set tv.glasshome GET_USAGE_STATS allow
home
sleep 6
shot 01-setup-welcome
key DPAD_CENTER 1.5; shot 02-setup-look
key DPAD_CENTER 1.5; shot 03-setup-profile
key DPAD_RIGHT; key DPAD_CENTER; key DPAD_DOWN 1.5; shot 04-setup-name
key DPAD_CENTER 1.5; shot 05-setup-weather
key BACK 3

# The weather's city, as the installer sets it.
home --es city London
sleep 8
shot 06-home
key DPAD_RIGHT 1.5; shot 07-dock-2
key DPAD_RIGHT 1.5; shot 08-dock-3
key DPAD_DOWN 1.8; shot 09-apps
key MENU 1.5; shot 10-app-menu
key DPAD_DOWN 0.6; key DPAD_CENTER 1.5; shot 11-moving
key DPAD_RIGHT 1.5; shot 12-moved
key DPAD_CENTER 1.5
# Into a new folder.
key MENU 1.2; key DPAD_DOWN 0.5; key DPAD_DOWN 0.5; key DPAD_DOWN 0.5; key DPAD_CENTER 1.5; shot 13-folder-picker
key DPAD_CENTER 1.5
adb shell input text "Games"; sleep 0.8
key ENTER 2; key BACK 1; shot 14-folder-tile
key DPAD_CENTER 1.5; shot 15-folder-open
key BACK 1.5
key BACK 1.5
home --es open control; sleep 2.5; shot 16-control-center
key DPAD_DOWN 0.8; key DPAD_DOWN 0.8; key DPAD_DOWN 0.8; key DPAD_DOWN 0.8; key DPAD_DOWN 1; shot 17-control-recents
key BACK 1.5
home --es open weather; sleep 2.5; shot 18-weather
key BACK 1.5
home --es open settings; sleep 2.5; shot 19-settings
key BACK 1.5

# The other looks.
home --es theme dark; sleep 6; shot 20-home-dark
key DPAD_RIGHT 1.5; shot 21-dark-dock-2
home --es open control; sleep 2.5; shot 22-dark-control-center
key BACK 1.5
home --es theme aurora; sleep 6; shot 23-home-aurora
home --es theme light; sleep 6

home --es open standby; sleep 3; shot 24-standby
key BACK 2

# The glide, recorded: along the dock, down into the apps and back up.
adb shell screenrecord --time-limit 10 --bit-rate 8000000 /sdcard/motion.mp4 &
rec=$!
sleep 1
for k in DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_DOWN DPAD_RIGHT DPAD_DOWN DPAD_UP DPAD_UP DPAD_LEFT; do
  key $k 0.7
done
wait $rec
adb pull /sdcard/motion.mp4 shots/motion.mp4

adb logcat -d | grep -iE "glasshome|AndroidRuntime|FATAL" | tail -120 > shots/logcat.txt
adb shell dumpsys meminfo tv.glasshome | head -40 > shots/meminfo.txt
true
