#!/usr/bin/env bash
# Roda os testes instrumentados e guarda logs, vídeos e fotos gerados para conferência.
set -u
OUT="$GITHUB_WORKSPACE/emu-out"
mkdir -p "$OUT"
adb logcat -c || true
adb shell getprop ro.build.version.release > "$OUT/android-version.txt" 2>&1 || true
./gradlew --no-daemon connectedDebugAndroidTest > "$OUT/connected.log" 2>&1
STATUS=$?
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1 || true
grep -E "GravadorOcrTest|RecordingService|OcrPipeline|AlertPlayer|MediaSaver|CameraX|Recorder|VideoCapture|AndroidRuntime|TestRunner" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt" || true
adb shell ls -la /sdcard/Movies/GravadorOCR /sdcard/Pictures/GravadorOCR /sdcard/Download/GravadorOCR > "$OUT/files.txt" 2>&1 || true
mkdir -p "$OUT/media"
adb pull /sdcard/Movies/GravadorOCR "$OUT/media/videos" > /dev/null 2>&1 || true
adb pull /sdcard/Pictures/GravadorOCR "$OUT/media/fotos" > /dev/null 2>&1 || true
adb pull /sdcard/Download/GravadorOCR "$OUT/media/docs" > /dev/null 2>&1 || true
echo "exit=$STATUS" > "$OUT/exit.txt"
exit $STATUS
