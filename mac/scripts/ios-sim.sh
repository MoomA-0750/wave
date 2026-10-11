#!/usr/bin/env bash
# iPhone のシミュレーター（名前 wave-iphone）を用意して、wave.app を入れて起動する。
set -euo pipefail
name=wave-iphone
if ! xcrun simctl list devices | grep -q "$name ("; then
  type=$(xcrun simctl list devicetypes | grep -E "^iPhone [0-9]+ Pro \(" | tail -1 | sed -E 's/.*\((com[^)]*)\).*/\1/')
  runtime=$(xcrun simctl list runtimes | grep -E "^iOS" | tail -1 | sed -E 's/.* - (com[^ ]*).*/\1/')
  xcrun simctl create "$name" "$type" "$runtime" >/dev/null
fi
xcrun simctl boot "$name" 2>/dev/null || true
open -a Simulator
case "${1:-}" in
install)
  xcrun simctl terminate "$name" xyz.mooma0750.wave 2>/dev/null || true
  xcrun simctl install "$name" "$2"
  xcrun simctl launch "$name" xyz.mooma0750.wave
  ;;
esac
