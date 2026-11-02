# KernelSU Next AVD 实验台

## 状态：通过（2026-11-03）

- AVD：`dsh-su-next-api36`，serial `emulator-5584`，Android 16 / API 36，x86_64，kernel `6.6.66-android15-8-gb66429556fb8-ab13070261`，Enforcing。
- `su -v` = `3.4.2:KernelSU`，`su -V` = 33355；`su 0 id -Z` = `u:r:ksu:s0`。
- `/proc/modules`：`kernelsu 430080 1 - Live`；`ksu_handle` 精确计数 25。
- `ksud 3.4.2 (uapi: 5)`；`/data/adb/ksu/bin/{busybox,ksud,resetprop}` 完整。
- 管理器 `com.rifsxd.ksunext` v3.4.2，uid 10150。

## 关键证据

见 `notes/lab-kernelsu-next-verify-evidence.log`。

| 产物 | sha256 |
| --- | --- |
| pristine ramdisk（备份） | `e1e798efba67b02c6813946283d94c6a33e55a30514865e1efe4b9b0c81fbac0` |
| AVD `ramdisk.img`（boot 用，`ksud boot-patch --ramdisk` 补丁） | `ac310cf8bc1723e058bd4d1dbc445599ac5ce284cc9196dfdf06820f19846998` |
| AVD `initrd`（ksuinit cpio，LZ4） | `b9b100aeae1b41a9a880031ea30e3da13cef657ee6fc5397a329ef881d82ef96` |
| ksuinit cpio `kernelsu.ko`（与标准 KSU 实验台同 BuildID） | `50ebef0daa5b3fa1ce0e4d3267db8cf25b22b2a49a867dc6fd99f71d2f3efeb3` |
| ksuinit cpio `init` | `b0ae99c7eabcd470262b88cc8ee8c0be41376c5dccc084ac925aa06e4eedecf8` |
| `ksu_config` | `allow_shell=1 bundled=1` |
| ksud x86_64（KernelSU-Next v3.4.2 release） | `6f01d6277d1675c617c9ee8b8d21f907075555e0a0ae724c04d42bc21dae8f89` |
| 管理器 APK `KernelSU_Next_v3.4.2_33355-release` | `774a5511f7034a4930e881114e202319cbdc34c5aaaa5c7c6b6d2902364e4c58` |

## 加载路径

1. `ymuuu/KernelSU-Next-AVD`（pin `88aa62b`）把 pristine ramdisk 推入运行实例，用官方管理器内置 ksud 执行 `boot-patch --ramdisk --allow-shell`，拉回替换 profile 的 `ramdisk.img`。
2. 模拟器冷启动加载 `initrd`（ksuinit + `kernelsu.ko`，`allow_shell=1 bundled=1`）。
3. 管理器安装 `/data/adb/ksud`（Next v3.4.2），`ksud install` 后 `/system/bin/su` = ksud 前端，`su 0` context `u:r:ksu:s0`。
4. `runtime_mode: lkm`，无 inline patch 证据；`/proc/modules` Live 行是 LKM 事实。

## 边界

- 5582 曾误用本 AVD 的补丁 ramdisk + Next ksud（两台 `ramdisk.img` sha 相同属事实：两实验台用同一 pristine 经 boot-patch 后产物一致）；标准 KSU 的判别以各自 `/system/bin/su` = 各自 ksud（hash 不同）为准。
- ksuinit / LKM 两个实验台 byte 一致（同 BuildID），说明上游 AVD 方案复用同一内核侧产物；差异在 userspace（ksud、管理器、APK 签名）。
- App 授权矩阵留待统一 dsh-Su APK 验收；工作树脚本：`tooling/lab/kernelsu-next.sh`。
