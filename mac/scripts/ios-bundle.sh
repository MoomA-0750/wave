#!/usr/bin/env bash
# Mac の上で iPhone 版の wave.app を組む。
#   ios-bundle.sh          シミュレーター向け（ad-hoc の署名で動く）
#   ios-bundle.sh device   実機向け。署名していない build/ios-device/wave.ipa を作る（Sideloadly などで本人の Apple ID で署名して入れる）
set -euo pipefail
cd "$(dirname "$0")/.."
flags=()
[ "${WAVE_DEBUG:-}" = 1 ] && flags=(-Xswiftc -DWAVE_DEBUG)
if [ "${1:-}" = device ]; then
    sdk=$(xcrun --sdk iphoneos --show-sdk-path)
    # 組んだ SDK の版を書き込む（swift build は最低の版を書いてしまい、iOS 26 以降の見た目 Liquid Glass にならない）
    ver=$(xcrun --sdk iphoneos --show-sdk-version)
    args=(--product wave --triple arm64-apple-ios18.0 --sdk "$sdk" -Xlinker -platform_version -Xlinker ios -Xlinker 18.0 -Xlinker "$ver" --scratch-path .build-ios-device -c release ${flags[@]+"${flags[@]}"})
    out=build/ios-device
    platform=iPhoneOS; dt=iphoneos
else
    sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
    ver=$(xcrun --sdk iphonesimulator --show-sdk-version)
    args=(--product wave --triple arm64-apple-ios18.0-simulator --sdk "$sdk" -Xlinker -platform_version -Xlinker ios-simulator -Xlinker 18.0 -Xlinker "$ver" --scratch-path .build-ios ${flags[@]+"${flags[@]}"})
    out=build/ios
    platform=iPhoneSimulator; dt=iphonesimulator
fi
swift build "${args[@]}" 2>&1 | grep -E "error|warning: unre|Build" | tail -20
app=$out/wave.app
rm -rf "$app"
mkdir -p "$app"
cp "$(swift build "${args[@]}" --show-bin-path)/wave" "$app/wave"
cp Resources/iOS/Info.plist "$app/Info.plist"
plutil -replace CFBundleSupportedPlatforms -json "[\"$platform\"]" "$app/Info.plist"
plutil -replace DTPlatformName -string "$dt" "$app/Info.plist"
if [ "${1:-}" = device ]; then
    # 署名は入れる側（Sideloadly・AltStore など）が本人の Apple ID で行う
    rm -rf "$out/Payload" "$out/wave.ipa"
    mkdir -p "$out/Payload"
    cp -R "$app" "$out/Payload/"
    (cd "$out" && zip -qry wave.ipa Payload && rm -rf Payload)
    echo "built $out/wave.ipa"
else
    codesign --force --sign - "$app"
    echo "built $app"
fi
