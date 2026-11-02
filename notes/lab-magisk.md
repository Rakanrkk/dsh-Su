# Magisk 5580 实际 App UID 固定 probe（2026-11-03）

- APK: dsh-su-x64-debug-11030641-74db...fdd84.apk, versionName 1.0.9, 包 io.github.rakanrkk.dsh_su, cert SHA abecaedc...b72026。唯一源改动: RootShellProbeActivity.kt 固定候选列表 /system/bin/su + /debug_ramdisk/su, 命令固定 id, 无注入。
- 设备: emulator-5580, AVD dsh-su-root-api36, systemd transient unit dsh-su-lab-magisk2.service（用户级, 任务给的名字 dsh-su-lab-magisk 实际带 2）, active 2h+。
- 状态: sys.boot_completed=1; /data/app 两个包目录完整; 无 bootloop、无 App 消失、无 dropbox 异常。
- Magisk 31.1 daemon 版本码 31100, tmpfs /debug_ramdisk; settings 无 enforce 字段; policies 初始仅 UID 10151=allow（重装后 App UID 变 10152）。
- 首跑（授权前）: /system/bin/su ENOENT; /debug_ramdisk/su exit 13 Permission denied（App 域 SELinux 拒绝, 非 ENOENT）。
- 授权后（10152=allow 写入 magisk.db）: /debug_ramdisk/su -c id exit 0, UID 0, context u:r:magisk:s0, stderr empty; force-stop + 重启 + 点击两次均相同结果。
- App 域读 /sys/fs/selinux/enforce FileNotFoundException, 与 5586 SukiSU 行为一致, 不影响 su 链。

## 与 5586 SukiSU 差异

| 事实 | SukiSU 5586 | Magisk 5580 |
| --- | --- | --- |
| su 实体位置 | App 授权后 /system/bin/su 可见（sucompat 虚拟挂载） | 始终 ENOENT; 真实路径 /debug_ramdisk/su |
| 授权域 | untrusted_app_27:c151... → UID 0 | untrusted_app_27:c152... → magisk:s0 UID 0 |
| 授权载体 | SukiSU 管理器 UI / allowlist | magisk.db policies（管理器 UI 或 root CLI 写入） |
| App UID 稳定性 | 重装后 UID 变化, 需重新授权 | 同左: 10151 → 10152 后旧 policy 失效 |
| file.exists 预筛 | 不可用（sucompat 授权后才挂载） | 不可用（/debug_ramdisk/su 始终存在但未授权时 exit 13） |

## 产品 RootStarter 最小复用方案

现状: RootCommands.appProcess 只返回固定 ["su", "-c", app_process], 裸 su 在 Magisk 5580 的 App PATH 不存在。最小改动是让 RootStarter 在启动失败后按固定候选顺序重试候选路径: "su" → "/debug_ramdisk/su"。不改 PrivilegedCommands（它同时服务 ADB 路径）, 只在 RootStarter/RootProcess 层加一个固定候选数组, 失败时换 argv[0] 重启子进程。禁止 file.exists/canExecute 预筛, 只按 ProcessBuilder 抛出的 IOException 分类失败。该改动跨 RootStarter.kt 与 RootProcess.kt, 属于 root 权限发布团队所有文件, 先交主代理裁决。

替代方案: 只在 debug 探针 Activity 保留候选列表（当前 patch）, RootStarter 不动。代价是产品 root 通道在 Magisk 5580 上仍不可用, 直到 RootStarter 支持候选重试。当前证据只覆盖探针 Activity, 不覆盖完整 RootStarter → app_process → PrivilegedDisplayMain 链路。