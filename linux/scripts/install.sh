#!/usr/bin/env bash
# wave の Linux 版を自分のホーム（~/.local）に入れる。ランチャーに wave が1つ出る（起動すると選び役が GTK 版か QML 版を選ぶ）。
# 入れる対象は「本体」と「qt」に分かれる:
#   scripts/install.sh                本体を入れる・入れ直す（GTK 版 wave-gtk・選び役 wave・ランチャーの項目・アイコン）
#   scripts/install.sh --qt           QML 版 wave-qt を入れる・入れ直す（本体は入れておく。Qt 6 の qt6-base・qt6-declarative が要る）
#   scripts/install.sh --uninstall-qt QML 版だけ外す（選び役は自動で GTK 版に戻る）
#   scripts/install.sh --uninstall    全部外す（キーリングのログインは残す）
set -euo pipefail
cd "$(dirname "$0")/.."
bin_dir="$HOME/.local/bin"
desktop="$HOME/.local/share/applications/xyz.mooma0750.wave.desktop"
icon="$HOME/.local/share/icons/hicolor/256x256/apps/xyz.mooma0750.wave.png"

refresh() {
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
    gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
}

case "${1:-}" in
    --uninstall)
        rm -f "$bin_dir/wave" "$bin_dir/wave-gtk" "$bin_dir/wave-qt" "$desktop" "$icon"
        refresh
        echo "removed"
        ;;
    --uninstall-qt)
        rm -f "$bin_dir/wave-qt"
        echo "removed: $bin_dir/wave-qt"
        ;;
    --qt)
        cargo build --release -p wave-qt
        install -Dm755 target/release/wave-qt "$bin_dir/wave-qt"
        [ -x "$bin_dir/wave" ] || echo "note: 本体がまだ入っていません（scripts/install.sh で入れると、ランチャーから開けます）"
        echo "installed: $bin_dir/wave-qt"
        ;;
    "")
        cargo build --release -p wave-gtk
        install -Dm755 target/release/wave-gtk "$bin_dir/wave-gtk"
        install -Dm755 scripts/wave "$bin_dir/wave"
        # ランチャーから PATH を頼らずに開けるよう、入れた場所を書き込む
        install -d "$(dirname "$desktop")"
        sed "s|^Exec=wave|Exec=$bin_dir/wave|" data/xyz.mooma0750.wave.desktop > "$desktop"
        # アイコンは Windows 版と同じ絵
        install -Dm644 ../windows/Wave.App/Assets/wave.png "$icon"
        refresh
        echo "installed: $bin_dir/wave（選び役）・$bin_dir/wave-gtk"
        ;;
    *)
        echo "使い方: scripts/install.sh [--qt | --uninstall-qt | --uninstall]" >&2
        exit 2
        ;;
esac
