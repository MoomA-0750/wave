#!/usr/bin/env bash
# 時計（to-fu の Wear OS エミュレーター wave_watch）で確かめる道具。
#   watch.sh start            エミュレーターを起動し、この機械の adb から 127.0.0.1:15557 で見えるようにする
#   watch.sh install          時計版の APK を to-fu へ送って入れる（大きいので、トンネル越しでなく to-fu の adb で入れる）
#   watch.sh shot <file.png>  画面を撮る
#   watch.sh adb <args...>    時計に adb のコマンドを送る
# to-fu への ssh（ssh tofu）と、この機械の adb サーバーが to-fu の adb の鍵で起動していること（ADB_VENDOR_KEYS）が要る。
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
serial=127.0.0.1:15557
# 常駐の通知の見張り（chatnotify）が /usr/bin/adb を使うので、同じものを使う（版の違う adb が混ざると、互いに相手の adb サーバーを止める）
adb() { /usr/bin/adb "$@"; }
sdk='$env:LOCALAPPDATA\Android\Sdk'
remote_adb() { ssh tofu "& \"$sdk\\platform-tools\\adb.exe\" -s emulator-5556 $*"; }
case "${1:-}" in
start)
  ssh tofu "\$s=\"$sdk\"; if (-not (Get-CimInstance Win32_Process -Filter \"Name='qemu-system-x86_64.exe'\" | Where-Object { \$_.CommandLine -match 'wave_watch' })) { Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine=\"\`\"\$s\\emulator\\emulator.exe\`\" -avd wave_watch -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect\"} | Out-Null }"
  pgrep -f 'L 15557:127.0.0.1:5557' >/dev/null || ssh -f -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L 15557:127.0.0.1:5557 tofu
  adb connect "$serial"
  ;;
install)
  apk="$here/androidApp/build/outputs/apk/wear/debug/androidApp-wear-debug.apk"
  scp -q "$apk" tofu:wave-wear.apk
  remote_adb install -r 'wave-wear.apk'
  ;;
shot)
  adb -s "$serial" exec-out screencap -p > "$2"
  ;;
adb)
  shift
  adb -s "$serial" "$@"
  ;;
*)
  sed -n 2,7p "$0"
  exit 1
  ;;
esac
