#!/usr/bin/env bash
# macOS 版を Mac（ssh mac）で組む・確かめる道具。この機械で書いたものを Mac の ~/wave-build へ送ってから動かす。
#   mac.sh sync               ソースを送る（mac/ と、テストが使う fixture）
#   mac.sh build              送ってから swift build
#   mac.sh test [filter]      送ってから swift test
#   mac.sh app                送ってから wave.app を組む（release、署名つき）
#   mac.sh run                wave.app を組んで起動する
#   mac.sh log                アプリのログの末尾
set -euo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
# 作業木ごとに別の置き場（並べて組んでもぶつからない）
remote=wave-build-$(basename "$here")
sync() {
  rsync -az --delete --exclude .build --exclude '*.app' "$here/mac/" "mac:$remote/mac/"
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
app) sync; ssh mac "cd $remote/mac && scripts/bundle.sh"; sign ;;
run) sync; ssh mac "cd $remote/mac && scripts/bundle.sh"; sign; ssh mac "(pkill -x wave || true); sleep 1; open ~/$remote/mac/build/wave.app" ;;
log) ssh mac "tail -n ${2:-80} ~/Library/Application\ Support/wave/wave.log" ;;
*) sed -n 2,8p "$0"; exit 1 ;;
esac
