# 开发入口

- [Git 阶段管理状态](git-management-status.md)：分支收敛、远端推送、凭证解决方式与当前提交边界。
- [Worktree 状态](worktree-status.md)：当前工作区分支、HEAD、dirty、远端一致性与分支保留策略。
- [并行任务状态快照](agent-status.md)：当前协作会话状态、owner 边界与 04:10 历史事件快照。
- [Root 开发计划](ROOT_RUNTIME_PLAN.md)：目标、并行边界、依赖、验收和测试硬规则。
- [黑屏事故根因](probes/incident-root-cause.md)：挂载泄漏与清理根因、已确认/高度可能区分和安全要求。
- [Root 后端接口契约](root-backend-interface.md)：dsh-Su 与 core 的接口依赖，已实现与待提供划分。
- [黑屏事故本地汇总](probes/incident-local-report.md)：设备侧执行与清理事实汇总。
- [原始事故快照](incidents/2026-11-03-black-screen/raw/)：脚本与报告哈希快照，不得重跑。
- [WSL 开发环境](local-development.md)：工具版本、加载方法与验证结果。
- [原版 arm64 runtime 底座](runtime-base.md)：官方 v1.0.9 来源、两层 hash、产物路径与构建输入漂移。
- Root 版本目标：保留原版功能和交互，仅调整本地环境启动与管理；以真实 root 运行 Ubuntu、DSH 和子进程，优先验证 chroot。
- 原版 `DshProcess.kt` 的进程识别和信号发送依赖 App UID；切换为 root 时必须同步处理进程归属、停止清理和文件属主。
- 当前尚未修改运行时，尚未进行 root 真机验证。
- [原生模拟器状态](native-emulator-status.md)：5580/5582 未运行，5584 KernelSU 3.4.2 root 可用，5586 在线装 SukiSU 管理器但 su 形态差异待核。
- [探针验收修正](emulator-lab.md)：原两次 cleanup=clean 结论不实；探针与 common 读取失败/缺失 cgroup.kill/清理失败现已硬失败，负向用例见 tooling/lab/test-probe-capabilities.sh。
