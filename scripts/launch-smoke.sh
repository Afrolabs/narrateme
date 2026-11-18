#!/usr/bin/env bash
# Installs the APK, launches MainActivity, and fails if the app crashes or dies within 20 s.
set -u
APK="$1"
PKG=com.tapeaids.narrateme
adb install -r -g "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 20
adb logcat -d > logcat.txt
echo "===== crash log ====="
grep -A 60 -E "FATAL EXCEPTION|AndroidRuntime" logcat.txt | head -150
echo "===== app log ====="
grep -E "narrateme|GlassesSession|MainActivity|NarrationRecorder|mwdat|Wearables" logcat.txt | head -80
if grep -q "MetaWearablesConfig: Failed to read metadata" logcat.txt; then
  echo "Meta toolkit could not read its manifest metadata"
  exit 1
fi
if grep -q "FATAL EXCEPTION" logcat.txt || [ -z "$(adb shell pidof $PKG)" ]; then
  echo "App crashed or is not running"
  exit 1
fi
echo "App is still running after 20 s"
