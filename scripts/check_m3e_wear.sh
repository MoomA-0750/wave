#!/usr/bin/env bash
# Wear OS の Compose（androidx.wear.compose）から「Expressive になっていない」書き方を探す。見つかれば 1 で終わる。
# 使い方: check_m3e_wear.sh [検査するディレクトリかファイル]   例外は同じ行に「// m3e-allow: 理由」
# テーマを定義するファイル（Theme.kt・Color.kt・Shape.kt・Type.kt）は色と形の直書きを許す。
set -u
root="${1:-.}"
quiet_if_none="${2:-}"
fail=0

mapfile -t files < <(grep -rlE --include='*.kt' '^import androidx\.wear\.compose\.' "$root" 2>/dev/null \
  | grep -vE '/(build|third_party)/')
if [ "${#files[@]}" -eq 0 ]; then
  [ "$quiet_if_none" = --quiet-if-none ] || echo 'm3e-wear: Wear のファイルなし'
  exit 0
fi

report() { # $1=説明 $2=grep -E の式 $3=テーマファイルを除くか(1/0)
  local hits
  hits=$(grep -HnE "$2" "${files[@]}" 2>/dev/null \
    | grep -v 'm3e-allow' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' \
    | { if [ "$3" = 1 ]; then grep -vE '/(Theme|Color|Shape|Type)\.kt:'; else cat; fi; })
  if [ -n "$hits" ]; then
    printf '\n## [Wear] %s\n%s\n' "$1" "$hits"
    fail=1
  fi
}

report '旧 Wear Material（M2）→ androidx.wear.compose.material3' \
  '^import androidx\.wear\.compose\.material\.' 0
report 'ScalingLazyColumn → TransformingLazyColumn ＋ 各項目に transformedHeight と SurfaceTransformation' \
  '(^import androidx\.wear\.compose\.foundation\.lazy\.ScalingLazyColumn$|(^|[^A-Za-z.])ScalingLazyColumn[[:space:]]*[({])' 0
report '形が動かない shapes() → animatedShapes()（トグルでオン・オフも形で見せるなら variantAnimatedShapes()）' \
  '(IconButton|IconToggleButton|TextButton|TextToggleButton)Defaults\.shapes\(' 0
report 'MotionScheme.standard() → 渡さない（Wear の MaterialTheme の既定が Expressive）' \
  'MotionScheme\.standard\(' 0
report 'tween / 自前の spring → MaterialTheme.motionScheme.*Spec()' \
  '(^|[^A-Za-z.])(tween|spring)[[:space:]]*[(<]' 0
report 'animate…AsState の動きを省いている（既定のばね）→ MaterialTheme.motionScheme.*Spec() を渡す' \
  'animate[A-Za-z]*AsState\([^,]*,[[:space:]]*label[[:space:]]*=' 0
report '色の直書き → MaterialTheme.colorScheme' \
  '(^|[^A-Za-z])Color\(0x' 1
report '角丸の直書き → MaterialTheme.shapes / MaterialShapes' \
  'RoundedCornerShape\(' 1

# ファイル単位: transformedHeight を使っているのに SurfaceTransformation が無い（高さだけ縮んで形が変わらない）
for f in "${files[@]}"; do
  if grep -q 'transformedHeight(' "$f" && ! grep -q 'SurfaceTransformation(' "$f" && ! grep -q 'm3e-allow: no-surface-transformation' "$f"; then
    printf '\n## [Wear] transformedHeight だけで SurfaceTransformation が無い → 部品に transformation = SurfaceTransformation(spec) を渡す\n%s\n' "$f"
    fail=1
  fi
  # ScreenScaffold があるのに AppScaffold がどこにも無い（TimeText が画面をまたいで残らない）は、リポジトリ単位で見る
done
if grep -lq 'ScreenScaffold(' "${files[@]}" 2>/dev/null && ! grep -lqE 'AppScaffold[[:space:]]*[({]' "${files[@]}" 2>/dev/null; then
  printf '\n## [Wear] ScreenScaffold はあるが AppScaffold が無い → アプリの一番外側を AppScaffold(timeText = { TimeText() }) で包む\n'
  fail=1
fi

if [ "$fail" = 0 ]; then echo 'm3e-wear: 問題なし'; fi
exit "$fail"
