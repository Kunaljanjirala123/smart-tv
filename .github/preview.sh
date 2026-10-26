#!/usr/bin/env bash
# Installs the APK on the emulator, walks the remote through the screens and saves screenshots.
set -x
mkdir -p shots
adb install -r apk/glass-home.apk
adb shell am start -W -n tv.glasshome/.Home
sleep 6
shot() { adb exec-out screencap -p > "shots/$1.png"; }
key() { adb shell input keyevent "$1"; sleep "${2:-1.2}"; }
shot 01-home
key DPAD_RIGHT; shot 02-dock-right
key DPAD_UP; shot 03-controls
key DPAD_DOWN; key DPAD_DOWN 1.5; shot 04-all-apps
key DPAD_RIGHT; key DPAD_DOWN 1.5; shot 05-all-apps-row2
key MENU 1.5; shot 06-app-menu
key BACK; key BACK 1.5; shot 07-back-home
key DPAD_UP; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT; key DPAD_CENTER 1.5; shot 08-inputs
key BACK; key MENU 1.5; shot 09-edit
adb logcat -d | grep -iE "glasshome|AndroidRuntime|FATAL" | tail -80 > shots/logcat.txt
adb shell dumpsys meminfo tv.glasshome | head -40 > shots/meminfo.txt
true
