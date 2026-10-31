#!/bin/bash
# Mac の上で動かす。署名済みの iOS の .app を IPA にして、tailnet の HTTPS に置き、iPhone から入れられるページを作る。
# wave 以外のアプリでも使える（.app と置き場の名前を渡すだけ）。
#   ota.sh <署名済みの .app> <置き場の名前> [表示名]
# 置き場は ~/ota/<名前>/（wave.ipa・manifest.plist・index.html）。
# ~/ota を 127.0.0.1:$port の小さな HTTP サーバー（launchd の local.ota-web）で出し、tailscale serve で https://<Mac>:$https/ に載せる。
# ~/ota/notify-to に電話番号かメールアドレスが書いてあれば、そこへ iMessage でページの URL を送る。
set -euo pipefail
app=${1:?署名済みの .app}
slug=${2:?置き場の名前}
title=${3:-$slug}
root=~/ota
port=8791
https=8444
ts=/opt/homebrew/bin/tailscale
imsg=/opt/homebrew/bin/imsg

host=$($ts status --self --json | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
base="https://$host:$https/$slug"
info="$app/Info.plist"
bid=$(plutil -extract CFBundleIdentifier raw "$info")
ver=$(plutil -extract CFBundleShortVersionString raw "$info")
build=$(plutil -extract CFBundleVersion raw "$info")
name=$(basename "$app" .app)

# IPA（Payload/<名前>.app を zip したもの）
dir="$root/$slug"
mkdir -p "$dir"
tmp=$(mktemp -d)
mkdir "$tmp/Payload"
cp -R "$app" "$tmp/Payload/"
(cd "$tmp" && zip -qry "$name.ipa" Payload)
# 名前に版を入れて、古いものを掴まないようにする
rm -f "$dir"/*.ipa
ipa="$name-$build.ipa"
mv "$tmp/$name.ipa" "$dir/$ipa"
rm -rf "$tmp"

# iPhone が読む manifest
cat > "$dir/manifest.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>items</key><array><dict>
<key>assets</key><array><dict>
<key>kind</key><string>software-package</string>
<key>url</key><string>$base/$ipa</string>
</dict></array>
<key>metadata</key><dict>
<key>bundle-identifier</key><string>$bid</string>
<key>bundle-version</key><string>$ver</string>
<key>kind</key><string>software</string>
<key>title</key><string>$title</string>
</dict>
</dict></array>
</dict></plist>
EOF

# manifest の URL は符号化して渡す
link="itms-services://?action=download-manifest&amp;url=$(/usr/bin/python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$base/manifest.plist")"
cat > "$dir/index.html" <<EOF
<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>body{font:17px -apple-system,sans-serif;margin:32px 16px;color:#111;background:#fff}
@media(prefers-color-scheme:dark){body{color:#eee;background:#000}}
a.b{display:block;padding:16px;border-radius:14px;background:#0a84ff;color:#fff;text-align:center;text-decoration:none;font-weight:600}
p{color:#888;font-size:14px}</style></head>
<body><h1>$title</h1>
<p>$ver（$build）・$(date '+%Y-%m-%d %H:%M')</p>
<a class="b" href="$link">入れる</a>
<p>Safari で開いて押す（ほかのアプリの中の画面では入れられない）。押したら確かめの窓が出るので「インストール」を選び、ホーム画面で入るのを待つ。</p>
</body></html>
EOF

# ~/ota を出すサーバー（無ければ入れる）
agent=~/Library/LaunchAgents/local.ota-web.plist
if [ ! -f "$agent" ]; then
    cat > "$agent" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>local.ota-web</string>
<key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>-m</string><string>http.server</string><string>--bind</string><string>127.0.0.1</string><string>--directory</string><string>$root</string><string>$port</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardErrorPath</key><string>/tmp/ota-web.log</string>
</dict></plist>
EOF
    launchctl load "$agent"
fi
# tailnet の中だけに出す（他の serve の設定には触らない）
$ts serve status 2>/dev/null | grep -q ":$https " || $ts serve --bg --https=$https "http://127.0.0.1:$port" >/dev/null

for _ in $(seq 10); do curl -sf -o /dev/null "http://127.0.0.1:$port/$slug/manifest.plist" && break; sleep 1; done
echo "page: $base/"
if [ -s "$root/notify-to" ]; then
    $imsg send --to "$(head -1 "$root/notify-to")" --service imessage --text "$title $ver（$build）を入れられます: $base/" >/dev/null && echo "notified"
fi
