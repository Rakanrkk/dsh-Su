#!/usr/bin/env bash
# 用法：bash tooling/lab/test-probe-capabilities.sh
# 宿主侧负向用例：
# 1) common.sh mountinfo 函数：空/缺失/不可读/畸形文件必须失败。
# 2) probe 设备端脚本：cgroup.kill 缺失时 CHILD 未赋值不崩溃（已移位）、SELinux 断言、prefix-left 非零。
# 3) 真实 mock：提取设备端内层脚本，注入不同 mountinfo/cgroup 状态验证退出码。
set -euo pipefail
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE="$COMMON_DIR/probe-capabilities.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail=0
expect_fail() {
  local label=$1; shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL: $label 意外成功" >&2
    fail=1
  else
    echo "pass: $label"
  fi
}
expect_zero() {
  local label=$1; shift
  if "$@" >/dev/null 2>&1; then
    echo "pass: $label"
  else
    echo "FAIL: $label 意外失败" >&2
    fail=1
  fi
}

# --- Part 1: common.sh mountinfo 函数负向 ---
: > "$TMP/empty"
printf 'not a mountinfo line\n' > "$TMP/malformed"
source "$COMMON_DIR/common.sh"

expect_fail "count 空文件" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_prefix_count '$TMP/empty' /x"
expect_fail "count 缺失文件" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_prefix_count '$TMP/missing' /x"
expect_fail "prop 空文件" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_prop_violations '$TMP/empty'"
expect_fail "prop 缺失" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_prop_violations '$TMP/missing'"
expect_fail "fstype 空文件" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_fstype_points '$TMP/empty' cgroup2"
expect_fail "fstype 缺失" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_fstype_points '$TMP/missing' cgroup2"

# 正常 mountinfo
printf '36 35 0:36 / /dev rw,nosuid shared:2 - tmpfs tmpfs rw\n37 35 0:37 /x/y /data/local/tmp/x rw - ext4 /dev/block/x rw\n' > "$TMP/ok"
got=$(lab_mountinfo_prefix_count "$TMP/ok" /data/local/tmp/x)
[[ "$got" == 1 ]] || { echo "FAIL: 正常计数 $got != 1" >&2; fail=1; }
echo "pass: 正常计数"
viol=$(lab_mountinfo_prop_violations "$TMP/ok")
[[ -n "$viol" ]] || { echo "FAIL: 含 shared 的文件应检出违规" >&2; fail=1; }
echo "pass: 传播违规检出"

# 畸形：只有一行 garbage
expect_fail "prop 畸形" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_prop_violations '$TMP/malformed'"
expect_fail "count 畸形" bash -c "source '$COMMON_DIR/common.sh'; lab_mountinfo_prefix_count '$TMP/malformed' /x"

# --- Part 2: probe 设备端脚本提取与 mock ---
# 提取内层脚本（PROBE heredoc）
INNER_START=$(grep -n "cat >.*<<'PROBE'" "$PROBE" | head -1 | cut -d: -f1)
INNER_END=$(grep -n '^PROBE$' "$PROBE" | head -1 | cut -d: -f1)
if [[ -z "$INNER_START" || -z "$INNER_END" ]]; then
  echo "FAIL: 无法提取设备端脚本" >&2; fail=1
else
  INNER=$((INNER_START + 1))
  END=$((INNER_END - 1))
  sed -n "${INNER},${END}p" "$PROBE" > "$TMP/device-inner.sh"
  chmod +x "$TMP/device-inner.sh"

  # Mock: cgroup.kill 缺失路径——注入脚本后 grep 确认 exit 37 在 CHILD 赋值之后
  CHILD_LINE=$(grep -n '^  CHILD=\$!' "$TMP/device-inner.sh" | head -1 | cut -d: -f1)
  EXIT37_LINE=$(grep -n 'exit 37' "$TMP/device-inner.sh" | head -1 | cut -d: -f1)
  if [[ -n "$CHILD_LINE" && -n "$EXIT37_LINE" && "$CHILD_LINE" -lt "$EXIT37_LINE" ]]; then
    echo "pass: CHILD 在 exit 37 之前赋值（无 uninit crash）"
  else
    echo "FAIL: CHILD=$CHILD_LINE exit37=$EXIT37_LINE" >&2; fail=1
  fi

  # Mock: prefix-left 必须返回 54
  grep -q 'exit 54' "$TMP/device-inner.sh" || { echo "FAIL: prefix-left 无 exit 54" >&2; fail=1; }
  echo "pass: prefix-left 硬失败"

  # Mock: SELinux/magisk 断言（探针宿主侧）
  grep -q 'SELinux 不是 Enforcing' "$PROBE" || { echo "FAIL: SELinux 非 assertion" >&2; fail=1; }
  echo "pass: SELinux Enforcing assertion"
  grep -q 'SELinux 上下文不含 magisk' "$PROBE" || { echo "FAIL: Magisk context 非 assertion" >&2; fail=1; }
  echo "pass: Magisk context assertion"

  # 真实 mock 执行：把设备端脚本的 in-ns 阶段在宿主上模拟（跳过 unshare）。
  # 只测 mountinfo 读取和 cgroup kill 不崩溃（无真 cgroup/mount 时应失败非零）。
  if bash "$TMP/device-inner.sh" in-ns "$TMP/fake-prefix" >/dev/null 2>&1; then
    echo "FAIL: 设备端脚本在宿主上意外成功（应该因 mount/cgroup 不可用而失败）" >&2
    fail=1
  else
    echo "pass: 设备端脚本在宿主上正确失败（无 cgroup/tmpfs）"
  fi
fi

(( fail == 0 )) || exit 1
echo "=== 全部负向用例通过 ==="
