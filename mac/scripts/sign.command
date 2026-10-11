#!/bin/bash
# GUI のセッション（Terminal）で wave.app に Apple Development の証明書で署名する。
# ssh からはログインキーチェーンが閉じていて署名できないので、mac.sh が open でこれを開く。
cd "$(dirname "$0")/.."
id=$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ {print $2; exit}')
codesign --force --options runtime --entitlements Resources/wave.entitlements --sign "$id" build/wave.app > build/sign.log 2>&1
echo "exit=$?" >> build/sign.log
osascript -e 'tell application "Terminal" to close (every window whose name contains "sign.command")' >/dev/null 2>&1 &
