# KernelSU 标准 AVD 实验台

## 状态：通过（2026-11-03）

- AVD：dsh-su-kernelsu-api36，serial `emulator-5582`，Android 16 / API 36，x86_64，kernel `6.6.66-android15-8-gb66429556fb8-ab13070261`，Enforcing。
- `su -v` = `3.3.1:KernelSU`，`su -V` = 32680；`su 0 id -Z` = `u:r:ksu:s0`。
- `/proc/modules`：`kernelsu 430080 0 - Live`；`ksu_handle` 符号精确计数 25（awk，不要用 shell 通配 grep）。
- `ksud 3.3.1 (uapi: 5)`；`/data/adb/ksu/bin/{busybox,ksud,resetprop}` 完整（busybox 1767048 B）。
- 管理器 `me.weishu.kernelsu` v3.3.1，uid 10150。

## 关键证据

见 `notes/lab-kernelsu-verify-evidence.log`。

哈希：

| 产物 | sha256 |
| --- | --- |
| pristine ramdisk（备份，不动） | `e1e798efba67b02c6813946283d94c6a33e55a30514865e1efe4b9b0c81fbac0` |
| AVD `ramdisk.img`（boot 用，经 `ksud boot-patch --ramdisk` 补丁） | `ac310cf8bc1723e058bd4d1dbc445599ac5ce284cc9196dfdf06820f19846998` |
| AVD `initrd`（ksuinit cpio，LZ4） | `a1a549323131b565ce3a8dfdcf714630bd27f243eedf113feb7eb994a7802dc1` |
| ksuinit cpio `kernelsu.ko`（vermagic `6.6.127 SMP preempt mod_unload modversions`） | `50ebef0daa5b3fa1ce0e4d3267db8cf25b22b2a49a867dc6fd99f71d2f3efeb3` |
| ksuinit cpio `init`（静态 ksuinit） | `b0ae99c7eabcd470262b88cc8ee8c0be41376c5dccc084ac925aa06e4eedecf8` |
| `ksu_config` | `allow_shell=1 bundled=1` |
| ksud x86_64（tiann 官方 v3.3.1 release） | `5324a1e8f2f3c74dc69624c4cd973fb9bc1d5da3614a2f2e694583f73bd13cbd` |
| 管理器 APK `KernelSU_v3.3.1_32680-release.apk` | `b4a059ace71de2fa65cc7f12b1c58d55fc23ab25e057ee4230ce779742abe450` |
| tiann 官方 `lkm-x86_64-android15-6.6_kernelsu.ko`（备用，未加载；AVD 走 ksuinit 内嵌模块） | `c15aadcdd9507bcd3f0ba88f70438f86cfc69317b6e7e63e89f7025710fe276f` |

## 加载路径

1. `HChenX/KernelSU-AVD`（ksuAVD.sh）补丁 pristine ramdisk，生成 `initrd`（LZ4 cpio：静态 `init`（ksuinit）+ `kernelsu.ko` + `ksu_config`）。
2. 模拟器用 `-initrd` 加载 ksuinit，`ksu_config allow_shell=1 bundled=1`，启动时 insmod 内嵌 LKM，日志 `load kernelsu with params "allow_shell=1 bundled=1"`。
3. `ksud install`（tiann v3.3.1 官方 x86_64）安装 `/data/adb/ksu/bin/*` 和 `/system/bin/su`（KSU 的 su = ksud 前端；sucompat 拦截 open/exec 使 `/system/bin/su` 对 shell 可见可执行，`cat` 读不到属正常）。
4. 标准 `su 0`、`su -c` 走 KernelSU 判别：context `u:r:ksu:s0`。

## 修复记录

- 前一次把 Next 补丁 ramdisk 和 Next ksud 误用于 5582，`/data/adb/ksu/bin/busybox` 为 0 字节，`su 0` context 只落到 `u:r:su:s0`（AOSP setuid su 路径）。
- 修复：从 tiann 官方 v3.3.1 release 取 `ksud-x86_64-linux-android`，推送后 `ksud install`，重建 `/data/adb/ksu/bin`、重装 `/system/bin/su`。
- 复测 `su 0 id -Z` = `u:r:ksu:s0`；busybox 完整；ksud `runtime_mode: lkm`。

## 边界

- 该 AVD 实际加载的 LKM 来自 ksuinit cpio（vermagic 6.6.127），非 tiann release 的 `lkm-*.ko`（BuildID 不同）；两者同为 KernelSU 源码构建，运行时版本以 `su -V` / `ksud --version` 为准。
- `su 10234 -c id` / `su 10235 -c id` 只是 sucompat uid 模拟，不代表 App 授权矩阵；App 授权由管理器（uid 10150）判定，留待统一 dsh-Su APK 验收。
- 工作树脚本：`tooling/lab/kernelsu.sh`（setup / patch / start / wait / verify / status / stop）。
