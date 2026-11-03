#!/usr/bin/env bash
# 用法：tooling/emulator-lab.sh setup|sync-config|start [snapshot]|wait|status|stop|snapshot save|list|delete <name>|probe
# 专用 root AVD；DSH_LAB_HOME、DSH_LAB_PORT 可覆盖默认目录和端口。
# 默认资源：2560MB（API36 平台下限，见下方 LAB_MEMORY_FLOOR）、2 核、720p、headless；
# start 经 systemd --user transient unit dsh-su-lab-emulator 托管，工具会话退出不影响实例。
set -euo pipefail
PROJECT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck disable=SC1091
source "$PROJECT/tooling/lab/common.sh"
lab_load_env
ADB="$LAB_ADB"
SERIAL="$LAB_SERIAL"
EMULATOR="$LAB_EMULATOR"
STATE_DIR="$LAB_STATE_DIR"
LAB_MEMORY=${DSH_LAB_MEMORY:-2560}
LAB_CORES=${DSH_LAB_CORES:-2}
# API36 镜像有 emulator 侧最低 RAM：低于 2560MB 启动时会被自动提升（ Increasing RAM size to 2560MB）。
LAB_MEMORY_FLOOR=2560

LAB_UNIT=dsh-su-lab-emulator

lab_unit_active() {
  [[ $(systemctl --user is-active "$LAB_UNIT" 2>/dev/null) == active ]]
}

owned_device() {
  lab_owned_device "$SERIAL"
}

sync_config() {
  local cfg="$ANDROID_AVD_HOME/$LAB_NAME.avd/config.ini"
  [[ -f "$cfg" ]] || return 0
  local eff=$(( LAB_MEMORY < LAB_MEMORY_FLOOR ? LAB_MEMORY_FLOOR : LAB_MEMORY ))
  sed -i "s/^hw\.ramSize.*/hw.ramSize = ${eff}/" "$cfg"
  sed -i "s/^hw\.cpu\.ncore.*/hw.cpu.ncore = ${LAB_CORES}/" "$cfg"
  echo "config.ini 已同步：hw.ramSize=$eff hw.cpu.ncore=$LAB_CORES"
}

case "${1:-status}" in
  setup)
    sdkmanager --sdk_root="$ANDROID_HOME" emulator 'system-images;android-36;default;x86_64'
    if [[ ! -f "$ANDROID_AVD_HOME/$LAB_NAME.ini" ]]; then
      echo no | avdmanager create avd --name "$LAB_NAME" --package 'system-images;android-36;default;x86_64' --device pixel_7
    fi
    sync_config
    "$EMULATOR" -accel-check
    ;;
  sync-config)
    sync_config
    ;;
  start)
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then owned_device; echo "$SERIAL 已运行"; exit 0; fi
    if lab_unit_active; then
      echo "unit $LAB_UNIT active 但 $SERIAL 不在线，等待或查看 $STATE_DIR/emulator.log；重启请先 systemctl --user stop $LAB_UNIT" >&2
      exit 1
    fi
    test -f "$ANDROID_AVD_HOME/$LAB_NAME.ini"
    # 资源守卫：生效内存（不低于平台下限）+ 512MB 运行余量，不挤占用户进程。
    eff_mem=$(( LAB_MEMORY < LAB_MEMORY_FLOOR ? LAB_MEMORY_FLOOR : LAB_MEMORY ))
    lab_assert_memory "$((eff_mem + 512))"
    sync_config
    lab_options=(-memory "$LAB_MEMORY" -cores "$LAB_CORES")
    if [[ -n "${DSH_LAB_RAMDISK:-}" ]]; then lab_options+=(-ramdisk "$DSH_LAB_RAMDISK"); fi
    if [[ -n "${DSH_LAB_KERNEL:-}" ]]; then lab_options+=(-kernel "$DSH_LAB_KERNEL"); fi
    if [[ -n "${2:-}" ]]; then
      echo "从快照启动：$2"
      snap_args=(-snapshot "$2")
    else
      snap_args=(-no-snapshot-load)
    fi
    # systemd --user transient unit 持有 emulator：调用会话退出后实例仍在。
    # 只管本项目 unit，不碰其他进程。
    systemd-run --user --unit="$LAB_UNIT" \
      -p Description="dsh-su emulator lab ($LAB_NAME)" \
      -p StandardOutput="append:$STATE_DIR/emulator.log" \
      -p StandardError="append:$STATE_DIR/emulator.log" \
      --setenv=ANDROID_AVD_HOME="$ANDROID_AVD_HOME" \
      --setenv=ANDROID_SDK_ROOT="$ANDROID_HOME" \
      --setenv=ANDROID_HOME="$ANDROID_HOME" \
      --setenv=HOME="$HOME" \
      --setenv=PATH="$PATH" \
      -- "$EMULATOR" -avd "$LAB_NAME" -port "$LAB_PORT" -no-window -no-audio -no-boot-anim -gpu swiftshader -skin 720x1280 -dpi-device 240 "${lab_options[@]}" "${snap_args[@]}" -writable-system
    ;;
  wait)
    boot_deadline=$(( $(date +%s) + 300 ))
    while (( $(date +%s) < boot_deadline )); do
      if [[ $("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
        owned_device
        "$ADB" -s "$SERIAL" root >/dev/null
        echo "uid: $("$ADB" -s "$SERIAL" shell id | tr -d '\r')"
        echo "android: $("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
        echo "magisk-su: $("$ADB" -s "$SERIAL" shell "'${DSH_SU_PATH:-/debug_ramdisk/su}' -c id" | tr -d '\r')"
        echo "模拟器就绪：$SERIAL"
        exit 0
      fi
      sleep 2
    done
    echo "模拟器未在启动期限内就绪，查看 $STATE_DIR/emulator.log" >&2
    exit 1
    ;;
  status)
    echo "WSL 可用内存：$(lab_mem_available_mb)MB；Windows 可用：$(lab_windows_free_mb || echo unknown)MB；qemu RSS 合计：$(lab_qemu_rss_total_mb)MB"
    echo "unit $LAB_UNIT：$(systemctl --user is-active "$LAB_UNIT" 2>/dev/null)"
    "$EMULATOR" -accel-check 2>&1 | head -3 || true
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
      owned_device
      echo "状态：运行中"
      "$ADB" -s "$SERIAL" shell "'${DSH_SU_PATH:-/debug_ramdisk/su}' -c id" | tr -d '\r' || true
    else
      echo "状态：停止"
    fi
    ;;
  stop)
    if owned_device; then
      "$ADB" -s "$SERIAL" emu kill
    fi
    # emu kill 后 emulator 退出会连带 unit 转为 inactive；兜底停本项目 unit。
    for _ in $(seq 1 15); do
      lab_unit_active || { echo "unit $LAB_UNIT 已退出"; exit 0; }
      sleep 2
    done
    systemctl --user stop "$LAB_UNIT"
    echo "unit $LAB_UNIT 已强制停止"
    ;;
  snapshot)
    op=${2:?用法：snapshot save|list|delete <name>}
    owned_device
    case "$op" in
      list) "$ADB" -s "$SERIAL" emu avd snapshot list | tr -d '\r' ;;
      save) "$ADB" -s "$SERIAL" emu avd snapshot save "${3:?快照名}" | tr -d '\r' ;;
      delete) "$ADB" -s "$SERIAL" emu avd snapshot delete "${3:?快照名}" | tr -d '\r' ;;
      *) echo "未知快照操作：$op" >&2; exit 2 ;;
    esac
    ;;
  probe)
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
      exec bash "$PROJECT/tooling/lab/probe-capabilities.sh"
    else
      echo "模拟器未运行，先 start/wait" >&2
      exit 1
    fi
    ;;
  *) echo "用法：$0 setup|sync-config|start [snapshot]|wait|status|stop|snapshot save|list|delete <name>|probe" >&2; exit 2 ;;
esac
