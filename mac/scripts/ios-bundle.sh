#!/usr/bin/env bash
# Mac の上で iPhone 版（シミュレーター向け）の wave.app を組む。シミュレーターは ad-hoc の署名で動く。
set -euo pipefail
cd "$(dirname "$0")/.."
flags=()
[ "${WAVE_DEBUG:-}" = 1 ] && flags=(-Xswiftc -DWAVE_DEBUG)
sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
args=(--product wave --triple arm64-apple-ios18.0-simulator --sdk "$sdk" --scratch-path .build-ios "${flags[@]}")
swift build "${args[@]}" 2>&1 | grep -E "error|warning: unre|Build" | tail -20
app=build/ios/wave.app
rm -rf "$app"
mkdir -p "$app"
cp "$(swift build "${args[@]}" --show-bin-path)/wave" "$app/wave"
cp Resources/iOS/Info.plist "$app/Info.plist"
codesign --force --sign - "$app"
echo "built $app"
