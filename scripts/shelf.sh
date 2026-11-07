#!/usr/bin/env bash
# wave を appshelf（general-console の上の自分用の配布ページ）に載せる。general-console の上で動かす。
#
#   scripts/shelf.sh [OS...] [-- 変更点]
#     OS は android・wear・ios・macos・windows・linux・linux-arm・all（既定は all）。変更点の既定は最後の commit の題
#
# Android（スマホ・時計）と Linux はこの機械で、iOS・macOS は Mac（ssh mac）で、Windows は to-fu（scripts/win.sh）で組む。
# Linux の aarch64（linux-arm）は cramgent（WAVE_ARM_HOST で変えられる）で組む。
# 時計は appshelf の android に「wave-wear」として載せる。Linux は x86_64 と aarch64 を同じ「wave」の linux に -arch で分けて載せる。
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
# Gradle は Android Studio に付いてくる JDK で動かす（JAVA_HOME が無いとき）
export JAVA_HOME=${JAVA_HOME:-/opt/android-studio/jbr}
appshelf=${APPSHELF_BIN:-$(command -v appshelf || echo ~/.local/bin/appshelf)}

oses=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do oses+=("$1"); shift; done
[ "${1:-}" = "--" ] && shift
note=${1:-$(git -C "$REPO" log -1 --format=%s)}
[ ${#oses[@]} -eq 0 ] || [ "${oses[0]}" = all ] && oses=(android wear ios macos windows linux linux-arm)

commit=$(git -C "$REPO" rev-parse HEAD)
stamp=$(date +%Y%m%d%H%M)
version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$REPO/androidApp/build.gradle.kts" | head -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
publish() { "$appshelf" publish -commit "$commit" -note "$note" "$@"; }

android() {
  local flavor=$1 app=$2 title=$3 Flavor
  Flavor=$(tr '[:lower:]' '[:upper:]' <<<"${flavor:0:1}")${flavor:1}
  (cd "$REPO" && ./gradlew -q ":androidApp:assemble${Flavor}Release")
  cp "$REPO/androidApp/build/outputs/apk/$flavor/release/androidApp-$flavor-release.apk" "$work/$app-$stamp.apk"
  publish -app "$app" -os android -title "$title" -version "$version" -build "$stamp" "$work/$app-$stamp.apk"
}

# 組んだ wave-gtk とランチャーの定義・アイコン・入れる道具を $work/<name>-$stamp.tar.gz にする（展開して ./install.sh）
linux_package() {
  local bin=$1 name=$2 pkg=$work/$2
  mkdir -p "$pkg"
  install -m755 "$bin" "$pkg/wave-gtk"
  cp "$REPO/linux/data/xyz.mooma0750.wave.desktop" "$REPO/windows/Wave.App/Assets/wave.png" "$pkg/"
  cat >"$pkg/install.sh" <<'SH'
#!/usr/bin/env bash
# wave を自分のホーム（~/.local）に入れる。ランチャー（GNOME・fuzzel など .desktop を読むもの）に wave が出る
set -euo pipefail
cd "$(dirname "$0")"
bin="$HOME/.local/bin/wave-gtk"
install -Dm755 wave-gtk "$bin"
install -d "$HOME/.local/share/applications"
sed "s|^Exec=wave-gtk|Exec=$bin|" xyz.mooma0750.wave.desktop > "$HOME/.local/share/applications/xyz.mooma0750.wave.desktop"
install -Dm644 wave.png "$HOME/.local/share/icons/hicolor/256x256/apps/xyz.mooma0750.wave.png"
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
echo "installed: $bin"
SH
  chmod +x "$pkg/install.sh"
  tar -C "$work" -czf "$work/$name-$stamp.tar.gz" "$name"
}

for os in "${oses[@]}"; do
  case $os in
  android) android phone wave wave ;;
  wear) android wear wave-wear "wave（時計）" ;;
  ios) "$REPO/mac/scripts/mac.sh" ios-shelf "$note" ;;
  macos)
    # 署名した wave.app を、署名を崩さない ditto で zip にして持ってくる
    "$REPO/mac/scripts/mac.sh" app
    remote=wave-build-$(basename "$REPO")
    build=$(ssh mac "plutil -extract CFBundleVersion raw ~/$remote/mac/build/wave.app/Contents/Info.plist")
    ssh mac "cd ~/$remote/mac/build && rm -f wave-mac.zip && ditto -c -k --keepParent wave.app wave-mac.zip"
    scp -q "mac:$remote/mac/build/wave-mac.zip" "$work/wave-mac-$build.zip"
    publish -app wave -os macos -version "$version" -build "$build" "$work/wave-mac-$build.zip" ;;
  windows)
    # Release で組み（自己完結。.NET も Windows App SDK も同梱）、出力のフォルダーを zip にして持ってくる
    "$REPO/scripts/win.sh" push
    . "$REPO/scripts/win.env"
    ssh -o BatchMode=yes "$HOST" "pwsh -NoProfile -Command -" <<PS
\$ErrorActionPreference = 'Stop'
Set-Location '$WIN_DIR/windows/Wave.App'
dotnet build -c Release -p:Platform=x64 -v q -nologo 2>&1 | Select-String -Pattern ' error ' | Select-Object -First 20 | ForEach-Object { \$_.Line }
if (\$LASTEXITCODE -ne 0) { exit 1 }
\$out = Resolve-Path 'bin/x64/Release/net9.0-windows10.0.22621.0/win-x64'
Remove-Item -Force -ErrorAction SilentlyContinue "\$env:TEMP/wave-windows.zip"
Compress-Archive -Path "\$out/*" -DestinationPath "\$env:TEMP/wave-windows.zip"
PS
    scp -q "$HOST:AppData/Local/Temp/wave-windows.zip" "$work/wave-windows-$stamp.zip"
    publish -app wave -os windows -version "$version" -build "$stamp" "$work/wave-windows-$stamp.zip" ;;
  linux)
    # x86_64 はこの機械で組む
    (cd "$REPO/linux" && cargo build -q --release -p wave-gtk)
    linux_package "$REPO/linux/target/release/wave-gtk" wave-linux
    publish -app wave -os linux -arch x86_64 -version "$version" -build "$stamp" "$work/wave-linux-$stamp.tar.gz" ;;
  linux-arm)
    # aarch64 は aarch64 の Linux 機（既定は cramgent）で組む。GTK などを揃えたクロスの土台を持たずに済む
    host=${WAVE_ARM_HOST:-cramgent}
    remote=wave-build-$(basename "$REPO")
    # include_str! と試験の手本が linux/ の外（ai/・core の fixtures・windows の手本）を読むので一緒に送る
    (cd "$REPO" && git ls-files linux ai core/src/desktopTest/resources/fixtures windows/Wave.Core.Tests/Fixtures/connect) |
      rsync -a --files-from=- "$REPO" "$host:$remote/"
    ssh "$host" "cd ~/$remote/linux && cargo build -q --release -p wave-gtk"
    scp -q "$host:$remote/linux/target/release/wave-gtk" "$work/wave-gtk-aarch64"
    linux_package "$work/wave-gtk-aarch64" wave-linux-aarch64
    publish -app wave -os linux -arch aarch64 -version "$version" -build "$stamp" "$work/wave-linux-aarch64-$stamp.tar.gz" ;;
  *) sed -n 2,8p "$0"; exit 1 ;;
  esac
done
