# SayIn 多设备 v3 TDD 实施计划

更新：2026-11-26。  
状态：P0–P7 工程门禁通过。P8 有一对一真实局域网验证记录，真实 2×2 按用户安排暂缓。P9 的紧凑二维码和 WebP 已实现，多图原型已完成。执行记录见 §9。  
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
| P3 | T13–T16 | 完成。输入不确定性按目标窗口或全局范围分类，审查票据绑定请求与控制代次，局部与全局闸门均通过回归测试。 | 本提交 |
| P4 | T17–T22 | 完成。v3 路径、认证、fencing、查询边界、控制错误、出站预算和连接槽回归通过。 | `d780826` |
| P5 | T23–T26 | 持久候选、主动重配、每配对退避和上一目标的未决回执连接已接入。三目标隔离回归、草稿与 Outbox 同事务落盘、K40 日常包安装通过。 | 本提交 |
| P6 | T27–T29 | 双配对可同时上传并登记资源，绑定拒绝与 Android 图片草稿隔离回归通过。一对一真实 LAN 原生图片已验证，真实 2×2 暂缓。 | `bfbcbc4` |
| P7 | T30–T31 | 旧配对、草稿、文字及图片回执原地升级通过，原图片可解密，K40 隔离全套和日常包安装通过。 | 本提交 |
| P8 | T32–T33 | 已取得 1×1 真实 LAN 发现、图片粘贴和暂停恢复证据。完整性能门槛、2×2 和 M1–M28 验收待完成。 | — |
| P9 | T34 | 71 字节邀请、V5-M 原生渲染、K40 短码和二维码数据配对通过。物理相机对屏待验证。 | — |
| P9 | T35 | 无损 WebP、完整帧预算、本机样本和 K40 解码与加密历史恢复通过。 | 本提交 |
| P9 | T36 | Windows 原生 RichEdit 原型已比较整批、逐张和局部遗漏。Android 多选与逐张发送产品化待实施。 | — |

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

### P3 门禁与行为对比

- `InputOutcome::PartialUnicode` 仅在已知输入窗口的纯文字操作中归类为 `target`，其余不确定结果归类为 `global`。目标窗口使用持久化 HMAC 身份，窗口重建后不会继续阻断旧窗口。
- `global` 不确定结果暂停全局输入并优先进入审查，`target` 不确定结果只把同一窗口的请求置为 `held/uncertain_target`。全局审查解除后，目标范围仍按各自窗口独立处理。
- `ReviewTicket` 绑定 `pair_id`、`request_id`、`request_rev`、`state_rev` 和范围。旧票据、旧控制代次或不同请求均返回 `STATE_CONFLICT`。每条已核对记录转为 `uncertain_acknowledged`，不会再次执行或重试。
- 桌面端本地核对只接受当前快照对应的具体票据。重启恢复后即使没有当前窗口目标，也能审查全局不确定结果，确认后创建新的前台作用域。
- 已通过完整 workspace 测试。`cargo +1.99.1 test --manifest-path desktop/Cargo.toml --workspace --all-targets --locked` 的全部测试目标通过，0 失败，其中桌面 shell 13 项、receiver 单元 51 项、ledger 50 项、worker 15 项、transport 网络 8 项。
- 已通过 `cargo +1.99.1 clippy --manifest-path desktop/Cargo.toml --workspace --all-targets --locked -- -D warnings`、包级 `cargo +1.99.1 fmt --check`、i18n 检查和桌面依赖检查。真实 2×2、真实局域网、真机落字和性能测量仍未执行。

### P4 门禁与行为对比

- v3 控制路径固定为 `/sayin/v3`，图片路径固定为 `/sayin/v3/images`。旧路径、带查询参数的路径和带 `Origin` 的 Upgrade 均返回 403。
- `welcome` 使用 `pair_id` 和图片通道描述，包含 `foreground_ttl_ms=5000`，不再发送 `active_device_id`。同一 pair 的新会话会使旧会话收到 `STALE_SESSION`。
- 首条应用消息必须是空或有界 `query`。查询最多 64 个 id，每个 id 返回 `status` 或 `unknown`，批次以 `queried` 结束。手机控制只接受 `cancel` 和 `release`，`pause` 返回 `UNSUPPORTED`。
- 接收端新回执从 `not_started/none` 开始持久化执行元数据，执行阶段更新为 `prepared`，完成回执映射到合法 v3 输入方式和阶段。测试适配器的内部方法名不会泄漏到线协议。
- loopback WSS 网络测试 8 项通过，transport 单元测试 13 项通过。真实 2×2、真实局域网、真机落字和性能测量仍未执行。

### P5 门禁与行为对比

- T23 从单份凭据扩展到按 `(host_id, pair_id)` 保存。手机先用 Keystore 加密落盘，再在同一固定证书连接确认，桌面随后才显示含来源 IP 的批准。桌面旧实现红测等待 `pair_candidate` 超时。修复后，批准前候选认证、跨 socket 确认、错误 ID 均被拒绝，断开释放占位。K40 复验了已批准候选恢复、明确拒绝后清理候选，以及旧回执查询未重放。历史继续使用原配对的密钥读取。
- 主动重配需要桌面选中原设备和手机持有原授权。桌面 loopback 测试覆盖错误旧 token、旧会话不被提前关闭、批准后只保留一个有效配对、旧 token 失效。Android 通过加密候选原子替换可发送凭据，保留历史解密密钥。
- T24 从单一 Outbox 配额扩展到配对和主机两层。每配对最多 16 条、64 KiB、1 张图片，每主机最多 32 条、128 KiB、2 张图片。每次连接先查旧回执，查询结束才允许发送，结果不明的请求进入手动核对。
- T25 统一回执标题，补充 `uncertain_scope`、`file_saved` 和缺失执行方式的文案。`uncertain` 可继续接收核对后的回执。JVM 展示映射测试通过。
- T26 退避序列 1/2/5/10 秒按主机与配对独立。原切换关闭旧连接，当前保留仍有未决回执的上一目标，并隔离两个控制会话。K40 三目标 ADB reverse 测试中，B 发送时 A 可取消并收回执。切到 C 后，B 保持可查询与取消，A 冷却并在再选中时重新建连。三个隔离电脑共计 1 次 fake 输入，未重放。
- K40 首次复跑安装曾返回 `INSTALL_FAILED_USER_RESTRICTED`。后续使用原隔离脚本成功安装应用 APK 和测试 APK，设备回归继续验证。
- 本轮日常 APK 通过 `:app:assembleDebug` 后执行覆盖安装。K40 的 MIUI `AdbInstallActivity` 恢复前台约 59 ms 后以 `app-request` 退出，ADB 返回 `INSTALL_FAILED_USER_RESTRICTED`。当时包管理器未登记 `seeyuer.sayin`。

- K40 主动重配定向测试通过。有未决回执时拒绝重配。取消未决请求后可更换同一电脑的配对，旧配对凭据移除，原历史仍用原密钥解密。
- 边界红测中，第二个配对插入同一请求 UUID 会触发 `UNIQUE constraint failed: outbox.id`。Android Room 由 schema 7 升至 8，用主机、配对、请求 UUID 确定一条记录。历史正文、应用、图片、隐藏和回执操作沿用完整身份。升级事务保留旧行及其密文。
- 草稿定向用例首次误用了旧的应用 APK。应用和测试 APK 对齐后通过。切到 B 后解除 A 的草稿锁，A 的迟到回执和同 UUID 的 A 回执都不会清空 B 的正文。B 自己的 `injected` 回执可以清空对应草稿。

- 最新隔离 APK 在 K40 通过 12 项定向测试，0 项跳过，涵盖 Room 旧库升级、独立回执、单条隐藏、图片读取和历史筛选。`-HotTargets` 与 `-PairReplacement` 各通过 1 项，0 项跳过，各有 1 次 fake 输入。三电脑夹具中 A 和 B 使用同一个请求 UUID，A 取消后 B 的状态仍为 `injected`，两个配对的正文各自解密，无重放。该组经 ADB reverse 连接。

### P6–P7 工程验证

- 图片上传和登记原先依赖旧版全局单一活动配对。现在使用已认证会话和接收范围判定，两个配对可同时完成上传并各自登记资源。loopback WSS 网络测试 9 项通过，新增缺失 binding、跨配对错误 binding 和双配对占槽验证。
- Windows 合成 schema 1–6 迁移测试覆盖保留配对、收据、去重、历史和接受序号。新增 schema 6 `uncertain` 回填 `global`、保留缺省执行信息和重复打开的检查，定向用例通过。
- Android Room schema 7 增加回执字段，迁移保留既有 Outbox 行。图片读取按原配对解密，并读取旧文件封装中的 `device_id`。真实 2×2、真实局域网、实际输入与性能测量暂缓。

### 继续推进记录（2026-11-26）

- 修正图片重启回归夹具。手动恢复的 image outbox 记录现在使用同一条未提交草稿的 `resource_id`、加密引用和字节数，避免夹具把第一张历史图片与第三张待恢复草稿混用。该修正只统一测试场景的数据关系，不改变生产逻辑。
- Android 完整隔离门禁通过：文本/UI 165 项通过、7 项条件跳过，图片 4 项通过，均无重复 fake input。`scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -FullSuite -SkipBuild`。
- Android JVM 单元测试和 lint 通过：`:app:testDebugUnitTest`、`:app:lintDebug`。
- Rust 门禁通过：workspace 全目标测试、transport 网络 9 项、workspace clippy `-D warnings`、包级 rustfmt、i18n 和桌面依赖检查。
- 真实 2×2、真实局域网、真机实际落字、性能和功耗验收仍未执行。当前 Android 证据来自隔离安装、ADB reverse、loopback WSS、测试输入适配器和 fake input，不能替代 L3 真机证据。

### P7 原地升级验证（2026-11-26）

- 原基线留存文件的 Android Keystore 密钥已随隔离包清数据失效。本次从冻结的 `cd2b92a` 源码重建 v2 隔离应用和桌面夹具，重新捕获可解密的匹配样本。两份源码曾共享构建目录，首次捕获误用了 v3 缓存制品。核对 SQLite 版本定位后，重新构建并用 schema 6 验证来源。
- v2 留存测试 1 项通过，双方保存 `injected`、`expired`、`uncertain` 各一条。v3 通过 `adb install -r -t` 原地覆盖隔离包，保留数据库、偏好和 Keystore。原配对认证和三条旧回执查询通过，草稿与历史可解密，隐藏标记与待核对闸门保留。
- 初次升级测试在查询旧回执时失败。终态已清空 payload，原 `saveStatus` 因此跳过新元数据。现在只有已认证、配对和请求匹配、状态及接受序号相同、尚无 `request_rev` 的旧行能回填元数据，原状态、原因、正文和历史标记继续保留。空接受序号的补充测试先失败，改用 SQLite `IS` 后通过。该用例同时验证跨主机、错误状态和重复回填均被拒绝。
- Windows SQLite 实测 6 → 7。原配对、3 条回执、3 条历史的所有旧列逐项一致，原 DPAPI 文件和源快照哈希一致，`uncertain_scope=global`，`request_rev=0`，执行信息保持缺省，`integrity_check=ok`。升级期间 fake/native 输入调用均为 0。安全汇总见 [升级证据](research/91-upgrade-2026-11-26.json)。原始数据库、凭据与日志仅留在被忽略的本地构建目录。
- Rust 工作区 193 项通过，Clippy `-D warnings`、四包 rustfmt、i18n 和桌面依赖检查通过。Android JVM 243 项通过，lint 和 Debug 构建成功。新增 Room 边界设备测试 1 项通过。
- 完整设备回归期间 K40 进入安全锁屏。解锁后，原先失败的 7 项逐项复核全部通过，包含 `AssistStorageInstrumentedTest` 2 项和 `AssistPanelInstrumentedTest` 5 项。
- 在包含 T34/T35 的工作区中运行 `-FullSuite -SkipBuild -SkipInstall`，文字和界面 167 项通过、7 项条件跳过，图片 4 项通过，0 失败。两套隔离电脑身份分别记录 2 次和 1 次 fake input，未重放。
- 日常 APK 在解锁及用户确认准备后再用 `adb -s dc39c31d install -r` 尝试，MIUI `AdbInstallActivity` 进入前台后约 80 ms 退出，系统返回 `INSTALL_FAILED_USER_RESTRICTED`。设备包管理器未登记 `seeyuer.sayin`，同一设备的隔离包和测试 APK 可通过仪器化脚本安装。后续日常包安装结果见下文。
- Windows 独立原生文字夹具通过。临时 EDIT 控件收到预期的中文、emoji 和字面括号文本，原前缀保留，无附加 Enter，重复请求未执行。该验证只覆盖本机输入路径。

### P9 WebP 图标（T35）

- Windows 从原始 RGBA 编码 48×48 无损 WebP。资源管理器样本解码后逐字节匹配原始像素，包含透明度。旧 PNG 历史仍显示原应用名。名称、序号及回执在图标超出 4,096 字节或帧预算时继续传输。
- 编码格式红测试实测收到 PNG，改用 WebP 后通过；Android 解析红测试原先拒绝 WebP，改用有界 WebP 解析后通过。前台显示 7 项定向测试覆盖 8,192 字节、8,193 字节、超长应用名和图标字段上限。
- 本机 6 个可执行文件采样，4 个保留图标：资源管理器 3,496 字节、命令处理程序 1,736 字节、任务管理器 2,954 字节、Windows PowerShell 4,024 字节。记事本和 Edge 未获得符合预算的图标。此样本用于观察当前机器覆盖率。
- Rust 工作区 195 项和 Android JVM 245 项通过。Clippy、四包 rustfmt、i18n、桌面依赖检查、Android lint、Debug 构建和隔离 APK 构建通过。
- K40 运行 `scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -TransportOnly -SkipBuild`，1 项通过、0 跳过，2 次 fake input。生产 WebP 从回执进入 Android 真实位图解码并随加密历史读回。该回归使用 ADB reverse。
- 首轮全套定位到说话页测试仍提供旧 PNG，导致位图为空。将测试数据更新为 48×48 无损 WebP，保留像素核对和损坏数据拒绝断言。该项定向复跑通过，随后完整隔离门禁通过。

### P9 紧凑二维码（T34）

- 旧邀请的红测试实际输出 436 字符，预期 114 字符。紧凑布局启用后，二维码和 CPace 短码 AEAD 正文共用 71 字节数据。固定向量、非法字符、Base45 分组溢出和非法路由、UUID 均已验证。
- 手机上通过 TLS 1.3 核对叶证书 DER 摘要、SAN、有效期与自签名，确认后才发送邀请秘密，配对完成保存完整 DER。旧主机继续使用原 SAN，新主机采用 `sayin-` 前缀。错误摘要的回环 TLS 握手被拒绝。
- `scripts/verify_pairing_qr.ps1 -Iterations 3` 在 Windows 上采集 18 张原生渲染帧，包含亮暗模式和 3 个字号。ZXing 全部解码并核对 114 字符，含静区为 45×45 模块。
- Rust 工作区 196 项、Clippy、四包 rustfmt、i18n 和桌面依赖检查已通过。Android JVM 245 项、lint、Debug 构建和隔离 APK 构建通过。
- K40 短码和 `-TransportOnly -SkipInstall -QrPairing` 分别通过 1 项，均 0 跳过、2 次 fake input。扫码 Activity 配置和 JNI 定向测试 2 项通过，独立图片回归 4 项通过。配对测试以 ADB reverse 传入二维码数据。物理相机对屏扫码仍待验证。

### P9 多图原型（T36）

- `powershell.exe -NoProfile -Sta -File scripts/verification/probe_image_batch.ps1` 使用 3 张生成图片和两个本地原生 RichEdit 目标。通过 `IRichEditOle::ImportDataObject` 直接交付，读取目标 RTF 中的图片数和宽度顺序。系统剪贴板、焦点和日常应用数据保持原样。
- `RICHEDIT50W` 连续 5 组均正确收到 3 张，目标中图片宽度依次为 105、195、165 twips。整批 1 次 OLE 调用耗时 0.15～5.04 ms，逐张 3 次调用合计 0.29～0.57 ms。耗时仅覆盖本机控件调用。
- 第一轮默认 WinForms RichEdit 无法导入整批 RTF。定向复查确认 `RichEdit20W` 整批收到 0 张，逐张位图导入收到 3 张。另一个故障注入将第二张截断，`RICHEDIT50W` 返回成功且已插入第一、三张。批量 API 的成功返回无法指明第二张被跳过。
- 原型支持采用“一次选择、逐张发送”的单一产品模型。手机多选、逐张 UUID、进度与停止位置仍待实施，真实目标应用组合仍需补验。

### P8 一对一真机验证（2026-11-26）

Redmi K40（Android 13 / API 33）使用独立 `imageTest` 包，电脑使用随机 LAN 端口和隔离账本。发现与常规图片使用 Debug 夹具，原生粘贴和暂停使用 Release 接收核心。五组验证均走真实 LAN，无 ADB reverse。手机已安装的应用和测试 APK 与本地构建产物的 SHA-256 一致。

| 验证 | 实际结果 | 本地证据目录（`desktop/target/` 下） |
| --- | --- | --- |
| `verify_android_transport.ps1 -Lan -SkipBuild -SkipInstall` | 4 项通过、0 跳过，恰好 1 次 fake 输入 | `image-android-fixture-a89dbca9-ffc9-4add-8ea2-ad28ade4d593/` |
| `verify_android_discovery.ps1 -Lan -SkipInstall` | 1 项通过，NSD 找回同一主机在同 IP 上的新端口，保留原身份和暂停，恰好 1 次 fake 输入，无重放 | `discovery-fixture-17657e34-26aa-4bb1-ad5e-f2c3b8137cdf/` |
| `verify_android_discovery.ps1 -Lan -Revoked -SkipInstall` | 1 项通过，配对撤销后未恢复在线，保存地址未被新端口覆盖，恰好 1 次撤销前的 fake 输入，无重放 | `discovery-fixture-2d4435f6-13bc-42a9-9c56-e8f7dc6d641c/` |
| `verify_image_performance.ps1 -ImageDirectory refer/image-test -Native -Repeats 3 -SkipBuild -SkipInstall` | 5 张图片各发 3 次。隔离 RichEdit 确认新增 15 次，保留原文，无额外 Enter。15 次原生调用，0 次 fake 输入。3 张超 10 MiB 的源图在读取阶段拒绝 | `image-performance-ea13f5a0-56e4-476f-893e-c3e1d508100e/` |
| `verify_image_performance.ps1 -ImageDirectory refer/image-test -PauseProbe -SkipBuild -SkipInstall` | 5 次在首个 48 KiB 图片块确认后触发电脑暂停，重连保留暂停及图片草稿，无自动重放，0 次输入。手机测试标记到观察到暂停的 p50/p95 为 129.8/136.8 ms，含标记往返及夹具轮询 | `image-performance-94d6a0c5-88ea-4d53-9a06-5d26a5ff1937/` |

原生测试与 [v2 历史实测](46-image-input-production-validation.md#2-实际结果) 的 8 个源文件 SHA-256 一致，15 条归一化字节数一致。下表单位为毫秒，每轮 15 条，nearest-rank 的 p95 和 p99 均为单轮最大值。

| 耗时 | v2 历史 p50 / p95 | v3 本轮 p50 / p95 |
| --- | ---: | ---: |
| 手机准备 | 589.5 / 1,278.4 | 721.4 / 1,654.6 |
| 发送到 RichEdit 确认终态 | 1,869.8 / 3,433.0 | 2,306.6 / 4,081.3 |
| 选图到确认终态 | 2,455.9 / 4,701.9 | 2,980.3 / 5,735.9 |

v2 参照来自另一天。发送到确认终态的 p95 本轮高约 648 ms，需交替复跑固定 v2 与当前 v3，分段判断手机准备、网络、持久化及原生确认的影响。接收核心峰值工作集为 252.3 MiB，RichEdit 为 243.7 MiB，手机采样最大 PSS 为 158.9 MiB。隔离 RichEdit 观察的是单图新增次数。完整性能门槛、2×2、物理相机对屏、DHCP 换 IP 和其他实际目标应用仍待验证。

本轮 `:app:assembleDebug` 通过。日常包的 `adb install -r` 流式及非流式重试均被 MIUI `AdbInstallActivity` 拒绝，返回 `INSTALL_FAILED_USER_RESTRICTED`。当时设备包管理器未查到 `seeyuer.sayin`。

### K40 日常包安装与配对隔离复验（2026-11-26）

- 用户授权使用设备现有 Root。对日常 Debug APK 运行 `pm install -r` 返回 `Success`，包管理器登记 `seeyuer.sayin` 0.1.0。已安装 `base.apk` 与本地构建的 SHA-256 同为 `D48C4879773EB700AA2F8B4CC4682741ECAA878BDDE11CD07B0A6AFC3133273F`。
- 最终隔离 APK 的文字/UI 为 170 项通过、9 项条件跳过，图片为 4 项通过、0 项跳过。两套夹具分别记录 2 次和 1 次 fake 输入，无重放。证据目录分别为 `desktop/target/image-android-fixture-05a2ddee-094a-4998-b382-a0af6b093d7d/` 和 `desktop/target/image-android-fixture-756e1f99-743a-45c5-80f9-baa837e29571/`。Android JVM 247 项通过，lint 与 Debug 构建成功。

### 草稿事务、图片迁移与性能门槛（2026-11-26）

- P5 真机红测确认旧发送路径持久化 Outbox 后没有 Room 草稿快照，报 `no such table: speaking_draft`。Room schema 9 在一个事务保存文字 Outbox 行和含主机、配对、请求 UUID 及提交内容的 Keystore 加密快照，提交成功才允许网络发送。编辑自动保存通过同一仓库串行处理。旧加密草稿先解密校验，再把原密文导入 Room，成功后移除旧文件。
- P7 从 `cd2b92a` 导出 v2，在导出目录用 `git apply --unidiff-zero` 应用[仪器化样本补丁](../scripts/verification/v2-upgrade-image-fixture.patch)，运行 `verify_android_transport.ps1 -TransportOnly -KeepUpgradeFixture` 捕获文字和图片样本。K40 原地覆盖隔离 APK，保留原配对、草稿、3 条文字回执及 1 条图片回执。升级后核对 Android 历史图片尺寸、像素、加密草稿及全部回执，输入重放为 0。Windows SQLite 6 → 7 逐列比对一致，含 4 条回执、4 条历史及 1 条图片资源。比较工具首轮被 PowerShell 的标量参数展开阻断，修正参数数组后用同一轮冻结账本完成比对。[迁移汇总](research/91-upgrade-2026-11-26.json)
- K40 完整回归为 170 项文字/UI 通过、9 项条件跳过，以及 4 项图片通过，0 项失败。Room 故障注入确认第二次写入失败时不保存新 Outbox 行，旧草稿保留。提交后重启可读取原快照，迁移后篡改会阻止发送。三目标和主动重配各复跑 1 项通过。Android JVM 247 项、lint、Debug 构建通过，Rust 工作区 199 项、Clippy、包级 rustfmt、i18n 和依赖检查通过。日常 Debug APK 已在 K40 覆盖，安装文件 SHA-256 为 `D9086C3838BBC2566ED1185913525F81CC37C49C46DE84CDEB23502E4277E7D9`。
