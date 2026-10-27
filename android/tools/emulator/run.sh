#!/usr/bin/env bash
# Runs inside the emulator job: installs the release APK, pushes the test scans, walks the app.
set -u
OUT="walk-out/api-$API"
mkdir -p "$OUT"
adb wait-for-device
adb install -r app-release.apk
adb push fixtures/Pictures/. /sdcard/Pictures/ >/dev/null
adb push fixtures/Download/. /sdcard/Download/ >/dev/null
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
python3 android/tools/emulator/walk.py "$OUT" || true
kill %1 2>/dev/null || true
cat "$OUT/report.md" >> "$GITHUB_STEP_SUMMARY"
