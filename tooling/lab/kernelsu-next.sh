#!/usr/bin/env bash
# 用法: tooling/lab/kernelsu-next.sh setup|patch|boot|wait|verify|status|stop|restore
#
# KernelSU Next LKM 实验台。方式来自 ymuuuu/KernelSU-Next-AVD（钉在 commit
# 88aa62b24c626ef5b799a4987cb6f7f4e3625095）：把 pristine ramdisk 推进运行中的
# 模拟器，用官方管理器 APK 内置的 ksud 执行 `boot-patch --ramdisk --allow-shell`，
# 拉回替换本 profile 的 ramdisk.img，冷启动后内核加载 android15-6.6 LKM。
#
# 固定目标（勿改）：
#   AVD      dsh-su-next-api36（ini 在 ~/dev/avd/，数据目录 ~/dev/avd/profiles/next.avd）
#   serial   emulator-5584
#   pristine ~/dev/avd/backups/20261102T194623Z/ramdisk.img
#            （只复制进本 profile；SDK 共享 ramdisk 与其他实例一律只读）
#
# 资源约定：与 lab_operator 协调，每实例 1.5GB / 2 cores，dsh-su-* 同时最多 4 台。
# 所有 adb 操作固定 -s emulator-5584；第三方脚本经 adb shim + ANDROID_SERIAL 收敛；
# 禁止物理设备、kill-server、操作其他模拟器。verify 不使用 adb root，
# root 证据只认 KernelSU 的 su（allow_shell=1 内核参数授权 shell）。
#
# 启动走 tooling/emulator-lab.sh（前台 exec），本脚本在后台拉起：
#   DSH_LAB_MEMORY / DSH_LAB_CORES 可覆盖（默认 1536 / 2）。
set -euo pipefail

PROJECT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=../env.sh
source "$PROJECT/tooling/env.sh"
: "${ANDROID_HOME:?请先加载 tooling/env.sh}"
: "${JAVA_HOME:?请先加载 tooling/env.sh}"

LAB_NAME=dsh-su-next-api36
LAB_PORT=5584
SERIAL=emulator-$LAB_PORT

AVD_HOME="${DSH_LAB_HOME:-$HOME/dev/avd}"
PROFILE_DIR="$AVD_HOME/profiles/next.avd"
AVD_INI="$AVD_HOME/$LAB_NAME.ini"
STATE_DIR="$AVD_HOME/lab-state"
CACHE_DIR="${DSH_KSU_CACHE:-$STATE_DIR/cache/ksu-next}"
PRISTINE="$AVD_HOME/backups/20261102T194623Z/ramdisk.img"
PRISTINE_SHA256=e1e798efba67b02c6813946283d94c6a33e55a30514865e1efe4b9b0c81fbac0

SYS_IMAGE_DIR="$ANDROID_HOME/system-images/android-36/default/x86_64"
SYS_IMAGE_PKG='system-images;android-36;default;x86_64'

KSUAVD_PIN=88aa62b24c626ef5b799a4987cb6f7f4e3625095
KSUAVD_URL="https://raw.githubusercontent.com/ymuuuu/KernelSU-Next-AVD/$KSUAVD_PIN/ksuAVD.sh"
KSU_VER=v3.4.2
APK_URL="https://github.com/KernelSU-Next/KernelSU-Next/releases/download/$KSU_VER/KernelSU_Next_v3.4.2_33355-release.apk"
KO_NAME=x86_64_android15-6.6_kernelsu.ko
KO_URL="https://github.com/KernelSU-Next/KernelSU-Next/releases/download/$KSU_VER/$KO_NAME"

ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
export ANDROID_AVD_HOME="$AVD_HOME"
MAX_INSTANCES=4

SHIM_DIR=
cleanup() { if [[ -n "$SHIM_DIR" ]]; then rm -rf "$SHIM_DIR"; fi; }
trap cleanup EXIT

log() { echo "[ksu-next] $*"; }
die() { echo "[ksu-next] 错误: $*" >&2; exit 1; }

sh() { "$ADB" -s "$SERIAL" shell "$@"; }          # 设备侧，返回设备退出码
dev_rc() { "$ADB" -s "$SERIAL" shell "$@" >/dev/null 2>&1; }

owned_device() {
  local name
  name=$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | tr -d '\r' | head -1)
  [[ "$name" == "$LAB_NAME" ]] || { echo "目标不是本实验台 AVD：$SERIAL → $name" >&2; return 1; }
  [[ $(sh getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "$SERIAL 不是 qemu 模拟器" >&2; return 1; }
}

# 把 shim 目录插到 PATH 最前，目录内 adb 强制 -s $SERIAL，
# 拒绝 kill-server/connect/usb 等；devices/version 只读放行。
make_adb_shim() {
  SHIM_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ksu-next-adb-shim.XXXXXX")
  cat >"$SHIM_DIR/adb" <<EOF
#!/usr/bin/env bash
set -e
REAL="$ADB"
case "\${1:-}" in
  devices|version|--help|-help|-h) exec "\$REAL" "\$@" ;;
  kill-server|connect|disconnect|usb|track-devices|track-jdwp)
    echo "shim: 禁止 adb \$1，本实验台只允许对 $SERIAL 的定向操作" >&2
    exit 2 ;;
esac
if [ "\${1:-}" = "-s" ]; then
  [ "\$2" = "$SERIAL" ] || { echo "shim: 只允许 $SERIAL，收到 \$2" >&2; exit 2; }
  exec "\$REAL" "\$@"
fi
exec "\$REAL" -s "$SERIAL" "\$@"
EOF
  chmod +x "$SHIM_DIR/adb"
}

# 资源协调：dsh-su-* 实例计数 + 可用内存 + 端口独占，超限/被占拒绝启动（每台 ~1.5GB）。
resource_guard() {
  local count avail port_owner
  count=$(pgrep -fc 'emulator.*-avd dsh-su-' 2>/dev/null || true)
  count=${count:-0}
  avail=$(free -m | awk '/^Mem:/{print $7}')
  log "运行中的 dsh-su-* 实例: $count / $MAX_INSTANCES，可用内存 ${avail}MB"
  [[ "$count" -lt "$MAX_INSTANCES" ]] || die "已有 $count 台 dsh-su-* 实例，达到协调上限 $MAX_INSTANCES，先与 lab_operator 协调"
  [[ "$avail" -ge 3000 ]] || die "可用内存 ${avail}MB 不足（需给本实例留 ≥1.5GB + 余量）"
  port_owner=$(pgrep -af 'qemu.*-port 5584' 2>/dev/null | grep -v "dsh-su-next-api36" || true)
  [[ -z "$port_owner" ]] || die "端口 $LAB_PORT 被其他实例占用，等它释放或与 lab_operator 协调: $port_owner"
}

download_once() {  # download_once <url> <dest> <期望用途描述>
  local url=$1 dest=$2 desc=$3
  if [[ -f "$dest" && -f "$dest.sha256" ]] && sha256sum -c "$dest.sha256" >/dev/null 2>&1; then
    log "缓存命中: $desc"
    return 0
  fi
  log "下载 $desc: $url"
  mkdir -p "$(dirname "$dest")"
  curl -fsSL --retry 2 -o "$dest" "$url"
  sha256sum "$dest" >"$dest.sha256"
}

ensure_cache() {
  download_once "$KSUAVD_URL" "$CACHE_DIR/ksuAVD.sh" "ksuAVD.sh @ $KSUAVD_PIN"
  download_once "$APK_URL" "$CACHE_DIR/Apps/KernelSU.apk" "KernelSU Next 管理器 $KSU_VER"
  # 管理器必须能供出 ksud，ksuAVD.sh 靠它做 boot-patch
  # （不用 grep -q：pipefail 下 grep 提前退出会让 unzip 吃 SIGPIPE 误判失败）
  if ! unzip -l "$CACHE_DIR/Apps/KernelSU.apk" | grep 'lib/x86_64/libksud.so' >/dev/null; then
    die "管理器 APK 里没有 lib/x86_64/libksud.so: $CACHE_DIR/Apps/KernelSU.apk"
  fi
  # 预检 ksud 是否内嵌本机 KMI 的 LKM；没有则准备独立 .ko 兜底
  if unzip -p "$CACHE_DIR/Apps/KernelSU.apk" lib/x86_64/libksud.so | strings \
      | grep 'android15-6.6_kernelsu.ko' >/dev/null; then
    log "ksud 内嵌 android15-6.6 LKM，无需独立 .ko"
    rm -f "$CACHE_DIR/$KO_NAME" "$CACHE_DIR/$KO_NAME.sha256"
  else
    log "ksud 未内嵌 android15-6.6 LKM，下载独立 .ko 兜底"
    download_once "$KO_URL" "$CACHE_DIR/$KO_NAME" "独立 LKM $KO_NAME"
  fi
}

ensure_profile() {
  [[ -d "$SYS_IMAGE_DIR" ]] || die "缺系统镜像: $SYS_IMAGE_DIR（先跑 tooling/emulator-lab.sh setup）"
  [[ -f "$PRISTINE" ]] || die "缺 pristine ramdisk: $PRISTINE"
  local got
  got=$(sha256sum "$PRISTINE" | awk '{print $1}')
  [[ "$got" == "$PRISTINE_SHA256" ]] || die "pristine ramdisk 哈希不符（应是原版 AOSP，不是 Magisk 版）: $got"
  if [[ ! -f "$AVD_INI" ]]; then
    log "创建 AVD $LAB_NAME（数据目录 $PROFILE_DIR）"
    echo no | avdmanager create avd --name "$LAB_NAME" --package "$SYS_IMAGE_PKG" --device pixel_7
    mkdir -p "$AVD_HOME/profiles"
    mv "$AVD_HOME/$LAB_NAME.avd" "$PROFILE_DIR"
    sed -i "s|^path=.*|path=$PROFILE_DIR|" "$AVD_INI"
  fi
  grep -q "^path=$PROFILE_DIR$" "$AVD_INI" || die "$AVD_INI 的 path 不是 $PROFILE_DIR"
}

# setup：确保 AVD/profile 存在，ramdisk 为 pristine 副本，缓存就绪。
cmd_setup() {
  ensure_profile
  if [[ -f "$PROFILE_DIR/ramdisk.img.backup" ]]; then
    log "profile 已打补丁（存在 ramdisk.img.backup），跳过 pristine 覆盖；要重来先 restore"
  else
    log "复制 pristine ramdisk → $PROFILE_DIR/ramdisk.img"
    cp -f "$PRISTINE" "$PROFILE_DIR/ramdisk.img"
  fi
  ensure_cache
  "$EMULATOR" -accel-check >/dev/null 2>&1 || "$EMULATOR" -accel-check
  log "setup 完成"
}

# 后台冷启动本 AVD（走 emulator-lab.sh，-ramdisk 显式钉到本 profile 的镜像）。
start_bg() {
  resource_guard
  [[ -f "$AVD_INI" ]] || die "AVD 未创建，先 setup"
  : >"$STATE_DIR/next-api36.emulator.log"
  DSH_LAB_NAME=$LAB_NAME DSH_LAB_PORT=$LAB_PORT \
  DSH_LAB_MEMORY="${DSH_LAB_MEMORY:-1536}" DSH_LAB_CORES="${DSH_LAB_CORES:-2}" \
  DSH_LAB_RAMDISK="$PROFILE_DIR/ramdisk.img" \
    setsid bash "$PROJECT/tooling/emulator-lab.sh" start >>"$STATE_DIR/next-api36.emulator.log" 2>&1 &
  echo $! >"$STATE_DIR/next-api36.pid"
  log "已后台拉起（pid $(cat "$STATE_DIR/next-api36.pid")，日志 $STATE_DIR/next-api36.emulator.log）"
}

wait_boot() {
  local deadline=$(( $(date +%s) + 300 ))
  while (( $(date +%s) < deadline )); do
    if [[ $(sh getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && owned_device 2>/dev/null; then
      log "$SERIAL 冷启动完成，归属校验通过（$LAB_NAME, qemu=1）"
      return 0
    fi
    sleep 3
  done
  die "$SERIAL 300s 内未完成冷启动，看 $STATE_DIR/next-api36.emulator.log 与 $AVD_HOME/lab-state/emulator.log"
}

cmd_boot() {
  if [[ $(sh getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    owned_device
    log "已在运行，跳过启动"
    return 0
  fi
  [[ -f "$PROFILE_DIR/ramdisk.img.backup" ]] || log "警告: profile ramdisk 未打补丁（无 backup），启动的是 pristine"
  start_bg
  wait_boot
}

cmd_wait() { wait_boot; }

# patch：冷启动 pristine → 机内 boot-patch → 拉回替换 → 装管理器 → 停机。
cmd_patch() {
  ensure_profile
  ensure_cache
  if [[ -f "$PROFILE_DIR/ramdisk.img.backup" ]]; then
    die "profile 已打过补丁（存在 ramdisk.img.backup）；要重打先 restore"
  fi
  local got
  got=$(sha256sum "$PROFILE_DIR/ramdisk.img" | awk '{print $1}')
  [[ "$got" == "$PRISTINE_SHA256" ]] || die "profile ramdisk 不是 pristine 副本，拒绝在此状态打补丁"

  start_bg
  wait_boot

  log "交由 ksuAVD.sh（commit $KSUAVD_PIN）在机内打补丁"
  make_adb_shim
  ( export PATH="$SHIM_DIR:$PATH" ANDROID_SERIAL="$SERIAL"
    bash "$CACHE_DIR/ksuAVD.sh" "$PROFILE_DIR/ramdisk.img" )

  local patched backup
  backup="$PROFILE_DIR/ramdisk.img.backup"
  [[ -f "$backup" ]] || die "补丁后未见备份 $backup"
  [[ $(sha256sum "$backup" | awk '{print $1}') == "$PRISTINE_SHA256" ]] || die "备份与 pristine 不符"
  patched=$(sha256sum "$PROFILE_DIR/ramdisk.img" | awk '{print $1}')
  [[ "$patched" != "$PRISTINE_SHA256" ]] || die "补丁后 ramdisk 与 pristine 相同，boot-patch 未生效"
  log "补丁完成: pristine 已存为 $backup，新 ramdisk sha256=$patched"
  log "停机，之后用 boot 冷启动验证"
  cmd_stop
}

# verify：不改任何状态，只取证据。不用 adb root；root 证据只认 KernelSU 的
# su（SELinux context u:r:ksu:s0）。注意本镜像 /system/xbin/su 是 AOSP 自带的
# toybox setuid su（无 -c、无 ksu context），不能当 KernelSU 证据。
cmd_verify() {
  [[ $(sh getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] || die "未启动或未完成开机，先 boot"
  owned_device

  # ksud 尚未就绪时启动管理器：内核按签名放行管理器，由它安装 ksud 并挂出 /system/bin/su
  if ! sh 'ls /system/bin/su' >/dev/null 2>&1; then
    log "/system/bin/su 未就绪，启动管理器触发 ksud 安装…"
    sh 'monkey -p com.rifsxd.ksunext -c android.intent.category.LAUNCHER 1' >/dev/null || true
    local i
    for i in $(seq 1 20); do
      sh 'ls /system/bin/su' >/dev/null 2>&1 && break
      sleep 3
    done
    sh 'ls /system/bin/su' >/dev/null 2>&1 || die "管理器未能建立 /system/bin/su，检查 logcat | grep -i kernelsu"
  fi

  PASS=0; FAIL=0
  check() {   # 关键项：失败计入 FAIL
    local label=$1; shift
    local out rc=0
    out=$(sh "$@" 2>&1) || rc=$?
    if [[ $rc -eq 0 ]]; then
      PASS=$((PASS+1)); printf -- '---- [PASS] %s\n%s\n' "$label" "$out"
    else
      FAIL=$((FAIL+1)); printf -- '---- [FAIL] %s (rc=%s)\n%s\n' "$label" "$rc" "$out"
    fi
  }
  check_out() {  # 关键项 + 输出断言（每个子串都命中才算过，子串用换行分隔）
    local label=$1 pattern=$2; shift 2
    local out rc=0 p bad=0
    out=$(sh "$@" 2>&1) || rc=$?
    while IFS= read -r p; do
      [[ -z "$p" ]] && continue
      grep -qF -- "$p" <<<"$out" || { bad=1; break; }
    done <<<"$pattern"
    if [[ $rc -eq 0 && $bad -eq 0 ]]; then
      PASS=$((PASS+1)); printf -- '---- [PASS] %s\n%s\n' "$label" "$out"
    else
      FAIL=$((FAIL+1)); printf -- '---- [FAIL] %s (rc=%s)\n%s\n' "$label" "$rc" "$out"
    fi
  }
  check_deny() {  # 关键项：期望被拒绝（rc≠0 或输出含 denied）
    local label=$1; shift
    local out rc=0
    out=$(sh "$@" 2>&1) || rc=$?
    if [[ $rc -ne 0 ]] || grep -qiE 'denied|not allowed' <<<"$out"; then
      PASS=$((PASS+1)); printf -- '---- [PASS] %s\n%s\n' "$label" "$out"
    else
      FAIL=$((FAIL+1)); printf -- '---- [FAIL] %s（未被拒绝, rc=%s）\n%s\n' "$label" "$rc" "$out"
    fi
  }
  note() {    # 记录项：只留证据
    local label=$1; shift
    printf -- '---- [记录] %s\n%s\n' "$label" "$(sh "$@" 2>&1 || true)"
  }

  echo "================ E0 目标身份（每次全验） ================"
  note "qemu / AVD 归属"     "getprop ro.kernel.qemu"
  note "系统"                 "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi"
  note "内核 / 页大小"         "uname -r; getconf PAGESIZE"

  echo "================ E1 KernelSU Next 加载与版本 ================"
  check "管理器已安装"                       "pm list packages | grep -w com.rifsxd.ksunext"
  check_out "LKM 在 /proc/modules 标记 Live" "Live"  "grep -w kernelsu /proc/modules"
  check_out "内核日志: 模块加载与 allow_shell" $'load kernelsu with params\nwelcome to KernelSU' "logcat -d -s ksuinit KernelSU 2>/dev/null | grep -E 'load kernelsu with params|welcome to KernelSU' | head -4"
  check_out "su 归属 ksud(/system/bin/su)"   "root"  "ls -la /system/bin/su"
  check    "ksud 版本"                       "su -c /data/adb/ksud --version"

  echo "================ E2 标准 su -c（KernelSU 判别依据） ================"
  check_out "adbd 保持 shell(uid=2000)" "uid=2000" "id"
  check_out "su 路径优先命中 /system/bin/su" "/system/bin/su" "command -v su"
  check_out "su -c id → uid=0 且 context=u:r:ksu:s0" $'uid=0(root)\nu:r:ksu:s0' "su -c id"
  check_out "su -c 执行 root 命令" $'0\nroot\ninit' "su -c 'id -u; id -un; head -1 /proc/1/comm'"

  echo "================ E3 App 授权（授权模型证据） ================"
  check_deny "未授权 app uid 请求 root（降权后二次 su）"  "su 10234 -c 'su -c id'"
  note "对照: 已授权 shell 降权到 system uid（授权方的合法行为）" "su 1000 -c id"
  note "对照: 镜像自带 toybox setuid su（非 KernelSU，仅证明 stock 路径存在）" "/system/xbin/su 0 id; true"
  note "管理器版本"                            "dumpsys package com.rifsxd.ksunext | grep -E 'versionName|firstInstallTime'"

  echo "================ E4 namespace / cgroup 条件（只读探针） ================"
  note "mountinfo 行数"                       "su -c 'wc -l < /proc/self/mountinfo'"
  note "shared 传播挂载数（事故关注点）"        "su -c 'grep -c shared: /proc/self/mountinfo'"
  note "/data 传播字段"                        "su -c 'grep \" /data \" /proc/self/mountinfo'"
  note "cgroup 版本"                           "su -c '/data/adb/ksu/bin/busybox stat -f -c %T /sys/fs/cgroup'"
  note "cgroup 控制器 / 层级"                  "su -c 'cat /sys/fs/cgroup/cgroup.controllers; cat /proc/self/cgroup'"
  note "busybox mount 是否支持 --make-rprivate（0 = 不支持，启动器需小助手）" \
    "su -c '/data/adb/ksu/bin/busybox mount --help 2>&1 | grep -c make-rprivate'"
  note "瞬时 mount namespace 探针（子进程即退，先 make-rprivate）" \
    "su -c '/data/adb/ksu/bin/busybox unshare -m sh -c \"mount --make-rprivate / && grep -c shared: /proc/self/mountinfo; id\"'"
  note "user namespace 可用性"                 "su -c '/data/adb/ksu/bin/busybox unshare -Ur id'"

  echo "================ E5 adbd root 不冒充 ================"
  note "未执行 adb root（service.adb.root 应为空/false；uid 证据见 E2 第一条）" "getprop service.adb.root"

  echo "================ 汇总 ================"
  echo "关键项: PASS=$PASS FAIL=$FAIL"
  [[ $FAIL -eq 0 ]] || die "有 $FAIL 项关键检查未通过，见上方 [FAIL]"
  log "verify 全部通过"
}

cmd_status() {
  if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    owned_device || true
    local boot
    boot=$(sh getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    echo "状态: 运行中 boot_completed=$boot"
    sh id
    sh 'su -c id' 2>&1 || echo "(su 不可用或未授权)"
  else
    echo "状态: 未运行"
  fi
  echo "实例: $LAB_NAME @ $SERIAL, profile=$PROFILE_DIR"
  echo "补丁: $( [[ -f "$PROFILE_DIR/ramdisk.img.backup" ]] && echo 已打 || echo 未打 )"
  ps -eo args 2>/dev/null | grep -E 'emulator(64)? .*-avd dsh-su-' | grep -v grep || true
}

cmd_stop() {
  owned_device || { log "目标不归本脚本管或未运行，拒绝 stop"; exit 2; }
  "$ADB" -s "$SERIAL" emu kill
  local i
  for i in $(seq 1 15); do
    "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || { log "$SERIAL 已停止"; return 0; }
    sleep 2
  done
  die "$SERIAL 停止超时，请人工检查进程"
}

cmd_restore() {
  local backup="$PROFILE_DIR/ramdisk.img.backup"
  [[ -f "$backup" ]] || die "没有备份可恢复: $backup"
  if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    cmd_stop
  fi
  cp -f "$backup" "$PROFILE_DIR/ramdisk.img"
  local got
  got=$(sha256sum "$PROFILE_DIR/ramdisk.img" | awk '{print $1}')
  [[ "$got" == "$PRISTINE_SHA256" ]] || die "恢复后哈希不符: $got"
  log "已恢复 pristine（管理器 App 仍安装在 userdata，如需移除: adb -s $SERIAL uninstall com.rifsxd.ksunext）"
}

case "${1:-}" in
  setup)   cmd_setup ;;
  patch)   cmd_patch ;;
  boot)    cmd_boot ;;
  wait)    cmd_wait ;;
  verify)  cmd_verify ;;
  status)  cmd_status ;;
  stop)    cmd_stop ;;
  restore) cmd_restore ;;
  *) sed -n '2,22p' "$0"; exit 2 ;;
esac
