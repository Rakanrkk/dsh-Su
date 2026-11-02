# dsh-Su 仓库红线
真源为当前目标分支的 AGENTS.md、CONTRIBUTING.md 全文；本表非穷举。严重度属本技能政策，仓库文本仅定义义务。

## 1. 源码与生成物分离｜❌
只改真源，不手改生成物：`app/build/`、`.dart_tool/`、l10n 生成结果、guide 同步结果。改 `app/lib/l10n/parts/*.arb` 后跑 `python3 app/tool/l10n_merge.py`；改 `docs/guide/` 后跑 `python3 app/tool/guide_sync.py`；改插件 skill 输入后跑 `node sdk/plugin-kit/scripts/sync-skill.mjs`。生成物与输入不一致即阻断。

## 2. 引擎补丁与 pins 同步｜❌
`runtime/patches/` 的补丁文件与 `pins.json` 必须同步；不手改正被补丁覆盖的 vendor 产物；升级引擎版本时补丁必须仍可应用。查 pins、patch 内容、runtime 启动路径。

## 3. 桥接协议只有一个真源｜❌
`/dsh-go/v1/*` 路由的请求/响应形状、错误码（如 `unavailable`）以 `runtime/bridge/dsh-go-bridge/` 为准；App 端（`packages/dsh_client`、`app/lib/state`）只消费，不另立平行定义。改一端必须同步另一端与测试。

## 4. 危险操作以主机回答为准｜❌
删除会话、权限、凭据这类操作：能力/失败以主机/引擎实际回答为准，不用本地推断冒充；失败给稳定错误码，不静默吞。能力未知不得当作"不支持"。

## 5. 状态所有者唯一｜⚠️/❌
Riverpod provider 的 autoDispose 生命周期、mux generation、连接对象归属清晰；异步回调不复活已销毁状态；切主机/换连接时旧对象必须退休。

## 6. 双语文案同步｜⚠️
`app/lib/l10n/parts/*.arb` 与 `.en.arb` 同步；README、CONTRIBUTING、SECURITY、docs/guide 中英两版同步；语义等价而非逐字翻译。

## 7. 凭据与隐私｜❌
不记录/回显/持久化完整 API key、token、cookie、登录态；诊断包与日志外发前人工检查；自动打码不是保证。

## 8. Git 安全｜❌
只暂存明确路径，不用 `git add .`/`-A`；不 stash 藏他人改动；不 force push main 或重写共享历史，除非用户明确要求；重写后必须重建受影响的分支与 PR；未经要求不 commit/push/tag/release。

## 9. 构建与真机契约｜⚠️/❌
APK 构建走 `tooling/build-apk.sh`；Flutter widget 测试需要对应架构引擎；手机操控/PRoot/直接启动/更新安装改动须真机验证，桌面结果不能替代。声称跑过不存在的命令即阻断。

## 10. 范围纪律｜⚠️/❌
一 PR 一主题；不夹带无关格式化、依赖升级、上游同步树、本地配置；越界到不可可靠审查/回滚按 ❌，少量可独立剔除 churn 按 ⚠️。

## 11. 上游补丁集｜⚠️/❌
上游同步是开发要求。root 专有行为必须集中在少数独立模块，共享 argv/patch/token ready/log/chat/plugins 逻辑直接复用上游实现；复制上游流程形成第二套实现、把 root 行为散入不必要的共享文件、无关重构/重命名/格式化或破坏可导出的清晰提交序列按 ⚠️，阻碍后续上游合并按 ❌。优先成熟开源组件和上游实现，不重复造轮子；无合适现成组件时允许必要自写或 DSH 专用薄适配，以总体最佳为准。合适现成组件可替换既有自写实现，按综合稳定性、上游改动面、自维护量、接入成本和维护成本判断，不受沉没成本限制；不为套用组件增加复杂度，替换需说明这些影响。每次变更评估 `git format-patch` 导出与后续 merge 的影响；定向验证按 CONTRIBUTING.md。
