#!/usr/bin/env bash
# Compose のコードから「無印 Material 3」の書き方を探す。見つかれば 1 で終わる。
# 使い方: check_m3e.sh [検査するディレクトリ]   例外は同じ行に「// m3e-allow: 理由」
# テーマを定義するファイル（Theme.kt・Color.kt・Shape.kt・Type.kt）は色と形の直書きを許す。
set -u
root="${1:-.}"
fail=0

# Compose を import している .kt だけを見る（build・third_party は除く）
mapfile -t files < <(grep -rlE --include='*.kt' '^import androidx\.compose\.' "$root" 2>/dev/null \
  | grep -vE '/(build|third_party)/')
[ "${#files[@]}" -eq 0 ] && { echo 'm3e: Compose のファイルなし'; exit 0; }

report() { # $1=説明 $2=grep -E の式 $3=テーマファイルを除くか(1/0)
  local hits
  hits=$(grep -nE "$2" "${files[@]}" 2>/dev/null \
    | grep -v 'm3e-allow' \
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

# Wear OS（androidx.wear.compose.material3）は名前が同じでも中身が Expressive。そのかわり、既定のままだと Expressive にならないものを見る
mapfile -t wear_files < <(grep -lE '^import androidx\.wear\.compose\.material3\.' "${files[@]}" 2>/dev/null)
if [ "${#wear_files[@]}" -gt 0 ]; then
  report_wear() { # $1=説明 $2=見つけた行
    if [ -n "$2" ]; then
      printf '\n## %s\n%s\n' "$1" "$2"
      fail=1
    fi
  }
  # テーマの動きの既定は Standard。MotionScheme.expressive() を渡す（m3e-allow があっても見る）
  report_wear 'Wear のテーマに Expressive の動きを渡していない → MaterialTheme(…, motionScheme = MotionScheme.expressive())' \
    "$(grep -nE '(^|[^.A-Za-z])MaterialTheme[[:space:]]*\(' "${wear_files[@]}" | grep -v 'MotionScheme\.expressive()')"
  # アイコンのボタンの形の既定は押しても変わらない。呼び出しの引数（中身の { まで）に shapes = …animatedShapes() を探す
  report_wear 'Wear のアイコンのボタンが押しても形が変わらない → shapes = IconButtonDefaults.animatedShapes()（トグルは IconToggleButtonDefaults.animatedShapes()）' \
    "$(awk '
      FNR == 1 { open = 0 }
      !open && /(^|[^A-Za-z.])(Filled|FilledTonal|Outlined)?Icon(Toggle)?Button[[:space:]]*\(/ && !/m3e-allow/ { open = 1; start = FILENAME ":" FNR ": " $0; ok = 0 }
      open { if (/[Aa]nimatedShapes\(\)/) ok = 1; if (/\)[[:space:]]*\{/ || /\{[[:space:]]*$/) { if (!ok) print start; open = 0 } }
    ' "${wear_files[@]}" | sed -E 's/[[:space:]]+/ /g')"
fi

if [ "$fail" = 0 ]; then echo 'm3e: 問題なし'; fi
exit "$fail"
