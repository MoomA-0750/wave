#!/usr/bin/env bash
# wave の Linux 版を自分のホーム（~/.local）に入れる。GNOME のランチャーに wave が出る。
# 使い方: scripts/install.sh（入れる・入れ直す） / scripts/install.sh --uninstall（外す。キーリングのログインは残す）
set -euo pipefail
cd "$(dirname "$0")/.."
bin="$HOME/.local/bin/wave-gtk"
desktop="$HOME/.local/share/applications/xyz.mooma0750.wave.desktop"
icon="$HOME/.local/share/icons/hicolor/256x256/apps/xyz.mooma0750.wave.png"
if [ "${1:-}" = "--uninstall" ]; then
    rm -f "$bin" "$desktop" "$icon"
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
    echo "removed"
    exit 0
fi
cargo build --release -p wave-gtk
install -Dm755 target/release/wave-gtk "$bin"
# ランチャーから PATH を頼らずに開けるよう、入れた場所を書き込む
install -d "$(dirname "$desktop")"
sed "s|^Exec=wave-gtk|Exec=$bin|" data/xyz.mooma0750.wave.desktop > "$desktop"
# アイコンは Windows 版と同じ絵
install -Dm644 ../windows/Wave.App/Assets/wave.png "$icon"
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
echo "installed: $bin"
