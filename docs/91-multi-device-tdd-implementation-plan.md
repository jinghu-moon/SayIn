# SayIn 多设备 v3 TDD 实施计划

更新：2026-11-26。  
状态：P0–P7 工程门禁通过。P8 有一对一真实局域网验证记录，真实 2×2 按用户安排暂缓。P9 已实现紧凑二维码、WebP 和逐张多图发送，物理相机对屏仍待验证。执行记录见 §9。  
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
| P5 | T23–T26 | 持久候选、主动重配、每配对退避和上一目标的未决回执连接已接入。三目标隔离回归、草稿与 Outbox 同事务落盘、K40 日常包安装通过。 | `0e806b7` |
| P6 | T27–T29 | 双配对可同时上传并登记资源，绑定拒绝与 Android 图片草稿隔离回归通过。一对一真实 LAN 原生图片已验证，真实 2×2 暂缓。 | `bfbcbc4` |
| P7 | T30–T31 | 旧配对、草稿、文字及图片回执原地升级通过，原图片可解密，K40 隔离全套和日常包安装通过。 | `0e806b7` |
| P8 | T32 | 已冻结八项性能门槛、单次超时、允许超时率和最少样本数。按用户要求，单图登记至原生确认与手机持久终态的 p95 上限为 800 ms，点击发送至手机持久保存首条回执的 p95 上限为 150 ms（单次 500 ms，允许超时率 1%，至少 100 条）。 | 89 §6 |
| P8 | T33 | 部分通过。一对一真实 LAN 单图准备、上传和原生粘贴至持久终态三段均达标。100 张样本的终态 p95 为 752.63–772.10 ms，满足 800 ms 上限。本地持久暂停和已配对冷切换各 100 轮达标。K40 原生短文本每轮 100 条均准确入窗。K40 的 100 条真实点击至手机持久首回执 p95 为 142.49 ms，0 条超过 500 ms，通过 150 ms 门槛。该轮桌面入队与执行至终态 p95 分别为 51.72、122.07 ms，均超过各自门槛。手机可见暂停和 M1–M28 仍待完整验收，真实 2×2 暂缓。 | §9 |
| P9 | T34 | 71 字节邀请、V5-M 原生渲染、K40 短码和二维码数据配对通过。物理相机对屏待验证。 | — |
| P9 | T35 | 无损 WebP、完整帧预算、本机样本和 K40 解码与加密历史恢复通过。 | `0e806b7` |
| P9 | T36 | 已实现 Android 多选和逐张发送。K40 与 Windows RichEdit 真实 LAN 验证顺序、损坏项停止、原生路径复制停止和无重放。暂停、取消、跨目标迟到回执与配对撤销均有夹具证据。 | `a7433c8` 与 §9 |

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
- 产品采用「一次选择、逐张发送」。一次最多 10 张，每张独立 UUID，持久化回执之后再准备下一张。进度显示正在发送的序号或停止位置。旧版每次只选一张，单图仍走原上传和原生输入路径。
- `verify_android_transport.ps1 -ImageBatch -NativeBatch -Lan -SkipBuild -SkipInstall` 通过。隔离 RichEdit 读取的图片宽度依次为 465、510、465 twips，原文本 `existing|` 保留，无额外 Enter。第二个批次的第二张损坏，第三张从未进入输入路径。仪器化耗时 2.435 秒，3 次原生输入，0 次假输入。证据 `desktop/target/image-android-fixture-bc59d0d4-a076-440b-8bb7-7cb944be2a13/`。
- `-ImageBatch -BatchPathReceipt -Lan` 用夹具返回路径复制回执，验证第二张不会覆盖剪贴板中的第一条路径。一次夹具调用，耗时 1.244 秒。真实剪贴板模式仍沿用此前的单图原生验证。证据 `desktop/target/image-android-fixture-4da4eb9c-1c1d-4bd1-8f72-700c58d342fc/`。
- `verify_image_performance.ps1 -ImageDirectory refer/image-test -PauseProbe -SlowLink -SkipBuild -SkipInstall` 验证上传首块后显式取消五次、暂停五次及重连，每轮余图都停止，电脑输入为 0。仪器化耗时 51.429 秒。证据 `desktop/target/image-performance-67ca4d2b-c723-4443-a718-6e2c048473f6/`。
- Rust 199 项和 Android JVM 247 项通过，Clippy、包级 rustfmt、i18n、依赖、Android lint 和 Debug 构建通过。最终 K40 常规回归首轮有 40 项界面失败，系统控制中心占据前台。收起后先定向复验 5 项，再复跑完整回归，文字/UI 170 项、图片 4 项通过，10 项条件跳过，0 项失败。两套回归耗时 347.377 / 1.808 秒。最终 APK 的原生批次复跑耗时 1.481 秒，图片宽度与调用次数一致，证据 `desktop/target/image-android-fixture-311d6407-9d38-4b27-b31d-e29ee8f4afe8/`。
- 日常 Debug APK 已在 K40 用 `pm install -r` 覆盖，安装文件 SHA-256 为 `B8671C2F10E44C9BE421288392372A4C60D221EA3B051B75BD5B6B5B275C5626`。Windows 原生内容证据覆盖隔离 RichEdit。其他生产应用组合仍未验证。

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
- T32 的 8 项指标已填入 p95、超时和样本数。沿用 v2 短文本与冷连接目标，为服务器分段、暂停、图片和并发补充独立预算。当前口径见[验证计划](89-multi-device-verification.md#6-性能门槛)。

### T33 单图真实 LAN 采样（2026-11-26）

使用 K40 隔离 APK、Windows Release 接收核心和 RichEdit，运行 `verify_image_performance.ps1 -ImageDirectory refer/image-test -Native -Repeats 5 -SkipBuild -SkipInstall` 四轮。五张固定源图各 20 次，实际原生粘贴 100 次。四轮中超 10 MiB 的三个源文件均在读取阶段拒绝。下表用 nearest-rank，单位为毫秒。

| 路径 | p50 | p95 | p99 | 最大值 |
| --- | ---: | ---: | ---: | ---: |
| 手机选图至就绪 | 738.7 | 1,745.4 | 1,770.3 | 1,777.3 |
| 点击发送至原生粘贴及持久终态回执 | 2,119.3 | 3,951.2 | 3,983.3 | 4,028.7 |
| 选图至持久终态 | 2,865.0 | 5,686.2 | 5,731.1 | 5,733.0 |

准备耗时超过 1,300 ms 的 20 条全部来自 `005_2.04MB_5.jpg`，准备超过 4,000 ms 的样本为 0。发送总耗时包含上传和原生粘贴，两个分段各需独立计时。阈值保持现有口径。

本地 `desktop/target/image-performance-` 目录后缀依序是 `ab4bc91f-3fef-4f94-af86-ae843dd1209b`、`2242207a-faf7-4ddb-b251-5491d62943e5`、`6066a78a-a271-4a13-a60c-2cdefdc0752b`、`ba5fcd4a-9aee-4269-a83b-22e2dd05544f`。第二轮测试本身通过，脚本在 RichEdit 写完最终快照前尝试读取。随后单独从快照核对 25 张、原文本及零假输入，并修复脚本等待 RichEdit 退出。后两轮用修复后的脚本通过。

#### K40 分段复测

以相同的五张样图各跑 5 次。K40 经真实 LAN 向隔离 RichEdit 粘贴 25 次，原生调用 25 次，假输入 0 次。另有 3 张超限源图按预期被拒绝。证据位于 `desktop/target/image-performance-7dbe074d-ec78-469d-a0e5-e866147e24c5/`。

| 手机计时边界 | p50（ms） | p95（ms） |
| --- | ---: | ---: |
| 选图至就绪 | 748.9 | 1,766.2 |
| 点击发送至手机处理桌面的上传登记确认 | 1,006.6 | 2,074.8 |
| 上传登记确认至手机观察到持久终态 | 1,157.9 | 1,909.1 |
| 点击发送至手机观察到持久终态 | 2,145.2 | 3,987.7 |

2.04 MB 样图的准备耗时中位数为 1,755.2 ms。其中受保护的图片文件写入为 1,399.2 ms，包含校验、加密、写入、同步及原子替换。像素解码、编码、缩略图的中位数分别为 97.3、144.5、46.9 ms。后续需把文件写入继续分段，确定加密和同步各自的开销。登记确认前后的两段由手机单调时钟计时，跨设备的精确登记边界和 100 个分段样本仍待补齐。前两次尝试受桌面键盘操作干扰，首图进入待核对，保留原始失败记录。

#### K40 图片加密前后对照

同一 K40、相同五张固定源图各 5 次，通过真实 LAN 走正式 Android 图片准备、Room、双 WSS 及桌面登记。桌面使用假输入以避免占用工作窗口。前后端对端样本的归一化字节数与尺寸一致。

| 指标 | 整张由 Android Keystore 加密（ms） | Keystore 封装每张随机密钥（ms） |
| --- | ---: | ---: |
| 选图至就绪 p95 | 1,699.5 | 374.6 |
| 2.04 MB 源图加密中位数 | 1,332.5 | 13.7 |
| 同一源图同步写盘中位数 | 14.9 | 14.9 |
| 点击发送至手机处理上传登记确认 p95 | 2,084.1 | 673.0 |

新文件使用随机的 256 位 AES-GCM 数据密钥，由原配对的 Keystore 密钥在独立认证域封装。图片正文绑定文件格式与资源 ID，解密后继续校验主机、配对、资源引用和摘要。旧格式在读取时按原路径认证解密。新旧都在同步写入完整密文后原子发布，由 Room 保存引用。K40 回归验证旧密文读取、新格式头、密钥封装、随机数与正文篡改拒绝、串配对拒绝、重启及批次回执。测试曾因重新选图时提前读到旧资源失败，修正为等待新资源 ID 后再构造重启场景。

证据目录位于 `desktop/target/`，对照前为 `image-performance-b48f7911-9eed-4ee8-9b1c-17ce88446804`，对照后为 `image-performance-4429f4cc-749c-467b-a2a5-508aa7817cd3`。上述手机观测口径不能代替桌面登记至原生控件确认。两次测试的 3 张超限源图均被拒绝。

优化后四轮共 100 次，五张固定源图各 20 次。选图至就绪 p50 / p95 / p99 为 220.2 / 377.6 / 386.6 ms，最大值 387.2 ms，0 次超过 4,000 ms。点击发送至手机处理上传登记确认的 p95 为 673.0 ms，确认至手机观察到持久终态的 p95 为 845.7 ms。两段包含各自的手机观测开销，桌面使用假输入，按定位数据记录。其余三轮目录后缀为 `b0c9b998-0a92-4fe1-be6d-5af59e2f32ca`、`9b21c9cf-5650-43bc-a2ba-727ce27012ac`、`6dcc2611-1283-41d1-a804-9a2c5ba8cf07`。

Rust 199 项、Android JVM 247 项、K40 完整回归文字/UI 170 项及图片 4 项通过，10 项条件跳过，另有一次定向多图测试通过。Clippy、包级 rustfmt、i18n、桌面依赖检查、Android lint 与 Debug 构建通过。日常包以 Root `pm install -r` 覆盖安装，已安装 APK 的 SHA-256 与本地一致，为 `9B882BE6B795F67D9C79998D9222AB8FFB07DE6F4977806F40114C1DE5CA0416`。新加密格式已通过同机旧密文读取回归，优化后的原生桌面确认分段仍待采样。

#### 优化后单图原生分段

K40 隔离 APK 保持正式 Repository、Room、Keystore 与双 WSS 路径，Windows Release 接收端在登记成功后记录 QPC。按桌面资源 ID 与手机样本一对一匹配。RichEdit 先确认实际新增，再允许夹具返回执行结果。手机以本地历史观察到持久终态为截止。脚本用 USB ADB 在每轮前后采集单调时钟偏移区间，LAN 载荷仍走双 WSS。每轮合并后的区间宽度为 20.5–23.7 ms，包含 100 ppm 漂移预算。旧夹具红测完成 5 次粘贴后因缺少登记记录退出。一轮 25 次原生粘贴因逐次启动 ADB 导致校准区间超限，仅保留功能结果。正式四轮采用持久 ADB shell。

执行 `verify_image_performance.ps1 -DeviceSerial dc39c31d -ImageDirectory refer/image-test -Native -Repeats 5 -SkipBuild -SkipInstall` 四轮。每张固定图 20 个样本，共 100 次原生粘贴，零假输入、零重放，原文本保留。每轮 3 张超过 10 MiB 的源图均被拒绝。下表用 nearest-rank，单位为毫秒。

| 路径 | p50 | p95 | p99 | 最大值 | 结论 |
| --- | ---: | ---: | ---: | ---: | --- |
| 选图至就绪 | 221.4 | 385.8 | 389.1 | 395.5 | 通过 1,300 ms |
| 发送至桌面上传登记 | 450.9–468.2 | 767.6–790.3 | 847.6–861.5 | 926.7–947.7 | 通过 3,000 ms |
| 登记至原生确认和手机观察终态 | 1,146.8–1,164.6 | 1,948.3–1,972.0 | 1,998.8–2,020.0 | 2,012.4–2,036.1 | 超过当时的 700 ms 门槛 |

三段均无单次超时。接收核心和 RichEdit 峰值工作集为 252.8 MiB、354.4 MiB。Android 有效采样最大 PSS 为 193.9 MiB，一轮采样返回 0，按缺值处理。CPU、私有内存、磁盘和空闲功耗未采集。登记至终态包含手机提交、Windows 图片准备、实际粘贴确认和手机回执处理，需测量各子段定位。T33 仍为部分通过。

四轮本地证据目录为 `desktop/target/image-performance-`，后缀依次为 `8f00e9fe-0a82-461b-8831-5a732c9f714c`、`fa119aa9-0e5e-4efc-9763-ac3341d6af9d`、`97072275-4531-43ad-a06e-f54e199b289f`、`a2dd97b3-e65f-4ea8-8ea6-aa17e3426b28`。红测和校准超限的后缀分别为 `c2db6b2c-1496-4be8-a5c1-a82f3b03b78c`、`32767350-d107-4062-bc94-63dc11efcc67`。

使用 `-Native -ReverseImages -Repeats 1` 反向发送五图，区分源图大小与历史累积的影响。最大图先发仍用 1,592.7–1,606.9 ms，最小图最后发送用 462.9–471.1 ms。最大图的 Windows 预检、原生提交、等待 RichEdit 确认分别是 174.1、54.3、1,212.6 ms。确认段包含测试专用的 RTF 导出和解析，当前记录无法区分实际控件插入和导出开销。反向首轮的子进程日志与计时日志交错，分流后复跑证据为 `image-performance-0f8f9939-4713-402f-bb71-2481e624c73e`。这组定位样本未纳入 100 次统计。

#### 去除逐图 RTF 导出后的原生复测

性能夹具用 `EM_GETTEXTEX` 的原始文本确认原文之后恰好增加一个 `U+FFFC` 内嵌对象标记，再记录并清理本次插入。批次功能用例继续用 RTF 验证尺寸和顺序。早期尝试的 OLE 对象计数漏掉了 RichEdit 实际插入的图片，保留失败证据 `image-performance-6fbef211-d965-4993-96b6-c2bb82881ac1`。改用原始文本后的五图试跑通过，后缀为 `46811bac-8f27-4cdb-83a5-fa958ace4bc7`。

用 K40 和 Windows Release 夹具经真实 LAN 执行 `verify_image_performance.ps1 -DeviceSerial dc39c31d -Native -Repeats 5 -SkipBuild -SkipInstall` 四轮。五张固定样图各 20 次，100 次原生粘贴，假输入、重放和各段超时均为 0。时钟区间宽度 18.1–23.3 ms。下表使用 nearest-rank，单位为毫秒。

| 路径 | p50 | p95 | p99 | 最大值 | 结论 |
| --- | ---: | ---: | ---: | ---: | --- |
| 选图至就绪 | 210.5 | 383.9 | 395.4 | 396.4 | 通过 1,300 ms |
| 发送至桌面上传登记 | 388.8–406.9 | 598.6–619.5 | 659.6–682.9 | 669.2–690.0 | 通过 3,000 ms |
| 登记至原生确认和手机观察终态 | 683.9–699.1 | 1,092.1–1,115.4 | 1,318.4–1,333.0 | 1,695.9–1,719.2 | 超过当时的 700 ms 门槛 |

本轮只调整测试观测，发送与执行的生产路径保持原版本。四轮本地目录 `desktop/target/image-performance-` 的后缀依次为 `d5c4a182-da66-4c6d-9197-46c2f3d5a85f`、`c2ccd49a-c4c7-474c-ae22-1cce98be1564`、`53197272-43bf-4686-b0aa-fb3931df63f5`、`a9e15c0b-fdd5-40f8-ac8b-98544aa150ab`。Rust 工作区 199 项、Clippy 和包级 rustfmt 通过。改动后复跑 RTF 顺序及损坏项停止用例，以及路径回执停止用例，分别观察到 3 次和 1 次原生调用，假输入和重放均为 0。证据目录为 `image-android-fixture-c2796bab-bc3d-4282-a29a-69c6b483f01c`、`image-android-fixture-abba19d7-23f7-475a-a9e1-cb64ef31f0be`。

#### 单机与批次复验

- K40 连接 Windows Release 夹具，运行 `verify_android_transport.ps1 -Lan -ImageBatch -NativeBatch -BatchPathReceipt -SkipBuild -SkipInstall`。选择两图后，仅收到一条路径复制回执，手机批次停止在 1/2。Windows 导出一个非空文件，剪贴板文本为该文件的完整路径，RichEdit 保留原文且新增图片为 0，调用 1 次，假输入 0 次。第一次运行在最终快照生成前读取失败，脚本修复为通知并等待目标进程写完退出。通过记录为 `image-android-fixture-da5b9bf4-ecdb-4358-9e2a-0ca34f9e5291`。
- 普通 `-Lan -ImageBatch -NativeBatch` 复验确认顺序 `465,510,465` twips，损坏第二项停止，3 次原生粘贴、0 次假输入。证据为 `image-android-fixture-6c30b74a-5ba4-4554-9aab-e433aefcfabb`。独立 Windows 原生 `--text` 验证中日韩文字、emoji、字面量 `{ENTER}` 和原文保留，重复请求未落字。独立 `--copy-path` 核对剪贴板路径与导出原字节。
- K40 三目标和主动重配定向回归均为 1 项通过，证据为 `image-android-fixture-92db71f5-b9bc-415d-99ac-d905e7ad66f4` 与 `image-android-fixture-b0bde6f8-eb73-46ce-a5f4-9038c05797ab`。不同电脑身份在同一 Windows 上，通过 USB 回环和假输入，覆盖文字切换及旧配对收敛。
- 暂停探针经真实 LAN 执行五次，零次输入。电脑本地关闭闸门用时 2.3–2.9 ms，样本数未达到 100。手机可见暂停仍需按 §6 的同画面口径验收。证据为 `image-performance-1985a433-2fca-4602-b290-d37805e33e7a`。
- 用 `verify_image_performance.ps1 -DeviceSerial dc39c31d -PauseProbe -Repeats 100` 执行 100 轮真实 LAN 图片上传中暂停及重连。每轮核对上传中断、批次停止、配对保留和零重放；假输入和原生输入调用均为 0。Windows Release 夹具以同机单调时钟测量 `Receiver.pause()` 入口至持久化成功，p50 / p95 / p99 / 最大值为 2.73 / 3.51 / 4.50 / 5.76 ms，0 次超过 500 ms，达到本地 50 ms 门槛。手机端回显记录包含往返及夹具轮询，手机屏幕可见暂停仍需双端同画面采集。证据为 `image-performance-f8fc685f-e793-42ed-8e2d-7a5732e31a76`。前一轮使用设备上的旧测试 APK，只跑了 5 次，样本数校验正确失败，证据为 `image-performance-6d7cd81b-61c9-47a4-81bd-234743ee4fee`。
- Android JVM 247 项通过，lint、Debug 与隔离 APK 构建通过。初次 K40 全量回归的 `connectionSheetTracksDesktopGateWithoutRemoteResumeOrSending` 缺少状态节点，定向复跑和下一次全量均通过。全量复跑文字/UI 170 项、图片 4 项通过，10 项条件跳过，0 项失败。本地证据为 `image-android-fixture-8dd27189-f8ec-460c-99bc-423c153d5459` 及 `image-android-fixture-bbcfc9b2-af49-47f3-91f4-64576006974b`。Rust 工作区 199 项通过，Clippy、包级 rustfmt、i18n、桌面依赖检查和桌面构建通过。
- K40 日常包通过 Root `pm install -r` 覆盖安装，设备 APK 与本地的 SHA-256 均为 `9009E763795959C1C8708EB2D8849CEE70973FCC3AB527FC547FA02B57E60C10`。

#### 短文本与已配对冷切换

运行 `verify_android_transport.ps1 -DeviceSerial dc39c31d -Lan -ControlPerformance -SkipBuild -SkipInstall`。K40 使用隔离应用和正式 Repository、Room 与固定证书 WSS。Windows 同一台电脑启动三个独立 Release 身份。依次发送 100 条短文本到同一个热会话，收到持久终态后才发送下一条。另做 100 次已知地址的切换，每次用新认证会话 ID 验证连接确实重建，再等到手机可发送。USB 只用于驱动仪器和提取记录，桌面使用假输入，共 100 次，全部在第二个身份，零重放。

首条回执全部为 `accepted`，手机计时截止于该条回执完成 Room 状态更新。桌面在 SQLite 提交成功后记录 `accepted`、`executing` 和 `injected` 的单调时间，停机后导出，并按 UUID 逐一匹配手机样本。下表采用 nearest-rank，单位为毫秒，超时样本保留在排序中。

| 路径 | p50 | p95 | p99 | 最大值 | 超时 | 相对 89 §6 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 发送入口至手机首条持久回执 | 91.24 | 181.30 | 501.69 | 795.99 | 2/100 超过 500 ms | 超过当时的 30 ms 门槛和 1% 超时率 |
| 发送入口至手机持久终态 | 130.06 | 255.96 | 542.00 | 845.99 | — | 超过 v2 的 80 ms / 150 ms |
| 桌面持久 accepted 至 executing | 10.41 | 22.94 | 108.97 | 138.95 | 0/100 | 超过 20 ms |
| 桌面持久 executing 至终态 | 2.93 | 4.01 | 4.74 | 5.25 | 0/100 | 假输入基线低于 30 ms |
| 冷切换至可发送 | 173.94 | 229.66 | 516.25 | 616.30 | 0/100 | 通过 1,000 ms |

证据位于 `desktop/target/image-android-fixture-b63a34ac-de43-4d69-a96b-baf772bdeab2/control-segments.json`。五样本试跑目录后缀为 `3ace47ea-0d33-477d-8496-456e94328db5`。短文本在设备侧从 Repository 入口计时，桌面假输入夹具还会生成应用显示元数据，数据作为真 LAN 软件路径基线。原生目标和点击至回执的完整门槛留待原生短文本测量。

- Rust 199 项和 Android JVM 247 项通过，Clippy、包级 rustfmt、i18n、桌面依赖检查、Android lint 与 Debug 构建通过。K40 隔离全套通过文字/UI 170 项、图片 4 项，另有 10 项条件跳过。文字与图片的证据目录后缀分别为 `4754279a-a70b-40ca-945c-7f0ef1115dfb` 和 `0778f04f-6d7d-4fc5-91cb-2ef3db5e850c`。
- K40 以当前 v3 发现夹具分别复验真实 LAN 路由恢复与撤销。两组各有一次初始假输入，路由迁移保留暂停，撤销后的旧配对保持拒绝，零重放。证据目录为 `discovery-fixture-0944ba63-1ecf-49a1-9d5b-6f76d39826bd` 和 `discovery-fixture-3b281900-f321-44a2-83f7-1e04e7698088`。重建前旧夹具在协议阶段失败，日志位于 `discovery-fixture-ad674710-7777-4dea-9155-49f6ffbea757`。
- API 29 独立 x86_64 模拟器使用 `sayin.emulatorTests=true` 的隔离 APK，最低系统的 TLS 1.3 能力门禁通过，证据为 `android-platform-api29-08fbf481-8700-443d-ab06-a35739db8178`。
- API 37 x86_64 Google APIs 镜像已启动。SwiftShader、主机 GPU、软件渲染禁用 GLDMA 和 ANGLE 均未能完成 APK 安装。模拟器进程日志和设备崩溃日志显示 `SurfaceFlinger` 在 `hasReadColorBufferDma` 断言中退出，非流式安装亦出现包管理服务断开。权限用例未执行。日志保留在 `desktop/target/v3-api37-*-crash.log`，测试 AVD 已关闭。

#### M1–M28 证据索引

Rust 工程证据来自本次工作区回归日志 `desktop/target/v3-control-cargo-test.log`。K40 设备证据来自本节各次隔离测试。验收编号与完整断言见 [89 §4](89-multi-device-verification.md#4-多设备与竞态)。

| 编号 | 已有证据 | 仍缺的验收 |
| --- | --- | --- |
| M1 | K40 三桌面身份切换及单对单 LAN。 | 真实 2×2 暂缓。 |
| M2 | `identical_request_ids_have_independent_receipts_in_two_pairs` 在同一桌面账本验证两个配对以同一 UUID 提交不同正文，查询、执行、持久终态及去重均保持独立。K40 三目标测试也覆盖跨桌面复用 UUID。 | 物理多手机的 LAN 复验。 |
| M3 | `session_replacement_and_revocation_fence_old_senders` 验证旧会话无法提交或查询，当前会话仍能查询。双 WSS 测试验证旧上传绑定失效。 | 完整设备链路上的迟到时序实测。 |
| M4–M6 | `scheduler_orders_authenticated_pair_heads_and_skips_a_held_pair`、`target_uncertainty_holds_only_the_same_execution_window_across_pairs` 及全局副作用、部分输入的账本和 worker 测试。 | 多手机、多个原生目标窗口的执行与核对。 |
| M7、M8 | 账本配额、撤销、取消预检和部分输入闸门测试。K40 真实 LAN 撤销后旧凭据保持拒绝。 | 八台并发连接及撤销与各原生输入阶段的竞态。 |
| M9、M10 | `newer_pause_fences_resume_queued_behind_a_blocked_accept_commit`，K40 100 次上传暂停与重连，三目标待核对回环测试。 | 三个物理桌面目标的热连接数和收尾上界。 |
| M11–M13 | Android 配对策略回归、桌面单待批准槽，以及 K40 三目标同 UUID 与跨目标回执回归。 | 两台物理手机同时申请；跨目标文字草稿的 K40 Compose 回归已通过，物理多桌面联测仍待验收。 |
| M14、M15 | 注入时钟验证同配对文字等图片队首 31 秒后仍有完整执行期限。K40 顺序多图及损坏项停止通过。图片槽空闲与占满各 100 次 TLS 回环采样，单配对重复连接 100 次均被拒绝。 | 原生图片后接文字的期限，及物理多手机 LAN 的图片槽实测。 |
| M16、M17 | TLS 撤销回归、短码拒绝和账本请求限速。K40 移动路由后旧凭据未恢复连接。 | 撤销期间其他配对继续工作，以及共享 NAT 来源洪泛下的桶独立性。 |
| M18、M19 | Rust 完整帧 8,192/8,193 字节、超长应用名、修订字符串、状态单调与并发写入回归。 | 在真实连接上重放旧修订并完成端到端边界检查。 |
| M20、M21 | K40 主动重配回环测试、Rust 替换配对迁移、重启和 `held` 期限回归。 | 真实 LAN 多配对重配与未决请求并发。 |
| M22–M24 | Rust 查询缺失向量、账本保留期、恢复及顺序。K40 等待 `hold_expired` 与手机恢复回归。M22 在 K40 真 LAN 通过，M23 三条暂停请求通过确定性账本测试，M24 通过注入时钟的 TLS 回环联测。 | M23、M24 的物理设备复验。 |
| M25 | 桌面 schema 6 → 7 用同一配对查询旧 `held` 和 `uncertain`，验证元数据缺省、原序号、原文和 600 秒重新起算。已有 K40 原地迁移及缺省执行字段的展示回归。 | 按用户要求，本轮从验收范围移出旧版兼容验证。 |
| M26 | 确定性账本覆盖两个配对同时待核对。较早接收的全局阻断优先，解除后同窗口仍保持 `held`，两条独立进入 `uncertain_acknowledged`。 | 两台物理手机对应多个原生窗口的复验。 |
| M27、M28 | Rust 与 K40 使用同一份完整回执样本，检查逐字节序列化、8,192 字节、96 个补充平面字符、4,096 字节的 48×48 无损 WebP 和超长名称剔除。Rust 验证第 8,193 字节导致省略图标，并校验 73 个合法执行组合。 | 已通过同机 TLS 1.3/WSS 回环边界联测。物理多设备场景待验收。 |

补齐 M2 同一账本双配对用例和 M3 旧会话查询断言后，账本集成测试 51 项、回环网络集成测试 12 项通过，包级 rustfmt 与 Git diff 检查通过。

#### T36 批次跨目标与撤销（K40）

使用隔离 Android 应用和 Windows 夹具，配对与图片传输走真实 LAN，ADB reverse 仅用于控制夹具放行。首张图片持久进入 executing 后，分别切换目标和撤销配对。两组各 1 项通过、0 跳过、1 次假输入，桌面各保留一条持久终态。

- Switch：两套 Windows 身份。旧目标迟到终态只写入原配对历史，新目标的 38 像素草稿保持原资源 ID。切回旧目标，批次及已使用草稿均未恢复；第二个接收身份零输入。证据目录为 image-android-fixture-6030f94c-7a3f-48c2-80e0-1c305f7f4b22。
- Revoke：调用桌面正式撤销入口，手机批次停止，第二张未进入执行。已开始的首项在桌面保留原终态。证据目录为 image-android-fixture-3ec66c39-4aea-4beb-a84b-4be01db1dac9。
- 常规图片套件 4 项通过，2 个定向用例按条件跳过且已单独运行。顺序与损坏项停止回环回归通过，恰好 3 次假输入。Android 仪器包构建、桌面夹具 Clippy 和 Git diff 检查通过。只覆盖安装了 K40 的仪器 APK。

#### T33 图片槽容量与原生短文本

**图片槽 M15。** 修复前，同配对重复连接直接 EOF，且认证成功后的容量拒绝计入来源认证重试预算。修复后，绑定及会话校验通过时清除认证失败计数，达到单配对或全机上限时携带会话 ID 返回 IMAGE_UPLOAD_LIMIT，已有连接不受影响。Android 识别匹配当前会话的回执，终止本次上传并保留草稿，显示可重试提示。K40 仪器测试直接覆盖当前与错误会话 ID 和上传握手停止。新版代码的 13 项 TLS 网络回归通过。

同一电脑三个正式配对通过 TLS 1.3 WSS 回环保持控制会话。A 保持第一个活跃上传，A 的重复连接、B 申请剩余槽、C 在满槽时申请各 100 次。A 的重复连接全部明确拒绝，B 全部获准，C 全部明确拒绝。B 申请 p50/p95/p99/最大值为 2.21/3.60/17.70/27.82 ms；C 拒绝为 2.25/3.43/19.93/22.23 ms，两组各 0 次超过 1,000 ms，零输入。记录含 TLS 连接建立。证据为 m15-network-final.log，原先 EOF 的红测证据为 m15-capacity-red.log。这组是回环容量证据。物理多手机 LAN 仍需单独测量。

**原生短文本。** 执行 verify_android_transport.ps1 -DeviceSerial dc39c31d -Lan -ControlPerformance -NativeText -SkipBuild -SkipInstall。K40 向同一 Windows 上的隔离 Release 接收端发送 100 条固定的中文、emoji 与字面量 {ENTER} 混合短句，并冷切换 100 次。Windows EDIT 验证每次原文与新增内容完全一致，逐条清理。夹具确认窗口内容后返回原生执行结果。共 100 次原生调用、0 次假输入、0 次重放，无额外 Enter。样本从 Repository 发送入口开始。耗时采用 nearest-rank，单位为毫秒。

| 路径 | p50 | p95 | p99 | 最大值 | 结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| 发送入口至首条持久回执 | 108.29 | 145.50 | 164.50 | 296.44 | 超过当时的 30 ms 门槛，0 次超过 500 ms |
| 发送入口至手机持久终态 | 205.20 | 257.37 | 576.70 | 820.12 | 超过 v2 参照 80/150 ms，2 次超过 500 ms |
| 桌面持久 accepted 至 executing | 8.11 | 37.74 | 48.56 | 71.02 | 超过 20 ms，0 次超过 500 ms |
| 桌面持久 executing 至终态 | 64.35 | 90.88 | 94.01 | 114.01 | 超过 30 ms，含夹具窗口确认，0 次超过 500 ms |
| 冷切换至可发送 | 164.92 | 217.58 | 506.72 | 509.79 | 通过 1,000 ms，0 次超时 |

证据位于 image-android-fixture-1b4e0d27-8769-4684-85a6-f926b6045a95/control-segments.json。五条原生试跑位于 image-android-fixture-35760983-1b73-4f67-b27a-1eb119ec6fde。原生采样覆盖一个固定短句和 Windows EDIT，语言与换行变化由此前五句假输入基线覆盖。

**原生短文本分段复验。** 用同一 K40、真实 LAN、短句和 Release EDIT 夹具再跑两轮，各发送 100 条并核对原文、零假输入、零重放。按 UUID 关联桌面每次提交、预检、原生执行及夹具确认。各段 p95 单位为毫秒。

| 路径 | 第一轮 | 第二轮 | 89 §6 |
| --- | ---: | ---: | --- |
| 发送入口至手机首条持久回执 | 136.87 | 121.91 | 超过当时的 30 ms 门槛 |
| 桌面持久 accepted 至 executing | 10.93 | 8.15 | 低于 20 ms |
| 桌面持久 executing 至终态 | 80.00 | 77.01 | 超过 30 ms |

第二轮手机持久终态 p95 为 229.31 ms。Windows 预检、原生执行和夹具等待 EDIT 的 p95 分别为 2.72、65.06 和 18.99 ms。原生执行内部 SendInput 为 61.12 ms，执行校验、发送前和发送后守卫分别低于 1 ms。各段百分位不能直接相加。证据为 `desktop/target/image-android-fixture-589cbaaa-2a73-4aeb-9347-accaf7afdb79/control-segments.json` 和 `desktop/target/image-android-fixture-fdb61a6f-840f-4051-9d07-19d3e4466f07/control-segments.json`。

**Android 回执说明。** 中英文帮助文案对应 v3 的队首执行期限及 10 分钟暂挂保留期。i18n、JVM、Lint 和 Debug 构建通过。

**单图分段复核。** 把去除逐图 RTF 后的四轮 100 个样本按资源 ID 与接收端日志逐一对齐。原生预检、提交及等待 RichEdit 确认的 p95 分别为 176.52、64.45、243.73 ms。逐样本扣除这三段后，登记至手机持久终态的剩余时间 p95 为 702.52–720.99 ms。剩余区间包含手机提交、两端持久化、网络及回执观测，现有数据无法进一步归因。证据为 t33-image-native-segments-analysis.json。这批样本未达到当时的 700 ms 门槛。

K40 日常 APK 通过 Root pm install -r 覆盖安装，设备与本地的最终 APK SHA-256 均为 F5FBF2508E373D595F8969098F7C2C71C0D870E3493BEC2EDCAA2A00EC467F7E。新隔离包的 LAN 图片套件 5 项通过、2 项按场景跳过，1 次假输入、零重放，证据目录为 image-android-fixture-8efb3100-4eb1-441a-9a85-cab42d31a47d。

最终 Rust 201 项、Android JVM 247 项通过，Clippy、四包 rustfmt、i18n、依赖、Android lint 和 Debug 构建通过。K40 完整回归通过文字/UI 170 项、图片 5 项，11 项条件跳过，零失败，目录后缀分别为 594a6962-e8e7-4fa7-984c-07b7abdc7742 和 235ebb9f-02ee-48d3-8712-bdf4fd453422。手机 WebSocket 回执用例从 JVM 移到仪器套件，使用系统 JsonReader。协议穷举红测发现文档与 Rust 错误码表、固定数量未同步，更新 18 项契约并通过全量复验。
#### M24 保留期到点解除重配限制

TLS 回环测试 held_expiry_pushes_without_queries_and_unblocks_execution_and_replacement 使用 ManualClock 推进 600 秒。先用合法旧凭据申请并批准重配，持久事务返回 PAIR_REPLACE_BLOCKED。到期时桌面主动推送 expired / hold_expired，旧请求零输入，后续新文字输入一次。再次重配成功，新凭据查询保留原到期原因、接受序号与请求修订，旧凭据当场失效。回环网络 13 项、Clippy、包级 rustfmt 通过。
#### M22 缺失查询与显式重试

K40 隔离 Room 预存一条桌面从未收到的加密文字请求。连接与重新连接分别产生不同认证会话，正式查询结束后均为 review，保留原 UUID、正文及加密待发载荷，无接受序号、请求修订和执行阶段。显式重试原 UUID 后才收到持久 injected，桌面总共一次假输入。真实 LAN 证据目录为 image-android-fixture-1688d3ee-83a3-4b7a-9bdf-5cfbac91a9e2。原传输回环 1 项通过、2 次预期假输入，证据目录后缀为 eeccfd10-9405-420c-89c5-ebd03d9d16f0。
#### M23 暂停期间的三条请求

账本集成测试持久保存暂停期间的三条 held 和递增的接受序号。推进注入时钟到保留期最后一毫秒，恢复后仍无可执行请求，先显式放行第二条也无法越过队首。再放行首条后，验证执行期限不会因重复放行续期。首条因 expired_before_input 终止，第二条随后按队首顺序执行，第三条以 hold_expired 终止。账本集成 51 项、Clippy 和包级 rustfmt 通过。

#### M14、M25、M26 账本复验

图片执行 31 秒时，同配对文字仍为 accepted。图像结束后文字成为队首，再经过完整 30 秒仍可执行。旧 held 迁移后保留原文、接受序号和 paused 原因，保留期从迁移起算，在 599.999 秒重新打开仍保持 held，600 秒调度时转为 expired / hold_expired。旧 uncertain 为 global，请求修订为 0，执行字段缺省。全局与窗口审核共存时先处理全局票，窗口任务仍无法显式放行或执行，单独完成窗口审核后才释放。重复使用已完成的票返回 STATE_CONFLICT。53 项账本测试通过。

#### M27、M28 完整回执与帧预算

完整回执黄金样本改用真实无损 WebP。工作区测试发现 GPUI 间接启用 serde_json 的 preserve_order，使同一回执在独立包和工作区构建中产生不同键顺序。编码时按键递归排序后，两种构建产出相同字节。K40 使用系统 JSON 和位图解码器校验两份共享样本，96 字符应用名和 48×48 图标均可读取，第 97 个字符使应用元数据被拒绝。Android 测试用系统 JsonWriter 按相同键序重编码完整帧，两份样本均与 Rust 输出逐字节一致。

Rust 工作区 203 项、Android JVM 247 项、Clippy、四包 rustfmt、i18n、依赖、Android Lint 和 Debug 构建通过。K40 协议定向 5 项通过，完整回归通过文字/UI 171 项、图片 5 项，12 项条件跳过，零失败。证据为 v3-m14-m28-cargo-test-final.log、m27-m28-k40.log，设备回归目录后缀为 aa6ae304-06fb-44d6-ba0e-4a3399054593 和 21dd0bd7-1417-4fb8-a8da-a484ae3520f4。

补充同机 TLS 1.3/WSS 回环证据。由测试适配器经正常配对、提交与账本生成失败回执，客户端直接断言文本帧原始字节。96 字符应用名和 4,096 字节 WebP 在完整帧恰好 8,192 字节时保留，候选帧 8,193 字节时仅省略图标，97 字符应用名使应用对象缺省。三种结果均可按原 UUID 查询，字节与推送一致，连接持续可用。边界测试使用夹具专用的失败原因加长帧，真实多设备场景仍待验收。

#### T33 单图持久化与历史刷新分段

在 K40 和 Windows Release 接收端复用回执观测，按图片资源 ID 与请求 UUID 对齐桌面登记、accepted、executing、injected 和手机 Room 终态。手机从终态落盘至 Repository 历史可见使用同机单调时钟，跨端区间沿用 ADB 时钟校准。基线四轮各 25 张，100 次 Windows RichEdit 原生确认，零假输入、零重放。时钟偏移区间宽度 22.0–24.3 ms。基线目录后缀为 a381bbda-d1b8-46c5-aa71-3022b051482a、0b9584ea-df6d-4ec5-9282-8d4f359f0c81、da1eab6e-e99b-4632-9d81-7e5250cb75e7、2721bf5f-ea04-45e4-8cef-d8641802d0eb。

此前每次 Room 发出历史变动都会重新解密并解析整份历史列表，包含未变化的正文、应用快照和图片元数据。优化复用上一次发布快照，匹配完整 OutboxKey 与各字段密文内容后才复用成功解密的值。字段新增或改变时按原路径解密，移除的记录随快照淘汰，终态持久化顺序不变。

| 路径 | 基线 p95（ms） | 优化后 p95（ms） |
| --- | ---: | ---: |
| 手机图片准备 | 408.99 | 402.97 |
| 手机发送至桌面登记 | 720.54–742.61 | 703.40–717.49 |
| 桌面登记至持久 accepted | 109.61 | 94.93 |
| 桌面持久 accepted 至 executing | 230.93 | 281.08 |
| 桌面持久 executing 至 injected | 332.05 | 353.08 |
| 桌面持久 injected 至手机持久 injected | 113.00–136.26 | 112.00–134.60 |
| 手机持久 injected 至历史可见 | 512.42 | 28.64 |
| 桌面登记至手机持久 injected | 708.01–730.02 | 801.87–821.48 |
| 桌面登记至历史可见 | 1,200.06–1,222.13 | 814.20–833.80 |

优化后用相同样图和夹具复测四轮，100 次原生确认、零假输入、零重放，准备、上传与终态均无超时。桌面预检 p95 从 219.06 变为 272.36 ms，夹具等待 RichEdit 确认从 242.28 变为 249.79 ms。单图历史观察总延迟降低，手机持久终态超过当时的 700 ms 门槛。Android 峰值 PSS 从 201.93 变为 201.38 MiB。优化复测时钟区间宽度 19.6–22.4 ms。四轮目录后缀为 d22486ce-734c-4d2f-97ef-1eece2c4c8ba、72859b7e-33aa-4925-a58f-4aeac9354533、7d8d7129-f66f-4bcb-a30a-da21f53d01ed、e3252693-1eda-4454-93b5-49931014c76a。另有一轮在第 9 张时失去前台，接收端保持 held / fixture_not_foreground，8 次实际输入。该轮未纳入完成样本的百分位，证据目录后缀为 26b0fda6-087c-45b0-97bd-0c569392fc2b。

优化版 Rust 工作区 203 项、Android JVM 247 项、Clippy、包级 rustfmt、Lint 与 Debug 构建均通过。K40 完整回归通过文字/UI 171 项、图片 5 项，12 项条件跳过，零失败，目录后缀为 1b321a08-67d5-47f5-852c-b7632a2daa21 和 48168310-b08d-410a-8c2a-0002d4db050c。含内置模型的 imageTest APK 通过两份权重摘要、ABI、依赖和 16 KB 对齐校验。K40 日常 Debug 按现行按需导入模型的打包规则覆盖安装，保留原应用 UID。设备 APK 与本地 SHA-256 一致，为 558269FA13CA8A8F36E8264941D5C631F1BFFE49C85C9B808651AA1D95B6046B。


#### T33 Windows 图片准备分段诊断

测试夹具按桌面图片资源 UUID 记录存储读取、验证解码、像素转换、PNG 编码、DIB 生成、前台检查与剪贴板发布等时间。诊断默认关闭，开启时在预检和执行结束后分别输出各段耗时。日志不包含图片、文件路径或窗口标题。

完整诊断基线 20 张中，PNG 编码 p95 为 119.00 ms，是预检内最耗时的操作。使用同一批五种 K40 实际上传文件，分别运行无损 PNG 编码和像素回读对照。RGB8 + Up 相对 RGBA8 + Adaptive 的编码中位数下降约 28%–35%，PNG 体积下降约 0.3%–5%。Windows 现在保留 RGB8 解码像素用于 PNG，仅在直接构建 DIB 时填充全不透明 alpha。含透明度的源图保留 RGBA 输出。回归覆盖 PNG/DIB 像素、底向上行序、方向和源 EXIF 剥离。

| 路径 | 诊断基线 20 张 p95（ms） | 优化后同配置 20 张 p95（ms） | 再验证 100 张 p95（ms） |
| --- | ---: | ---: | ---: |
| PNG 编码 | 119.00 | 74.05 | 71.16 |
| Windows 预检 | 253.21 | 173.70 | 180.27 |
| 剪贴板发布 | 92.29 | 79.14 | 65.84 |
| SendInput 返回后至 RichEdit 确认 | 250.26 | 179.17 | 176.17 |
| 手机图片准备 | 389.67 | 398.96 | 397.95 |
| 手机发送至桌面登记 | 732.31–751.36 | 625.06–641.06 | 670.57–692.82 |
| 桌面登记至手机历史可见 | 807.14–817.27 | 586.26–608.39 | 766.28–785.75 |

五轮各 20 张共 100 次 RichEdit 原生确认，零假输入、零重放，准备、上传与终态均无超时。登记至手机持久终态 p95 为 752.63–772.10 ms，按调整后的 800 ms 门槛通过。尾部受多段影响，单段 p95 不可相加。手机持久终态至历史可见 p95 为 27.69 ms。接收核心和 RichEdit 的峰值工作集分别为 249.80 和 278.38 MiB。

诊断基线和同配置复测目录后缀分别为 5e9dc210-bff3-465e-9732-761ecdf92937、1368fe57-58a6-42a4-b85a-aa0c7904d5ce。百张目录后缀为 f024f2c4-ddcd-484c-8d6c-95bdce004d23、659a44c1-ba08-442a-a8e9-b64a00fa3ebf、b2e1aca6-e041-486c-8e06-d03f88e414c4、3a4b12b6-1208-4fce-891f-6949ea9fb50e、1774b3da-7448-44e6-8e4a-8fdfb2ad4a07。另有一次五轮次配置执行到 23 次原生确认后，手机夹具缺少某条样本登记时间而中止。该轮未纳入 20 张基线，原始证据目录后缀为 28b34765-4e42-48a2-b63b-35f14dffb227。


#### T33 终态 WSS 回传分段

同一 K40 与原生 RichEdit 路径新增桌面 WSS 发送完成、手机 OkHttp 回调、Repository 处理和 Room 落盘四点。20 张隔离真机样本全部原生确认，零重放。桌面提交至 WSS 发送完成 p95 2.15 ms，手机回调至处理 p95 6.08 ms，处理至落盘 p95 21.76 ms。两条尖峰主要发生在 WSS 发送完成至 OkHttp 回调，时钟校准后的区间分别为 350–368 ms 和 446–464 ms。此区间包括系统发送与接收缓冲、网络传输以及客户端读取调度。现有证据还无法区分这些因素，桌面连接已启用 TCP_NODELAY。证据目录后缀为 fd6eb6de-c694-4921-be98-202d918f7488。


第二轮 20 张通过脚本的逐请求自动对齐，发送完成至手机回调的 p95 为 76–97 ms，最慢一张为 149–170 ms。证据目录后缀为 cbfefa5b-03fb-4e56-8b0a-0c643a4e15e3。手机夹具同时改用当前草稿 ID 匹配历史终态，缺少登记时间时报告该条记录的状态、原因和图片错误。两轮均未复现先前的登记时间缺失。

#### T33 手机短文本发送与首回执分段

K40 隔离包与 Windows Release 夹具通过真实 LAN 连续采样。替身输入轮和原生 EDIT 轮各发送 100 条，并各做 100 次冷切换。原生轮准确确认 100 次输入，零替身输入、零重放；100 条首回执均为 accepted。手机计时从 Repository 调用开始，覆盖 actor、加密、配额、草稿封存、Outbox、WebSocket 入队、回调及 Room 提交。下表为 nearest-rank p95，单位为毫秒。

| 路径 | 原生 EDIT | 替身输入 |
| --- | ---: | ---: |
| Repository 入口至 WebSocket 入队 | 112.72 | 110.88 |
| 载荷与正文加密 | 33.68 | 39.23 |
| 配额查询 | 12.46 | 13.46 |
| 草稿封存 | 23.11 | 24.07 |
| Outbox 与草稿事务 | 23.62 | 20.01 |
| 提交时重新解密及解析操作 | 25.79 | 24.68 |
| WebSocket 入队返回至首回执回调 | 21.52 | 57.15 |
| 首回执处理至 Room 提交 | 17.22 | 18.37 |
| Repository 入口至首条持久回执 | 142.95 | 167.71 |
| 桌面持久 accepted 至 executing | 10.10 | 27.92 |
| 桌面持久 executing 至终态 | 92.96 | 4.09 |
| Repository 入口至手机持久终态 | 246.40 | 219.97 |
| 已配对冷切换至可发送 | 220.93 | 221.15 |

原生轮第一条持久回执最长为 239.76 ms，0/100 超过 500 ms。桌面 SendInput p95 为 73.92 ms，夹具等待 EDIT 确认为 17.42 ms。替身轮有 1/100 首条持久回执超过 500 ms，该条从 WebSocket 入队至回调为 441.69 ms。此区间包含客户端发送队列、桌面接收与落盘、网络往返和手机回调。各阶段 p95 独立统计。手机界面点击至 Repository 的耗时仍待采集。

首次原生试跑因操作鼠标导致目标失去前台，请求为 held / fixture_not_foreground / not_started，零输入。复测证据为 `desktop/target/image-android-fixture-9dc0bb2f-973c-4bb4-a703-8b9c5e5c3bfe/control-segments.json`，替身输入证据目录后缀为 e13695c5-2bd9-47a6-98fa-bfc726e5c5a9，前台中断目录后缀为 3fb253dc-5d6a-475c-974e-0bcd6cd3a018。Android JVM 247 项、Lint、Debug 与隔离 APK 构建通过。

#### T33 复用同次提交的操作并合并配额查询

新文本仍先原子保存加密 Outbox 与绑定草稿。事务提交后使用同一次调用构造的操作，历史显式重试继续解密已保存载荷，图片沿用持久载荷提交。主机与配对的条数和字节限制通过一条 SQL 判定，入队事务内再次校验。前后使用 K40、真实 LAN、同一固定短句和原版 Release EDIT 夹具，各 100 条全部准确入窗，零替身输入、零重放。nearest-rank p95 如下，单位为毫秒。

| 分段 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 发送前配额查询 | 12.46 | 5.31 |
| Outbox 与草稿事务提交 | 23.62 | 17.71 |
| 提交前准备操作 | 25.79 | 0.15 |
| Repository 入口至 WebSocket 入队 | 112.72 | 80.38 |
| Repository 入口至首条持久回执 | 142.95 | 111.86 |
| 桌面持久 accepted 至 executing | 10.10 | 10.92 |
| 桌面持久 executing 至终态 | 92.96 | 80.92 |
| Repository 入口至手机持久终态 | 246.40 | 241.10 |
| 冷切换至可发送 | 220.93 | 234.22 |

首条回执两轮各 0/100 超过 500 ms，手机持久终态各 1/100 超过 500 ms。优化后证据为 `desktop/target/image-android-fixture-ca2d20d9-9cb2-4b65-be92-896c13d3e27d/control-segments.json`。按调整后的 150 ms 首回执门槛，Repository 分段样本已低于预算，点击发送至手机持久保存回执的完整链路仍待按 89 §6 验证。桌面执行至终态仍超过 30 ms。

另用同一 APK 试验 EDIT 消息驱动唤醒并关闭逐键日志，100 条仍准确入窗。夹具确认 p95 从 19.24 降至 7.42 ms，同一轮 SendInput p95 从 66.23 升至 92.53 ms，桌面执行至终态为 105.97 ms。保留原验收夹具。试验目录后缀为 c26b14ac-934f-4d39-9633-d69847fc6259。

用独立 Windows 多行 EDIT 原生控件，以每个 UTF-16 单元一对 Unicode 按下和释放事件调用 SendInput。交错测试 1、8、21、64 个单元各 25 次，逐条读取并确认精确文本。四组调用 p50 为 4.05、24.03、63.68、188.75 ms，p95 为 5.58、35.66、107.93、224.06 ms。这组对照衡量当前环境下批量事件的成本，未隔离具体键盘钩子的贡献。临时探针位于 `desktop/target/sayin-native-length-probe.ps1`。

回归通过：Android JVM 247 项，Lint、Debug 和隔离 APK 构建，i18n 与桌面依赖检查。K40 隔离全套文字/UI 171 项、图片 5 项通过，分别 10 项与 2 项条件跳过，零失败。证据后缀为 fc426ffe-7d2f-4ee7-803e-b57e6e10da49 和 3a6dd91d-72c7-424c-9ae6-a9cba8d69a2a。另用真实 LAN 核对缺失回执恢复和显式重试，原 UUID 提交恰好执行一次，证据目录后缀为 1528c82c-f919-4ad2-8d69-b56c05abe071。

#### T32 首条持久回执门槛与手机发送反馈

首条持久回执按点击发送入口至手机数据库提交计时，p95 上限 150 ms，单次超时 500 ms，允许超时率 1%，至少 100 条。手机发送页在等待回执时显示「发送中」，持久 accepted 显示「电脑已接收」，injected 短暂显示「已提交输入」。K40 隔离包的界面用例模拟回执顺序并验证三段状态，证据为 desktop/target/v3-first-receipt-ui-k40.log。Android JVM 247 项、Lint、i18n、Debug 和隔离 APK 构建通过。真实 LAN 点击全程待采样。

#### T33 K40 点击发送至首条持久回执

使用隔离包渲染正式说话页，实际触摸发送按钮，经正式 ViewModel 提交短文本。以手机单调时钟记录按钮回调入口及 Room 提交首条有效回执，按 UUID 关联桌面阶段。真实 LAN 到 Windows 原生 EDIT，100 条准确入窗、100 条持久终态、零假输入和零重放。测试期间临时 EDIT 保持前台。

| 路径 | 样本 | p50 ms | p95 ms | p99 ms | 最大 ms | 验收 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 点击发送至手机持久首回执 | 100 | 65.94 | 142.49 | 182.62 | 191.24 | 通过，0/100 超过 500 ms |
| 冷切目标至可发送 | 100 | 134.48 | 195.16 | 453.90 | 476.95 | 通过，0/100 超过 3,000 ms |
| 桌面持久 accepted 至持久 executing | 100 | 7.94 | 51.72 | 61.69 | 63.74 | 超过 20 ms，0/100 超过 500 ms |
| 桌面持久 executing 至持久终态 | 100 | 59.09 | 122.07 | 180.99 | 193.88 | 超过 30 ms，0/100 超过 500 ms |
| 点击发送至手机持久终态 | 100 | 170.69 | 304.15 | 527.48 | 870.49 | 超过 v2 p95 80 ms、p99 150 ms 参照值 |

统计采用 nearest-rank。SendInput p95 为 63.52 ms，目标预检 p95 为 3.19 ms。该轮未采集资源和功耗指标。

运行命令 `pwsh -NoProfile -File scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -Lan -ControlPerformance -NativeText -ControlClick -SkipBuild -SkipInstall`。证据目录 `desktop/target/image-android-fixture-87794228-7ef7-49c4-b629-ccf627f3ae83` 含 `click-receipt-gate.json`、100 条点击及 100 条冷切换样本 `control-segments.json`。三条试跑证据目录后缀为 `c65012d2-ccf0-4e00-99b8-9b0afe9a74c7`。

Rust 工作区 204 项、Clippy、包级 rustfmt、桌面依赖检查、i18n、Android JVM、Lint、Debug 构建、隔离包与仪器包构建均通过。
