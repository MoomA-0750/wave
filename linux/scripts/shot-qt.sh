#!/usr/bin/env bash
# QML 版（wave-qt）を画面に窓を出さずに開き（QT_QPA_PLATFORM=offscreen）、QQuickWindow::grabWindow で PNG に書き出す。
# 使い方: scripts/shot-qt.sh 出力.png [テーマ（/usr/share/omarchy/themes の名前・none）] [待つミリ秒]
#   WAVE_DEMO=search:語・album:ID・playlist:ID・artist:ID・lyrics・queue・settings・full・mood:題:params で、開くページを決める
#   WAVE_DEMO_QUERY=語 で検索の1曲目を鳴らす（音は fakesink に流すので鳴らない）
#   firstalbum:語・firstartist:語 は検索の1件目のアルバム・アーティストのページ
# 人の機械でも触らないように: 設定・データ・キャッシュ・テーマは一時的な場所、キーリングは読まない（WAVE_KEYRING_OFF）、
# ログインせずに使う（WAVE_SKIP_LOGIN）、D-Bus は自分だけのもの（MPRIS を本人の机に出さない）。
# 描き方は Qt Quick の software（GPU も人の画面のセッションも使わない）。Material の部品のうち地が効果で描かれるもの
# （ボタン・メニュー・ダイアログ）は、wave-qt の側で地を自分で描いているので software でも出る。
set -euo pipefail
out=$(realpath -m "$1"); theme=${2:-catppuccin}; delay=${3:-6000}
cd "$(dirname "$0")/.."
cargo build -q -p wave-qt
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/config" "$tmp/data" "$tmp/cache" "$tmp/state/omarchy/current"
if [ "$theme" != none ]; then
    src=/usr/share/omarchy/themes/$theme
    [ -d "$src" ] || src=$HOME/.local/share/omarchy/themes/$theme
    cp -r "$src" "$tmp/state/omarchy/current/theme"
fi
skip=(WAVE_SKIP_LOGIN=1)
# WAVE_SHOT_LOGIN_PAGE=1 ならログインの入り口を撮る
if [ -n "${WAVE_SHOT_LOGIN_PAGE:-}" ]; then skip=(); fi
env -u DISPLAY -u WAYLAND_DISPLAY XDG_CONFIG_HOME="$tmp/config" XDG_DATA_HOME="$tmp/data" XDG_CACHE_HOME="$tmp/cache" XDG_STATE_HOME="$tmp/state" \
    WAVE_KEYRING_OFF=1 "${skip[@]}" WAVE_AUDIO_SINK="fakesink sync=true" WAVE_THEME_ROUNDING="${WAVE_THEME_ROUNDING:-0}" \
    LANG="${LANG_SHOT:-ja_JP.UTF-8}" QT_QPA_PLATFORM=offscreen QT_FORCE_STDERR_LOGGING=1 QT_QUICK_BACKEND=software \
    WAVE_SNAPSHOT="$out" WAVE_SNAPSHOT_DELAY_MS="$delay" \
    timeout "${WAVE_SHOT_TIMEOUT:-90}" dbus-run-session -- target/debug/wave-qt
echo "$out"
