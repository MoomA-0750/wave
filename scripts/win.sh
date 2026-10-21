#!/usr/bin/env bash
# Windows 版（windows/）を Windows 機で組み立てて動かす。正本はこの Linux 側のリポジトリ。
#
#   win.sh setup     Windows 側に受け取り用のリポジトリを作る（初回だけ）
#   win.sh push      いまの作業ツリーを（一時コミットにして）Windows 側へ送る
#   win.sh build     Windows 側で Wave.App を組み立てる
#   win.sh run       Windows の画面側（ログオン中のセッション）で Wave.App を起動し直す
#   win.sh ps        標準入力の PowerShell を Windows 側で実行する
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
# 機械ごとの値（ssh のホスト名・Windows 側の置き場所）は scripts/win.env に書く（リポジトリには入れない）
if [ ! -f "$REPO/scripts/win.env" ]; then
  echo "scripts/win.env がありません。HOST=... と WIN_DIR=... を書いてください" >&2; exit 1
fi
# shellcheck source=/dev/null
. "$REPO/scripts/win.env"
: "${HOST:?}" "${WIN_DIR:?}"

ps() { ssh -o BatchMode=yes "$HOST" "pwsh -NoProfile -Command -" < <(if [ -n "${WIN_DEBUG:-}" ]; then tee /dev/stderr; else cat; fi); }

ensure_remote() {
  git -C "$REPO" remote get-url win >/dev/null 2>&1 || git -C "$REPO" remote add win "$HOST:$WIN_DIR"
  # Windows の既定シェルは PowerShell なので、git receive-pack として呼ばせる
  git -C "$REPO" config remote.win.receivepack 'git receive-pack'
  git -C "$REPO" config remote.win.uploadpack 'git upload-pack'
}

EXE="$WIN_DIR/windows/Wave.App/bin/x64/Debug/net9.0-windows10.0.22621.0/win-x64/Wave.App.exe"

case "${1:-}" in
  setup)
    ps <<PS
New-Item -ItemType Directory -Force '$WIN_DIR' | Out-Null
Set-Location '$WIN_DIR'
if (-not (Test-Path .git)) { git init -q -b main }
git config receive.denyCurrentBranch updateInstead
"ok"
PS
    ensure_remote
    ;;
  push)
    ensure_remote
    # 未コミットの変更も送れるよう、作業ツリーを一時コミット（stash create）にして送る。
    # stash create は追跡していないファイルを含めないので、windows/ と ai/（Claude の定義）の新しいファイルは先に索引へ入れる
    git -C "$REPO" add -A windows ai
    rev=$(git -C "$REPO" stash create)
    rev=${rev:-$(git -C "$REPO" rev-parse HEAD)}
    git -C "$REPO" push -q -f win "$rev:refs/heads/main"
    echo "pushed $rev"
    ;;
  build)
    ps <<PS
Get-Process Wave.App -ErrorAction SilentlyContinue | Stop-Process -Force
Set-Location '$WIN_DIR/windows/Wave.App'
dotnet build -c Debug -p:Platform=x64 -v q -nologo 2>&1 | Select-String -Pattern 'error|warn|Warn|Error|Elapsed|経過' | Select-Object -First 60 | ForEach-Object { \$_.Line }
"exit=\$LASTEXITCODE"
PS
    ;;
  run)
    # ssh のセッションではなく、ログオン中の画面のセッションで起こす（タスクスケジューラ経由）。PowerShell へ送る文には日本語を書かない（読み違えて次の行が消える）
    ps <<PS
Get-Process Wave.App -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 300
\$exe = '$EXE'.Replace('/', '\\')
\$dir = Split-Path \$exe
\$act = New-ScheduledTaskAction -Execute \$exe -WorkingDirectory \$dir
\$who = New-ScheduledTaskPrincipal -UserId \$env:USERNAME -LogonType Interactive
Register-ScheduledTask -TaskName wave-app-run -Action \$act -Principal \$who -Force | Out-Null
Start-ScheduledTask -TaskName wave-app-run
Start-Sleep -Seconds 3
Get-Process Wave.App -ErrorAction SilentlyContinue | Select-Object Id, MainWindowTitle | Format-Table -HideTableHeaders | Out-String
PS
    ;;
  uia)
    # 画面のセッションで scripts/uia.ps1 を動かし、結果を表示する（例: win.sh uia 'invoke:Liked Songs' 'sleep:1500' 'list'）
    shift
    scp -q "$REPO/scripts/uia.ps1" "$HOST:$WIN_DIR/uia.ps1"
    steps=$(printf '%s\n' "$@" | base64 -w0)
    ps <<PS
Remove-Item "\$env:LOCALAPPDATA\wave\uia-out.txt" -ErrorAction SilentlyContinue
[IO.File]::WriteAllBytes("\$env:LOCALAPPDATA\wave\uia-steps.txt", [Convert]::FromBase64String('$steps'))
\$act = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -ExecutionPolicy Bypass -File $WIN_DIR/uia.ps1'
\$who = New-ScheduledTaskPrincipal -UserId \$env:USERNAME -LogonType Interactive
Register-ScheduledTask -TaskName wave-uia -Action \$act -Principal \$who -Force | Out-Null
Start-ScheduledTask -TaskName wave-uia
for (\$i = 0; \$i -lt 120 -and -not (Test-Path "\$env:LOCALAPPDATA\wave\uia-out.txt"); \$i++) { Start-Sleep -Milliseconds 500 }
Get-Content "\$env:LOCALAPPDATA\wave\uia-out.txt" -Encoding utf8
PS
    ;;
  cmd)
    # Debug ビルドの wave に命令を送る（windows/Wave.App/DebugCommands.cs。例: win.sh cmd 'pane queue' 'queue-dump'）
    shift
    cmds=$(printf '%s\n' "$@" | base64 -w0)
    ps <<PS
[IO.File]::WriteAllBytes("\$env:LOCALAPPDATA\wave\debug-command.txt", [Convert]::FromBase64String('$cmds'))
PS
    ;;
  log) ps <<PS
Get-Content "\$env:LOCALAPPDATA\wave\wave.log" -Tail ${2:-30} -Encoding utf8
PS
    ;;
  ps) ps ;;
  *) sed -n 2,9p "$0"; exit 1 ;;
esac
