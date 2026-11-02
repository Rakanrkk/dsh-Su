#!/usr/bin/env bash
# 用法：tooling/lab/kernelsu.sh setup|patch|start|wait|verify|status|stop
# KernelSU 独立 AVD 实验室（默认 dsh-su-kernelsu-api36），与 root/Magisk 实验室完全隔离：
#   - ramdisk 一律从 pristine 备份复制到私有 profile 后补丁，绝不改 SDK 共享镜像
#   - 补丁流程复用 HChenX/KernelSU-AVD 官方脚本，ksud 来自 tiann/KernelSU 官方 APK
#   - 所有 adb 调用强制 -s "$SERIAL" 且 ANDROID_SERIAL 同步；owned_device 校验
#     ro.kernel.qemu=1 且 emu avd name 匹配；不 kill-server、不触碰其他设备
#   - 资源预算：四实例共用主机，单实例默认 1536MB / 2 核（DSH_LAB_MEMORY/CORES 可覆盖）
# 环境变量：DSH_LAB_NAME/PORT/MEMORY/CORES/RAMDISK/KERNEL、DSH_LAB_PROFILE、
#   DSH_LAB_PRISTINE、DSH_LAB_KSUAVD、DSH_LAB_KSU_TAG；需先 source tooling/env.sh
# 证据落盘：$DSH_LAB_PROFILE/evidence/（verify 日志、manager 截图、ksu.db）
set -euo pipefail

PROJECT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
if [[ -f "$PROJECT/tooling/env.sh" ]]; then source "$PROJECT/tooling/env.sh"; fi
: "${ANDROID_HOME:?请先设置 ANDROID_HOME 或加载 tooling/env.sh}"

export ANDROID_AVD_HOME="${DSH_LAB_AVD_HOME:-$HOME/dev/avd/profiles}"
LAB_NAME=${DSH_LAB_NAME:-dsh-su-kernelsu-api36}
[[ "$LAB_NAME" == dsh-su-* ]] || { echo "AVD 名称须以 dsh-su- 开头" >&2; exit 2; }
LAB_PORT=${DSH_LAB_PORT:-5582}          # adb serial 固定为 emulator-5582（-port 即 adb 端口）
PROFILE_DIR=${DSH_LAB_PROFILE:-$HOME/dev/avd/profiles/kernelsu}
PRISTINE=${DSH_LAB_PRISTINE:-$HOME/dev/avd/backups/20261102T194623Z/ramdisk.img}
KSUAVD=${DSH_LAB_KSUAVD:-$HOME/dev/KernelSU-AVD/ksuAVD.sh}
KSU_TAG=${DSH_LAB_KSU_TAG:-v3.3.1}
APK_NAME=${DSH_LAB_APK_NAME:-KernelSU_${KSU_TAG}_32680-release.apk}
APK_URL=${DSH_LAB_APK_URL:-https://github.com/tiann/KernelSU/releases/download/${KSU_TAG}/${APK_NAME}}
APK="$PROFILE_DIR/$APK_NAME"
WORK_RAMDISK="$PROFILE_DIR/ramdisk-work.img"
PATCHED="$PROFILE_DIR/ramdisk-kernelsu.img"
PRISTINE_COPY="$PROFILE_DIR/ramdisk-pristine.img"
STATE_DIR="$HOME/dev/avd/lab-state"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
SERIAL=""
SHIM_DIR=""

mkdir -p "$PROFILE_DIR" "$STATE_DIR"

# ---- adb 唯一入口：serial 强制 + ANDROID_SERIAL 双重绑定 ----
adb_s() { ANDROID_SERIAL="$SERIAL" "$ADB" -s "$SERIAL" "$@"; }

# console 端口 P 的 adb 串口是 emulator-P 或 emulator-(P+1)，按 AVD 名唯一匹配
resolve_serial() {
  local cand found=""
  for cand in "emulator-$LAB_PORT" "emulator-$((LAB_PORT + 1))"; do
    ANDROID_SERIAL="$cand" "$ADB" -s "$cand" get-state >/dev/null 2>&1 || continue
    if [[ $(ANDROID_SERIAL="$cand" "$ADB" -s "$cand" emu avd name 2>/dev/null | tr -d '\r' | head -1) == "$LAB_NAME" ]]; then
      found="$cand"; break
    fi
  done
  [[ -n "$found" ]] || return 1
  SERIAL="$found"
}

owned_device() {
  resolve_serial || { echo "未找到运行中的 $LAB_NAME（端口 $LAB_PORT）" >&2; return 1; }
  [[ $(adb_s shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] \
    || { echo "$SERIAL 不是 qemu 模拟器" >&2; return 1; }
}

# ksuAVD.sh 使用裸 adb，套一层注入 -s 的串行 shim，ANDROID_SERIAL 一并导出
make_adb_shim() {
  SHIM_DIR=$(mktemp -d "$STATE_DIR/adb-shim.XXXXXX")
  cat >"$SHIM_DIR/adb" <<EOF
#!/usr/bin/env bash
exec "$ADB" -s "$SERIAL" "\$@"
EOF
  chmod +x "$SHIM_DIR/adb"
}

case "${1:-status}" in
  setup)
    [[ -f "$PRISTINE" ]] || { echo "缺 pristine 备份：$PRISTINE" >&2; exit 1; }
    resolve_serial && { echo "$LAB_NAME 正在运行，setup 前请 stop" >&2; exit 1; }
    # 只重建本实验室 AVD（私有 profiles 目录内），不触碰其他实例
    rm -rf "$ANDROID_AVD_HOME/$LAB_NAME.avd" "$ANDROID_AVD_HOME/$LAB_NAME.ini"
    echo no | avdmanager create avd --name "$LAB_NAME" \
      --package 'system-images;android-36;default;x86_64' --device pixel_7
    [[ -f "$PRISTINE_COPY" ]] || cp -f "$PRISTINE" "$PRISTINE_COPY"
    if [[ ! -f "$APK" ]]; then
      echo "下载官方 KernelSU APK：$APK_URL"
      curl -fL --retry 3 -o "$APK" "$APK_URL"
    fi
    sha256sum "$PRISTINE" "$PRISTINE_COPY" "$APK"
    "$EMULATOR" -accel-check
    echo "setup 完成：$ANDROID_AVD_HOME/$LAB_NAME.ini，镜像目录 $PROFILE_DIR"
    ;;

  patch)
    # 前置：以 pristine ramdisk 启动的实例（DSH_LAB_RAMDISK=$PRISTINE_COPY start）
    test -f "$APK" || { echo "缺官方 APK，先 setup" >&2; exit 1; }
    test -f "$PRISTINE" || { echo "缺 pristine 备份：$PRISTINE" >&2; exit 1; }
    owned_device
    mkdir -p "$HOME/dev/KernelSU-AVD/Apps"
    cp -f "$APK" "$HOME/dev/KernelSU-AVD/Apps/KernelSU.apk"   # 官方脚本按此路径取 APK
    cp -f "$PRISTINE" "$WORK_RAMDISK"
    resolve_serial
    make_adb_shim
    set +e
    PATH="$SHIM_DIR:$PATH" ANDROID_SERIAL="$SERIAL" bash "$KSUAVD" "$WORK_RAMDISK" 2>&1 | tee "$STATE_DIR/patch-$LAB_NAME.log"
    rc=${PIPESTATUS[0]}
    set -e
    rm -rf "$SHIM_DIR"; SHIM_DIR=""
    [[ $rc -eq 0 ]] || { echo "补丁失败，见 $STATE_DIR/patch-$LAB_NAME.log" >&2; exit "$rc"; }
    grep -q "android15-6.6" "$STATE_DIR/patch-$LAB_NAME.log" \
      || { echo "patch 日志未见目标 KMI android15-6.6，人工核对" >&2; exit 3; }
    rm -f "$WORK_RAMDISK.backup"
    mv -f "$WORK_RAMDISK" "$PATCHED"
    sha256sum "$PRISTINE" "$PATCHED"
    echo "补丁完成：$PATCHED；manager 已随官方脚本装入设备"
    ;;

  start)
    RAMDISK_OPT=${DSH_LAB_RAMDISK:-$PATCHED}
    if [[ ! -f "$RAMDISK_OPT" ]]; then
      echo "缺 ramdisk：$RAMDISK_OPT（补丁前请 DSH_LAB_RAMDISK=$PRISTINE_COPY start）" >&2
      exit 1
    fi
    if resolve_serial; then owned_device; echo "$SERIAL 已在运行"; exit 0; fi
    ADB_PORT=$((LAB_PORT + 1))
    if ss -tln | awk '{print $4}' | grep -qE ":($LAB_PORT|$ADB_PORT)$"; then
      echo "端口 $LAB_PORT/$ADB_PORT 被占用，不抢占" >&2; exit 1
    fi
    lab_options=(-memory "${DSH_LAB_MEMORY:-1536}" -cores "${DSH_LAB_CORES:-2}" -ramdisk "$RAMDISK_OPT")
    if [[ -n "${DSH_LAB_KERNEL:-}" ]]; then lab_options+=(-kernel "$DSH_LAB_KERNEL"); fi
    # 前台保留进程：exec 后本 shell 即模拟器；调用方可用 nohup/& 后台化
    exec "$EMULATOR" -avd "$LAB_NAME" -port "$LAB_PORT" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader -skin 720x1280 -dpi-device 240 "${lab_options[@]}" \
      -no-snapshot-load -no-snapshot-save -writable-system \
      >"$STATE_DIR/emulator-$LAB_NAME.log" 2>&1 </dev/null
    ;;

  wait)
    deadline=$(( $(date +%s) + 300 ))
    while (( $(date +%s) < deadline )); do
      if resolve_serial && [[ $(adb_s shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
        owned_device
        echo "serial: $SERIAL"
        echo -n "uptime: "; adb_s shell cat /proc/uptime
        adb_s shell getprop ro.build.version.release
        adb_s shell getprop ro.build.version.sdk
        adb_s shell uname -r
        adb_s shell id
        exit 0
      fi
      sleep 3
    done
    echo "未在期限内就绪，查看 $STATE_DIR/emulator-$LAB_NAME.log" >&2
    exit 1
    ;;

  verify)
    owned_device
    EVID="$PROFILE_DIR/evidence"
    mkdir -p "$EVID"
    STAMP=$(date -u +%Y%m%dT%H%M%SZ)
    {
      echo "== $STAMP serial=$SERIAL avd=$LAB_NAME =="
      echo "-- qemu/AVD 匹配 --"
      echo -n "ro.kernel.qemu="; adb_s shell getprop ro.kernel.qemu
      adb_s emu avd name
      echo "-- 版本 --"
      echo -n "release="; adb_s shell getprop ro.build.version.release
      echo -n "sdk="; adb_s shell getprop ro.build.version.sdk
      echo -n "kernel="; adb_s shell uname -r
      echo "-- shell/adbd 身份（对照用，不算 KernelSU 证据）--"
      adb_s shell id
      echo "-- KernelSU su --"
      adb_s shell 'command -v su; ls -l /system/bin/su 2>/dev/null'
      adb_s shell 'su -c id'
      echo "-- ksud / framework --"
      adb_s shell 'su -c "ls /data/adb/ksu 2>/dev/null"'
      adb_s shell 'su -c "/data/adb/ksu/bin/ksud -V 2>/dev/null || ksud -V 2>/dev/null"'
      adb_s shell 'su -c "pgrep -l ksud" '
      echo "-- 内核模块 --"
      adb_s shell 'su -c "grep -i ksu /proc/modules; ls /sys/module | grep -i ksu"'
      echo "-- namespace / cgroup --"
      adb_s shell 'su -c "ls /proc/self/ns | head; echo mnt-init=$(readlink /proc/1/ns/mnt); echo mnt-self=$(readlink /proc/self/ns/mnt)"'
      adb_s shell 'su -c "cat /proc/self/cgroup; stat -fc %T /sys/fs/cgroup"'
      echo "-- manager app --"
      adb_s shell pm path me.weishu.kernelsu
      adb_s shell dumpsys package me.weishu.kernelsu | grep -m1 'userId=' || true
      echo "-- uptime（冷启动佐证）--"
      adb_s shell cat /proc/uptime
    } | tee "$EVID/verify-$STAMP.log"
    # 允许列表数据库（App 授权证据，存在才拉）
    adb_s shell 'su -c "cp /data/adb/ksu.db /data/local/tmp/ksu.db 2>/dev/null; chmod 644 /data/local/tmp/ksu.db"' || true
    adb_s pull /data/local/tmp/ksu.db "$EVID/ksu.db" >/dev/null 2>&1 || true
    adb_s shell 'rm -f /data/local/tmp/ksu.db' >/dev/null 2>&1 || true
    # manager 真机运行截图（App 授权 Working 证据）
    adb_s shell monkey -p me.weishu.kernelsu -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 8
    adb_s exec-out screencap -p >"$EVID/manager-working-$STAMP.png"
    adb_s shell am force-stop me.weishu.kernelsu >/dev/null 2>&1 || true
    echo "证据目录：$EVID"
    ;;

  status)
    "$EMULATOR" -accel-check || true
    if owned_device; then
      echo "$LAB_NAME: $SERIAL 运行中"
      adb_s shell uname -r
    else
      echo "$LAB_NAME: 未运行（端口 $LAB_PORT）"
    fi
    ls -la "$PROFILE_DIR"
    ;;

  stop)
    owned_device
    adb_s emu kill
    echo "$LAB_NAME 已停止"
    ;;

  *)
    sed -n '2,12p' "$0"
    exit 2
    ;;
esac
