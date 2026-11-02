#!/usr/bin/env bash
# dsh-su 实验室最小能力探针：cgroup2 kill + private mount 零泄漏。
# 用法：bash tooling/lab/probe-capabilities.sh
# 前置：模拟器已由 emulator-lab.sh start/wait 启动且 Magisk su 可用。
# rprivate 实现：优先 Magisk busybox mount --make-rprivate；缺失时用内嵌
# C 助手（NDK 编译，只调 mount MS_REC|MS_PRIVATE）。toybox -o rprivate
# 不可靠（见 notes/probes/incident-root-cause.md），禁止使用。
# 安全：探针全部工作在 su 启动的 mount namespace 内，第一步失败立即退出；
# 宿主 namespace 全程零挂载泄漏；不用 rm -rf；不动 adbd。
set -euo pipefail

COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$COMMON_DIR/common.sh"
lab_load_env

ADB="$LAB_ADB"
SERIAL="$LAB_SERIAL"
SU=${DSH_SU_PATH:-/debug_ramdisk/su}

lab_owned_device "$SERIAL"

echo "== 前置：Magisk su 身份 =="
su_id=$("$ADB" -s "$SERIAL" shell "'$SU' -c id" | tr -d '\r')
echo "su-id: $su_id"
grep -q 'uid=0' <<<"$su_id" || { echo "FAIL: su 未返回 UID 0" >&2; exit 10; }
grep -q 'magisk' <<<"$su_id" || { echo "FAIL: SELinux 上下文不含 magisk: $su_id" >&2; exit 11; }
ENFORCE=$("$ADB" -s "$SERIAL" shell "'$SU' -c getenforce" | tr -d '\r')
echo "getenforce: $ENFORCE"
[[ "$ENFORCE" == "Enforcing" ]] || { echo "FAIL: SELinux 不是 Enforcing: $ENFORCE" >&2; exit 11; }
echo "magisk-version: $("$ADB" -s "$SERIAL" shell "'$SU' -v" | tr -d '\r')"

echo "== Magisk 安装布局 =="
"$ADB" -s "$SERIAL" shell "'$SU' -c 'ls /data/adb/ 2>/dev/null; ls /data/adb/magisk/ 2>/dev/null'" | tr -d '\r' || true

echo "== rprivate 助手 =="
HELPER_REMOTE="/data/local/tmp/dsh-su-lab-make-rprivate"
has_busybox=$("$ADB" -s "$SERIAL" shell "'$SU' -c '[ -x /data/adb/magisk/busybox ] && echo yes || echo no'" | tr -d '\r')
if [[ "$has_busybox" == yes ]]; then
  echo "rprivate-tool: magisk-busybox"
else
  echo "rprivate-tool: 编译 NDK 助手"
  SRC="$(mktemp /tmp/dsh-su-lab-mrp.XXXXXX.c)"
  BIN="$(mktemp /tmp/dsh-su-lab-mrp.XXXXXX.bin)"
  trap 'rm -f "$SRC" "$BIN"' EXIT
  cat >"$SRC" <<'C'
#include <stddef.h>
#include <sys/mount.h>

int main(void) {
    return mount("/", "/", NULL, MS_REC | MS_PRIVATE, NULL);
}
C
  NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
  NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  CC="$NDK_BIN/x86_64-linux-android35-clang"
  if [[ ! -x "$CC" ]]; then
    CC=$(ls "$NDK_BIN" 2>/dev/null | grep -E '^x86_64-linux-android3[0-9]-clang$' | sort -V | tail -1 || true)
    [[ -n "$CC" ]] && CC="$NDK_BIN/$CC"
  fi
  [[ -x "$CC" ]] || { echo "FAIL: 无可用 NDK clang" >&2; exit 12; }
  "$CC" -O2 -static "$SRC" -o "$BIN"
  "$ADB" -s "$SERIAL" push "$BIN" "$HELPER_REMOTE" >/dev/null
  "$ADB" -s "$SERIAL" shell "'$SU' -c 'chmod 700 $HELPER_REMOTE'"
  rm -f "$SRC" "$BIN"
  echo "rprivate-helper: pushed $HELPER_REMOTE"
fi

INNER="$(mktemp /tmp/dsh-su-lab-probe-inner.XXXXXX.sh)"
trap 'rm -f "$INNER"' EXIT
cat >"$INNER" <<'PROBE'
#!/system/bin/sh
# 设备端两阶段探针：带 in-ns 参数在 mount namespace 内执行。
# 约定：全部事实以 key=value 输出；前置失败立即退出非零。
set -eu
TS=$(date +%s)
UNIQ=dsh-su-lab-$TS-$$
PREFIX=/data/local/tmp/$UNIQ
TINY=$PREFIX/tinyroot
BB=/data/adb/magisk/busybox
HELPER=/data/local/tmp/dsh-su-lab-make-rprivate
# mount 实现：有 busybox 用 busybox，否则 toybox（rprivate 除外）。
mt() {
  if [ -x "$BB" ]; then "$BB" mount "$@"; else /system/bin/toybox mount "$@"; fi
}

# --- namespace 内阶段 ---
if [ "${1:-}" = "in-ns" ]; then
  PREFIX=${2:?必须传入父阶段 PREFIX}
  TINY=$PREFIX/tinyroot
  echo ns-mnt=$(readlink /proc/self/ns/mnt)
  # 第一步：递归 private。只认 busybox 或 C 助手；失败立即退出。
  if [ -x "$BB" ]; then
    "$BB" mount --make-rprivate / || { echo make-rprivate=busybox-failed-rc=$?; exit 21; }
    echo make-rprivate=busybox
  elif [ -x "$HELPER" ]; then
    "$HELPER" || { echo make-rprivate=helper-failed-rc=$?; exit 21; }
    echo make-rprivate=helper
  else
    echo make-rprivate=no-tool
    exit 20
  fi
  BAD=$(awk '
    $0 == "" { next }
    {
      for (i = 7; i <= NF; i++) {
        if ($i == "-") break
        if ($i ~ /^(shared|master):/) { n++; break }
      }
    }
    END { if (NR == 0) exit 8; print n + 0 }
  ' /proc/self/mountinfo) || { echo mountinfo-empty-or-fail; exit 22; }
  [ "$BAD" = 0 ] || { echo propagation-violations=$BAD; exit 22; }
  echo propagation=recursive-private

  # cgroup2：优先复用现有挂载，不可写则在 namespace 内新挂实例。
  CG=
  CG2MOUNT=$(awk '
    $0 == "" { next }
    {
      for (i = 7; i <= NF; i++) {
        if ($i == "-") { if ($(i + 1) == "cgroup2") print $5; break }
      }
    }
    END { if (NR == 0) exit 8 }
  ' /proc/self/mountinfo | head -1) || { echo mountinfo-empty-or-fail-cg2; exit 30; }
  if [ -n "$CG2MOUNT" ]; then
    echo cgroup2-existing=$CG2MOUNT
    L=$CG2MOUNT/$UNIQ
    if mkdir "$L" 2>/dev/null; then CG=$L; echo cgroup2-source=existing-mount; fi
  else
    echo cgroup2-existing=none
  fi
  if [ -z "$CG" ]; then
    CGD=$PREFIX/cg2
    mkdir -p "$CGD"
    mt -t cgroup2 none "$CGD" || { echo cgroup2-mount=failed; exit 31; }
    L=$CGD/$UNIQ
    mkdir "$L" || { echo cgroup2-mkdir=failed; exit 32; }
    CG=$L
    echo cgroup2-source=fresh-mount:$CGD
  fi
  echo cgroup2-group=$CG
  sleep 300 &
  CHILD=$!
  echo "$CHILD" > "$CG/cgroup.procs" || { echo cgroup.procs-write=failed; exit 33; }
  if [ -f "$CG/cgroup.kill" ]; then
    echo cgroup.kill-file=present
  else
    echo cgroup.kill-file=absent
    kill "$CHILD" 2>/dev/null || true
    wait "$CHILD" 2>/dev/null || true
    rmdir "$CG" 2>/dev/null || true
    exit 37
  fi
  MEMBERS=$(cat "$CG/cgroup.procs")
  echo cgroup-members=$MEMBERS
  # 组里必须只有本探针的 sleep；发现其他成员立即退出，不杀任何宿主进程。
  [ "$MEMBERS" = "$CHILD" ] || { echo cgroup-members=unexpected; exit 34; }
  echo 1 > "$CG/cgroup.kill" || { echo cgroup.kill-write=failed; exit 35; }
  sleep 1
  if kill -0 "$CHILD" 2>/dev/null; then
    echo cgroup-kill=fail
    kill "$CHILD" 2>/dev/null || true
    exit 36
  fi
  echo cgroup-kill=ok
  wait "$CHILD" 2>/dev/null || true
  rmdir "$CG" 2>/dev/null || true

  # mount 隔离：私有 tmpfs 最小 /dev（mknod，失败逐节点 bind），bind 只读目录测传播。
  mkdir -p "$TINY/dev" "$PREFIX/hostshare"
  mt -t tmpfs tmpfs "$TINY/dev" || { echo tmpfs-dev=failed; exit 41; }
  DEVMODE=mknod
  for node in 'null c 1 3' 'zero c 1 5' 'random c 1 8' 'urandom c 1 9'; do
    set -- $node
    if ! mknod "$TINY/dev/$1" "$2" "$3" "$4" 2>/dev/null; then
      : > "$TINY/dev/$1"
      mt --bind "/dev/$1" "$TINY/dev/$1" || { echo dev-node-$1=failed; exit 42; }
      DEVMODE=bind
    fi
  done
  echo dev-mode=$DEVMODE
  mt --bind /system "$PREFIX/hostshare" || { echo bind-system=failed; exit 43; }
  # namespace 内可见、宿主 /proc/1/mountinfo（全局视角）必须为零。
  NS_FILE=/proc/self/mountinfo
  HOST_FILE=/proc/1/mountinfo
  for mf in "$NS_FILE" "$HOST_FILE"; do
    [ -r "$mf" ] || { echo mountinfo-unreadable=$mf; exit 45; }
  done
  NSN=$(awk -v p="$PREFIX" 'index($5, p) == 1 { n++ } END { print n + 0 }' "$NS_FILE")
  echo ns-internal-mounts=$NSN
  LEAK=$(awk -v p="$PREFIX" 'index($5, p) == 1 { n++ } END { print n + 0 }' "$HOST_FILE")
  [ "$LEAK" = 0 ] || { echo host-mount-leak=$LEAK; exit 44; }
  echo host-mount-leak=0
  echo namespace-done
  exit 0
fi

# --- 父阶段：进 namespace、回收、复查 ---
mkdir -p "$TINY/dev"
echo probe-prefix=$PREFIX
echo selinux=$(getenforce)
NSRC=0
unshare -m "$0" in-ns "$PREFIX" || NSRC=$?
echo namespace-rc=$NSRC
[ "$NSRC" = 0 ] || exit "$NSRC"
# namespace 退出即卸载全部挂载；全局 mountinfo 复查必须零残留。
sleep 1
HOST_FILE=/proc/1/mountinfo
[ -r "$HOST_FILE" ] || { echo mountinfo-unreadable=$HOST_FILE; exit 51; }
LEAK=$(awk -v p="$PREFIX" 'index($5, p) == 1 { n++ } END { print n + 0 }' "$HOST_FILE")
[ "$LEAK" = 0 ] || { echo leak-after-exit=$LEAK; exit 50; }
echo after-exit-leak=0
# 只 rmdir 已知目录；任何目录非空即保留现场报告，绝不用 rm -rf。
rmdir "$TINY/dev" "$TINY" "$PREFIX/hostshare" || { echo cleanup-rmdir-failed; exit 52; }
[ -d "$PREFIX/cg2" ] && rmdir "$PREFIX/cg2" || true
if rmdir "$PREFIX" 2>/dev/null; then
  echo cleanup=clean
else
  echo cleanup=prefix-left=$PREFIX
  exit 54
fi
PROBE

echo "== 探针执行 =="
rc=0
lab_run_su_script "$INNER" probe-inner.sh || rc=$?
if (( rc != 0 )); then
  echo "FAIL: 探针退出码 $rc" >&2
  "$ADB" -s "$SERIAL" shell "'$SU' -c 'cat /proc/1/mountinfo'" | \
    grep -F 'dsh-su-lab-' >&2 || true
  exit "$rc"
fi

echo "== 探针通过 =="
