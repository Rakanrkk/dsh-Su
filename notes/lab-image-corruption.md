# lab-image-corruption（2026-11-03）

## 已证 cause

宿主侧诊断脚本在 `/proc/<pid>/fd` 遍历时用 `echo pid=… fd=N -> $(realpath …)` 形式输出；被打开的 fd 指向 VM 镜像路径时，shell 把 `->` 里的 `>` 解析为重定向，`fd=N` 之前的 `pid=… user=…` 变成写入内容前缀，把整个镜像开头覆写成一行 fd 元数据文本。触发命令是宿主会话在 04:14:33.718Z 生成、04:14:34.955Z 执行完毕的 `tasks/next-app/check-storage.sh`，与备份 mtime `.596/.608` 对齐。45B 文本匹配 `pid=1037603 user=<user> fd=NN - inode=…`；8B 文本 `fd=NN -\n` 为同族（同一脚本另一分支，具体输出分支对应各自原文）。

## mtime 因果

- Suki cache.img / cache.img.qcow2 12:14:34 被 45B stub 覆写，对应 `check-storage.sh` inline 版执行时间窗。
- Next backup（`lab-state/backups/next-corrupt-20261103T-corrupt/`）mtime 12:18:01，同属宿主侧写窗，非 guest 内 format。
- Suki `userdata-qemu.img.qcow2` 块 0 文本（此前由 /root 确认）与 qcow2_free_clusters 报错窗口 12:14:50 起；本 note 不判断 guest 内 factory reset 或 GPU 崩溃与该写窗的后继因果。

## 实际影响样本（只读保留）

- `lab-state/backups/next-corrupt-20261103T-corrupt/cache.img.qcow2`：196608B，头 4B `00 00 00 00`（QCOW magic 丢失），末字节 0x01。
- `lab-state/backups/next-corrupt-20261103T-corrupt/userdata-qemu.img.qcow2`：1309999104B，offset 0 为 9B `fd=39 -\n`，后随大量 0x00。
- `lab-state/backups/next-corrupt-20261103T-corrupt/cache.img`：8B `fd=37 -\n`。
- `lab-state/backups/next-corrupt-20261103T-corrupt/userdata-qemu.img`：8B `fd=41 -\n`。
- `backup-dsh-su-sukisu-20261103T042711Z/cache.img(.qcow2)`：各 45B `pid=1037603 user=rakan fd=28 / fd=26 - inode=2429910 / 2429918`。
- SHA256 备查：cache.img `bb4efed0…`、userdata-qemu.img `ca5b7a4f…`（完整值见 `tasks/qcow-forensics/` 的 inspect.sh 输出）。

## 隔离与停用

四个同族 collector 已 timestamp 备份并原入口 fail-fast（BLOCKED + exit 3）：

- `tasks/next-app/check-fds.sh`、`check-storage.sh` → `tasks/qcow-forensics/quarantine-20261103T045642Z/`（sha256 原件 `8112b93f…`、`352d6e62…`）
- `tasks/next-app/procs-5584.sh`、`tasks/magisk-app/probe-root-app-20261103a.sh` → `tasks/qcow-forensics/quarantine-20261103T045732Z/`（`16926cfb…`、`9bc44221…`）

隔离副本均为只读（444），原入口现为 555。禁用脚本不得重跑；需元数据时用项目内只读 collector `tooling/lab/avd-metadata.py`（纯 stdlib，JSON stdout，无文件输出，只读显式 PID/指定 image 路径，不扫描全进程）。回归对应 `tooling/lab/test-avd-metadata.sh`；旧 task 路径 `dev/avd/tasks/qcow-forensics/fdscan.py` 已改为薄兼容调用，指向项目内单一有效源。

## 回归测试

`tasks/qcow-forensics/regression.sh`：临时 binary fixture + 含空格/`->` 文件名 + hardlink + symlink，collector 执行后 fixture hash/size/mtime 全部不变；stdout JSON 可被 json.load 解析；`--output` 未知 flag 被拒绝且未创建任何文件。结果 HASH-OK / STAT-OK / JSON-OK / REJECT-OK / NO-FILE-CREATED。

## 不能确认之处

- 活跃 QEMU 实例（例如 Next 现行 PID）`/proc/<pid>/fd` 因宿主 procfs 保护无法从普通 user readlink；本 note 不声称对当前任何活跃进程的 fd 指向做实时确认。
- Suki `userdata-qemu.img.qcow2` 具体是哪一条 inline 命令在哪个时间点覆写块 0（与 45B cache stub 同族但未一一对应），需进一步 rollout 会话证据才能定。
- SDK 底座 `system.img`/`vendor.img` 的具体打开模式和实际内容本轮未做校验，现有记录不足以断言宿主只读。
- factory reset、GPU 崩溃、qcow2_free_clusters 与宿主写窗之间的后继因果未证。
