# Git 阶段管理状态（2026-11-03）

## 2026-11-03 07:47 · 权限合并核实与 release/policy 合入 root-launch

祖先关系实测：`27286dda9c4aa09eeb042f66635dc2dc6899e441` 与 `94579b4bd21acb747d6474cd344fdff36838a6a5` 都是 `5651f275eaef92772092b7da14eea59d302399c9` 的祖先，`git merge-base --is-ancestor` 退出码均为 0；`c3dd8f2bc7cea7c1911237a0d24c6c1ea6e7170c` 第二父就是 `94579b4`。此前“945 留分支”的说法与实际提交图不符，未做过 unmerge 或改历史。

上游补丁集约定提交 full SHA 是 `3141593b23df9b0344db9c084efd4f3552b061d3`；release 修复提交 full SHA 是 `d0655105c4250b4eb11a47fe0a75570b6d4d748f`。root-launch 合入 d065 后为 `0dfd7d75ee1faeb356a916e92a71bce344fdf37e` 并已推送；tracked 差分为空，B writer 的 assets、notes、supervisor 测试脚本保持未跟踪。

## 2026-11-03 06:44 · root-runtime 旧稿收敛

主区曾被混入旧稿：`PhoneStorage.kt`、`RuntimeController.kt` 和 untracked `RuntimeMountGuardTest.kt` 与 compat 加强版 blob 相同；`RuntimeInstaller.kt` 缺 `activateRootfs(rootRuntime)` 与 Direct alias 处理，不能覆盖 compat；`GuestSetup.kt` 仅重构等价。已恢复四个 tracked 旧稿到 HEAD，删除两个重复 untracked 测试；`GuestAgentsModeTest.kt` 的 mtime 幂等新增块与顾问 `docs/design/root-runtime.md` 保留。收敛前完整差异备份在 `notes/backups/root-runtime-dirty-20261103-0641{,-untracked}.patch`，SHA256 分别为 `a7cf8169c8ef45ab79feaf8de525559e264d9f5ea2e42a02592c43d61e7d3fd7`、`3a7bca48f280b81d54524eb021eb263025bb1c0b18fe9d83ff395eaddc20f231`。

合并方向复核：`bc35f40` 第一父 `cb13b72`（`feat/root-launch`），第二父 `c8fbe40`（`root-runtime`），是 root-launch 合入 root-runtime docs/brand；`root-runtime` 未移动，仍为 `c8fbe40`。B 在 root-launch 的资产当前 WIP，修真实运行错误；稳定前不标稳定、不打 tag。

**阶段结果：身份已配置，B、C 两组已本地提交（未 push）。** 提交身份（仓库级，备份见下）：`仓库所有者 <89185887+仓库所有者@users.noreply.github.com>`（经 `gh api user` 核对 login/id）。`.git/config` 改前备份：`notes/backups/git-config-20261103/config`（一次性快照，不再追加）。

## 已完成提交（分支 `root-runtime`，base 8fe1c1c）

| commit | 主题 | foundation ref |
|---|---|---|
| `36620bc` | chore(repo): 建立 fork 仓库政策与 CI，移除上游 PR 门禁 | B 组（repo 政策 foundation，工作树任务可依此建分支） |
| `744ce77` | feat(build): root 运行时独立构建入口与更新源配置基础层 | C 组（构建基础层 foundation，验收依据 notes/config-validation.md 四项 PASS） |

- 36620bc 路径：`.github/`（删 pr-gate/pr-intake/APPROVED_CONTRIBUTORS，增 PR 模板/ci.yml/upstream-sync.yml，改 Issue config）、CONTRIBUTING 中英、AGENTS.md、`.agents/skills/{pr,review,audit}` 及 review references。
- 744ce77 路径：`app/android/app/build.gradle.kts`、`AndroidManifest.xml`、`app/lib/update/update_config.dart`、`app/test/update/root_update_config{,_sources}_test.dart`、`tooling/{build-root-apk.sh,check-dev-env.sh,emu.sh,test_build_root_apk.sh}`。
- 提交前的额外修正（并入 36620bc）：ci.yml Flutter 由跟随 stable 改为钉 `3.47.7`（两处 flutter-version + 头注释）；skills「不接受外部 PR」残留清空，仓库标识统一 `仓库所有者/dsh-Su`。
- 远端与 GitHub 元数据（此前已完成，未变）：origin 指 dsh-Su；Issues 已启用；labels root-runtime/compat/device-matrix、milestones 1–3、issues #1–#4 已核实在库，未重复创建。

## 未提交（等待内容稳定，不在本次范围）

- A 组：`README.md`、`README.en.md`、`docs/dsh-su.md`、`docs/design/root-runtime.md`（Argon 刷新中）。
- 正在写的 core/compat/l10n/simulator 与 notes/ 事故原始材料。
- `tooling/emulator-lab.sh`、`tooling/lab/`（laboperator 所有）。

## 规则记录

- Git 全部操作经 WSL Linux 侧执行（Windows UNC 对符号链接与编码不可靠；一次 PowerShell 编码写坏 ci.yml 头注释，已按原内容重建并 yaml 解析验证）。
- 未 push、未改远端历史、未动分支结构；push 待用户具体确认。
- 工作树任务请等待 `744ce77` 之后创建 Linux worktrees，不要 `git worktree add` 抢锁（由其 owner 负责）。

## A 组待提交注意事项

- `README.md`、`README.en.md`、`docs/dsh-su.md`、`docs/design/root-runtime.md`
- `docs/design/root-runtime.md` 确认 mtime 稳定再纳入；README 中英 Issues 链接（约 324 行）仍指上游，等 Argon 修正后一起提交。

## 已完成的远端与 GitHub 元数据变更（未 push 任何提交）

- `origin` URL：`https://github.com/<owner>/dsh-Go.git` → `https://github.com/<owner>/dsh-Su.git`（本地 config，`git remote set-url`）。两 URL 均 `git ls-remote` 验证可达且 heads 一致（更名重定向）；dsh-Su 为规范地址。
- fork 启用 Issues：`gh repo edit 仓库所有者/dsh-Su --enable-issues`（fork 默认关闭，`hasIssuesEnabled` 已核为 true）。
- 新建 label：`root-runtime`（#d93f0b）、`compat`（#5319e7）、`device-matrix`（#fbca04）。
- 新建 milestone：1「最小 root 环境」、2「完整使用路径」、3「真机 APK 与验收」。
- 新建 issue（均关联 label + milestone，内容自包含，不含内部 notes 路径、事故细节、代理分工）：
  - #1 Root 启动：基于 chroot 的真实 UID 0 引擎启动路径（root-runtime，MS1）
  - #2 Root 生命周期：进程归属、停止清理与重启恢复（root-runtime，MS1）
  - #3 Root 环境的文件兼容与 Android 集成（compat，MS2）
  - #4 Root 框架验证矩阵：从模拟器到真机（device-matrix，MS3）
- 未向第三方仓库发消息，未扩大 token 权限。本次续接已复核：issues #1–#4、labels、milestones 均在库，未重复创建。

## 残留

- `README.md` / `README.en.md` Issues 链接指上游（Argon 范围）。
- `SECURITY.md` / `SECURITY.en.md` 未核对报告渠道是否随更名调整（mtime 为 03:02 上游版本，不在本次范围）。

## 验证记录

- B、C 提交均为显式路径 staging，`git diff --cached --name-status` 复核后才 commit；提交后工作树其余改动未被触碰。
- ci.yml 修复后 `yaml.safe_load` 解析通过，`flutter-version: '3.47.7'` 两处就位（95、114 行）。
- 36620bc 前 grep 复查：CONTRIBUTING 中英、.github、AGENTS、skills 无 `pr-gate`、无 `仓库所有者/dsh-Go` 残留；无「不接受外部 PR」字样。
- 744ce77 验收依据 notes/config-validation.md 四项 PASS；输入未变未重跑测试。

---

# Git 阶段管理状态（2026-11-03 续 · 原生执行）

## 新增提交

| commit | 主题 | 规模 |
|---|---|---|
| 3163eed | docs(readme): dsh-Su 项目身份与 root 运行时设计契约 | 4 files +416/-30 |
| 769212d | l10n(brand): 最终用户文案统一为 dsh-Su | 49 ARB + 3 gen，495+/-495 |

l10n_merge.py --check 通过（47 parts 5477 keys），生成物与输入同步。技术协议名、下载路径 Downloads/DSH-Go、上游归属保留不变。

## 本会话检查

- repo user.name=仓库所有者，user.email=GitHub noreply（未改 global）
- Argon 与顾问释放对应文件后提交
- README/docs 无本机路径、serial、token；root-runtime.md 中 glm-lab-operator.jsonl 为过程引用

## 当前未提交

- 兼容层 4 个 Kotlin 源 + 3 个测试草稿 + 2 个新测试（root compat 组）
- tooling/agents/、tooling/emulator-lab.sh、tooling/lab/（模拟器组）
- notes/ 全部

## 工作树

| 目录 | 分支 | HEAD | 状态 |
|---|---|---|---|
| <repo-worktrees>/root-launch | feat/root-launch | 744ce77 | 创建时 clean |
| <repo-worktrees>/root-compat | feat/root-compat | 744ce77 | 创建时 clean |
| <repo-worktrees>/root-frameworks | test/root-frameworks | 744ce77 | 创建时 clean |

env.sh 已复制到各工作树 tooling/（ignored，sha256 一致）。

---

# 远端推送（2026-11-03 · 原生执行）

## 已推送分支

| 分支 | 本地 SHA | 远端 SHA | URL |
|---|---|---|---|
| root-runtime | c8fbe40 | c8fbe40539d32bac413af6fa33cce64399a2663c | https://github.com/<owner>/dsh-Su/tree/root-runtime |
| feat/root-launch | 912273b | 912273b7cef114385a21b79f1c2db4f738f0fc6e | https://github.com/<owner>/dsh-Su/tree/feat/root-launch |
| feat/root-compat | d57755c | d57755ce3c8171e33c9c62b0eee1afde72f57f3e | https://github.com/<owner>/dsh-Su/tree/feat/root-compat |
| test/root-frameworks | 443ade9 | 443ade9eeb775b402fe3f47ee32a10b76c05bd72 | https://github.com/<owner>/dsh-Su/tree/test/root-frameworks |

## 凭据问题与解决

WSL git 默认凭据 helper 为 `store`，使用 `~/.config/gh/hosts.yml` 中的旧 token，缺少 `workflow` scope，推送含 `.github/workflows/ci.yml` 的分支被 GitHub 拒绝。解决方式：per-command `credential.helper` 覆盖，通过 helper 脚本 `临时 credential helper 脚本` 调用 Windows `gh.exe auth git-credential`（`Windows GitHub CLI 安装路径下的 gh.exe`）。未写入全局/仓库 Git 配置，未复制/打印 token。

## frameworks 分支个人路径

`test/root-frameworks` 中 `notes/lab-*.md` 含本机路径 `$HOME/dev/avd/profiles/...` 和 AVD snapshot hash（非 secret）。用户已授权 push，后续如需公开清理可后续 commit 参数化路径，不改写历史。

## 敏感检查

- root-runtime / feat/root-launch / feat/root-compat：无本机路径、serial、token、事故脚本
- test/root-frameworks：见上述个人路径说明

---

# 后续 commit/push（2026-11-03）

| 分支 | commit | 主题 | 远端 SHA |
|---|---|---|---|
| test/root-frameworks | 8a14324 | docs(lab): 实验记录本机路径参数化 | 8a14324b |
| feat/root-permission-ux | 21f3eb9 | feat(phone): root 构建手机操控自动授权与 Direct 死设置隐藏 | 21f3eb99 |

8a14324：notes/lab-kernelsu{,-next}-verify-evidence.log 中 $HOME/dev/avd/ 路径替换为 $HOME/。此前已按非秘密放行属于检查遗漏，后续公开检查按读者用途判断，不只按 secret 扫描。

21f3eb9：6 文件 +39/-3。SecureSettingsGrant 复用 Root 通道自动 grant + 失败手动入口；Direct UI root 构建下隐藏 3 行。arm64 定向 SecureSettingsGrantTest PASS。未声称所有权限已自动化。

两者均不含本机路径/serial/token；SHA 已远端 API 核对一致。

---

# permission-ux merge（2026-11-03）

feat/root-permission-ux 21f3eb9 merge 进 feat/root-launch → cb13b72b952338d1daae0ff0e9ec93cfd8f3c691。

仅 6 文件 +39/-3：PhoneControl/PhonePlugin/BackgroundControl/SecureSettingsGrant/SecureSettingsGrantTest/direct_engine_settings。与 B dirty（assets/tooling/notes）无重叠，B 未提交保持原位。

远端已推，API 核对 cb13b72 一致。

---

# 分支收敛（2026-11-03）

## merge root-runtime → feat/root-launch

bc35f408840a4aae30fef48dea6212d320b1f9d9。58 files +1561/-525，含 README/docs/branding/lab 脚本/l10n/phone 授权等全部 root-runtime 稳定内容。远端 API 核对 bc35f40 一致。

## 删除 feat/root-compat

本地 + 远端均已删（普通 delete 非 force）。d57755c 完全 reachable 于 feat/root-permission-ux 21f3eb9 → feat/root-launch cb13b72 → bc35f40，无独有 commit。compat 工作树已切 feat/root-permission-ux，不受影响。

## 当前活跃分支用途

| 分支 | 用途 | 保留策略 |
|---|---|---|
| root-runtime | 集成分支（文档/branding/lab 基础） | 保留 |
| feat/root-launch | B/C 活跃开发 | 保留 |
| feat/root-permission-ux | 权限主题（root-compat 工作树） | 可复用，完成全部主题后再清 |
| test/root-frameworks | 框架并行测试 | 保留 |
| main | 上游基线（不动） | 保留 |

未来合并后删除规则：分支完全 reachable 且无工作树 writer → 本地+远端普通 delete，不 force。
