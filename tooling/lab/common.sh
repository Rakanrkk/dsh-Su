#!/usr/bin/env bash
# dsh 模拟器实验室共享函数：环境加载、资源守卫、ADB 安全锁定、mountinfo 字段解析。
# 由 lab/ 下脚本 source，不直接执行。

LAB_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB_PROJECT="$(cd "$LAB_COMMON_DIR/../.." && pwd)"

lab_load_env() {
  if [[ -f "$LAB_PROJECT/tooling/env.sh" ]]; then
    # shellcheck disable=SC1091
    source "$LAB_PROJECT/tooling/env.sh"
  fi
  : "${ANDROID_HOME:?请先设置 ANDROID_HOME 或存在 tooling/env.sh}"
  export ANDROID_AVD_HOME="${DSH_LAB_HOME:-$HOME/dev/avd}"
  LAB_NAME=${DSH_LAB_NAME:-dsh-su-root-api36}
  [[ "$LAB_NAME" == dsh-su-* ]] || { echo "AVD 名称须以 dsh-su- 开头" >&2; return 2; }
  LAB_PORT=${DSH_LAB_PORT:-5580}
  LAB_SERIAL="emulator-$LAB_PORT"
  LAB_ADB="$ANDROID_HOME/platform-tools/adb"
  LAB_EMULATOR="$ANDROID_HOME/emulator/emulator"
  LAB_STATE_DIR="$ANDROID_AVD_HOME/lab-state"
  mkdir -p "$LAB_STATE_DIR"
}

# WSL 当前可用内存（MB）
lab_mem_available_mb() {
  awk '/^MemAvailable:/ { print int($2/1024) }' /proc/meminfo
}

# Windows 宿主当前可用物理内存（MB）。查询失败返回空，由调用方决定是否阻断。
lab_windows_free_mb() {
  local out
  out=$(/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command \
    '[int](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB' 2>/dev/null | tr -d '\r')
  [[ "$out" =~ ^[0-9]+$ ]] && echo "$out"
}

# 本机所有 qemu 进程 RSS 合计（MB）。只读 /proc/<pid>/exe 符号链接与 VmRSS 字段，
# 不采集进程标题、启动参数或环境变量。
lab_qemu_rss_total_mb() {
  local pid exe rss total=0
  for pid in /proc/[0-9]*; do
    exe=$(readlink "$pid/exe" 2>/dev/null) || continue
    case "${exe##*/}" in qemu-system-*) ;; *) continue ;; esac
    rss=$(awk '/^VmRSS:/ { print int($2/1024) }' "$pid/status" 2>/dev/null)
    total=$(( total + ${rss:-0} ))
  done
  echo "$total"
}

# 启动前资源守卫：
# - WSL 侧按 MemAvailable 判（不是总上限）；
# - Windows 侧按可用物理内存判，扣除已在跑 qemu 的实测 RSS 并给宿主留 2GB 余量；
# - 不设总台数上限，按逐台实测放行。
# 参数 1：本次启动需要的 MB（模拟器 -memory 加上余量）。
lab_assert_memory() {
  local need=${1:?需要内存 MB} wsl_avail win_free qemu_rss win_budget
  wsl_avail=$(lab_mem_available_mb)
  qemu_rss=$(lab_qemu_rss_total_mb)
  win_free=$(lab_windows_free_mb)
  echo "内存守卫：本台需要 ${need}MB；WSL available ${wsl_avail}MB；Windows 可用 ${win_free:-unknown}MB；现有 qemu RSS 合计 ${qemu_rss}MB"
  (( wsl_avail >= need )) || {
    echo "WSL 可用内存不足：${wsl_avail}MB < ${need}MB，拒绝启动" >&2
    exit 3
  }
  if [[ -n "$win_free" ]]; then
    win_budget=$(( win_free - qemu_rss - 2048 ))
    (( win_budget >= need )) || {
      echo "Windows 宿主余量不足：可用 ${win_free}MB - qemu ${qemu_rss}MB - 宿主预留 2048MB = ${win_budget}MB < ${need}MB，拒绝启动" >&2
      exit 3
    }
  else
    echo "警告: Windows 可用内存查询失败，仅按 WSL 侧守卫放行" >&2
  fi
}

# 物理设备禁止：serial 必须是 emulator-N。
lab_assert_emulator_serial() {
  local serial=${1:?serial}
  [[ "$serial" =~ ^emulator-[0-9]+$ ]] || {
    echo "拒绝非模拟器目标：$serial（物理设备禁止）" >&2
    return 4
  }
}

# 只操作本实验室 AVD：serial 在线、AVD 名匹配、确认是 qemu 模拟器。
lab_owned_device() {
  local serial=${1:?serial} name
  lab_assert_emulator_serial "$serial" || return 4
  "$LAB_ADB" -s "$serial" get-state >/dev/null 2>&1 || {
    echo "$serial 不在线" >&2
    return 1
  }
  name=$("$LAB_ADB" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -1)
  [[ "$name" == "$LAB_NAME" ]] || {
    echo "目标不是本实验室 AVD：$serial ($name != $LAB_NAME)" >&2
    return 1
  }
  [[ $("$LAB_ADB" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || {
    echo "$serial 不是 qemu 模拟器（ro.kernel.qemu != 1）" >&2
    return 1
  }
}

# mountinfo 泄漏计数：第 5 列挂载点以 prefix 开头的行数。
lab_mountinfo_prefix_count() {
  local file=${1:?mountinfo 路径} prefix=${2:?前缀}
  # 读取失败或空文件必须失败：零泄漏断言不能把探测失败当成安全。
  [[ -r "$file" ]] || {
    echo "mountinfo 不可读或为空：$file" >&2
    return 6
  }
  awk -v p="$prefix" '
    NF < 7 { bad++; next }
    index($5, p) == 1 { n++ }
    END { if (NR == 0 || bad > 0) exit 8; print n + 0 }
  ' "$file"
}

# mountinfo 传播违规行数：第 7 列到 "-" 之间的可选字段含 shared:/master:。
lab_mountinfo_prop_violations() {
  local file=${1:?mountinfo 路径}
  [[ -r "$file" ]] || {
    echo "mountinfo 不可读或为空：$file" >&2
    return 6
  }
  awk '
    NF < 9 { bad++; next }
    {
      for (i = 7; i <= NF; i++) {
        if ($i == "-") break
        if ($i ~ /^(shared|master):/) { print; break }
      }
    }
    END { if (NR == 0 || bad > 0) exit 8 }
  ' "$file"
}

# mountinfo 中指定 fstype 的挂载点列表（第 5 列）。
lab_mountinfo_fstype_points() {
  local file=${1:?mountinfo 路径} fstype=${2:?fstype}
  [[ -r "$file" ]] || {
    echo "mountinfo 不可读或为空：$file" >&2
    return 6
  }
  awk -v fs="$fstype" '
    NF < 9 { bad++; next }
    {
      for (i = 7; i <= NF; i++) {
        if ($i == "-") { if ($(i + 1) == fs) print $5; break }
      }
    }
    END { if (NR == 0 || bad > 0) exit 8 }
  ' "$file"
}

# 把本地脚本推到设备 /data/local/tmp 并以 Magisk su 运行。
# 输出原样返回；设备端脚本负责自己的退出码语义。
lab_run_su_script() {
  local local_sh=${1:?本地脚本} remote_sh tag
  tag=$(basename "$local_sh")
  remote_sh="/data/local/tmp/$tag"
  local rc=0
  "$LAB_ADB" -s "$LAB_SERIAL" push "$local_sh" "$remote_sh" >/dev/null || return 5
  "$LAB_ADB" -s "$LAB_SERIAL" shell "chmod 700 $remote_sh" >/dev/null
  "$LAB_ADB" -s "$LAB_SERIAL" shell "'/debug_ramdisk/su' -c \"sh $remote_sh\"" || rc=$?
  "$LAB_ADB" -s "$LAB_SERIAL" shell "rm -f $remote_sh" >/dev/null
  return $rc
}
