# dsh-Su root runtime 设计契约

## 范围

第一版让本地 runtime 中的 DSH 引擎及其全部子进程以真实 UID 0 运行。App 界面、HTTP/SSE、插件、远程 SSH / DSH Remote 保持原版。Android 系统命令（Binder、`cmd`、`am`、`pm`）放在第二阶段。

Root 构建（`BuildConfig.ROOT_RUNTIME`）启动失败时报告命名错误，不回退 PRoot，安全模式同样走 root 启动：PRoot 以 App UID 运行，无法修改 root 创建的文件。

## 已确认的设备事实

| 事实 | 来源 |
|---|---|
| 一加 15 内核 6.12 的已保存探针未列出 pid、ipc、user namespace；本后端不依赖这些能力 | `notes/probes/device-root-probe.md` |
| KernelSU `su` 下 UID 0、`u:r:ksu:s0`、`Seccomp: 0`，SELinux Enforcing | 同上 |
| 真机 `/proc` 挂载为 `hidepid=invisible,gid=3009`；App 侧的进程扫描不能作为 root 进程归属证明 | 同上 |
| rootfs 自带 util-linux：`usr/bin/unshare`、`usr/bin/mount`、`usr/bin/nsenter`、`usr/bin/flock`、`usr/sbin/chroot` | `runtime/out/x86_64/assets/runtime/rootfs-x86_64.tar.xz` |
| Android 16/API 36 x86_64 模拟器：Magisk 31.1 的 `su -c` 返回 UID 0、`u:r:magisk:s0`、Enforcing | `notes/emulator-lab.md`；operator 2026-11-03 04:15:57、04:17:04 原始输出 |
| 原版 manifest 的 PRoot 参数包含 `-b /dev`、`-b /proc`、`-b /sys`、`-b /system`、`-b /apex` | `runtime/out/*/assets/runtime/manifest.json` |

## 模拟器能力证据

2026-11-03 的两轮 operator 输出分别来自冷启动和快照恢复。`tooling/lab/common.sh` 固定目标为实验室 AVD，校验 `emulator-N`、AVD 名称及 `ro.kernel.qemu=1`；`probe-capabilities.sh` 将设备端脚本推入 Android，再通过 `/debug_ramdisk/su` 执行。WSL 仅负责 NDK 编译和 ADB 传输，以下 cgroup/mount 结果不是 WSL 宿主的能力。原始事件保存在本地 `glm-lab-operator.jsonl`；时间均为 UTC+8。

| 项目 | 两次原始输出 | 对实现的含义 |
|---|---|---|
| cgroup2 | `/sys/fs/cgroup` 下建组、写入探针自己的单个 sleep，`cgroup-kill=ok` | 该 Magisk guest 可使用此基础机制；尚未覆盖 supervisor 迁组、并发子孙、App force-stop |
| 递归 private | NDK 静态助手成功，字段检查输出 `propagation=recursive-private` | 内核操作可行；未验证本契约中的 rootfs glibc loader + util-linux 启动链 |
| 最小 `/dev` | 私有 tmpfs 内 `mknod` null/zero/random/urandom 成功 | 可作为该 guest 的简化实现候选；节点实际打开、权限和其他 root 框架仍需验证 |
| 挂载视图 | namespace 内计数 2，运行中 `host-mount-leak=0`，退出后 `after-exit-leak=0` | 本次输出与私有挂载隔离一致；探针读取失败处理仍须修正后才能作为自动验收门 |
| 临时目录回收 | 04:15:57 为 `cleanup=prefix-left`，04:17:04 为 `cleanup=clean` | 首次目录未清完，不能记为两次均清理成功；两次调用却都返回 `probe-rc=0` |

这些结果允许 A/B 继续实现。探针须补齐以下验收逻辑：设备端的 `awk ... | wc -l` 不能把读取失败、空输入或畸形 mountinfo 计为 0；缺少 `cgroup.kill` 的 skipped、cgroup 目录回收失败及 `prefix-left` 不得汇总为通过。记录中的 Magisk/Enforcing 已有实际输出，后续自动验收也应显式断言这些条件。`cgroup.kill` 存在证明该接口可用，不用于反推内核精确版本或下限。

## 改动边界

- 切片 A：`RootLaunch.kt` 及其纯 JVM 单测，负责 su argv、启动帧编码、事件解析和额外挂载参数规划，不修改 `DshProcess`。
- 切片 B：固定源码路径为 `app/android/app/src/main/assets/root-runtime/supervisor.sh`；必要 helper 也在同一目录，采用静态脚本，Kotlin 不生成脚本文本。Android AssetManager 中的入口是 `root-runtime/supervisor.sh`，内部 helper 不构成公开调用接口。
- 切片 C：A/B 分别验证后，接入 `DshProcess` 与兼容层。C 把资产复制到 App 所有、权限 0700 的 `filesDir/root/<instance>/`，连同 helper 保留相对路径；运行中不覆盖这些文件。复制后的入口是该目录下的 `supervisor.sh`。
- `DshProcess`：增加 root 分支。root 模式不调用 `RuntimeProcessOwnership`、`signalOwned`、`killStrays`，也不向任何 pid 直接发信号。
- `RuntimeController`、`RuntimeInstaller`、`GuestSetup`：按「文件属主」一节处理。
- PRoot 与 direct 路径保持不变。

## A/B 调用契约 v1

本节规定两端的线协议。`notes/root-backend-interface.md` 登记兼容层依赖；`notes/root-supervisor-status.md` 登记 B 的实现与测试结果，二者引用本节，不各自定义参数。Kotlin 方法名由 A 确定，不影响脚本接口。

### 启动参数

App 使用 `ProcessBuilder` 的三个参数启动：`su`、`-c`、一个 shell 命令字符串。第三项是 `exec` 加下列 argv 的逐项 shell 引用结果，不拼接未引用的数据，不使用 `su -M`：

```text
/system/bin/sh <script> run
  --protocol 1
  --package <applicationId>
  --app-uid <Android UID>
  --instance <UUID>
  --rootfs <canonical host path>
  --loader <absolute glibc loader path>
  --library-path <colon-separated guest library directories on host>
  --cwd <absolute guest working directory>
  [--bind <host path> <guest path>]...
  --
```

上面的换行仅用于排版。单引号用标准 shell 单引号分段转义，空字符串和空格保持原值。`--` 是参数结束标记，其后没有 guest argv。

| 参数 | A 提供的值 | B 的行为 |
|---|---|---|
| `--protocol` | 固定 `1` | 其他版本返回 `PROTOCOL`，不建立运行实例 |
| `--package`、`--app-uid` | `context.packageName`、App UID；不能取 su 后的 UID | 划定本 App 的 cgroup/锁范围；多 Android 用户以 UID 区分 |
| `--instance` | 每次启动新建 UUID | 所有事件带相同实例号；实例号不作为凭据 |
| `--rootfs` | 已安装 rootfs 的规范绝对路径 | 检查路径、布局和载荷；不执行安装、重置或递归删除 |
| `--loader`、`--library-path` | 按当前 ABI 使用 `DirectLaunch.loaderPath`、`libraryDirectories` 生成 | 运行 rootfs 自带 util-linux；不把库路径注入 Android 系统程序 |
| `--cwd` | 解析 manifest 的 guest cwd；当前模板为 `/root` | chroot 后切换到这个目录，再启动原 guest 命令；不擅改为 `/root/workspace` |
| 重复 `--bind` | 仅额外的 App 挂载，host/guest 各占一个参数 | 依照固定基础挂载表后的扩展规则执行；不解析 `host:guest` 字符串 |

其他参数、缺值、重复的单值参数、无效 UID/UUID 或未消解的占位符返回 `ARGUMENT`。loader 和库目录必须属于给定 rootfs。A 不传任意 cgroup 根或任意脚本正文；B 从实际 mountinfo 发现 cgroup2。

`--bind` 不得覆盖 `/`、`/dev`、`/proc`、`/sys`、`/system`、`/apex` 及其下级路径，也不接受整个宿主 `/dev` 或 `/` 作为源。源路径必须存在；目的路径在 rootfs 内逐段核对，拒绝符号链接逃出 rootfs。相同目的路径重复即 `ARGUMENT`。目前 `PhoneStorage.binds()` 的 `host:/sdcard` 和 `/storage` 在 A 侧转成两组参数；`/storage` 需要递归纳入其子挂载，其他额外挂载使用普通 bind。挂载或验证失败须报告，不能静默丢弃 App 请求的共享存储。

### 启动帧与停止命令

supervisor 通过参数与 root 身份校验后，先发 `hello`。A 从进程创建起并行读取 stdout/stderr，收到当前实例的 `hello` 后，把启动帧写入 stdin 并 flush：

```text
argv <argc>\n
<argv[0] UTF-8 bytes>\0<argv[1] UTF-8 bytes>\0...<argv[argc-1] UTF-8 bytes>\0
```

这里的 `\n` 是 LF，`\0` 是一个 NUL 字节，均不是反斜杠文本；末尾 NUL 后不再加分隔换行。`argc` 是大于 0 的十进制参数个数。每个参数完整保留空字符串、空格、引号、反斜杠、中文和换行；参数内部不能含 NUL，这是 exec 参数本身的限制。不得截断或另设任意参数数量/长度上限，资源或 exec 系统限制通过明确错误上报。

`guestArgv` 是 `DshProcess` 已完成占位符、环境覆盖、safe/phone/search/auth/speech patch 选择后，自 `/usr/bin/env -i` 起的完整 guest argv。A 不把 PRoot 可执行文件、PRoot 参数或 `PROOT_*` 宿主环境塞入其中，也不改写命令字符串。应用密钥等环境值仅随匿名管道进入，不写入 su 命令、生成脚本、参数文件、事件或测试日志；禁止 `set -x` 和完整 argv/env 诊断。

B 用能保留 NUL 分隔记录的读取方式逐项接收，例如经验证的 mksh/bash `IFS= read -r -d ''`；不能用命令替换读取整个 NUL 流、按空白拆词或用 `eval` 解释数据。该读取能力属于模拟器前置验证；必要时复用随包的 helper 或 rootfs bash，不修改协议来绕过失败。半帧 EOF 返回 `PROTOCOL`，不得启动引擎。

最后一个 NUL 之后，stdin 只接受 LF 结尾的 `stop`。完整帧之后的 EOF、`stop`、supervisor 收到 TERM/HUP/INT，均触发同一个幂等停止流程；未知控制命令报告 `PROTOCOL` 后进入清理。运行时长没有额外超时。App 保持写端打开；引擎的 fd 0 改接 `/dev/null`，不继承控制读端的副本或实例锁。终端输入继续走原有引擎接口。

### 事件与退出语义

控制事件固定写入 **stderr**，格式为一条 UTF-8/LF 记录：

```text
[dsh-su] 1 <instance> <event> [fields...]
```

stdout 保留引擎 stdout；引擎 stderr 仍按原通道透传。事件由 supervisor 单点发出，helper 向 supervisor 报告结果；实现须保证事件行不与透传日志的半行拼接。A 仅解析 stderr 中完整、版本正确、实例号匹配的记录，其他行进入原日志脱敏路径。旧实例事件不能驱动新实例的回调。

| event | fields | 含义 |
|---|---|---|
| `hello` | 无 | root 身份与协议参数已接受，等待启动帧；每次调用一次 |
| `phase` | `preflight`、`isolated`、`launched` 或 `stopping` | 生命周期进度；`isolated` 表示传播检查已通过，`launched` 不表示 HTTP ready |
| `engine-exit` | `<status>` | 启动链实际 wait 状态，正常退出为 0..255；shell 信号状态沿用 128+signal。未创建子进程时不发此事件 |
| `error` | `<CODE> <detail>` | 稳定代码加单行诊断；detail 内 CR/LF 转义，省略凭据和完整用户参数 |
| `exit` | `<status> <cleanup>` | 最后一条控制事件；cleanup 为 `clean`、`incomplete` 或 `not-started`，紧接着以相同 status 结束 supervisor |

`not-started` 仅表示本次调用尚未建立实例资源，不能证明旧实例或 rootfs 可删除。`clean` 表示本实例的进程、内部 helper 和挂载引用已释放，且未发现本次挂载泄漏；清理没有得到证明则为 `incomplete`。清理失败返回非零，错误码保留实际原因。

显式停止或 EOF 且清理完成时，supervisor status 为 0，实际引擎退出码仍单独报告。引擎自行退出时，supervisor 自行清理后返回该退出码；启动或监督器失败返回非零。若同时发生清理失败，supervisor 必须返回非零，不被引擎的 0 覆盖。

引擎自行退出必须唤醒 supervisor 并触发清理，不能等待 App 收到 `engine-exit` 后再发送 `stop`。`onReady` 仍由原版 token/HTTP 就绪逻辑判断；`engine-exit` 不是 `onExit`。C 只在最终事件与实际进程退出都核对后发出一次 `onExit`。缺失/矛盾的最终事件、su 意外退出或管道断开均按停止状态未知处理，不能放行 reset/升级删除。

## 进程模型

```
App (u0_aNNN)
 └─ su ── supervisor  (/system/bin/sh, UID 0, su 提供的 mount ns, cgroup <CG>/<OWNER>/<ID>/sup)
           └─ engine 链 (cgroup <CG>/<OWNER>/<ID>/engine)
               sh -c 'echo $$ > engine/cgroup.procs; exec <ld-linux> --library-path <libs> <rootfs>/usr/bin/unshare --mount --propagation private -- /system/bin/sh stage2'
               stage2: 校验传播 → 挂载 → exec chroot <rootfs> <原版 /usr/bin/env -i ... /bin/sh -c '... dsh web ...'>
```

`<CG>` 是 cgroup2 挂载点，从 `/proc/self/mountinfo` 中文件系统类型为 `cgroup2` 的行读取，不写死路径。`<OWNER>` 固定为 `dsh-su.<applicationId>.<appUid>`，`<ID>` 是本次启动的 UUID。其下只创建本后端拥有的实例，不改 Android 系统控制器配置。

1. 不使用 PID namespace；当前设备证据未证明其可用，模拟器也走同一条启动链。
2. 进程归属只看 cgroup v2 目录，不按 UID、进程名、命令行或 `/proc` 扫描判断。
3. 进程加入 cgroup 的写入，必须由进程自己在 `sh -c` 内写 `$$` 后再 `exec`。不要在 `( ... )` 子 shell 里写 `$$`，那里的 `$$` 是父进程 pid。
4. supervisor 启动后先把自己写入 `sup` 叶子，迁出 App 进程所在的 cgroup。force-stop、LMK 和不同 su 转发方式下能否存活并收到 EOF，按实际框架验证，不仅凭迁移成功判断。
5. `<ID>` 目录本身不放进程，只放 `sup` 和 `engine` 两个叶子，符合 cgroup v2 的 no-internal-process 规则。
6. 同一 App UID 的启动、残留回收与维护操作共用 `filesDir/root/runtime.lock` 的排他锁；使用 `flock`，锁由 supervisor 持有，engine 不继承。已有实例持锁时返回 `ALREADY_RUNNING`，不能把它当残留杀掉。拿到锁后，才回收同 OWNER 的旧实例，再建立当前实例。
7. 进入私有 mount namespace 的所有工作进程都属于 `engine` 子树；supervisor 不进入该 namespace，也不向外保留 namespace fd 或持久 bind。worker 退出后还需回收内部 watcher/relay。cgroup 归属保障通常的 fork/exec 子进程；不把真实 root 主动迁出 cgroup 或通过外部守护进程启动的工作冒充已受控子进程。

## 挂载契约

stage2 在新 mount namespace 内、chroot 之前执行，使用 `set -eu`；任何一步失败，都以命名错误码退出。

1. 用 rootfs 的 util-linux `unshare --mount --propagation private`，经 rootfs 的 glibc loader 加 `--library-path` 运行，不设置 `LD_LIBRARY_PATH`。不用 toybox 的 `unshare`，也不用 `mount -o rprivate`。
2. 传播校验：逐行解析 `/proc/self/mountinfo`。第 5 字段是挂载点，第 7 字段到单独的 `-` 之间是可选字段。任何一行出现 `shared:` 或 `master:`，即以 `PROPAGATION` 退出。不用行首正则。读取失败、空输入或结构解析失败同样失败，不能当作「没有 shared」。保存进入前的 namespace 标识，在进入后核对变化；标识变化和传播检查缺一不可。
3. 挂载表固定，不逐条翻译 manifest 中的 PRoot `-b` 参数：

| guest 路径 | 来源 | 方式 |
|---|---|---|
| `/proc` | 宿主 `/proc` | bind |
| `/sys` | 宿主 `/sys` | bind 后 remount 只读 |
| `/sys/fs/selinux` | `rootfs/var/lib/dsh-app/empty` | bind（沿用 manifest） |
| `/dev` | 新 tmpfs | `mode=755,nosuid` |
| `/dev/null` `zero` `full` `urandom` `tty` | 宿主同名节点 | 逐个文件 bind |
| `/dev/random` | 宿主 `/dev/urandom` | 文件 bind（沿用 manifest） |
| `/dev/pts` | 新 devpts | `newinstance,ptmxmode=0666,mode=620`；`/dev/ptmx` 是指向 `pts/ptmx` 的符号链接 |
| `/dev/shm` | `rootfs/var/lib/dsh-app/shm` | bind（沿用 manifest） |
| `/dev/fd` `stdin` `stdout` `stderr` | `/proc/self/fd`、`/proc/self/fd/0..2` | 符号链接 |
| `/system` | 宿主 `/system` | 私有 namespace 内 bind；检查实际挂载属性，不假定所有设备都是 erofs |
| `/apex` | 宿主 `/apex` | 私有 namespace 内 rbind，随后重新验证传播；APEX 含 tmpfs 和模块子挂载，不声明整个树都只读 |
| App 额外挂载（共享存储等） | A/B 参数中的重复 `--bind` | 按调用契约处理；不复制整个 manifest 的 PRoot 挂载表 |

4. 挂载点目录由 App 在启动前以 App UID 创建，脚本只挂载、不在 rootfs 中建目录；tmpfs `/dev` 内部的节点和符号链接除外。父目录挂载前核对内层目标的实际存在性，不能挂上只读 `/sys` 后再在其中 mkdir。每步挂载失败都进入本实例回收，不用后续成功命令覆盖失败码。
5. 禁止：整体 bind 宿主 `/dev`；任何 `umount`；在 stage2 和 supervisor 中使用 `rm -r`；`su -M`；`--pid`；在 App 或全局 mount namespace 中执行任何 `mount`。

PRoot 选项的对应关系：

| PRoot 选项 | root 模式 |
|---|---|
| `-0` | 真实 UID 0 |
| `--kill-on-exit` | `engine/cgroup.kill` |
| `--link2symlink` | 不需要。rootfs 中若还有 `.l2s` 备份文件，先经 `L2sMigration` 消除（与 Direct 的 `compatibilityReason` 判定相同），否则以 `L2S_PRESENT` 拒绝启动 |
| `--sysvipc` | 不再使用 PRoot 的模拟层；实际 SysV IPC 由目标内核提供。缺少 ipc namespace 不能证明缺少 SysV IPC，须用对应调用验证 |
| `-L`、`PROOT_*` 环境变量 | 不使用 |

guest 内的 `NODE_OPTIONS` 与 PRoot 路径一致，继续使用 `DirectLaunch.prootNodeOptions` 注入 DNS 解析器；`/usr/bin/env -i` 之后的 argv、`{GUEST_ENV}` 和各 `--patch` 叠加，沿用 `DshProcess` 现有的组装逻辑。

## 停止流程

1. 优先向 supervisor 跟踪的本次引擎进程发 SIGTERM，发信号前校验本次进程身份与 cgroup 归属，不单凭磁盘 pid 文件或 UID 0 判断。身份无法确认时跳过单 PID 信号，走本实例 cgroup 回收。
2. 等 `engine/cgroup.events` 中的 `populated` 变为 0，最多 5 秒（与原版一致）。
3. 如仍有进程，写 `engine/cgroup.kill`，再等 `populated 0`，最多 5 秒；超时或读取失败报告 `KILL_TIMEOUT` 或 `CGROUP_READ`，以 `exit <nonzero> incomplete` 结束，保留恢复标识，禁止继续删除 rootfs 或报告清理成功。
4. `engine` 子树为空、相关子进程 wait 完成且外部没有 namespace 引用时，私有 mount namespace 及其挂载由内核释放，不执行 `umount`。再以 root 核对原宿主与 PID 1 的 mountinfo，没有本次目标路径的泄漏挂载；任一检查不可读或失败都不能产生 `clean`。
5. 回收内部 helper，supervisor 将自身移到已核验可写的非实例 cgroup，再 `rmdir` 掉空的 `engine`、`sup` 和 `<ID>`。子进程可能建立的后代 cgroup 仅在本实例下逐级确认空后 rmdir；不递归删除文件系统。迁移或目录回收失败保留标识并报告 `incomplete`。
6. 用 `rm -f` 删除 pid 文件（单个文件，宿主 namespace）。
7. 按 v1 事件格式输出最终 `exit` 后退出；输出失败不能阻止进程回收。调用方只有收到一致的最终事件和进程退出结果才能确认停止。

App 侧停止超时后，只报告失败并关闭 stdin，不尝试自行杀 root 进程。

## 崩溃恢复

- App 崩溃、被 force-stop 或被 LMK 杀死：当 su 链正确传递管道关闭时，supervisor 读到 EOF 并停止；该条件必须在各实际 root 框架验证。
- supervisor 被杀：不承诺当场自动回收。下一次启动取得实例锁后，回收 `<CG>/<OWNER>/` 下的旧实例 `sup`、`engine` 及其后代，确认 `populated 0` 再 rmdir；失败阻止新引擎启动，不把活实例当残留。
- 设备重启：cgroup 和 namespace 自然消失，预检阶段清掉残留的空目录。
- 只有 `<CG>/<OWNER>/` 下、当前锁范围内的旧实例是回收对象，不触碰其他 App UID 或 root 服务。

## 文件属主与 SELinux

root 新建文件的 UID/GID、模式位和 SELinux 标签逐项实测；不能从 UID 0 推导 App 一定能读，也不能预设其标签必为 `s0`。读取 0600 文件、写入、改属性和删除分别验证。

1. 读取：导出、分享、搜索、日志和会话历史验证实际 App UID 的访问结果。
2. compat 列出 App 需要访问的配置、`resolv.conf`、技能、凭据和上传落点，依据测试选定属主/标签恢复或 root 侧固定操作；不对全 rootfs 递归 chown/chcon，也不默认 rootfs 顶层标签适用于所有子树。
3. 停止结果只是维护操作的前提。重置、升级和删除必须在同一实例锁内，由 root 再确认没有活实例、自己与 PID 1 视图没有目标路径或其下级挂载，然后执行各自已核准的固定文件操作，不能把一次较早的 `exit ... clean` 当作永久删除许可。维护接口由 C/compat 对齐，v1 的 `run` 命令不提供任意删除参数。
4. pid 文件：`rootfs/tmp` 若为带粘滞位的 1777，App 删不掉 root 创建的 `dsh-web.pid`，原版启动前的 `pidFile.delete()` 和 `check(!exists)` 就会在第二次启动时失败。root 模式下 pid 文件由 supervisor 在预检和停止阶段删除，App 侧跳过这项检查。
5. 运行中 App 需要写入引擎所建目录的场景（上传附件等），改走引擎 HTTP 接口或启动前写入，由 compat 确认具体落点。

## 错误码

`ARGUMENT`、`PROTOCOL`、`ROOT_DENIED`、`ALREADY_RUNNING`、`CGROUP2_MISSING`、`CGROUP_KILL_MISSING`、`CGROUP_CREATE`、`CGROUP_JOIN`、`CGROUP_READ`、`UNSHARE`、`PROPAGATION`、`MOUNT`、`L2S_PRESENT`、`CHROOT`、`KILL_TIMEOUT`、`CLEANUP`。挂载目标作为 `MOUNT` 的 detail，不扩展 CODE 的语法。App 区分启动失败与停止未确认；无有效事件的 su 失败保留原始退出状态，不伪造细分原因。

## 待验证

每项先在模拟器验证，带「真机」标记的还要在 SukiSU 真机复验。

| 项目 | 影响 | 真机 |
|---|---|---|
| Magisk guest 已验证 cgroup2 建组及单进程 kill；仍需验证实例层级、supervisor 迁组、子孙进程和其他框架 | 进程归属方案能否成立 | 是 |
| su 进程最初所在的 cgroup；force-stop 和 LMK 后迁出的 supervisor 是否存活 | EOF 清理是否会执行 | 是（Magisk 守护进程转发与 KernelSU 就地提权不同） |
| su 继承哪个 mount namespace（App 的还是全局的） | 共享存储视图、挂载可见性 | 是 |
| App 死亡后 supervisor 读到 EOF 的时延 | 回收时延 | 是 |
| root 新建文件的 DAC、SELinux 等级，以及 App 读、写、删的结果 | 文件属主方案 | 是 |
| root 能否执行 `/data/data/<pkg>` 下 rootfs 的 loader 和 `unshare` | 启动链 | 是 |
| `rootfs/tmp` 的权限与粘滞位 | pid 文件处理 | 否 |
| SysV IPC 是否可用 | 已知差异范围 | 是 |
| NUL 启动帧、stdin EOF、输出分流及引擎自行退出能否唤醒清理 | A/B 协议和回收能否工作 | 是 |

## 防回归断言

模拟器验收脚本在每轮运行中自动检查：

1. 第一次挂载之后、运行期间、停止之后，宿主 `/proc/1/mountinfo` 中都没有本环境的路径。
2. 负向用例：保持 shared 传播，启动必须以 `PROPAGATION` 失败。
3. 引擎 namespace 内的 `/dev` 是 tmpfs，且只有挂载表列出的节点。
4. 正常停止、force-stop App、`kill -9` App、`kill -9` supervisor 四种场景：10 秒停止预算内（supervisor 被杀的场景从下次启动预检计时）本实例 engine 无活进程，宿主无泄漏挂载；adbd 和 su 守护进程不受影响。超时须返回 `incomplete` 并阻止重置，不能为了满足时限伪报完成。
5. 每轮结束后，宿主 `/dev/binder`、`/dev/socket/property_service` 存在，`sys.boot_completed` 为 1。
6. 引擎和它启动的 bash、Node、Python 子进程的 UID 都是 0，且都位于 `engine` cgroup。
7. 引擎自行退出但留下后台子进程时，App 不发送 stop，supervisor 也能回收；管道写端关闭与日志接收端断开分别测试。
8. 传入空字符串、中文、单引号、反斜杠和换行参数，分片写启动帧，验证 A 编码/B 解码完全一致。测试半帧 EOF、未知版本、错误实例事件、日志半行和 `KILL_TIMEOUT`，错误不得转换成 `clean`。
9. 并发两次启动时，第二次得到 `ALREADY_RUNNING`，不会清掉第一个引擎；supervisor 崩溃后，下一次能回收其残留再启动。

代码评审时的静态检查：`RootLaunch.kt` 和监督脚本中不得出现 `umount`、`su -M`、`--pid`、整体 bind `/dev`，`rm -r` 只允许出现在带前置检查的破坏性子命令中。

JVM 单测覆盖：mountinfo 解析（含 `shared:N master:M` 可选字段、空可选字段、挂载点含空格转义 `\040`）、su argv 与脚本参数生成、挂载表中没有宿主 `/dev` 整体 bind、事件行解析、pid 文件处理。监督脚本另由模拟器上的脚本级测试覆盖「待验证」和「防回归断言」两节。

## 协议核对依据

- [Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)：`cgroup.kill` 作用于子树，`cgroup.events` 的 populated 覆盖后代活进程；二者不单独证明 namespace 引用已释放。
- [Linux mountinfo 格式](https://www.kernel.org/doc/Documentation/filesystems/proc.txt)：按字段和 `-` 分隔符读取挂载点、传播标签与文件系统类型。
- [util-linux v2.39 unshare](https://github.com/util-linux/util-linux/blob/v2.39/sys-utils/unshare.c)：传播设置使用递归 private，系统调用失败会退出；Android 上实际 loader/SELinux 可用性仍由模拟器结果确认。
