#!/usr/bin/env bash
# 回归：avd-metadata.py 只读元数据 collector。
# 用法：test-avd-metadata.sh
# 检查：stdout JSON 有效；fixture（含空格/-> 文件名、hardlink、symlink）hash/size/mtime 不变；
#       未知 --output flag 被拒绝且不产生文件；临时进程退出清理。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTOR="$HERE/avd-metadata.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

printf 'binary-fixture-0123456789abcdef' > "$TMP/fixture.bin"
cp "$TMP/fixture.bin" "$TMP/hard link target"
ln "$TMP/hard link target" "$TMP/hard link"
ln -s "$TMP/hard link target" "$TMP/sym link"
mv "$TMP/fixture.bin" "$TMP/img -> note.bin"

sha_before=$(sha256sum "$TMP/img -> note.bin" "$TMP/hard link target" "$TMP/hard link" | md5sum)
stat_before=$(stat -c '%n %s %Y %i' "$TMP/img -> note.bin" "$TMP/hard link target" "$TMP/hard link" "$TMP/sym link")

"$COLLECTOR" --image "$TMP/img -> note.bin" --image "$TMP/hard link" --image "$TMP/sym link" > "$TMP/out.json"
python3 -c "import json,sys; json.load(open(sys.argv[1])); print('JSON-OK')" "$TMP/out.json"

sha_after=$(sha256sum "$TMP/img -> note.bin" "$TMP/hard link target" "$TMP/hard link" | md5sum)
stat_after=$(stat -c '%n %s %Y %i' "$TMP/img -> note.bin" "$TMP/hard link target" "$TMP/hard link" "$TMP/sym link")
[ "$sha_before" = "$sha_after" ] && echo "HASH-OK" || { echo "HASH-MISMATCH"; exit 1; }
[ "$stat_before" = "$stat_after" ] && echo "STAT-OK" || { echo "STAT-MISMATCH"; exit 1; }

"$COLLECTOR" --output "$TMP/should-not-exist.json" --image "$TMP/img -> note.bin" >/dev/null 2>&1 \
  && { echo "REJECT-FAIL"; exit 1; } || echo "REJECT-OK"
[ ! -e "$TMP/should-not-exist.json" ] && echo "NO-FILE-CREATED" || { echo "UNEXPECTED-FILE"; exit 1; }

python3 -c "
import subprocess, sys, json, os, time
p = subprocess.Popen(['sleep','5'])
try:
    out = subprocess.run(['$COLLECTOR','--pid',str(p.pid)], capture_output=True, text=True, timeout=10).stdout
    d = json.loads(out)
    assert d['pids'], 'no pids'
    print('PID-JSON-OK', len(d['pids']), 'fds')
finally:
    p.terminate(); p.wait(timeout=5)
"

echo "PASS"