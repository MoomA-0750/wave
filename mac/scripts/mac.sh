#!/usr/bin/env bash
# macOS 版を Mac（ssh mac）で組む・確かめる道具。この機械で書いたものを Mac の ~/wave-build へ送ってから動かす。
#   mac.sh sync               ソースを送る（mac/ と、Claude の定義 ai/、テストが使う fixture）
#   mac.sh build              送ってから swift build
#   mac.sh test [filter]      送ってから swift test
#   mac.sh app                送ってから wave.app を組む（release、署名つき）
#   mac.sh run                wave.app を組んで起動する
#   mac.sh cmd <命令>...      開発用の命令を送る（run で組んだときだけ効く。Support/DebugCommands.swift）
#   mac.sh log [行数]         アプリのログの末尾
#   mac.sh ios-run            iPhone 版を組んで、シミュレーター（無ければ作る）に入れて起動する
#   mac.sh ios-cmd <命令>...  iPhone 版に開発用の命令を送る
#   mac.sh ios-log [行数]     iPhone 版のログの末尾
#   mac.sh ios-shot <ファイル> シミュレーターの画面を撮ってこの機械に持ってくる
#   mac.sh ios-provision      Xcode に実機用の配布設定（wave・ウィジェット、1年）を作らせる
#   mac.sh ios-device         実機向けに組み、配布設定で署名して、つないだ iPhone へ入れる
#   mac.sh ios-dev-cmd <命令>... つないだ iPhone の wave に開発用の命令を送る
#   mac.sh ios-dev-log [行数]   つないだ iPhone の wave のログの末尾
#   mac.sh ios-ipa [ファイル]  iPhone の実機向けに、署名していない wave.ipa を組んで持ってくる（既定は build/wave.ipa）
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
# 作業木ごとに別の置き場（並べて組んでもぶつからない）
remote=wave-build-$(basename "$here")
# 連携の中継の宛先（Android と同じく local.properties の wave.relayV2Url・wave.relayV2Host）を、組む側へ渡す
prop() { sed -n "s/^$1=//p" "$here/local.properties" 2>/dev/null | head -1 || true; }
relay="WAVE_RELAY_V2_URL='$(prop wave.relayV2Url)' WAVE_RELAY_V2_HOST='$(prop wave.relayV2Host)'"
sync() {
  rsync -az --delete --exclude .build --exclude .build-ios --exclude .build-ios-device --exclude build "$here/mac/" "mac:$remote/mac/"
  rsync -az --delete "$here/ai/" "mac:$remote/ai/"
  rsync -az --delete "$here/core/src/desktopTest/resources/fixtures/" "mac:$remote/core/src/desktopTest/resources/fixtures/"
  rsync -az --delete "$here/windows/Wave.Core.Tests/Fixtures/" "mac:$remote/windows/Wave.Core.Tests/Fixtures/"
}
# GUI のセッションで署名する（キーチェーンの保存を組み直しても読めるよう、Team ID 付きの署名にする）
sign() {
  ssh mac "rm -f ~/$remote/mac/build/sign.log; open -a Terminal ~/$remote/mac/scripts/sign.command"
  for _ in $(seq 30); do
    ssh mac "cat ~/$remote/mac/build/sign.log 2>/dev/null" | grep -q '^exit=' && break
    sleep 1
  done
  ssh mac "cat ~/$remote/mac/build/sign.log; codesign -dv ~/$remote/mac/build/wave.app 2>&1 | grep TeamIdentifier"
}
case "${1:-}" in
sync) sync ;;
build) sync; ssh mac "cd $remote/mac && swift build 2>&1" ;;
test) sync; ssh mac "cd $remote/mac && swift test ${2:+--filter $2} 2>&1" ;;
app) sync; ssh mac "cd $remote/mac && $relay scripts/bundle.sh"; sign ;;
run) sync; ssh mac "cd $remote/mac && WAVE_DEBUG=1 $relay scripts/bundle.sh"; sign; ssh mac "(pkill -x wave || true); sleep 1; open ~/$remote/mac/build/wave.app" ;;
cmd) shift; printf "%s\n" "$@" | ssh mac "cat > ~/Library/Application\\ Support/wave/debug-command.txt" ;;
log) ssh mac "tail -n ${2:-80} ~/Library/Application\ Support/wave/wave.log" ;;
ios-run)
  sync
  ssh mac "cd $remote/mac && WAVE_DEBUG=1 $relay scripts/ios-bundle.sh && scripts/ios-sim.sh install build/ios/wave.app" ;;
ios-cmd) shift; printf "%s\n" "$@" | ssh mac "cat > \"\$(xcrun simctl get_app_container wave-iphone xyz.mooma0750.wave data)/Library/Application Support/wave/debug-command.txt\"" ;;
ios-log) ssh mac "tail -n ${2:-80} \"\$(xcrun simctl get_app_container wave-iphone xyz.mooma0750.wave data)/Library/Application Support/wave/wave.log\"" ;;
ios-shot) ssh mac "xcrun simctl io wave-iphone screenshot /tmp/wave-ios.png >/dev/null 2>&1" && scp -q mac:/tmp/wave-ios.png "$2" ;;
ios-device)
  # つないだ iPhone へ入れる（配布設定は ios-provision で作っておく）
  sync
  ssh mac "cd $remote/mac && WAVE_DEBUG=1 $relay scripts/ios-bundle.sh device && rm -f build/ios-sign.log && open -a Terminal scripts/ios-sign.command"
  until ssh mac "grep -q '^exit=' ~/$remote/mac/build/ios-sign.log 2>/dev/null"; do sleep 2; done
  ssh mac "cat ~/$remote/mac/build/ios-sign.log" | tail -3
  ssh mac "grep -q '^exit=0' ~/$remote/mac/build/ios-sign.log || exit 1
    d=\$(xcrun devicectl list devices 2>/dev/null | grep -E 'available \(paired\)|connected' | grep iPhone | grep -oE '[0-9A-F]{8}-[0-9A-F-]{27}' | head -1)
    [ -n \"\$d\" ] || { echo 'iPhone がつながっていません（Mac の近くでロックを外すか、ケーブルでつなぐ）'; exit 1; }
    xcrun devicectl device install app --device \$d ~/$remote/mac/build/ios-device/wave.app 2>&1 | tail -3" ;;
ios-dev-cmd)
  # つないだ iPhone の wave に開発用の命令を送る（ios-device で入れた版だけ効く）
  shift; printf "%s\n" "$@" | ssh mac "cat > /tmp/wave-debug-command.txt && xcrun devicectl device copy to --device \$(xcrun devicectl list devices 2>/dev/null | grep -E 'available \(paired\)|connected' | grep iPhone | grep -oE '[0-9A-F]{8}-[0-9A-F-]{27}' | head -1) --domain-type appDataContainer --domain-identifier xyz.mooma0750.wave --source /tmp/wave-debug-command.txt --destination 'Library/Application Support/wave/debug-command.txt' >/dev/null" ;;
ios-dev-log)
  ssh mac "xcrun devicectl device copy from --device \$(xcrun devicectl list devices 2>/dev/null | grep -E 'available \(paired\)|connected' | grep iPhone | grep -oE '[0-9A-F]{8}-[0-9A-F-]{27}' | head -1) --domain-type appDataContainer --domain-identifier xyz.mooma0750.wave --source 'Library/Application Support/wave/wave.log' --destination /tmp/wave-device.log >/dev/null && tail -n ${2:-80} /tmp/wave-device.log" ;;
ios-provision)
  # Xcode に wave とウィジェットの配布設定（1年）を作らせる。GUI のセッションで動かす
  rsync -a --delete scripts/ios-provision/ mac:wave-provision/
  ssh mac "rm -f ~/wave-provision/prov.log; open -a Terminal ~/wave-provision/prov.command"
  until ssh mac "grep -q '^exit=' ~/wave-provision/prov.log 2>/dev/null"; do sleep 3; done
  ssh mac "grep -E 'Provisioning Profile:|BUILD|exit=' ~/wave-provision/prov.log | sort -u" ;;
ios-ipa) sync; ssh mac "cd $remote/mac && $relay scripts/ios-bundle.sh device" && scp -q mac:$remote/mac/build/ios-device/wave.ipa "${2:-build/wave.ipa}" ;;
*) sed -n 2,13p "$0"; exit 1 ;;
esac
