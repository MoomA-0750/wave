#!/usr/bin/env bash
# Compose のコードから「無印 Material 3」の書き方を探す。見つかれば 1 で終わる。
# 使い方: check_m3e.sh [検査するディレクトリ]   例外は同じ行に「// m3e-allow: 理由」
# テーマを定義するファイル（Theme.kt・Color.kt・Shape.kt・Type.kt）は色と形の直書きを許す。
set -u
root="${1:-.}"
fail=0

# Compose を import している .kt だけを見る（build・third_party は除く）
# Wear の画面（androidx.wear.compose を import している）は決まりが違うので check_m3e_wear.sh に回す
mapfile -t files < <(grep -rlE --include='*.kt' '^import androidx\.compose\.' "$root" 2>/dev/null \
  | grep -vE '/(build|third_party)/' \
  | while read -r f; do grep -qE '^import androidx\.wear\.compose\.' "$f" || echo "$f"; done)
wear="$(dirname "$0")/check_m3e_wear.sh"
if [ "${#files[@]}" -eq 0 ]; then
  if [ -x "$wear" ]; then exec "$wear" "$root"; fi
  echo 'm3e: Compose のファイルなし'; exit 0
fi

report() { # $1=説明 $2=grep -E の式 $3=テーマファイルを除くか(1/0)
  local hits
  hits=$(grep -HnE "$2" "${files[@]}" 2>/dev/null \
    | grep -v 'm3e-allow' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' \
    | { if [ "$3" = 1 ]; then grep -vE '/(Theme|Color|Shape|Type)\.kt:'; else cat; fi; })
  if [ -n "$hits" ]; then
    printf '\n## %s\n%s\n' "$1" "$hits"
    fail=1
  fi
}

report 'material3 のワイルドカード import（検査をすり抜ける）' \
  '^import androidx\.compose\.material3\.\*' 0
report '無印の部品 → 置き換え表を見る' \
  '^import androidx\.compose\.material3\.(CircularProgressIndicator|LinearProgressIndicator|NavigationBar|NavigationBarItem|NavigationRail|NavigationRailItem|BottomAppBar|MediumTopAppBar|LargeTopAppBar|ExtendedFloatingActionButton|SegmentedButton|SegmentedButtonDefaults|SingleChoiceSegmentedButtonRow|MultiChoiceSegmentedButtonRow)$' 0
report '無印の部品を完全名で呼んでいる → 置き換え表を見る' \
  'androidx\.compose\.material3\.(CircularProgressIndicator|LinearProgressIndicator|NavigationBar|NavigationBarItem|NavigationRail|NavigationRailItem|BottomAppBar|MediumTopAppBar|LargeTopAppBar|ExtendedFloatingActionButton|SegmentedButton|SingleChoiceSegmentedButtonRow|MultiChoiceSegmentedButtonRow)[[:space:]]*[({]' 0
report 'animate…AsState の動きを省いている（既定のばね）→ MaterialTheme.motionScheme.*Spec() を渡す' \
  'animate[A-Za-z]*AsState\([^,]*,[[:space:]]*label[[:space:]]*=' 0
report 'MaterialTheme(...) でテーマを張っている → MaterialExpressiveTheme' \
  '(^|[^.A-Za-z])MaterialTheme[[:space:]]*[({]' 0
report 'tween / 自前の spring → MaterialTheme.motionScheme.*Spec()' \
  '(^|[^A-Za-z.])(tween|spring)[[:space:]]*[(<]' 0
report '太字の直書き → MaterialTheme.typography.*Emphasized' \
  'fontWeight[[:space:]]*=[^,)]*FontWeight\.(SemiBold|Bold|ExtraBold|Black)' 1
report '色の直書き → MaterialTheme.colorScheme' \
  '(^|[^A-Za-z])Color\(0x' 1
report '角丸の直書き → MaterialTheme.shapes / MaterialShapes' \
  'RoundedCornerShape\(' 1

if [ "$fail" = 0 ]; then echo 'm3e: 問題なし'; fi
# 同じ場所に Wear 版があれば続けて走らせる（Wear のファイルが無ければ何も出さない）
if [ -x "$wear" ]; then "$wear" "$root" --quiet-if-none || fail=1; fi
exit "$fail"
