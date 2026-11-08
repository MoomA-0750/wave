#!/usr/bin/env bash
# 選び役（scripts/wave）が、WAVE_UI と XDG_CURRENT_DESKTOP の組み合わせ、入っている画面に合わせて正しい方を選ぶかを確かめる。
# 本物の画面は開かない（名前を出すだけの代わりの実行ファイルを置いて試す）。使い方: scripts/test-chooser.sh
set -euo pipefail
cd "$(dirname "$0")"
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
failures=0

# installed: both・gtk・qt・none
setup() {
    rm -rf "$root/bin"
    mkdir -p "$root/bin"
    cp wave "$root/bin/wave"
    case "$1" in
        both|gtk) printf '#!/bin/sh\necho gtk "$@"\n' > "$root/bin/wave-gtk"; chmod +x "$root/bin/wave-gtk" ;;
    esac
    case "$1" in
        both|qt) printf '#!/bin/sh\necho qt "$@"\n' > "$root/bin/wave-qt"; chmod +x "$root/bin/wave-qt" ;;
    esac
}

check() {
    local installed=$1 ui=$2 desktop=$3 want=$4
    setup "$installed"
    local got
    # PATH には本物の wave-gtk・wave-qt を入れない（入っている機械でも、置いたものだけで試す）
    got=$(env -i PATH=/usr/bin:/bin WAVE_UI="$ui" XDG_CURRENT_DESKTOP="$desktop" "$root/bin/wave" --flag 2>/dev/null || echo "fail")
    if [ "$got" = "$want" ]; then
        echo "ok   installed=$installed WAVE_UI=${ui:--} XDG_CURRENT_DESKTOP=${desktop:--} -> $got"
    else
        echo "FAIL installed=$installed WAVE_UI=${ui:--} XDG_CURRENT_DESKTOP=${desktop:--} -> $got (want $want)"
        failures=$((failures + 1))
    fi
}

# 環境変数が最優先
check both gtk Hyprland "gtk --flag"
check both qt GNOME "qt --flag"
# 机で決める: GNOME（ubuntu:GNOME も）なら GTK、それ以外は QML
check both "" GNOME "gtk --flag"
check both "" ubuntu:GNOME "gtk --flag"
check both "" Hyprland "qt --flag"
check both "" KDE "qt --flag"
check both "" "" "qt --flag"
# QML 版が入っていなければいつも GTK 版
check gtk "" Hyprland "gtk --flag"
check gtk qt Hyprland "gtk --flag"
# 選んだ方が無ければもう一方
check qt gtk GNOME "qt --flag"
check qt "" GNOME "qt --flag"
# どちらも無ければ失敗
check none "" Hyprland "fail"

if [ "$failures" -gt 0 ]; then
    echo "$failures 件が違いました"
    exit 1
fi
echo "すべて合っています"
