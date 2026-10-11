#!/usr/bin/env bash
# Mac の上で wave.app を組む（release で組み、Info.plist を添えて、Apple Development の証明書で署名する）。
# 署名がそろっていると、組み直してもキーチェーンの保存（ログイン情報）をそのまま読める。
set -euo pipefail
cd "$(dirname "$0")/.."
# WAVE_DEBUG=1 なら開発用の命令（debug-command.txt）を入れる
flags=()
[ "${WAVE_DEBUG:-}" = 1 ] && flags=(-Xswiftc -DWAVE_DEBUG)
swift build -c release "${flags[@]}" 2>&1 | grep -E "error|warning: unre|Compiling|Build" | tail -20
app=build/wave.app
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$(swift build -c release "${flags[@]}" --show-bin-path)/wave" "$app/Contents/MacOS/wave"
cp Resources/Info.plist "$app/Contents/Info.plist"
[ -f Resources/wave.icns ] && cp Resources/wave.icns "$app/Contents/Resources/"
# 署名は GUI のセッションで（scripts/sign.command）。ここでは仮に ad-hoc で署名しておく
codesign --force --entitlements Resources/wave.entitlements --sign - "$app"
echo "built $app"
