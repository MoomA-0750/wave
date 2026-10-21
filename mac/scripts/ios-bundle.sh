#!/usr/bin/env bash
# Mac の上で iPhone 版の wave.app を組む。
#   ios-bundle.sh          シミュレーター向け（ad-hoc の署名で動く）
#   ios-bundle.sh device   実機向け。build/ios-device/wave.ipa を作る（Sideloadly などで本人の Apple ID で署名し直して入れる）
# ウィジェット（PlugIns/WaveWidget.appex）も入れる。App Intents（ウィジェットのボタン・プレイリストの選び）の
# 一覧（Metadata.appintents）は Xcode が作るものなので、ここで appintentsmetadataprocessor を直に呼んで作る。
set -euo pipefail
cd "$(dirname "$0")/.."
flags=()
[ "${WAVE_DEBUG:-}" = 1 ] && flags=(-Xswiftc -DWAVE_DEBUG)
# App Intents の一覧の元（型ごとの定数）をコンパイラに書き出させる
flags+=(-Xswiftc -emit-const-values -Xswiftc -Xfrontend -Xswiftc -const-gather-protocols-file -Xswiftc -Xfrontend -Xswiftc "$PWD/Resources/iOS/const-protocols.json")
if [ "${1:-}" = device ]; then
    sdkname=iphoneos; triple=arm64-apple-ios18.0; scratch=.build-ios-device; config=(-c release); confdir=Release-iphoneos
    out=build/ios-device; platform=iPhoneOS; ldplatform=ios
else
    sdkname=iphonesimulator; triple=arm64-apple-ios18.0-simulator; scratch=.build-ios; config=(); confdir=Debug-iphonesimulator
    out=build/ios; platform=iPhoneSimulator; ldplatform=ios-simulator
fi
sdk=$(xcrun --sdk $sdkname --show-sdk-path)
# 組んだ SDK の版を書き込む（swift build は最低の版を書いてしまい、iOS 26 以降の見た目 Liquid Glass にならない）
ver=$(xcrun --sdk $sdkname --show-sdk-version)
args=(--triple $triple --sdk "$sdk" --scratch-path $scratch ${config[@]+"${config[@]}"} -Xlinker -platform_version -Xlinker $ldplatform -Xlinker 18.0 -Xlinker "$ver" "${flags[@]}")
swift build --product wave "${args[@]}" 2>&1 | grep -E "error|warning: unre|Build" | tail -20
# ウィジェットは Xcode と同じく入口を _NSExtensionMain にする（普通の main のままだと、実機で起動してすぐ落ち、ウィジェットの一覧に出ない）
swift build --product WaveWidget "${args[@]}" -Xswiftc -application-extension -Xlinker -e -Xlinker _NSExtensionMain 2>&1 | grep -E "error|warning: unre|Build" | tail -20
bin=$(swift build --product wave "${args[@]}" --show-bin-path)

# App Intents の一覧を作る（$1 = 製品の名前、$2 = モジュール、$3 = 入れる先のバンドル、$4 = 実行ファイル）
intents() {
    local base=$1 module=$2 dest=$3 binary=$4 list cv sl
    cv=$(find $scratch -path "*$confdir*" -name "$base-primary.swiftconstvalues" | head -1)
    sl=$(find $scratch -path "*$confdir*" -name "$base.SwiftFileList" | head -1)
    [ -n "$sl" ] || { echo "no source list for $module" >&2; return 1; }
    # 全体を1つに組む（release）と1つのファイル、ファイルごとに組む（debug）とソースのファイルごとに書き出される
    list=$(mktemp)
    if [ -n "$cv" ]; then echo "$PWD/$cv" > "$list"; else find "$PWD/$(dirname "$sl")" -name "*.swiftconstvalues" > "$list"; fi
    [ -s "$list" ] || { rm -f "$list"; echo "no const values for $module" >&2; return 1; }
    xcrun appintentsmetadataprocessor --output "$dest" --toolchain-dir "$(xcode-select -p)/Toolchains/XcodeDefault.xctoolchain" \
        --module-name $module --sdk-root "$sdk" --xcode-version "$(xcodebuild -version | awk '/Build version/ {print $3}')" \
        --platform-family iOS --deployment-target 18.0 --target-triple $triple --source-file-list "$sl" \
        --swift-const-vals-list "$list" --binary-file "$binary" --force --quiet-warnings 2>&1 | grep -iE "error" || true
    rm -f "$list"
    [ -d "$dest/Metadata.appintents" ] || { echo "no Metadata.appintents for $module" >&2; return 1; }
}

setplist() {
    plutil -replace CFBundleSupportedPlatforms -json "[\"$platform\"]" "$1"
    plutil -replace DTPlatformName -string "$sdkname" "$1"
}

app=$out/wave.app
rm -rf "$app"
mkdir -p "$app/PlugIns/WaveWidget.appex"
cp "$bin/wave" "$app/wave"
cp Resources/iOS/Info.plist "$app/Info.plist"
# 「Claude に聞く」のシステムプロンプトと道具の定義（Android・Windows と同じファイル。bundle.sh と同じ）
cp ../ai/agent.json "$app/agent.json"
# アイコン（Windows 版の wave.ico と同じ絵を白地に置いたもの。iPhone のアイコンは透明にできない）。
# 今の iOS はばらの PNG を読まないので、Xcode と同じく actool で Assets.car にし、Info.plist にアイコンの名前を足す
partial=$(mktemp)
xcrun actool Resources/iOS/Assets.xcassets --compile "$app" --platform $sdkname --minimum-deployment-target 18.0 \
    --app-icon AppIcon --target-device iphone --output-partial-info-plist "$partial" --output-format human-readable-text 2>&1 | grep -iE "error" || true
plutil -replace CFBundleIcons -json "$(plutil -extract CFBundleIcons json -o - "$partial")" "$app/Info.plist"
rm -f "$partial"
setplist "$app/Info.plist"
# 組むたびに版の番号を上げる（同じ番号のまま入れ直すと、iPhone がアイコンなどを前の版のまま使い続ける）
plutil -replace CFBundleVersion -string "$(date +%Y%m%d%H%M)" "$app/Info.plist"
# 連携の中継の宛先を書き込む（mac.sh がこの機械の local.properties から渡す。無ければ書かない）
if [ -n "${WAVE_RELAY_V2_URL:-}" ] && [ -n "${WAVE_RELAY_V2_HOST:-}" ]; then
    plutil -replace WaveRelayV2Url -string "$WAVE_RELAY_V2_URL" "$app/Info.plist"
    plutil -replace WaveRelayV2Host -string "$WAVE_RELAY_V2_HOST" "$app/Info.plist"
fi
appex=$app/PlugIns/WaveWidget.appex
cp "$bin/WaveWidget" "$appex/WaveWidget"
cp Resources/iOS/Widget/Info.plist "$appex/Info.plist"
setplist "$appex/Info.plist"
plutil -replace CFBundleVersion -string "$(plutil -extract CFBundleVersion raw "$app/Info.plist")" "$appex/Info.plist"
intents wave Wave "$app" "$app/wave"
intents WaveWidget WaveWidget "$appex" "$appex/WaveWidget"
# ボタン・プレイリストの選びの名前の英語（Mac と同じファイル。bundle.sh と同じ）
cp -R Resources/Widget/en.lproj Resources/Widget/ja.lproj "$appex/"
cp -R Resources/Widget/en.lproj Resources/Widget/ja.lproj "$app/"

# ウィジェットとアプリグループを分け合うため、権限（entitlements）を書いた署名にしておく。
# 実機ではこれを Sideloadly などが本人の Apple ID で署名し直す（権限は引き継がれる）
codesign --force --sign - --entitlements Resources/iOS/wave.entitlements "$appex"
codesign --force --sign - --entitlements Resources/iOS/wave.entitlements "$app"
if [ "${1:-}" = device ]; then
    rm -rf "$out/Payload" "$out/wave.ipa"
    mkdir -p "$out/Payload"
    cp -R "$app" "$out/Payload/"
    (cd "$out" && zip -qry wave.ipa Payload && rm -rf Payload)
    echo "built $out/wave.ipa"
else
    echo "built $app"
fi
