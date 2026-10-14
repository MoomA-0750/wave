#!/bin/bash
# GUI のセッション（Terminal）で、実機向けの wave.app を本人の開発用の証明書と配布設定で署名し直す。
# 配布設定は scripts/ios-provision/ の小さな Xcode プロジェクトで Xcode に作らせたもの（mac.sh ios-provision）。
# ssh からはログインキーチェーンが閉じていて署名できないので、mac.sh が open でこれを開く。
cd "$(dirname "$0")/.."
log=build/ios-sign.log
app=build/ios-device/wave.app
id=$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ {print $2; exit}')
profiles=~/Library/Developer/Xcode/UserData/Provisioning\ Profiles
# バンドル ID ごとに、名前の合う配布設定を探す
profile() {
    for f in "$profiles"/*.mobileprovision; do
        security cms -D -i "$f" 2>/dev/null > /tmp/wave-profile.plist
        [ "$(/usr/libexec/PlistBuddy -c 'Print :Name' /tmp/wave-profile.plist 2>/dev/null)" = "iOS Team Provisioning Profile: $1" ] && { echo "$f"; return; }
    done
}
sign() {
    local bundle=$1 bid=$2 p
    p=$(profile "$bid")
    [ -n "$p" ] || { echo "no profile for $bid" >> $log; return 1; }
    cp "$p" "$bundle/embedded.mobileprovision"
    security cms -D -i "$p" > /tmp/wave-profile.plist
    /usr/libexec/PlistBuddy -x -c 'Print :Entitlements' /tmp/wave-profile.plist > /tmp/wave-ent.plist
    codesign --force --sign "$id" --entitlements /tmp/wave-ent.plist "$bundle" >> $log 2>&1
}
: > $log
sign "$app/PlugIns/WaveWidget.appex" xyz.mooma0750.wave.widget && sign "$app" xyz.mooma0750.wave
echo "exit=$?" >> $log
osascript -e 'tell application "Terminal" to close (every window whose name contains "ios-sign.command")' >/dev/null 2>&1 &
