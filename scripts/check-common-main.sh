#!/usr/bin/env bash
# commonMain に JVM・Android 専用の API が混ざっていないか調べる。
# 今の core のターゲットは android と jvm だけ（どちらも JVM）なので、書いてもビルドが通ってしまう。
# Phase 5 で iOS ターゲットを足したときにまとめて壊れないよう、ここで止める。
set -euo pipefail
cd "$(dirname "$0")/.."

pattern='^\s*import\s+(java\.|javax\.|android\.|androidx\.)|System\.(currentTimeMillis|nanoTime|getProperty)|Thread\.sleep|@Synchronized|@Volatile|kotlin\.jvm\.(?!JvmInline)'
if grep -rnP "$pattern" core/src/commonMain core/src/commonTest; then
    echo "commonMain / commonTest に JVM・Android 専用の API があります（上の行）" >&2
    exit 1
fi
echo "ok: commonMain / commonTest に JVM・Android 専用の API はありません"
