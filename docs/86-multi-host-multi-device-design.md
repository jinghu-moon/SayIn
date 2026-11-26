# SayIn 多手机 / 多电脑规范（sayin.v3）

更新：2026-11-25。  
状态：待实现规范；实现前按 [验证计划](89-multi-device-verification.md) 建立 v2 基线。  
协议名：`sayin`；线协议版本：`sayin.v3`。  
适用范围：Android 10+ 与 Windows 之间的同一可信局域网 IPv4。

本文只定义已冻结或待冻结的行为。验证步骤、性能数据、历史调研不写入规范；二维码、图标 WebP、多图批量发送属于独立变更轴，见 [独立增量](90-multi-device-increment-design.md)。

## 1. 产品边界

- 一次发送由一台手机发起，只指向用户选定的一台电脑，产生一个独立请求。向第二台电脑发送必须重新选择目标并生成新的请求 UUID。
- 一台手机可配对多台电脑，一台电脑可接收多台已授权手机。配对关系独立；电脑只有一个原生输入执行器。
- 局域网直连，无中心服务、无广播发送、无跨电脑执行顺序。
- 手机只选择接收电脑；电脑本地决定接收是否启用，以及执行时能否向当前前台输入。`target` 由电脑签发，Windows 前台窗口在每次执行时重新捕获。
- 电脑暂停、存储故障关闭整台电脑的新输入。`uncertain` 的范围按副作用与目标窗口决定，见 §5.4。
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
- 第一次配对使用同一条已固定证书的 WSS。手机发送 `pair`（设备名和邀请秘密），电脑返回 `pair_candidate`（`host_id`、`pair_id`、`token`）。手机将候选凭据加密落盘后发送 `pair_saved`（候选 `pair_id`）。电脑仅接受原 socket 上与当前候选匹配的确认，随后显示含来源 IP 的本地批准界面。批准事务提交后发送 `paired`（`host_id`、`pair_id`），其中不重复发送 token。所有帧带 `v=3`，此时无 `session_id`。
- 候选凭据不进入可发送目录，只有匹配的 `paired` 或持候选凭据通过固定证书和身份校验的 `welcome` 才能将其启用。若连接在批准事务提交后、收到 `paired` 前中断，重建连接可用该候选凭据发送一次普通 `hello` 来确认结果。电脑明确返回 `UNAUTHORIZED` 时移除该候选，网络失败保留候选供用户重试。候选确认必须受邀请有效期约束，失败、断线和拒绝清理该 socket 占用的申请位置。
- **手机端不变量**：每台手机对同一 `host_id` 只允许一段可发送配对。普通添加在手机端直接返回 `PAIR_ALREADY_EXISTS`，不发起配对请求。
- 电脑无法执行同一不变量：§2.1 规定手机没有跨电脑身份，`device_id` 是按配对签发的，电脑无法判断两次配对来自同一台手机。电脑只受"已授权手机 8 台"约束，批准对话框显示来源地址供用户识别。`PAIR_ALREADY_EXISTS` 不由电脑产生。
- 重配必须由电脑端明确选中旧配对并批准；手机不能凭同名、IP 或新安装声明认领旧配对。
- 电脑在已授权设备列表选中要替换的配对后签发同格式的短期邀请，服务端将邀请绑定到该旧 `pair_id`。手机从已配对电脑的重配入口打开扫码或短码，复用 `pair` 握手并附带 `replace_pair_id`、`replace_token`。两字段必须同时出现且只发送给已固定为原电脑身份的 TLS 端点。普通邀请拒绝重配字段，重配邀请拒绝无旧授权或不匹配的申请。核验旧 token 不关闭已有会话。
- 重配复用 `pair_candidate`、`pair_saved` 与 `paired`。手机加密候选槽保存被替换的旧配对标识，收到确认后原子替换同一主机的可发送凭据，保留旧配对的历史解密密钥。最终确认丢失时仍以候选 `hello` 恢复，服务器拒绝候选时继续保留手机上原有的授权。电脑在批准事务中再次验证旧 token 及非终态请求，失败不改变旧授权。
- 手机"忘记电脑"删除本地凭据和首选目标，保留历史展示；有未决请求时先同步或由电脑本地核对，并说明忘记后无法继续查询。
- 电脑端的"断开"只关闭该 pair 当前会话，手机可以自动重连；需要持久禁止该手机提交时必须"撤销配对"。断开不取消已接受请求；撤销会使 token 和图片授权永久失效。

### 2.3 升级与重配

| 场景 | 身份 | 旧回执 | 新发送 |
| --- | --- | --- | --- |
| 原地升级 | 保留原 `pair_id`（原 `device_id`）、token、证书、SAN | 原 pair 直接查询 | 双方 v3 后可以 |
| 普通断线重连 | 保留原 pair，签发新 session | 原 pair 查询 | 可以 |
| 主动重配 | 新 `pair_id` 和新 token；旧 pair 立即失效 | 事务内复制旧终态回执快照到新 pair，只读 | 仅新 pair |
| 撤销/旧凭据丢失 | 旧 pair 永久失效 | 电脑本地历史；不转移 | 否 |

主动重配规则：

1. 只有旧 pair 不存在 `accepted`、`held`、`executing`、`uncertain` 等非终态请求时才允许。`uncertain` 必须先由电脑本地核对并写成不可再变的 `uncertain_acknowledged`；不能靠时间或手机确认绕过。`held` 有保留期上限（§5.2），重配最多被阻塞到该期限结束。
2. 重配在电脑端单一事务中完成：写新 pair、复制旧终态回执快照、撤销旧 token、关闭旧会话。
3. 不保留 `retired_query_only`、旧 token、旧控制连接或"借用空闲槽"的只读会话。旧请求不自动改挂、补发或重试。
4. 新 pair 只能读取被明确转移的**终态回执快照**，不能对旧请求 submit、上传、cancel 或 release。
5. 旧凭据已经丢失时无法转移；电脑保留本地核对记录，新 pair 不继承旧请求。
6. 主动重配是原授权名额的一进一出；普通添加仍受活动配对上限约束。
7. 手机端重配前必须清零 Outbox。`saved` 项无法核对、无法改挂，只能由用户显式取消或删除；非空时手机本地返回 `PAIR_REPLACE_BLOCKED`。

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
| 图片通道 | 独立按需 WSS，绑定当前控制 session 和 pair；绑定链见 §6.6 |
| 会话 ID | `session_id` 只用于关联与丢弃迟到响应，不作授权依据；授权由已认证 socket 的 pair 决定 |

连接规则：

- 切换目标时只回收空闲旧连接；有未决请求的目标保留到查询结束。
- 每对配对独立退避和取消任务；电脑 1 离线不影响电脑 2。
- 连接建立后先查询原 Outbox，查询完成且当前范围有效才允许新提交。`query` 是每条连接的第一条应用消息。
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
- 只有当前草稿仍关联同一 `(pair_id, request_id)`、提交快照相符、且该请求的持久化状态为 **`injected`** 时，才清除正文。`failed`、`cancelled`、`expired`、`uncertain`、`uncertain_acknowledged` 都保留草稿，由用户决定重试或丢弃。
- 自动发送绑定录音开始时的 host、pair、接收范围、连接代次和前台观察；编辑、切电脑、重连、暂停、撤销或前台变化都撤销票据。旧倒计时不能恢复。
- 显式 Enter 是独立请求；文本或图片成功不自动追加 Enter。
- 历史按发送时的接收电脑和请求键展示；电脑历史显示来源设备和执行时应用快照。历史清理不删除去重收据。

## 5. 接收、状态与权限

### 5.1 接受

1. 协调器检查存储健康、当前 socket 的 pair、消息格式，再按 `(pair_id, request_id)` 查重。
2. 相同 payload 和原始 `target` 返回现有回执；相同 UUID 但 payload 或 target 不同返回 `ID_CONFLICT`。
3. 新请求检查当前 target、全局闸门、配对闸门和配额。接受与暂停竞争由协调器裁决。
4. 接受事务在 WAL/FULL 中写入载荷、指纹、接受序号、来源快照和配额；提交后才返回 `accepted`。
5. 每段配对按接受顺序看队首。不可执行的 `held` 仍占队首并保序，但受保留期约束（§5.2）。全局从各配对当前可执行队首中选接受序号最早者。
6. 单输入线程预检；执行前重新核对授权、target、状态、期限、闸门代次，以及是否命中 `uncertain` 的阻断范围（§5.4）；有系统副作用后无法排除部分输入时进入 `uncertain`。

用同一回执表的待处理索引调度，不另建队列或轮转调度器。`accept_order` 是审计顺序；实际执行按"各配对可执行队首"公平选择。

### 5.2 期限

- `accepted` 在尚未成为可执行队首时**不启动**执行期限；排队在图片或其他配对之后不会因等待而过期。
- 成为可执行队首时在内存中记录起点并启动 30 秒执行期限。预检成功后，原始起点 `executable_at` 随 `executing` 标记在同一持久事务写入，仍先于任何系统输入。
- `held` 不启动执行期限，但有独立的**保留期**：首轮 10 分钟，从进入 `held` 起算，到期写 `expired`，`reason=hold_expired`。理由：执行期限防的是过久的意图被自动执行，而 `held` 永远需要用户动作；但没有上界的 `held` 会让该配对的后续请求永久排队，也会把重配永久锁死，所以保留期是正确性要求，不是安全要求。
- 放行 `held` 后按队列顺序等待，成为可执行队首时启动执行期限。重复放行不重置已经开始的期限。
- 全局暂停、配对暂停或未核对的 `global` `uncertain` 期间没有可执行队首，不启动执行期限；`target` 范围只把命中同一前台窗口的请求转成 `held`，不影响其他请求成为可执行队首。`held` 的保留期照常流逝。
- 到期只表示电脑不会为该请求产生新的输入，`expired` 为终态；迟到提交不能复活。
- 重启时：`executing → uncertain`；`accepted → expired`（`reason=restart_confirmation`）；`held` 保留，保留期继续流逝。区分依据是是否需要自动执行：`accepted` 会被调度器自动执行，进程重启后继续执行等于在新进程里自动落字；`held` 永远不会自动执行，保留内容不产生副作用。
- 本节取代 v2"30 秒期限同时覆盖 `accepted` 与 `held`"的行为，迁移规则见 §8。

### 5.3 状态与终态

| 状态 | 含义 |
| --- | --- |
| `saved` | **仅手机侧**：已持久化，电脑可能未接受；恢复先查询 |
| `accepted` | 已持久接受，等待可执行 |
| `held` | 零副作用阻断；保留原因，按保留期约束，可逐条放行 |
| `executing` | 已写执行标记，可能已调用原生输入 |
| `injected` | 系统输入事件已提交；不等于目标控件显示 |
| `failed` | 可确认失败且未提交输入事件，`execution.stage` 说明停在哪一步 |
| `cancelled` | 执行前取消 |
| `expired` | 执行前到期，含执行期限与 `held` 保留期 |
| `uncertain` | 可能已有副作用，非终态；按范围阻断，等待电脑本地核对 |
| `uncertain_acknowledged` | 电脑本地核对完成，终态；不重试、不补发；仅用于清理和审计 |

`unknown` 不是状态：它只是 `query` 对"本 pair 没有该请求回执"的回答，不持久化、不占配额、不产生 `request_rev`，见 §6.3。

`uncertain` 不在终态集合中；重配/撤销前必须先把它转为 `uncertain_acknowledged` 或由电脑本地保留历史。

### 5.4 uncertain 的范围

`uncertain` 不是单一闸门，按**副作用种类**和**受影响的输入目标**分类：

| 副作用 | 范围 | 阻断对象 |
| --- | --- | --- |
| 纯文字输入，已提交但无法确认全部字符 | `target` | 执行时前台窗口与该请求目标窗口相同的请求 |
| Enter/按键、剪贴板、图片、图片路径，或任何无法排除部分原生调用的操作 | `global` | 关闭整台电脑的新输入，等待本地核对 |

- 目标窗口身份 = 进程映像 + 进程创建时间 + 窗口句柄，执行前每次重新捕获并与该请求记录的快照比较。
- 采用 `target` 而不是按配对：拼接风险发生在共享的输入目标，不在请求队列。同一台手机打到另一个窗口不会继承残句；另一台手机打到**同一窗口**必须阻断。按配对既会误挡无关请求，又会放过真正的冲突。
- 被 `target` 范围挡住的请求转 `held`，`reason=uncertain_target`，不占用执行期限。阻断范围清除后自动转回 `accepted` 并成为可执行队首，按 §5.2 重新计时；这不是用户放行，不产生 `release`。
- 纯文字能被归为 `target` 的前提：`op.kind=text`、未使用剪贴板、未提交按键、没有无法分类的原生调用；否则保守归为 `global`。
- `status.uncertain_scope` 明确为 `target` 或 `global`；不能只在 UI 猜测。
- `global` 范围必须持久关闭输入闸门；电脑本地核对后写 `uncertain_acknowledged`，只解除闸门，不重试。

### 5.5 多个 uncertain 并存

- 不同配对、不同目标可以同时存在多条 `uncertain`；它们互不覆盖。
- 电脑端按 `global` 优先、`target` 逐条的固定顺序核对；每条核对都绑定 `(request_id, request_rev, state_rev)`，任一变化使旧确认失效并返回 `STATE_CONFLICT`。
- 解除 `global` 闸门只表示不再全局阻断，不影响其它 `target` 范围继续阻断。
- 每条 `uncertain` 都必须单独变为 `uncertain_acknowledged`；不存在"一次核对清除全部"的操作。
- 存在未核对的 `global` 时，手机端不提供任何绕过入口，也不能通过重配或撤销绕过。

## 6. 线协议契约

### 6.1 帧与校验

- 控制 WSS 路径为 `/sayin/v3`，图片 WSS 为 `/sayin/v3/images`；其他路径在 Upgrade 阶段拒绝。
- 文本帧是严格 JSON 对象：UTF-8、无重复键、无尾随内容，根必须是对象。
- 入站控制帧不超过 65,536 字节；单次提交载荷不超过 32,768 字节；文本不超过 4,000 个 Unicode 标量或 16,384 字节。超限返回 `INVALID_REQUEST`，不关闭会话。
- UUID 使用 RFC 4122 小写连字符形式。
- 拒绝带 `Origin` 头的 Upgrade 请求：浏览器不能直连。
- 每条消息带 `v`（=3）与 `t`。认证后的消息带 `session_id`；作用域由已认证 socket 的配对决定，消息里的 `pair_id` 不作为授权依据。
- 日志只记录操作、状态或稳定原因码；凭据、原始载荷、图标数据和路径不进入诊断。

### 6.2 hello 与 welcome

- `hello`：`pair_id`、`token`、`name`（设备显示名）。
- `welcome`：`host_id`、`pair_id`、`session_id`、`state_rev`、`paused`、`pause_reason`、可空 `target`、`capabilities`、`limits`、`image`（图片通道信息）。`limits` 至少包含 `max_message_bytes`、`max_submit_bytes`、`max_text_scalars`、`max_text_bytes`、`pending_ops`、`pending_bytes`、`foreground_ttl_ms`。
- `target` 是**主机级**接收范围，所有已认证配对看到同一值；v3 不提供 v2 的 `active_device_id`。执行时能否输入由执行器按当前前台窗口重新判断，不按配对区分。
- 设备名只在认证成功后更新；更新不推进 `state_rev`。
- 同一 pair 的新 `hello` fencing 旧 session：旧 session 收到 `STALE_SESSION` 后关闭，不得继续提交。

### 6.3 query、cancel 与 release

- `query`：`{"v":3,"t":"query","ref":"<uuid>","ids":[...]}`，有界（首轮 64 个 id），必须是每条连接的第一条应用消息。
- 每个 id 回一条 `status`；本 pair 没有该请求回执时回 `st:"unknown"`，只带 `v`、`t`、`session_id`、`id`、`st`。由于回执在提交前写入，`unknown` 可判定为该请求未被接受；手机仍只能用原 `(pair_id, request_id)` 并由用户显式操作重试。
- 手机不得在提交结果返回前查询同一 `request_id`；`unknown` 只在提交已成功或失败返回后才解释为未被接受，否则会把在途请求误判为丢失并产生第二个 UUID。
- 批次结束：`{"v":3,"t":"queried","session_id":...,"ref":"<同一 uuid>"}`。手机在收到 `queried` 前不得认为查询完成，也不得开始新提交。
- 错误帧携带 `ref`（批次）与 `id`（单条）。
- `control`：`{"v":3,"t":"control","ref":"<uuid>","name":"cancel|release","refs":[id],"target":...,"expected_rev":...,"expected_request_rev":...}`；响应为 `{"v":3,"t":"controlled","ref":...,"ok":true,"state_rev":...,"paused":...,"pause_reason":...,"target":...}`。
- v3 的 `control` 只接受 `cancel` 和 `release`；`pause`/`resume` 只能由电脑本地执行，手机发送一律返回 `UNSUPPORTED`。
- `cancel`：幂等；已终态返回现有回执；尚未出现的请求写持久取消记录，防止迟到提交复活。
- `release`：仅对本 pair 未过期的文本 `held`，`refs` 恰好一项；携带 `target`、`expected_rev`（主机 `state_rev`）与 `expected_request_rev`；任一不匹配返回 `STATE_CONFLICT`。放行不重置期限；放行推进 `state_rev`，但不改变无关配对的状态。

### 6.4 `status` 字段

```json
{
  "v": 3,
  "t": "status",
  "session_id": "a9c265c2-7819-4866-a3d8-31c090f6fc25",
  "id": "dcd90d23-d73f-46a6-a998-ad136cd72de9",
  "accept_order": "42",
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
{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","accept_order":"42","request_rev":"8","st":"failed","execution":{"stage":"file_saved","method":"clipboard_image_path","confirmed":false},"reason":"paste_not_started"}
```

| 字段 | 规则 |
| --- | --- |
| `id` | 原请求 UUID；只在本 session 的 pair 内解释 |
| `accept_order` | 无符号十进制字符串，0..2^63−1；已接受的回执始终携带此字段。接受前写入的 `cancelled` 和查询回答 `unknown` 省略此字段。**是接受序号，不是执行顺序** |
| `request_rev` | 无符号十进制字符串，0..2^63−1；单调递增；仅在有回执时存在；客户端丢弃更旧 rev |
| `st` | 生命周期状态；终态集合：`injected/failed/cancelled/expired/uncertain_acknowledged` |
| `execution` | 见下表；v2 迁移的旧回执可整体缺省，表示未记录执行方式 |
| `reason` | 稳定原因码或已脱敏说明；不包含正文、路径和凭据 |
| `server_ms` | 已实现的阶段耗时；缺阶段不得伪造 |
| `application` | 可选显示元数据：`{name, icon_webp?}`；图标受 8,192 帧预算约束 |
| `uncertain_scope` | 仅 `st=uncertain` 与 `uncertain_acknowledged` 时存在：`target` 或 `global` |

`execution` 的合法组合（实现和测试都以此为准）：

| `st` | 允许的 `stage` | 允许的 `method` |
| --- | --- | --- |
| `accepted`、`held` | `not_started` | `none` |
| `executing` | `prepared`、`file_saved`、`input_submitted`、`paste_submitted` | 非 `none` |
| `injected` | `input_submitted`、`paste_submitted`、`paste_confirmed` | 非 `none` |
| `failed` | `not_started` | `none` |
| `failed` | `prepared`、`file_saved` | 非 `none` |
| `cancelled` | `not_started`、`prepared` | `none` |
| `expired` | `not_started`、`prepared` | `none` |
| `uncertain` | `input_submitted`、`paste_submitted`；重启恢复还可保留 `prepared`、`file_saved` | 非 `none` |
| `uncertain_acknowledged` | 与 `uncertain` 相同，核对时不得改写 | 同左 |

`stage` 枚举：`not_started`、`prepared`、`input_submitted`、`file_saved`、`paste_submitted`、`paste_confirmed`。  
`method` 枚举：`unicode`、`key`、`clipboard_image`、`clipboard_image_path`、`none`。

语义：

- `stage=paste_confirmed` 与 `confirmed=true` 必须同时成立或同时不成立。`paste_confirmed` 只在确实观察到目标插入时使用；无法观察时保持 `paste_submitted` 且 `confirmed=false`。
- 已提交任何输入事件之后不得记 `failed`，只能是 `injected` 或 `uncertain`。
- 文件已保存但粘贴失败：`st=failed`、`stage=file_saved`、`method=clipboard_image_path/clipboard_image`、`reason=paste_not_started`。手机据此显示"已保存到电脑，未粘贴"。
- 重启恢复采用最后持久化的执行阶段与方式。若阶段仍为 `prepared` 或 `file_saved`，保留该阶段并写 `reason=restart_during_input`、`uncertain_scope=global`，不得补记未观察到的提交。接受前的取消记录使用 `not_started/none/false`，并带独立 `request_rev`。
- `uncertain` 不是终态；电脑本地核对后写 `uncertain_acknowledged`，`request_rev` 再增一次，之后不再变化。
- `query` 返回同一字段集；重复查询不能改变终态，也不能伪造新的 `request_rev`。

### 6.5 foreground 与心跳

- `foreground`：`{"v":3,"t":"foreground","session_id":...,"seq":N,"application":{...},"target":{...}}`；`seq` 在同一 session 内单调递增。
- 手机只用 `foreground` 做内存展示，不作为目标锁定或输入授权。暂停、断连、页面非活动或超过 `welcome.limits.foreground_ttl_ms` 时清除整个快照。
- 心跳与确认沿用会话内观察序号，不推进 `state_rev`；前台提示只发给有权接收的会话。
- 执行时的目标校验不依赖 `foreground`，由执行器每次重新捕获。

### 6.6 图片通道

- 独立 WSS `/sayin/v3/images`，复用同一固定证书、TLS 名称、端口与精确网卡绑定。
- `image_hello` 携带控制 `session_id` 和该会话签发的 binding 秘密；配对由控制会话继承。图片连接不能建立配对、不能产生新信任。
- 流程：`begin`（类型、字节数、像素）→ 顺序分块（正文 ≤ 48 KiB）→ ACK → `finish`（校验长度与摘要）→ `submit`（绑定 `request_id` 与 `target`）。
- 每一步都核对 pair、控制会话、接收范围、授权与暂停状态；撤销、断开或范围轮换后旧上传作废，迟到块丢弃。
- 完成前校验类型、长度、像素和单边；失败回收临时资源。一个图片连接只服务一个上传会话，完成后关闭。

### 6.7 错误码

错误帧沿用 `t="err"` 与字符串 `code`，带 `v=3`。已认证时携带 `session_id`；认证前省略。按 §6.3 携带适用的 `ref` 与 `id`。仅手机本地产生的错误不编码为错误帧。

错误码是穷举契约，不写"至少"，并注明产生端：

| 错误码 | 产生端 | 触发 |
| --- | --- | --- |
| `UNAUTHORIZED` | 电脑 | 跨 pair、已撤销、token 无效 |
| `STALE_SESSION` | 电脑 | 被新 `hello` fencing 的旧会话继续提交 |
| `PAIRING_BUSY` | 电脑 | 已有有效邀请或待批准请求 |
| `PAIR_ALREADY_EXISTS` | **手机本地** | 同一 `host_id` 已有可发送配对；不经网络 |
| `PAIR_REPLACE_BLOCKED` | 电脑 / 手机本地 | 旧 pair 有非终态请求；或手机 Outbox 非空 |
| `TARGET_MISMATCH` | 电脑 | 新请求的 `target` 不是当前接收范围 |
| `STATE_CONFLICT` | 电脑 | `release`/核对携带的 rev 与当前状态不符 |
| `ID_CONFLICT` | 电脑 | 相同 UUID 但 payload 或 target 不同 |
| `QUEUE_FULL` | 电脑 | 触及待执行请求或字节配额 |
| `IMAGE_UPLOAD_LIMIT` | 电脑 | 已认证图片连接遇到单配对或全机上传槽上限。返回控制会话的 session_id 并关闭该图片连接。 |
| `RATE_LIMITED` | 电脑 | 短码或连接频率超限 |
| `STORAGE_UNAVAILABLE` | 电脑 | 存储健康检查失败 |
| `GLOBAL_PAUSED` | 电脑 | 全局暂停中提交 |
| `BLOCKED_REVIEW` | 电脑 | `uncertain` 阻断范围命中 |
| `TOO_LATE` | 电脑 | 已过执行期限或保留期 |
| `INVALID_REQUEST` | 电脑 | 格式、长度、UUID 或字段非法 |
| `UNSUPPORTED` | 电脑 | 未知消息类型或不支持的载荷类型 |
| `VERSION_MISMATCH` | 电脑 | 明确的应用层版本拒绝 |

旧 pair 已撤销返回 `UNAUTHORIZED`；不存在 `PAIR_RETIRED`、`LEGACY_RECEIPT_QUERY_ONLY` 或只读控制会话。

### 6.8 出站显示预算

`foreground` 和携带图标的 `status.application` 共用完整 JSON 帧的 UTF-8 预算：

| 项目 | 上限 |
| --- | --- |
| 完整出站帧 | 8,192 字节 |
| 应用名 | 96 个 Unicode 标量；JSON 转义上界 1,152 字节（星平面按代理对 `\uXXXX\uXXXX` = 12 字节/标量） |
| 图标原始编码 | 4,096 字节；Base64 后最多 5,464 字节 |
| 回执/序号/结构预留 | 1,200 字节 |
| 计算上界 | 1,152 + 5,464 + 1,200 = 7,816，余 376 字节 |

超预算时先省略图标，保留应用名、观察序号和完整回执；应用名本身超限时**拒绝该显示元数据**（不截短、不改写），但不能关闭会话，手机显示"未知应用"。不得继续使用 12 KiB 图标上限塞入 8 KiB 帧。WebP 编码和 8,192/8,193 边界属于独立增量，见 [独立增量](90-multi-device-increment-design.md)。

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

### 8.1 收据状态的迁移

| v2 状态 | 迁移后 | 规则 |
| --- | --- | --- |
| `held` | `held` | 保留内容；保留期从迁移完成时刻重新起算，不因迁移立即过期 |
| `accepted` | `expired` | `reason=restart_confirmation`：迁移采用 v3 重启规则。v2 重启曾把未执行请求转为 `held`，迁移中的 `accepted` 直接过期以免复活陈旧意图 |
| `executing` | `uncertain` | 与重启同规则；不重试 |
| `uncertain` | `uncertain` | 回填 `uncertain_scope=global`：v2 只有一个全局闸门，不能假定为 `target` |
| `injected/failed/cancelled/expired` | 不变 | 终态不改写 |

- v2 线回执没有 `accept_order`，账本已有原接受顺序：迁移时保留账本接受序号；没有 `request_rev` 的写 0 并从此递增；没有 `execution`/`server_ms` 的整体缺省，手机显示"执行方式未记录"，不得补造。
- 手机 Outbox 里的 `saved` 项不迁移到新 pair，也不能改挂；由用户在重配前显式取消或删除（§2.3 第 7 条）。

## 9. 独立变更轴

以下不阻塞多对多核心，各自有独立验收：

- 紧凑二维码：固定的 Base45/71 字节格式、TLS 摘要和一次性邀请；见 [独立增量](90-multi-device-increment-design.md)。
- 应用图标：无损 WebP、48×48、独立帧预算；见同文档。
- 多图一次发送：先原型证明一个原生操作能交付整批；否则逐图请求，不能把多图承诺写成核心 v3 契约。

## 10. 验证

规范验证入口：[多设备验证计划](89-multi-device-verification.md)。  
验证计划中的 p95 门槛、基线产物和实测数字不构成规范行为；阶段 1 未填入具体门槛前，不得进入阶段 2。
