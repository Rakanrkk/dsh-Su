# Worktree 状态（dsh-Su · WSL）

## 当前实测（2026-11-03 06:44 Git steward 复核）

| 目录 | 分支 | HEAD | tracked 状态 | remote |
|---|---|---|---|---|
| `<repo>/..`（root-runtime） | `root-runtime` | `c8fbe40539d32bac413af6fa33cce64399a2663c` | 保留 `GuestAgentsModeTest.kt` 的 mtime 幂等新增块与顾问 `root-runtime.md` 草稿；旧稿已按 blob 比对收敛 | `origin/root-runtime` 一致 |
| `<repo-worktrees>/root-launch` | `feat/root-launch` | `0dfd7d75ee1faeb356a916e92a71bce344fdf37e` | tracked 干净；B 的 assets、supervisor 测试脚本、`tooling/dbg.sh`、`tooling/lab/`、notes 未跟踪 | `origin/feat/root-launch` 一致 |
| `<repo-worktrees>/root-compat` | `feat/root-permission-ux` | `21f3eb990bcac9557f71a8f979a241da4bd97709` | tracked 干净；root-compat 测试结果 notes 不入库 | `origin/feat/root-permission-ux` 一致 |
| `<repo-worktrees>/root-frameworks` | `test/root-frameworks` | `8a14324b2fbe10afb538c710cf23e0c9778be1d3` | tracked 干净；SukiSU/APatch notes、探针脚本、logs、`runtime/out` 未跟踪 | `origin/test/root-frameworks` 一致 |

主区曾有的旧稿已收敛：`PhoneStorage.kt`、`RuntimeController.kt`、untracked `RuntimeMountGuardTest.kt` 与 compat 加强版 blob 相同，`RuntimeInstaller.kt` 是缺 `activateRootfs(rootRuntime)` 与 Direct alias 处理的旧稿，`GuestSetup.kt` 仅重构等价，四个 tracked 旧稿已恢复 HEAD，两个重复 untracked 测试已删除。收敛前完整 tracked 与 untracked 差异保存在 `notes/backups/root-runtime-dirty-20261103-0641{,-untracked}.patch`，SHA256 分别为 `a7cf8169c8ef45ab79feaf8de525559e264d9f5ea2e42a02592c43d61e7d3fd7`、`3a7bca48f280b81d54524eb021eb263025bb1c0b18fe9d83ff395eaddc20f231`。`GuestAgentsModeTest.kt` 唯一有益新增是 mtime 幂等断言块，不阻塞内测；`root-runtime.md` 是顾问 WIP，不触碰。B 在 root-launch 的 dirty 资产仍是 WIP，未验证前不标稳定、不打 tag；稳定后由 `supervisor_runtime_owner` 给精确快照提交。

`feat/root-compat` 本地与远端已删，`d57755c` 完全 reachable 于 `feat/root-permission-ux`。`bc35f40` 的第一父是 `feat/root-launch`，第二父是 `root-runtime`，方向是 root-launch 合入 root-runtime docs/brand；`root-runtime` 仍为 `c8fbe40`。维护少数活跃分支；合并后确认完全 reachable 且无 writer 才普通删除。

`git ls-remote` 于 06:36 核对四个远端 SHA 一致。各工作区 `tooling/env.sh` 当前实测缺失，与历史“已复制”记录不一致；运行验证前先由对应 owner 重建并核验。

## 历史创建记录（2026-11-03 · foundation commit 744ce77）

## 已创建

| 目录 | 分支 | HEAD | 状态 |
|---|---|---|---|
| <repo-worktrees>/root-launch | feat/root-launch | 744ce77f47f7747ef90b316730331596c6e9e29f | clean |
| <repo-worktrees>/root-compat | feat/root-compat | 744ce77f47f7747ef90b316730331596c6e9e29f | clean |
| <repo-worktrees>/root-frameworks | test/root-frameworks | 744ce77f47f7747ef90b316730331596c6e9e29f | clean |

脚本：tooling/agents/worktrees.sh，幂等重用已存在匹配分支/worktree。

## 已知缺失

744ce77 未包含（主区 untracked），工作树中不存在：
- tooling/env.sh
- tooling/emulator-lab.sh
- tooling/lab/
- notes/

主会话分配任务时需将所需 env.sh 复制或链接到工作树。

## 注意事项

- 共享 .git 对象库在 <repo>/../.git
- 主区 root-runtime 仍有大量未提交改动，不会被工作树吸收
- Windows Git 不用于 WSL 仓库操作
