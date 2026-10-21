#!/usr/bin/env bash
# CC の PostToolUse フック。書いたファイルを検査し、外れていれば CC へ返して直させる。
#   .kt                          → check_m3e.sh（Compose。Wear のファイルは check_m3e_wear.sh へ回る）
#   .java / .xml / .py / .patch  → check_m3e_views.sh（View。同じディレクトリにあるときだけ）
f=$(jq -r '.tool_input.file_path // empty')
[ -n "$f" ] && [ -f "$f" ] || exit 0
dir=$(cd "$(dirname "$0")" && pwd)
case "$f" in
  *.kt) check="$dir/check_m3e.sh" ;;
  *.java|*.xml|*.py|*.patch|*.diff) check="$dir/check_m3e_views.sh" ;;
  *) exit 0 ;;
esac
[ -x "$check" ] || exit 0
if ! out=$("$check" "$f"); then
  printf 'Material 3 Expressive の決まりから外れています（無印M3を書かない。置き換え表は android-m3-expressive スキル）:\n%s\n' "$out" >&2
  exit 2
fi
exit 0
