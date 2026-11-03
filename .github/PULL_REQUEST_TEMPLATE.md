<!-- 一个 PR 对应一个可独立审阅的问题；中英文均可。规范见 CONTRIBUTING.md。 -->

## 问题与目标

<!-- 什么问题、为什么改、改后的行为；关联 Issue（#123）或写“无”。 -->

## 改动面

- [ ] app（Flutter 界面）
- [ ] packages/dsh_client
- [ ] packages/dsh_remote
- [ ] sdk/plugin-kit
- [ ] runtime / 引擎桥
- [ ] 文案 / 文档（中英两版已同步）

## 已执行的检查

<!-- 按 CONTRIBUTING「常用检查」按改动面选择；贴实际命令与结果，未运行的写明原因。 -->

- [ ] `(cd app && flutter pub get && flutter analyze && flutter test)`
- [ ] `(cd packages/dsh_client && dart pub get && dart analyze && dart test)`
- [ ] `(cd packages/dsh_remote && dart pub get && dart analyze && dart test)`
- [ ] `(cd sdk/plugin-kit && npm install && npm test)`
- [ ] `node --test runtime/tests/*.test.mjs`
- [ ] `python3 app/tool/l10n_merge.py --check`
- [ ] `python3 app/tool/guide_sync.py --check`

## 真机验证

<!-- 手机操控、PRoot、直接启动、更新安装相关改动必填：设备、Android 版本、ABI、启动方式。不涉及写“不涉及”。 -->

## 兼容性

<!-- 客户端、桥接、插件协议改动：另一端调用方与向后兼容。不涉及写“不涉及”。 -->

## 上游合并影响

<!-- 按 CONTRIBUTING「修改范围」检查：root 专有行为是否集中在少量独立模块、共享流程是否复用上游实现、是否引入无关重构/重命名/格式化。不涉及写“不涉及”。 -->

## 提交前自查

- [ ] 差异里没有密钥、个人配置、真实对话、设备序列号、构建缓存、私有运维资料
- [ ] 没有复制上游流程形成两套实现，root 行为没有散入不必要的共享文件
- [ ] 新引入第三方内容已记录来源、版本与许可证
- [ ] UI 改动附前后截图/录屏（测试数据，遮盖个人内容）
