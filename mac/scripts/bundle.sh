#!/usr/bin/env bash
# Mac の上で wave.app を組む（release で組み、Info.plist を添えて、Apple Development の証明書で署名する）。
# 署名がそろっていると、組み直してもキーチェーンの保存（ログイン情報）をそのまま読める。
set -euo pipefail
cd "$(dirname "$0")/.."
# WAVE_DEBUG=1 なら開発用の命令（debug-command.txt）を入れる
flags=()
[ "${WAVE_DEBUG:-}" = 1 ] && flags=(-Xswiftc -DWAVE_DEBUG)
# ウィジェットのボタン・プレイリストの選び（App Intents）の一覧の元を、コンパイラに書き出させる（ios-bundle.sh と同じ）
# 組んだ SDK の版を書き込む（swift build は最低の版を書いてしまい、macOS 26 以降でも古い見た目になり、上の帯のボタンが Liquid Glass にならない。ios-bundle.sh と同じ）
flags+=(-Xlinker -platform_version -Xlinker macos -Xlinker 15.0 -Xlinker "$(xcrun --sdk macosx --show-sdk-version)")
flags+=(-Xswiftc -emit-const-values -Xswiftc -Xfrontend -Xswiftc -const-gather-protocols-file -Xswiftc -Xfrontend -Xswiftc "$PWD/Resources/iOS/const-protocols.json")
swift build -c release --product wave "${flags[@]}" 2>&1 | grep -E "error|warning: unre|Compiling|Build" | tail -20
# ウィジェットは Xcode と同じく入口を _NSExtensionMain にする（ios-bundle.sh と同じ。普通の main のままだと起動してすぐ落ち、ウィジェットの一覧に出ない）
swift build -c release --product WaveWidget "${flags[@]}" -Xlinker -e -Xlinker _NSExtensionMain 2>&1 | grep -E "error|warning: unre|Build" | tail -20
bin=$(swift build -c release "${flags[@]}" --show-bin-path)
app=build/wave.app
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$app/Contents/PlugIns/WaveWidget.appex/Contents/MacOS"
cp "$bin/wave" "$app/Contents/MacOS/wave"
appex=$app/Contents/PlugIns/WaveWidget.appex
cp "$bin/WaveWidget" "$appex/Contents/MacOS/WaveWidget"
cp Resources/Widget/Info.plist "$appex/Contents/Info.plist"

# App Intents の一覧（Metadata.appintents）を作る。Xcode が作るものなので、ここで直に呼ぶ
intents() {
    local base=$1 module=$2 dest=$3 binary=$4 list cv sl
    cv=$(find .build -path "*Release*" -name "$base-primary.swiftconstvalues" -not -path "*iphone*" | head -1)
    sl=$(find .build -path "*Release*" -name "$base.SwiftFileList" -not -path "*iphone*" | head -1)
    [ -n "$sl" ] || { echo "no source list for $module" >&2; return 1; }
    # 全体を1つに組む（release）と1つのファイル、ファイルごとに組む（debug）とソースのファイルごとに書き出される
    list=$(mktemp)
    if [ -n "$cv" ]; then echo "$PWD/$cv" > "$list"; else find "$PWD/$(dirname "$sl")" -name "*.swiftconstvalues" > "$list"; fi
    [ -s "$list" ] || { rm -f "$list"; echo "no const values for $module" >&2; return 1; }
    xcrun appintentsmetadataprocessor --output "$dest" --toolchain-dir "$(xcode-select -p)/Toolchains/XcodeDefault.xctoolchain" \
        --module-name $module --sdk-root "$(xcrun --sdk macosx --show-sdk-path)" --xcode-version "$(xcodebuild -version | awk '/Build version/ {print $3}')" \
        --platform-family macOS --deployment-target 15.0 --target-triple arm64-apple-macos15.0 --source-file-list "$sl" \
        --swift-const-vals-list "$list" --binary-file "$binary" --force --quiet-warnings 2>&1 | grep -iE "error" || true
    rm -f "$list"
    [ -d "$dest/Metadata.appintents" ] || { echo "no Metadata.appintents for $module" >&2; return 1; }
}
intents wave Wave "$app/Contents/Resources" "$app/Contents/MacOS/wave"
intents WaveWidget WaveWidget "$appex/Contents/Resources" "$appex/Contents/MacOS/WaveWidget"
# ボタン・プレイリストの選びの名前の英語（キーは日本語。アプリの中のショートカットにも同じ名前が出る）
cp -R Resources/Widget/en.lproj "$appex/Contents/Resources/"
cp -R Resources/Widget/en.lproj "$app/Contents/Resources/"
cp Resources/Info.plist "$app/Contents/Info.plist"
# 組むたびに版の番号を上げる（同じ番号のままだと、ウィジェットの一覧が前の版の名前・説明を覚えたまま出す。ios-bundle.sh と同じ）
version=$(date +%Y%m%d%H%M)
plutil -replace CFBundleVersion -string "$version" "$app/Contents/Info.plist"
plutil -replace CFBundleVersion -string "$version" "$appex/Contents/Info.plist"
# 連携の中継の宛先を書き込む（mac.sh がこの機械の local.properties から渡す。無ければ書かない）
if [ -n "${WAVE_RELAY_V2_URL:-}" ] && [ -n "${WAVE_RELAY_V2_HOST:-}" ]; then
    plutil -replace WaveRelayV2Url -string "$WAVE_RELAY_V2_URL" "$app/Contents/Info.plist"
    plutil -replace WaveRelayV2Host -string "$WAVE_RELAY_V2_HOST" "$app/Contents/Info.plist"
fi
[ -f Resources/wave.icns ] && cp Resources/wave.icns "$app/Contents/Resources/"
# 署名は GUI のセッションで（scripts/sign.command）。ここでは仮に ad-hoc で署名しておく
codesign --force --entitlements Resources/Widget/widget.entitlements --sign - "$appex"
codesign --force --entitlements Resources/wave.entitlements --sign - "$app"
echo "built $app"
