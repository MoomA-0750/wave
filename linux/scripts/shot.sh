#!/usr/bin/env bash
# 見えない画面（mutter の headless）の中で wave-gtk を開き、窓を PNG に書き出す。
# 使い方: scripts/shot.sh 出力.png [幅x高さ] [待つミリ秒]
set -euo pipefail
out=$(realpath -m "$1"); size=${2:-1440x900}; delay=${3:-1500}
cd "$(dirname "$0")/.."
cargo build -q -p wave-gtk
sock=wave-shot-$$
mutter --headless --wayland --no-x11 --wayland-display "$sock" --virtual-monitor "$size" >/dev/null 2>&1 &
mutter_pid=$!
trap 'kill $mutter_pid 2>/dev/null' EXIT
for _ in $(seq 50); do [ -S "$XDG_RUNTIME_DIR/$sock" ] && break; sleep 0.1; done
WAYLAND_DISPLAY=$sock GDK_BACKEND=wayland WAVE_SNAPSHOT="$out" WAVE_SNAPSHOT_DELAY_MS="$delay" timeout 60 target/debug/wave-gtk
echo "$out"
