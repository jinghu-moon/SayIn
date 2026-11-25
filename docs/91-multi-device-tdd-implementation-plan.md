# SayIn 多设备 v3 TDD 实施计划

更新：2026-11-26。  
状态：P0 工程门禁已通过，按用户确认暂缓真实 2×2 及依赖该环境的性能验收。执行记录见 §9。  
规范：[86 多设备规范](86-multi-host-multi-device-design.md)；验收：[89 验证计划](89-multi-device-verification.md)；独立增量：[90](90-multi-device-increment-design.md)。

## 1. 自检结论

### 1.1 可以开始实现

规范层已经具备实现所需的全部结构：实体与作用域、状态集合、期限、`uncertain` 范围、线协议字段与合法组合、错误码、容量预算、迁移规则。剩余未定项全部是**局部数值或优先级**，不是架构缺口，可以按本计划给出的默认值实现并用测试固定。

自检中发现并已修正的 4 处规范与现有实现不对齐（已写入 86）：

| 项 | 结论 |
| --- | --- |
| 控制路径 | `/sayin/v3`、`/sayin/v3/images`，Upgrade 阶段拒绝其他路径 |
| 查询批次结束 | 沿用现有 `queried` 消息名，携带 `ref`；v2 实现已有该形状 |
| 取消/放行 | 统一为 `control` 消息，`name=cancel|release`，`refs` 恰好一项，携带 `target`/`expected_rev`/`expected_request_rev` |
| 接收目标归属 | `target` 是主机级、所有配对共享；v3 移除 v2 的 `active_device_id` |

### 1.2 已固定、实现期不再讨论的值

| 项 | 值 | 位置 |
| --- | --- | --- |
| 执行期限 | 从成为可执行队首起 30 秒 | 86 §5.2 |
| `held` 保留期 | 10 分钟，到期 `hold_expired` | 86 §5.2 |
| 查询批次 | 64 个 id | 86 §6.3 |
| 入站控制帧 / 提交载荷 | 65,536 / 32,768 字节 | 86 §6.1 |
| 文本上限 | 4,000 标量 / 16,384 字节 | 86 §6.1 |
| 出站帧 | 8,192 字节；应用名转义上界 1,152 | 86 §6.8 |
| 连接与授权 | 16 总槽 / 8 控制 / 2 图片 / 4 握手预留 / 8 观察者 / 8 已授权手机 | 86 §7.1 |
| 待执行请求 | 每 pair 16 项 64 KiB；全机 32 项 128 KiB | 86 §7.1 |
| 前台观察新鲜度 | `foreground_ttl_ms` 首轮 5,000，由 `welcome.limits` 下发 | 本篇固定 |

### 1.3 必须由实现阶段测出来、不能事先拍板的值

| 项 | 何时定 | 判定方式 |
| --- | --- | --- |
| 性能门槛 p95 | P0 结束前填写 | 89 §6，空表禁止进入 P8 |
| 4,096 字节是否够装 48×48 无损 WebP | P9 原型 | 真实图标样本分布 |
| 多图整批还是逐张 | P9 原型 | 90 §3 |
| 图片槽公平策略的具体轮转参数 | P6 | 容量测试 |

### 1.4 不可回避的代价

v3 整体替换 v2，不保留双协议。因此 P4 与 P5 之间必然出现"电脑端 v3、手机端 v2"的不可用窗口。本计划用里程碑 M1–M4 控制这个窗口，而不是保留兼容层。

## 2. 测试分层与执行口径

| 层 | 内容 | 运行方式 | 约束 |
| --- | --- | --- | --- |
| L0 单元 | 纯函数、状态机、协议编解码、时钟注入 | `cargo test -p <crate>` ／ `:app:testDebugUnitTest` | 无 I/O、无真实时间、无网络 |
| L1 组件 | 临时 SQLite、临时目录、假执行器 | `cargo test -p receiver` | 每个用例独立库/目录，不共享状态 |
| L2 集成 | loopback WSS + FakeExecutor + 独立测试包 | `scripts/verify_android_transport.ps1 -FullSuite` | 不碰日常配对与数据 |
| L3 真机 | 89 的 M 用例、真实窗口、真实 Wi-Fi | 手工 + 脚本留证 | 必须单独留证，不能用 L0–L2 代替 |

强制规则（对应 AGENTS §6）：

- 每个任务先写会失败的测试，运行并记录失败原因，再写最小实现；
- 禁止用 `sleep` 等待期限；期限、退避、保留期全部通过注入时钟推进；
- 禁止削弱断言、跳过用例、删除失败用例来制造通过；
- 每个任务完成时必须给出：执行命令、关键输出、修改前后行为差异、未覆盖风险；
- 阶段退出以 89 的退出条件为准，不以"代码写完"为准。

## 3. 里程碑

| 里程碑 | 定义 | 可运行状态 |
| --- | --- | --- |
| M1 | 电脑端 v3 能在 loopback 上完成"配对→提交→执行→回执" | 电脑端测试全绿；手机仍是 v2，不能连 |
| M2 | Android 切换到 v3，单对单真机文字落字 | 日常包可安装，单对单可用 |
| M3 | 多配对并存：2 电脑 / 2 手机交叉 | 多设备可用；图片暂缺 |
| M4 | `uncertain`、图片、迁移、取消/放行完整 | 功能完整，进入真机验收 |

里程碑之间不插入"临时兼容分支"；M1–M2 期间日常包可以短暂不可连，这是既定代价。

## 4. 任务卡

格式：**红**（先写的失败测试）→ **绿**（最小实现）→ **回归**（必跑）→ **退出**（可判定条件）。

### P0 契约冻结与基线

**T01 冻结 v2 基线**  
依赖：无。  
红：不适用（基线任务）。  
做法：按 89 §1 执行 7 条基线命令，结果与失败集合写入 `docs/research/91-baseline-<date>.json`，APK/数据库快照只读保存。  
退出：基线 JSON 存在且包含命令、提交、SHA-256、失败用例清单。

**T02 v3 线类型与严格反序列化**  
依赖：T01。  
红：`desktop/crates/receiver/src/protocol_v3.rs` 新增测试：未知 `stage` 拒绝、重复键拒绝、根非对象拒绝、`request_rev` 非十进制字符串拒绝、`accept_order` 超 2^63−1 拒绝、缺 `execution` 的迁移回执可解析。  
绿：只写类型与解析，不接数据库。  
退出：类型层测试全绿，且 v2 类型仍然编译。

**T03 黄金向量**  
依赖：T02。  
红：`docs/research/91-golden/*.json` + 测试逐条 round-trip；覆盖 86 §6.4 组合表每一行、`unknown` 形状、含图标与省略图标的 8,192 边界。  
绿：序列化实现。  
退出：组合表中每个合法组合都有向量，每个非法组合都有拒绝测试。

**T04 错误码穷举**  
依赖：T02。  
红：表驱动测试，遍历 86 §6.7 的 17 个码，断言每个码都能被构造、序列化并映射到客户端提示；断言不存在未列出的码。  
退出：新增错误码必须先改规范，测试才能通过。

### P1 账本 schema 7 与状态机

**T05 schema 7 迁移（已完成）**  
依赖：T01。  
红：`ledger.rs` 测试：从 schema 6 升级后保留收据/去重/历史；`receipts.device` 语义统一为 `pair_id`（现有 `devices` 表与 `UNIQUE(device,id)` 一并调整）；`method` 已存在，新增 `request_rev`、`execution_stage`、`confirmed`、`uncertain_scope`、`hold_expires_at` 且默认值正确；缺少旧结构时拒绝打开而不是静默重建。  
绿：迁移脚本与版本检查。  
退出：旧库升级、空库新建、版本过新拒绝三条路径都有测试。

实现：schema 7 已落地，schema 1–6 可升级，缺失结构和过新版本均拒绝打开。新增字段默认值、pair 隔离、状态触发器和终态保护均有测试。

**T06 状态转移表（已完成）**  
依赖：T05。  
红：对 86 §5.3 的 10 个状态 × 事件做表驱动测试：合法转移成功，非法转移返回 `STATE_CONFLICT` 且不改行。覆盖 `executing → uncertain`、`held → accepted`（放行）、`uncertain → uncertain_acknowledged`、以及"终态不再变化"。  
退出：非法转移零漏网；终态复写测试失败。

实现：合法状态转移、`STATE_CONFLICT`、原子回滚、`uncertain_acknowledged` 和终态元数据不可重写均已覆盖。

**T07 `request_rev` 与 `accept_order`（已完成）**  
依赖：T05。  
红：同一请求 rev 严格递增；并发两条写只允许一条推进；`query` 不推进 rev；`accept_order` 全机唯一且按接受顺序；重启后不回退。  
退出：重启、并发、回读三种场景通过。

实现：状态变更自动推进 `request_rev`，查询不推进版本，并发写入只允许一条成功，`accept_order` 全局递增且重启后不回退。达到 SQLite 最大整数时拒绝继续推进。

**T08 配额与计数（已完成）**  
依赖：T05。  
红：每 pair 16 项 / 64 KiB、全机 32 项 / 128 KiB 的边界（恰好通过、超一项拒绝）；终态不占配额；取消记录单独计数；转移快照不占授权名额。  
退出：86 §7.2 每行计数口径都有测试。

实现：每 pair 16 项/64 KiB、全机 32 项/128 KiB 的精确边界、终态释放配额、取消独立计数和重配转移快照均已覆盖。

### P2 调度、期限与 `held`

**T09 可执行队首与公平**  
依赖：T06。  
红：两对配对各有多条请求时按 `accept_order` 交替；`held` 占队首时本配对后续不越过；另一配对不受影响。  
退出：队首选择函数是纯函数并有全组合测试。

**T10 执行期限**  
依赖：T09。  
红：注入时钟；成为队首记录 `executable_at`；恰好 30 秒通过、30.001 秒 `expired`；`release` 不重置；排队等待不启动期限。  
退出：四个场景通过，且没有真实 `sleep`。

**T11 `held` 保留期**  
依赖：T10。  
红：进入 `held` 后 10 分钟内可放行；到点 `expired`/`hold_expired`；保留期不因暂停/查询刷新而延长；放行后从可执行时刻重新计 30 秒。  
退出：M21 对应的 L1 用例全绿。

**T12 重启恢复矩阵**  
依赖：T10、T11。  
红：`executing → uncertain`、`accepted → expired`/`restart_confirmation`、`held` 保留且保留期继续流逝；重启不重放已注入请求；协调线程主动过期并通知。  
退出：重启前后状态对比表与 86 §5.2 完全一致。

### P3 `uncertain` 与闸门

**T13 范围分类**  
依赖：T06。  
红：纯文字（无剪贴板/按键/未知原生调用）→ `target`；按键、Enter、剪贴板、图片、路径、无法分类调用 → `global`；`uncertain_scope` 必须出现在回执中。  
退出：分类函数对每种 `op.kind` 有测试，默认分支为 `global`。

**T14 目标窗口阻断**  
依赖：T13。  
红：同一窗口（进程映像+创建时间+句柄）的请求转 `held`/`uncertain_target`；不同窗口不受影响；窗口重建后不再阻断；范围清除后自动转回 `accepted` 重新计时。  
退出：4 个场景通过，且阻断判定使用执行时快照而不是接受时 `target`。

**T15 多 `uncertain` 并存**  
依赖：T13。  
红：`global` 优先核对；解除 `global` 不清除 `target`；每条单独 `uncertain_acknowledged`；核对绑定 `(request_id, request_rev, state_rev)`，任一变化返回 `STATE_CONFLICT`。  
退出：与 89 M26 对应的 L1 用例全绿。

**T16 闸门代次**  
依赖：T13。  
红：较早的恢复不能越过较晚的暂停；暂停期间不执行；本地 pause/resume 推进 `state_rev`，手机 `control` 发 pause 返回 `UNSUPPORTED`。  
退出：与现有 gate 测试合并后全绿，无退化。

### P4 传输 v3

**T17 路径与 Upgrade**  
依赖：T02。  
红：`/sayin/v3` 与 `/sayin/v3/images` 接受；`/v2` 与任意其他路径拒绝；带 `Origin` 拒绝；超过 65,536 字节拒绝。  
退出：4 条路径/边界测试通过。

**T18 `hello`/`welcome`/fencing**  
依赖：T05、T17。  
红：`welcome` 含 `host_id`、`pair_id`、`session_id`、`state_rev`、`paused`、`pause_reason`、`target`、`capabilities`、`limits`（含 `foreground_ttl_ms`）、`image`；不含 `active_device_id`；新 `hello` 使旧 session 收 `STALE_SESSION` 且不能再提交。  
退出：同一 pair 两会话、旧会话迟到提交两条用例通过。

**T19 查询批次**  
依赖：T02、T18。  
红：有界 64 个 id；每个 id 回 `status` 或 `unknown`；`unknown` 不带 `accept_order`/`request_rev`/`execution`；批次以 `queried` 结束；错误带 `ref`/`id`；提交在途时查询同一 UUID 的行为符合规范。  
退出：与 89 M22 对应的 L2 用例全绿。

**T20 取消与放行**  
依赖：T19。  
红：`control` 只接受 `cancel`/`release`；`refs` 恰好一项；`release` 版本不匹配 `STATE_CONFLICT`；放行只动本请求且推进 `state_rev`；`cancel` 幂等且对未出现请求写取消记录。  
退出：8 条组合测试通过。

**T21 出站预算**  
依赖：T02。  
红：含图标回执 8,192 通过、8,193 省略图标；应用名 96 个星平面字符仍不超帧；超长应用名被拒绝但会话不断；`foreground` 与 `status` 共用预算。  
退出：与 89 M27 对应的 L0 用例全绿。

**T22 连接槽与计数**  
依赖：T17。  
红：16 槽、8 控制、2 图片、4 握手预留；槽满时旧授权重连优先；未认证握手不长期占用；撤销后 socket 立即关闭。  
退出：并发连接测试无超卖。

### P5 Android

**T23 host/pair 凭据目录**  
依赖：T18。  
红：按 `host_id` 保存多段凭据；每段独立 Keystore 加密；缺失/篡改拒绝读取且不新建身份；重配不丢目标选择。  
退出：数据层 L0 测试 + 仪器化存储测试通过。

**T24 Outbox 双范围隔离**  
依赖：T23。  
红：`saved` 项按 `(host, pair)` 隔离；重连先查询；`unknown` 只在提交结果返回后可解释；重配前 Outbox 非空拒绝并返回 `PAIR_REPLACE_BLOCKED`。  
退出：与 89 M11/M23 对应的 JVM 用例全绿。

**T25 回执展示映射**  
依赖：T24。  
红：10 个状态 × 4 种输入类型映射到本地资源；`uncertain_scope` 决定提示；只有 `injected` 清空草稿；`failed/file_saved` 显示"已保存未粘贴"；迁移回执显示"执行方式未记录"。  
退出：映射表测试覆盖每格，无硬编码文案。

**T26 目标选择、重连与退避**  
依赖：T23。  
红：多电脑时记住最后选择；目标离线保留草稿；1/2/5/10 秒退避；切目标只回收空闲连接；3 台以上冷连接时 UI 显示连接中。  
退出：与 89 M10 对应的状态测试通过。

### P6 图片通道

**T27 绑定链**  
依赖：T17。  
红：`image_hello` 缺 binding 拒绝；控制会话断开后上传作废；跨 pair 复用拒绝；撤销/范围轮换后迟到块丢弃。  
退出：每一步的拒绝路径都有测试。

**T28 资源与公平**  
依赖：T27。  
红：10 MiB、1600 万像素、8192 单边、48 KiB 块边界；每 pair 1、全机 2；单一 pair 不能长期占满两槽（阈值见 P0 填写）。  
退出：配额与公平测试通过。

**T29 手机图片草稿**  
依赖：T24、T27。  
红：一次请求一张；`injected` 后清空草稿；`failed` 保留；删除草稿不删历史引用。  
退出：与 89 §5 对应的 JVM 用例全绿。

### P7 迁移

**T30 Windows 数据迁移**  
依赖：T05。  
红：`device_id → pair_id` 映射；收据状态按 86 §8.1 收敛；旧 `uncertain` 回填 `global`；旧回执缺 `execution` 时缺省；中止后重开可重入；密钥丢失拒绝启动。  
退出：89 §2 的迁移用例全绿。

**T31 Android 迁移**  
依赖：T23。  
红：单主机凭据导入目录；Outbox/历史/图片引用迁移；旧草稿按 `(pair_id, request_id)` 恢复；失败保留原数据并禁止发送。  
退出：升级前后数据对比脚本输出一致。

### P8 真机与性能

**T32 填写性能门槛**  
依赖：T26。  
红：不适用。  
做法：把 89 §6 的 8 项填成具体 p95、超时率、样本数，写入 89 并冻结。空表禁止继续。  
退出：89 §6 无空项。

**T33 执行 M1–M28**  
依赖：T32、T30、T31。  
做法：按 89 §4 在真实 2×2 上执行；L3 证据单独归档。  
退出：89 §7 全部条件满足。

### P9 独立增量

**T34 紧凑二维码**：固定 71 字节与 Base45，断言 114 字符 / V5-M / 37×37；库升级后重新断言。  
**T35 WebP 图标**：实测 4,096 字节覆盖率；超限省略；手机解码。  
**T36 多图原型**：先证明一次原生操作能否交付整批；能则一个 UUID，不能则逐图；不得双模式并存。

三项互不阻塞，可并行；任一项未完成不阻塞核心 v3 退出。

## 5. 状态与事件测试矩阵（T06 展开）

| 当前 | 事件 | 期望 |
| --- | --- | --- |
| `accepted` | 成为队首 | 记录 `executable_at`，状态不变 |
| `accepted` | 开始执行 | `executing` |
| `accepted` | 30 秒到期 | `expired` |
| `accepted` | 取消 | `cancelled` |
| `accepted` | 闸门关闭 | `held` + reason |
| `accepted` | 命中 `target` 阻断 | `held`/`uncertain_target` |
| `held` | 放行 | `accepted` + 重新计时 |
| `held` | 保留期到期 | `expired`/`hold_expired` |
| `held` | 取消 | `cancelled` |
| `held` | 阻断范围清除 | `accepted` + 重新计时 |
| `executing` | 输入提交完成 | `injected` |
| `executing` | 确认未提交 | `failed` |
| `executing` | 部分提交无法排除 | `uncertain` + scope |
| `executing` | 进程重启 | `uncertain` |
| `uncertain` | 本地核对 | `uncertain_acknowledged` |
| `uncertain_acknowledged` | 任意事件 | 拒绝，状态不变 |
| `injected/failed/cancelled/expired` | 任意事件 | 拒绝，状态不变 |

## 6. 回归与交付

每个任务结束：

```text
cargo test -p <crate> --all-targets
cargo clippy -p <crate> --all-targets -- -D warnings
```

每个阶段结束：

```text
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
scripts/gradle.ps1 :app:testDebugUnitTest
scripts/gradle.ps1 :app:lintDebug
```

M2 之后每个里程碑还要：

```text
scripts/gradle.ps1 :app:assembleDebug
adb -s <序列号> install -r android/app/build/outputs/apk/debug/app-debug.apk
scripts/verify_android_transport.ps1 -FullSuite
```

不得把编译通过、构建成功或 loopback 通过描述为真机落字通过。

## 7. 风险与处置

| 风险 | 处置 |
| --- | --- |
| v3 切换期日常包不可连 | 用 M1–M4 控制窗口；不保留兼容层 |
| `target` 阻断误伤 | 阻断判定必须用执行时窗口快照；L1 覆盖窗口重建场景 |
| `held` 保留期造成重配锁死 | 保留期有界；M24 验证重配可解除 |
| 迁移把旧 `accepted` 复活 | 迁移表规定收敛为 `expired`；T30 断言 |
| `query` 与在途提交竞态 | 规范禁止在途查询；T19 覆盖 |
| 性能门槛长期空白 | T32 在 P8 前强制冻结，空表即阻断 |

## 8. 完成定义

同时满足才视为一个任务完成：

1. 红测试先失败，失败原因与预期一致；
2. 最小实现使测试转绿；
3. 相关既有测试无退化；
4. 状态转移、期限、配额、错误码改动有对应测试；
5. 没有为通过测试而弱化断言或跳过用例；
6. 没有留下临时兼容分支或未登记 TODO；
7. 交付说明包含命令、输出与实际行为差异。

整体完成以 89 §7 的退出条件为准，不以本计划的勾选为准。

## 9. 执行记录（2026-11-26）

分支 `feat/sayin-v3`。v2 基线提交 `98bea67`。规范澄清已单独提交，`df8b06e` 确定了保留、接受前取消和重启回执规则，`a1aa313` 确定了错误帧形状。

| 阶段 | 任务 | 状态 | 阶段提交 SHA |
| --- | --- | --- | --- |
| P0 | T01 | 完成基线归档。保留首轮失败与复跑结果，修复 v2 前台帧溢出，保存可读回的 DPAPI／Keystore 匹配样本。见 [基线清单](research/91-baseline-2026-11-26.json)。 | P0 本提交 |
| P0 | T02 | 严格回执解析红、绿和 receiver 回归已通过。 | P0 本提交 |
| P0 | T03 | 73 个合法执行组合及其余 467 个组合、查询未命中和帧预算均通过。图标样本用于测量编码长度，真实 WebP 的编码和解码在 P9 验收。 | P0 本提交 |
| P0 | T04 | 17 个错误码与规范一致，16 个可出站，`PAIR_ALREADY_EXISTS` 只在手机本地映射。Rust 和 Android 测试均通过。 | P0 本提交 |
| P1 | T05–T08 | 完成。schema 7、状态机、request_rev/accept_order、配额与计数均已实现并通过测试。 | `ed65f23` |
| P2 | T09 | 完成。按各配对队首的 `accept_order` 调度，`held` 保留本配对顺序，其他配对可以执行。 | `feat(receiver)!: add fair scheduling and durable deadlines` |
| P2 | T10 | 完成。成为可执行队首后才开始计时，恰好 30 秒仍可执行，30.001 秒过期。注入时钟覆盖等待与重复放行。 | 同 T09 |
| P2 | T11 | 完成。`held` 独立保留 10 分钟，到期推送 `expired/hold_expired`，暂停和查询不续期。 | 同 T09 |
| P2 | T12 | 完成。重启把 `executing` 转为 `uncertain`、`accepted` 转为 `expired/restart_confirmation`，保留 `held` 的原期限，主动推送到期回执。 | 同 T09 |
| P3–P9 | T13–T36 | 待实施。真实环境验收暂缓，其余任务继续。 | — |

| 门禁 | 实际结果 |
| --- | --- |
| Rust 工作区测试 | `cargo +1.99.1 test --manifest-path desktop/Cargo.toml --workspace --all-targets --locked` 通过，0 失败。receiver ledger 41 项、receiver 单元 49 项均通过。 |
| Clippy、包级 rustfmt、i18n、桌面依赖检查 | `clippy -D warnings`、包级 `fmt --check`、i18n 和桌面依赖检查均通过。 |
| Android JVM 与 lint | `:app:testDebugUnitTest` 和 `:app:lintDebug` 均成功。 |
| Redmi K40 v2 隔离回归 | 文字/UI 首轮 126 通过、36 失败、7 条件跳过。系统控制中心抢占焦点，单独复跑剪贴板 4 项全过。修正清单数量后全套复跑文字/UI 162 通过、0 失败、7 条件跳过，图片 4 通过、0 失败。 |
| 升级样本捕获 | 额外的 v2 留存测试 1 通过、0 失败。Android Room schema 5、Windows SQLite schema 6，双方的 `injected`、`expired`、`uncertain` 各 1 条。旧版本可解密，原地升级验证仍待 P7。 |

关键行为对比：

| 项目 | 修改前 | 实测修改后 |
| --- | --- | --- |
| v2 `foreground` | 构造的 8,193 字节帧超限，触发现有出站拒绝。 | 8,192 字节保留图标。超预算保留名称和序号，省略图标。超长名称保留序号，省略显示元数据。 |
| v3 回执 | 无 v3 解析及黄金向量。 | 拒绝非法形状、重复键和溢出。查询未命中不带回执字段，迁移帧可保留缺省执行字段。 |
| v3 状态和错误 | 活跃连接使用 v2。 | v3 编解码和 Android 本地文案通过测试，活跃连接仍使用 v2。 |

用户确认暂缓真实 2×2，继续实施。当前已有的 K40 证据来自 ADB reverse 和假输入。89 §6 的真实局域网、2×2 并发、真实落字和功耗证据及相应门槛保留为未执行。v3 迁移、配对隔离、状态机、输入副作用、配额与故障注入依次由后续阶段验收。

### P2 门禁与行为对比

- Rust 1.99.1 工作区 `test --workspace --all-targets --locked` 共 185 项通过，0 失败。receiver 单元 50 项、ledger 45 项、worker 15 项、transport 网络 8 项。loopback 到期推送用例先因夹具缺少 DPAPI 账本密钥失败，改为通过正式初始化入口注入测试时钟后，定向测试 1 项和工作区测试均通过。
- 工作区 `clippy --all-targets --locked -- -D warnings`、`build --workspace --locked`、包级 `fmt --check`、i18n 和桌面依赖检查通过。Android `:app:testDebugUnitTest` 和 `:app:lintDebug` 成功，测试与 lint 复用有效构建产物。
- 真实 2×2、真实局域网与真机落字未执行。P2 网络证据来自 loopback TLS 和测试输入适配器。

| 项目 | 修改前 | 实测修改后 |
| --- | --- | --- |
| 调度 | 只接受活动设备，单一队首阻断后续请求。 | 各配对只看本配对队首，取最早的可执行队首。某配对被阻断时，其他配对继续。 |
| 执行期限 | `accepted` 和 `held` 共用接受后 30 秒期限。 | `accepted` 从成为可执行队首起计时。`held` 自进入起保留 10 分钟，放行并成为可执行队首时启动新的 30 秒期限。 |
| 重启 | 待执行请求进入 `held`，恢复后缺少原单调时钟期限。 | `accepted` 终止为 `expired/restart_confirmation`。`held` 保留原期限。`executing` 进入 `uncertain`，已 `injected` 的请求保持原终态。 |
