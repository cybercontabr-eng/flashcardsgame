#!/usr/bin/env bash
# Publica os resultados do job num branch "ci-out/<nome>" (força um commit único, sem histórico).
set -u
NAME="$1"
OUT="$RUNNER_TEMP/ci-out"
rm -rf "$OUT"; mkdir -p "$OUT"
cd "$GITHUB_WORKSPACE"
echo "$GITHUB_SHA" > "$OUT/sha.txt"
echo "${JOB_STATUS:-unknown}" > "$OUT/status.txt"
date -u +%FT%TZ > "$OUT/date.txt"
for f in build.log apksigner.txt badging.txt apks.txt; do [ -f "$f" ] && cp "$f" "$OUT/"; done
if [ "$NAME" = "build" ]; then
  mkdir -p "$OUT/apk"
  cp app/build/outputs/apk/release/*.apk "$OUT/apk/" 2>/dev/null || true
  cp app/build/reports/lint-results-debug.txt "$OUT/" 2>/dev/null || true
  # ferramentas oficiais de assinatura (para assinar o APK fora do CI)
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
  if [ -n "$BT" ]; then
    mkdir -p "$OUT/tools/lib64"
    cp "$BT/lib/apksigner.jar" "$BT/zipalign" "$OUT/tools/" 2>/dev/null || true
    cp "$BT"/lib64/libc++.so* "$OUT/tools/lib64/" 2>/dev/null || true
    basename "$BT" > "$OUT/tools/build-tools-version.txt"
  fi
fi
mkdir -p "$OUT/test-results"
cp -r app/build/test-results "$OUT/test-results/unit" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results "$OUT/test-results/android" 2>/dev/null || true
[ -d emu-out ] && cp -r emu-out "$OUT/emu"
cd "$OUT"
git init -q -b out
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
git commit -q -m "CI $NAME $GITHUB_SHA ($JOB_STATUS)"
git push -q -f "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "out:refs/heads/ci-out/$NAME"
echo "Publicado em ci-out/$NAME"
