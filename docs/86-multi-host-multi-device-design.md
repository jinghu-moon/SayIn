# SayIn 多手机 / 多电脑规范（sayin.v3）

更新：2026-11-25。  
状态：待实现规范；实现前按 [验证计划](87-multi-device-verification.md) 建立 v2 基线。  
协议名：`sayin`；线协议版本：`sayin.v3`。  
适用范围：Android 10+ 与 Windows 之间的同一可信局域网 IPv4。

本文只定义已冻结或待冻结的行为。验证步骤、性能数据、历史调研不写入规范；二维码、图标 WebP、多图批量发送属于独立变更轴，见 [独立增量](88-multi-device-increment-design.md)。

## 1. 产品边界

- 一次发送由一台手机发起，只指向用户选定的一台电脑，产生一个独立请求。向第二台电脑发送必须重新选择目标并生成新的请求 UUID。
- 一台手机可配对多台电脑，一台电脑可接收多台已授权手机。配对关系独立；电脑只有一个原生输入执行器。
- 局域网直连，无中心服务、无广播发送、无跨电脑执行顺序。
- 手机只选择接收电脑；电脑本地决定接收是否启用，以及执行时能否向当前前台输入。`target` 由电脑签发，Windows 前台窗口在每次执行时重新捕获。
- 电脑暂停、存储故障关闭整台电脑的新输入。`uncertain` 的范围按副作用种类决定，见 §5.4。
- 不自动重试可能已有副作用的请求。`injected` 只表示系统输入事件已提交，不表示目标控件已显示。
- 核心 v3 一次请求最多一张图片。多图一次选择是独立增量，不能在核心契约冻结前默认整批只有一个请求。

## 2. 身份、配对与升级

### 2.1 实体

| 实体 | 标识与作用域 |
| --- | --- |
| 电脑 | 稳定 `host_id`、DPAPI 保护的私钥、固定叶证书和 TLS 名称 |
| 配对 | 电脑生成的 `pair_id` 与 256 位 token；电脑保存带域分离的 HMAC，手机用 Keystore 加密保存凭据 |
| 控制会话 | 认证后由电脑签发 `session_id`；每对配对至多一个当前会话 |
| 请求 | 配对内唯一 `request_id`；电脑和手机均以 `(pair_id, request_id)` 定位 |
| 接收范围 | 电脑启用接收时签发的 `target`；重新激活或监听范围变化时轮换 |

`host_id` 只用于选择电脑和显示，不进入请求主键。手机不创建跨电脑的认证设备 ID；电脑为每段配对签发自己的 `pair_id` 和 `device_id`。

### 2.2 配对

- 配对使用二维码邀请或 CPace 短码，电脑本地批准；二维码/短码的具体编码是独立增量。
- 全机同一时刻最多一个有效邀请和一个待批准请求；第二个请求返回 `PAIRING_BUSY`。
- 手机必须先持久保存候选凭据并确认已保存，电脑才允许批准并持久启用。
- 每台手机对同一 `host_id` 只允许一段可发送配对。手机和电脑都执行该不变量；普通添加产生 `PAIR_ALREADY_EXISTS`，替换必须走明确重配。
- 重配必须由电脑端明确选中旧配对并批准；手机不能凭同名、IP 或新安装声明认领旧配对。
- 手机“忘记电脑”删除本地凭据和首选目标，保留历史展示；有未决请求时先同步或由电脑本地核对，并说明忘记后无法继续查询。
- 电脑端的“断开”只关闭该 pair 当前会话，手机可以自动重连；需要持久禁止该手机提交时必须“撤销配对”。断开不取消已接受请求；撤销会使 token 和图片授权永久失效。

### 2.3 升级与重配

| 场景 | 身份 | 旧回执 | 新发送 |
| --- | --- | --- | --- |
| 原地升级 | 保留原 `pair_id`（原 `device_id`）、token、证书、SAN | 原 pair 直接查询 | 双方 v3 后可以 |
| 普通断线重连 | 保留原 pair，签发新 session | 原 pair 查询 | 可以 |
| 主动重配 | 新 `pair_id` 和新 token；旧 pair 立即失效 | 事务内复制旧终态回执快照到新 pair，只读 | 仅新 pair |
| 撤销/旧凭据丢失 | 旧 pair 永久失效 | 电脑本地历史；不转移 | 否 |

主动重配规则：

1. 只有旧 pair 不存在 `accepted`、`held`、`executing`、`uncertain` 等非终态请求时才允许。`uncertain` 必须先由电脑本地核对并写成不可再变的 `uncertain_acknowledged`；不能靠时间或手机确认绕过。
2. 重配在电脑端单一事务中完成：写新 pair、复制旧终态回执快照、撤销旧 token、关闭旧会话。
3. 不保留 `retired_query_only`、旧 token、旧控制连接或“借用空闲槽”的只读会话。旧请求不自动改挂、补发或重试。
4. 新 pair 只能读取被明确转移的**终态回执快照**，不能对旧请求 submit、上传、cancel 或 release。
5. 旧凭据已经丢失时无法转移；电脑保留本地核对记录，新 pair 不继承旧请求。
6. 主动重配是原授权名额的一进一出；普通添加仍受活动配对上限约束。

### 2.4 发现与 TLS

- 已有安装保留原证书 SAN；只有全新主机使用 `sayin-<host_id>.local`。手机始终以实际证书 SAN 为准。
- 发现服务为 `_sayin._tcp.local`，TXT 只提供版本、host ID 和监听 IPv4；发现只产生候选地址。
- 手机按已知 `host_id` 筛选；TLS 1.3、固定叶证书、SAN、有效期和 `hello` 的 host/pair 均匹配后，才更新地址和凭据。
- 扫码只固定证书摘要；连接时从 TLS 握手取完整证书并核对。详见独立二维码增量。
- 混合版本不降级、不自动重配、不补发；只有收到明确版本拒绝才显示确定版本提示，否则按既有网络故障诊断。

## 3. 连接与会话

| 项目 | 规则 |
| --- | --- |
| 连接单位 | 每对配对一条控制 WSS；不存在跨主机复用的控制 socket |
| 手机热连接 | 当前目标保持热连接，上一目标可短暂保留；其余按需连接 |
| 手机上限 | 最多 8 台已配对电脑，同时最多 2 条控制连接 |
| 电脑会话 | 可同时有多段配对的 session；同一 pair 新 `hello` fencing 旧 session |
| 图片通道 | 独立按需 WSS，绑定当前控制 session 和 pair |
| 会话 ID | `session_id` 只用于关联与丢弃迟到响应，不作授权依据；授权由已认证 socket 的 pair 决定 |

连接规则：

- 切换目标时只回收空闲旧连接；有未决请求的目标保留到查询结束。
- 每对配对独立退避和取消任务；电脑 1 离线不影响电脑 2。
- 连接建立后先查询原 Outbox，查询完成且当前范围有效才允许新提交。
- 所有异步回调核对 host、pair 和连接代次；旧连接清理不能移除新连接。
- 无可见 Activity 且无已开始录音的前台服务时断开，沿用现有生命周期边界。

降级承诺：若用户有 3 台以上电脑同时有未决请求，只有当前目标和上一目标保持热连接；第 3 台及以后是冷连接，切换时必须重新建连，UI 显示连接中。验收必须测量三台同时有未决请求时的最坏收尾时间，不能宣称全部暖连接。

## 4. 发送、草稿与历史

- 完整页、快捷卡、悬浮入口共享一份可编辑草稿和当前选中电脑。
- 仅一台已配对电脑时直接选中；多台时记住最后明确选择。选择按 `host_id` 保存，不按 `pair_id`，重配不丢目标。
- 目标离线时保留草稿并显示离线；不自动改发其他电脑。
- 手动发送只在**当前目标**连接、该目标旧请求查询完成、当前范围允许输入时，冻结 `pair_id`、`request_id`、`target` 和载荷。
- 草稿关联、提交快照和 Outbox 在同一 Room 事务中保存；冻结后不可改目标或 UUID。
- A 的未决请求不得阻止用户为 B 开始新草稿或发送 B 的新请求；A 的迟到回执不能清除 B 的正文。
- 只有当前草稿仍关联同一 `(pair_id, request_id)`、提交快照相符、并收到该请求持久化的最终状态时，才按发送策略清除正文或保留翻译原文。
- 自动发送绑定录音开始时的 host、pair、接收范围、连接代次和前台观察；编辑、切电脑、重连、暂停、撤销或前台变化都撤销票据。旧倒计时不能恢复。
- 显式 Enter 是独立请求；文本或图片成功不自动追加 Enter。
- 历史按发送时的接收电脑和请求键展示；电脑历史显示来源设备和执行时应用快照。历史清理不删除去重收据。

## 5. 接收、状态与权限

### 5.1 接受

1. 协调器检查存储健康、当前 socket 的 pair、消息格式，再按 `(pair_id, request_id)` 查重。
2. 相同 payload 和原始 `target` 返回现有回执；相同 UUID 但 payload 或 target 不同返回 `ID_CONFLICT`。
3. 新请求检查当前 target、全局闸门、配对闸门和配额。接受与暂停竞争由协调器裁决。
4. 接受事务在 WAL/FULL 中写入载荷、指纹、接受序号、来源快照和配额；提交后才返回 `accepted`。
5. 每段配对按接受顺序看队首；全局从各配对当前可执行队首中选接受序号最早者。
6. 单输入线程预检；执行前重新核对授权、target、状态、期限和闸门代次；有系统副作用后无法排除部分输入时进入 `uncertain`。

用同一回执表的待处理索引调度，不另建队列或轮转调度器。`accepted_order` 是审计顺序；实际执行按“各配对可执行队首”公平选择。

### 5.2 期限

- `accepted` 在尚未成为可执行队首时**不启动**执行期限；排队在图片或其他配对之后不会因等待而过期。
- 成为可执行队首（或 `held` 被放行）时记录 `executable_at`；期限为 30 秒，`release` 不重置已经开始的期限。
- `held` 在持有期间不启动执行期限，也不会自动过期；它保持到用户放行、取消或撤销。放行时记录 `executable_at`，此后 30 秒内可执行。理由是 `held` 没有系统副作用，自动过期只会丢掉用户仍可能处理的内容，不能改善安全边界。
- 全局暂停、配对暂停或全局 `uncertain` 期间没有可执行队首，不启动期限。
- 到期只表示电脑不会为该请求产生新的输入，`expired` 为终态；迟到提交不能复活。
- 重启时 `executing → uncertain`；重启前 `accepted`/`held` 失去本进程执行授权，收敛为 `expired`，并记录 `restart_confirmation`。这是保守策略：进程重启后无法证明未产生副作用，不能重建原期限或继续执行。

### 5.3 状态与终态

| 状态 | 含义 |
| --- | --- |
| `saved` | 手机已持久化，电脑可能未接受；恢复先查询 |
| `accepted` | 已持久接受，等待可执行 |
| `held` | 零副作用阻断；保留原因，可按权限逐条放行 |
| `executing` | 已写执行标记，可能已调用原生输入 |
| `injected` | 系统输入事件已提交；不等于目标控件显示 |
| `failed` | 可确认失败，`execution.stage` 说明失败阶段 |
| `cancelled` | 执行前取消 |
| `expired` | 执行前到期 |
| `uncertain` | 可能已有副作用，非终态；按范围阻断，等待电脑本地核对 |
| `uncertain_acknowledged` | 电脑本地核对完成，终态；不重试、不补发；仅用于清理和审计 |

`uncertain` 不在终态集合中；重配/撤销前必须先把它转为 `uncertain_acknowledged` 或由电脑本地保留历史。

### 5.4 uncertain 的范围

`uncertain` 不是单一闸门，必须按实际副作用分类：

| 副作用 | 范围 | 阻断 |
| --- | --- | --- |
| 纯文字输入，无法确认全部字符是否已提交 | `pair` | 只阻断该 pair 的新输入；其他 pair 可继续 |
| Enter/按键、剪贴板、图片、图片路径，或任何无法排除部分原生调用的操作 | `global` | 关闭整台电脑的新输入，等待本地核对 |

- `status.uncertain_scope` 明确为 `pair` 或 `global`；不能只在 UI 猜测。
- `pair` 范围在电脑端显示该来源待核对，其他手机不因它被阻断。
- `global` 范围必须持久关闭输入闸门；电脑本地核对后写 `uncertain_acknowledged`，只解除闸门，不重试。
- `uncertain` 永远不是终态；`uncertain_acknowledged` 才是终态，且不再变化。
- 纯文字被归类为 `pair` 的前提是：`op.kind=text`、没有按键/剪贴板副作用、没有无法分类的原生调用；否则保守归为 `global`。

## 6. 回执契约

### 6.1 `status` 字段

```json
{
  "v": 3,
  "t": "status",
  "session_id": "a9c265c2-7819-4866-a3d8-31c090f6fc25",
  "id": "dcd90d23-d73f-46a6-a998-ad136cd72de9",
  "order": "42",
  "request_rev": "7",
  "st": "injected",
  "execution": {
    "stage": "input_submitted",
    "method": "unicode",
    "confirmed": false
  },
  "reason": null,
  "server_ms": {"accept": 4, "queue": 1, "execute": 3, "finish_commit": 3}
}
```

文件已保存但粘贴未开始时的回执：

```json
{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","order":"42","request_rev":"8","st":"failed","execution":{"stage":"file_saved","method":"clipboard_image_path","confirmed":false},"reason":"paste_not_started"}
```

| 字段 | 规则 |
| --- | --- |
| `id` | 原请求 UUID；只在本 session 的 pair 内解释 |
| `order` | 仅 accepted 及之后存在；全局接受序号 |
| `request_rev` | 单调递增 u64；回执不可倒退，客户端丢弃更旧 rev |
| `st` | 生命周期状态；终态集合：`injected/failed/cancelled/expired/uncertain_acknowledged` |
| `execution.stage` | `not_started/prepared/input_submitted/file_saved/paste_submitted/paste_confirmed/failed` |
| `execution.method` | `unicode/key/clipboard_image/clipboard_image_path/none` |
| `execution.confirmed` | 仅表示是否有可验证的目标确认；普通应用不支持确认时为 false |
| `reason` | 稳定原因码或已脱敏说明；不包含正文、路径和凭据 |
| `server_ms` | 已实现的阶段耗时；缺阶段不得伪造 |
| `application` | 可选显示元数据：`{name, icon_webp?}`；图标受 8,192 帧预算约束 |
| `uncertain_scope` | 仅 `st=uncertain` 时存在：`pair` 或 `global` |

语义：

- `injected` 表示系统输入事件已提交；`execution.stage` 再区分 `input_submitted`、`file_saved`、`paste_submitted`。
- `paste_confirmed` 只在确实观察到目标插入时使用；无法观察时保持 `paste_submitted` 或 `confirmed=false`，不能把“已发送 Ctrl+V”写成“目标已显示”。
- 文件已保存但粘贴失败：`st=failed`、`execution.stage=file_saved`、`method=clipboard_image_path/clipboard_image`、`confirmed=false`、`reason` 为失败原因。
- `uncertain` 不是终态；电脑本地核对后写 `uncertain_acknowledged`，`request_rev` 再增一次，之后不再变化。
- `query` 返回同一字段集；重复查询不能改变终态，也不能伪造新的 `request_rev`。

### 6.2 控制与错误

- 手机可查询、取消本 pair 请求；取消记录计入配额。`release` 仅允许对未过期的本 pair 手动文字 `held`，且不重置期限。
- 电脑本地管理全局暂停、恢复和 `uncertain` 核对；手机移除全局暂停/恢复按钮。
- 错误码是穷举契约，不写“至少”：
  `UNAUTHORIZED`、`STALE_SESSION`、`PAIRING_BUSY`、`PAIR_ALREADY_EXISTS`、`PAIR_REPLACE_BLOCKED`、`TARGET_MISMATCH`、`STATE_CONFLICT`、`ID_CONFLICT`、`QUEUE_FULL`、`RATE_LIMITED`、`STORAGE_UNAVAILABLE`、`GLOBAL_PAUSED`、`BLOCKED_REVIEW`、`TOO_LATE`、`INVALID_REQUEST`、`UNSUPPORTED`、`VERSION_MISMATCH`。
- 新请求 UUID 与当前 `target` 不匹配返回 `TARGET_MISMATCH`；相同 UUID 但 payload/target 不同返回 `ID_CONFLICT`。
- 旧 pair 已撤销返回 `UNAUTHORIZED`；不存在 `PAIR_RETIRED`、`LEGACY_RECEIPT_QUERY_ONLY` 或只读控制会话。

### 6.3 出站显示预算

`foreground` 和携带图标的 `status.application` 共用完整 JSON 帧的 UTF-8 预算：

| 项目 | 上限 |
| --- | --- |
| 完整出站帧 | 8,192 字节 |
| 应用名 | 96 个 Unicode 标量；JSON 转义后预留最多 768 字节 |
| 图标原始编码 | 4,096 字节；Base64 后最多 5,464 字节 |
| 回执/序号/结构预留 | 1,200 字节 |
| 计算上限 | 768 + 5,464 + 1,200 = 7,432，余 760 字节 |

超预算时先省略图标，保留应用名、观察序号和完整回执；应用名本身超限时拒绝该显示元数据，但不能关闭会话。不得继续使用 12 KiB 图标上限塞入 8 KiB 帧。WebP 编码和 8,192/8,193 边界属于独立增量，见 [独立增量](88-multi-device-increment-design.md)。

## 7. 连接、容量与安全

### 7.1 首轮预算

| 资源 | 首轮值 |
| --- | --- |
| 电脑总连接槽 | 16 |
| 控制连接 | 8 |
| 图片连接 | 2 |
| 握手/重连替换预留 | 4 |
| 观察者 | 8 |
| 已授权手机 | 8 |
| 每手机同时控制连接 | 2 |
| 待执行请求 | 每 pair 16 项/64 KiB；全机 32 项/128 KiB |
| 图片 | 每 pair 1、全机 2；10 MiB、1600 万像素、单边 8192、48 KiB 块 |

连接组合为 8 控制 + 2 图片 + 4 握手 = 14/16；剩余 2 槽仅用于短时替换/异常恢复，不允许长期占用。槽满时优先保证既有授权重连、查询和终态，不能饿死暂停/到期；图片等待按 pair 公平排队，不允许一对长期占满两槽。

### 7.2 计数口径

| 计数 | 包含 | 不包含 |
| --- | --- | --- |
| 已授权手机 | 未撤销且可建立新会话的 pair | 已撤销、旧历史、已转移的终态快照 |
| 控制连接 | 已认证控制 WSS | 未认证握手、图片 WSS、已关闭旧 socket |
| 图片连接 | 已认证且绑定当前控制会话的图片 WSS | 上传完成已关闭、排队中的上传 |
| 待执行请求 | `accepted/held/executing` 的非终态请求 | 终态回执、取消记录、转移快照 |
| 取消记录 | 持久取消键 | 已终结请求、历史记录 |

主动重配是原 pair 名额的一进一出；终态快照不占授权名额。撤销后旧 token 立即不能新建会话，已建立的 socket 由服务端关闭；未认证握手使用独立短时槽，不能长期占用控制预算。

### 7.3 安全

- 短码限流分为未认证来源桶和邀请/已认证 pair 桶；NAT 后多台手机不能互相耗尽唯一的全局预算，必须记录按来源与按邀请的计数。
- 撤销/过期 token 必须在认证阶段快速拒绝；未认证连接有独立短时槽，不能长期占用控制预算。
- 每 pair 的请求、回执、图片资源完全隔离；跨 pair 查询/取消/上传/复用返回 `UNAUTHORIZED`。
- 图片槽采用按 pair 轮转或公平等待，单一 pair 不能长期占满全机两个槽；具体策略在容量测试中固定。
- 发现、证书、TLS、配对批准和日志边界沿用现有安全规则；`session_id` 不是授权凭据。

## 8. 升级与混合版本

- 双方升级后只接受 v3；不运行 v2 双协议、不自动降级。
- 已有 Windows 数据目录原地迁移：保留 `host_id`、私钥、原叶证书/SAN、`device_id → pair_id`、token、收据、接受序号、去重、target 和图片引用。
- 已有 Android 数据把单主机凭据导入 host/pair 目录；迁移 Outbox、历史和图片引用；旧草稿用完整 `(pair_id, request_id)` 恢复，失败时保留原数据并禁止发送。
- 主动重配按 §2.3 的终态转移执行；旧 token 在迁移事务完成后立即失效。
- 混合版本不发送；只有明确版本拒绝才显示确定版本提示，其他失败保留网络诊断。发现不到服务不能推断为版本问题。
- 迁移必须有中止后重开、密钥丢失、存储失败和旧回执查询测试；不能静默生成新身份。

## 9. 独立变更轴

以下不阻塞多对多核心，各自有独立验收：

- 紧凑二维码：固定的 Base45/71 字节格式、TLS 摘要和一次性邀请；见 [独立增量](88-multi-device-increment-design.md)。
- 应用图标：无损 WebP、48×48、独立帧预算；见同文档。
- 多图一次发送：先原型证明一个原生操作能交付整批；否则逐图请求，不能把多图承诺写成核心 v3 契约。

## 10. 验证

规范验证入口：[多设备验证计划](87-multi-device-verification.md)。  
验证计划中的 p95 门槛、基线产物和实测数字不构成规范行为；阶段 1 未填入具体门槛前，不得进入阶段 2。
