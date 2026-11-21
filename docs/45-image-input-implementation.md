# 45 · 双 WSS 图片输入实施记录

日期：2026-11-15。共同基线：`f9b52bec3513515d14be3ebd75fac1946120e7b3`。用户已选定双 WSS，要求每阶段全部测试通过后提交、推送，再继续下一阶段。

## 范围与阶段

范围按 [38](38-image-input-design.md)：手机单张静态 PNG/JPEG，系统选图、明确预览发送、双 WSS、受保护资源与收据、一次当前光标图片粘贴、双端回执及电脑图片历史。拍照、分享入口、多图、OCR、公网和单图并行不在首版范围。已有文字、Enter、配对、草稿、Outbox、暂停和 uncertain 语义必须保留。

| 阶段 | 内容 | 当前状态 |
| --- | --- | --- |
| 1 | 选型定案、原有基线、本地图片剪贴板/真实粘贴原型 | 已完成并推送 `23146bb` |
| 2 | 双端严格图片类型与分块格式、会话/资源边界、受保护资源测试 | 已完成并推送 `040359f` |
| 3 | 电脑双 WSS、持久接受与去重、生产输入与故障恢复 | 已完成并推送 `6124f1b` |
| 4 | Kotlin 图片准备、受保护 Outbox、传输和生命周期 | 已完成并推送 `86270a4` |
| 5 | 手机选图/预览/回执、电脑历史、双语与响应式界面 | 已完成并推送 `4b28edf` |
| 6 | 完整自动回归、独立身份真机 LAN、真实应用与实施收尾 | 首版验收完成；15 次 LAN 原生粘贴、受控慢网/取消/暂停、历史压力、API 29/33 各 44 项与 API 37 权限补验通过，人工验收通过；具体证据及扩展边界见 46、49 |

实现使用独立 worktree `../.zcf/VoiceInput/image-input`，分支 `codex/image-input`。其他 worktree、未提交改动及用户配对不重置、不 stash。审核后的阶段产物逐文件同步主仓库；仅暂存本阶段文件。

## 阶段 1 · 当前证据

修改前基线已实际执行：

- `scripts/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 通过，45 项 JVM 测试，0 失败。原有两个 Kotlin 编译警告仍在，未屏蔽。
- `cargo test --workspace --all-targets --locked` 通过，111 项桌面测试。使用独立数据的既有文字、收据、暂停、uncertain、发现与重连回归全部通过。
- `python scripts/check_i18n.py` 通过。
- 桌面依赖图检查通过，但 Yue 来源摘要与记录不符，不能把该命令记为通过。当前 Yue 工作区干净，版本已从 `c7ccc39` 前进到 `cb0fa6e`；新增 Alert/Notice、Disclosure settled 回调及内部按钮 icon 替换，不改变版本约束或引入第二套后端。完成审阅、全工作区测试和构建后再记录新摘要。

新增 opt-in `image_input_fixture` 原型只在 example 中使用图片依赖，不开放生产图片路径、不改生产配对或账本。准备阶段无副作用：10MiB 编码限制、1600 万像素/单边 8192、解码限额、拒绝 APNG/其他格式、应用 EXIF 方向，从相同 RGBA 像素构造 PNG 和 bottom-up BGRA DIBV5。PNG 不携带原 EXIF；色彩管理和所有真实应用透明度尚未验收。

本地原型已实际验证：

- 7 项纯测试通过，含透明 PNG、JPEG、EXIF 旋转、APNG、畸形与预算前置拒绝。
- `cargo run -p voicelink-receiver --example image_input_fixture -- --run` 通过。程序只向自己的临时 RichEdit 窗口提交两次真实 Ctrl+V；RTF 中图片从 0 → 1 → 2，文本长度仅增加图片占位符，没有额外 Enter。
- PNG/DIBV5 实际剪贴板字节一致；Windows 历史/云排除标记存在。拒绝授权、剪贴板忙的有界超时、重试时取消和内容序列替换均不触发额外粘贴。

RichEdit 将图片存为轻量图形，不能用 OLE `GetObjectCount()` 判断是否插入。本原型以导出的 RTF `pict` 数量和文本长度验证实际文档内容，未用 SendInput 返回值代替结果。无需因此增加 CF_DIB 回退。

原型会使用真实系统剪贴板并聚焦专用临时窗口；不向用户日常应用输入，不打开正式手机/电脑身份、历史或 Outbox，不定时恢复/覆盖剪贴板。常规 `cargo test` 只运行纯测试，不自动运行真实输入。

真实 RichEdit 控件试验仍不代表微信、QQ、Word/WPS、浏览器或所有应用均兼容；阶段 6 必须单独记录实际应用版本、读取格式、透明度及输入结果。

修改后实际执行并通过：完整 `cargo test --workspace --all-targets --locked`（118 项，含原有 111 项和新增 7 项）、`cargo clippy --workspace --all-targets --locked -- -D warnings`、四包限定 `cargo fmt ... -- --check`、`cargo build --workspace --locked`；Android 未改代码，修改前本轮的 JVM/构建/Lint 基线作为阶段回归；i18n 检查通过。另执行原有 `native_input_fixture`，真实独立 EDIT 收到精确中文、emoji 和字面 `{ENTER}`，前缀保留、无追加回车、重复 ID 无第二次执行，目标 PID 记录正确。

Yue 差异审阅和上述完整构建/回归已完成，使用 `python scripts/check_desktop_dependencies.py --record-source` 记录 `cb0fa6e7594558c3e88c8ed387a457b94f855092`，随后无参数依赖检查通过。没有修改 Yue 工作区、版本约束或补丁路径。

归档的四组测速可用源码/数据摘要已逐项重算通过（42 个可复算归档项；初期清单还含当时二进制摘要，未将这些摘要冒充已归档二进制）。44 的 gzip 原始记录用 `analyze.py` 重新解析，384 次上传、复用和取消断言均通过。独立实验源码与依赖仅用于复现，不将 Kwik/QUIC 引入正式 App。

| 行为 | 修改前 | 本阶段修改后 |
| --- | --- | --- |
| 生产文字/Enter、配对、Outbox | 已有实现 | 未改变，完整回归及真实 EDIT 通过 |
| 图片承载决定 | 双 WSS 为候选 | 用户已定双 WSS，协议与设计同步 |
| 图片本地输入证据 | 无 | PNG/JPEG 真实 RichEdit 粘贴通过 |
| 生产图片端点/收据/UI | 无 | 仍未开放，后续阶段实施 |

## 阶段 2 · 图片类型、分块与资源基础

此阶段改用共同基线 `23146bb` 的 `../.zcf/VoiceInput/image-input-core` / `codex/image-input-core`。后续阶段在该分支提交，主仓库保持无其他改动时以 fast-forward 接入，不覆盖或重置其他工作区。

图片准备从 example 移入接收核心 `images::payload`，原 7 项测试原样保留。新增 `ImageMetadata`、`ImageReference`、32 字节 SIMG 头、顺序上传状态与独立受保护资源文件。双端使用同一黄金向量和限额。此阶段还没有图片 `Operation`、新 capability 或公开图片 WSS 路径，不能把基础模块记为手机可发图。

Windows 完成当前用户真实 DPAPI 的文件落盘、重开、换 host/device、替换资源、密文篡改、缺失及启动孤儿清理测试。资源先加密再同步和原子替换；SQLite 引用事务由下一阶段接入，文件系统和数据库不是一个原子事务。临时资源内存有界，原图不落明文磁盘。10MiB 上限下直接复用 DPAPI，避免另建文件密钥体系。

Android 新增纯 Kotlin 元数据/块编码及私有资源文件基础。图片加密复用现有 Keystore 密钥管理，独立 AAD 为 `SayIn/image/v1`，原凭据/文字 AAD 不变。文件先写完整密文并同步，再在同目录原子发布；只允许 UUID 文件名。源流逐次计限，不把长度提示当成读取上限。实际调用方须位于 noBackupFilesDir 和 IO 线程；AndroidKeyStore 真机与 Room 引用事务在后续阶段验收。本轮 JVM 使用独立生成 AES-GCM 密钥验证文件边界，不将它冒充 AndroidKeyStore 真机测试。

新增 Rust 7 项资源/上传测试、Kotlin 4 项测试；已执行完整桌面 125 项测试，49 项 JVM 测试及 Android Debug 构建/Lint，通过。i18n、Yue 依赖来源检查、Clippy（`-D warnings`）、包限定格式和最终工作区构建全部通过。本阶段不访问用户配对数据、不安装 APK、不修改正式监听。

阶段 3–6 尚未全部验收或提交。下面逐项区分已有实现、自动测试、真机传输与真实应用输入，不能相互替代。


## 阶段 3 · 电脑生产链路

共同基线 `040359f`。同一现有精确绑定 TLS 监听区分 `/v2` 与 `/v2/images`，复用固定证书、IPv4/端口及网络范围监督，没有全网卡监听、第二端口或自动防火墙修改。当前控制会话提供 32 字节随机 binding，图片连接不能创建 session；每会话最多一张、全局两张、完整解码并发一。顺序二进制块与上传限额直接使用阶段 2 定义。

资源持久化与收据分开：后台校验/DPAPI 落盘 → 注册有期限的单次授权 → 原控制连接 submit → 图片引用/去重/接受顺序/历史同一 WAL/FULL 事务 → 串行原生输入。未接受资源使用有限所有权；异步 future 在等待 spawn_blocking 期间被取消，返回值无人接收也会触发资源回收。独立有界清理线程执行 unlink，不在 UI/账本线程做图片文件 I/O；清理完成前仍占临时额度，清理失败阻断新资源。

### 单次草稿生命周期（2026-11-21）

用户反馈发送成功后再次打开图片入口仍显示上一张图，必须先移除才能选图。根因是 `injected` 终态只清请求载荷，未清 `image_drafts`；启动 `loadDraft()` 又会把已花费的草稿读回来。现在：

- `injected` 终态在更新请求的同一 actor 中删除图片草稿并清空 `imageDraft`/`imageRequest`；下一次打开图片入口直接回到选图状态。
- `failed`、`cancelled`、`expired`、`uncertain` 保留草稿，供用户重试或查看；只有在明确移除或重新选图时替换。
- 启动时若草稿对应的请求已 `injected` 且载荷为空，则直接清理，避免进程重启把旧图带回。
- 资源文件仍按 `imageResource` 服务历史；删草稿不会删除仍被历史引用的原图。

验证：`scripts/verify_android_transport.ps1 -UseExistingRootInstall` 图片套件 4 项通过（含新增“injected 后草稿为空、未提交草稿跨重启恢复”断言），1 次 fake 输入、无重放。
单次图片授权同时受控制连接存活与本地意图闸门约束。新会话/撤销立即失效，暂停与换范围失效，恢复不复活旧授权；恢复后的新图片可重新获得新意图。prepared 最多 60 秒，账本定时维护回收；接受后的 30 秒不因上传/人工操作而续期。旧图片 held 不做 release，防止重新使用旧光标意图；文字人工 release 不变。

生产 NativeInput 复用阶段 1 已验证的 PNG/DIBV5/隐私标记实现，DPAPI 读取与解码先于目标捕获，执行标记先于剪贴板写入。发布后再次核对 clipboard sequence/owner、焦点、输入桌面、权限和暂停代次，单批 Ctrl+V，不追加 Enter；部分提交保守 uncertain，只补释放键，不重复粘贴。账本 schema 6 保留原身份/收据，新增资源引用。旧 schema 测试改为实际移除新增结构后再模拟升级，不能仅调低版本号冒充旧库。

新增验证覆盖资源所有权/取消/配额、清理失败阻断、接受/执行重启、图片同 ID 去重/冲突、暂停/会话替换/撤销、真实固定证书双 WSS 上传及独立控制响应、错误 binding/偏移、取消、断控制和撤销。网络测试使用隔离临时身份/DPAPI 目录、随机 loopback 端口及 fake adapter，只证明通信和调度，**不是实际图片上屏或 LAN 性能证据**。

本轮真实生产 NativeInput/RichEdit 试验尚未通过：临时 RichEdit 已创建且拥有焦点，GetForegroundWindow 返回 0，未提交任何输入；已请求解锁电脑后重验。不得把阶段 1 的原型结果代替本阶段生产调用链结果。

### 续作核验（2026-11-15）

继续使用 `image-input-core` / `codex/image-input-core`，HEAD 为已推送的 `040359f`；主工作区仍干净，未重置、覆盖或 stash 其他工作区。本阶段尚未提交或推送：阶段验收包含生产 NativeInput 的真实临时 RichEdit 粘贴，不能把因环境无法执行的测试记为通过。

- 已执行完整 `cargo test --workspace --all-targets --locked`，先为 134 项通过；审阅后补充部分 Ctrl+V 提交回归，最终重跑完整套件 **135 项通过、0 失败**。最终 `cargo clippy --workspace --all-targets --locked -- -D warnings`、四包限定格式检查、工作区构建、i18n、桌面依赖/来源检查及 `git diff --check` 均通过；examples 构建及全目标测试编译也通过。
- 发现并修正部分按键提交后的清理范围：只释放本次已按下且尚未释放的键，1/3 个事件只补 Ctrl-up，2 个事件补 V-up 与 Ctrl-up；不会再发 key-down 或第二次粘贴。新增逐前缀测试已通过。临时真实输入程序增加退出时最终内容检查，避免只检查重复 submit 之前的内容。
- 当前整个未提交工作区的 Android JVM 51 项、Debug 构建和 Lint、隔离 `imageTest` 应用/测试构建通过。不能把包含后续阶段代码的工作区构建称为这些阶段已经交付。
- Windows 只读诊断显示测试进程位于 `WinSta0/Default`，当前输入桌面为 `Screen-saver`，前台 HWND=0；真实 NativeInput 测试没有执行输入。没有关闭屏保、切换输入桌面、绕过锁屏或削弱产品的前台校验。
- 连接手机检测到 `showing=true`、`secure=true`、屏幕关闭。已尝试的图片 UI 测试无法获得 Compose 界面；随后只终止隔离 `seeyuer.sayin.imagetest` 应用，未更改解锁策略、日常应用或后台权限。UI 测试未通过，不能因 adb 进程退出码为 0 而算成功。

### 已执行的隔离手机数据验证（不代替阶段 4–6 验收）

使用独立包 `seeyuer.sayin.imagetest`、随机 host/device/端口、独立 Room/偏好/图片目录及现有 Root 安装授权；手机 M2012K11AC，Android API 33。未替换日常 `seeyuer.sayin` 应用、配对和 Outbox。

1. loopback/ADB reverse：4 项实际设备测试通过，恰好一次 fake 输入；涵盖 EXIF 八方向像素/去 GPS、透明 PNG、真实 AndroidKeyStore 保护及篡改/身份拒绝、真实 schema 2→3 升级/图片独立配额，以及固定证书双 WSS、取消和重启查询不重放。
2. 真实 LAN：同样 4 项通过，手机直接连接电脑当前物理网卡 IPv4 和随机端口，没有 ADB reverse、代理、网络类别或防火墙修改；恰好一次 fake 输入。只证明该局域网的生产协议/持久化链路，不证明真实粘贴、最大图性能或所有网络条件。
3. EXIF 测试先暴露了一项错误断言：已归一化的 JPEG 方向字段可以为 undefined(0) 或 normal(1)，不能强制为 1。修正只允许这两个值，八方向仍逐角检查实际像素和宽高，并要求 GPS 不存在；修正后上述两轮 4 项均通过。

机器相关原始日志和结果保留于已忽略的 `desktop/target/image-android-fixture-2526eb70-23f1-4841-8406-f6e1c8272b27`（loopback）、`desktop/target/image-android-fixture-309ac8f2-ddd6-41c5-9200-01cf621198cc`（LAN）及本 worktree 的 `*-phase45.log`。配对邀请码文件已由验证脚本清除，不提交密钥、运行数据库或测试图片原件。

### 继续条件与未验收范围

先解锁电脑与手机，再运行生产 NativeInput 真实图片/文字临时窗口测试、图片 UI 及原有完整设备回归。阶段 3 所有检查与真实临时窗口测试通过后才提交、推送，再依次完成阶段 4、5、6。真实应用矩阵、图片复制/另存为/预览操作、生产链路大图 p50/p95/p99/内存/暂停响应、Android 26–27 回退解码器及 Android 37 本地网络运行时授权仍未验收；不得将此前独立网络测速或当前小 PNG 的 fake 输入结果作为这些项目的证据。

### 解锁后的阶段 3 验收

用户确认解锁后，实际重跑 `cargo run -p voicelink-receiver --example native_input_fixture --locked -- --image`，生产 DPAPI 资源读取 → 持久收据 → NativeInput → 真实系统剪贴板/一次 Ctrl+V 全链路通过。独立临时 RichEdit 最终恰有一张图片、保留原有前缀、无额外 Enter；同 ID 再次 submit 返回旧终态，退出时再次检查仍只有一张。执行目标 PID 与历史一致。

随后不带 `--image` 重跑同一个 example，真实 EDIT 精确收到中文、emoji 和字面 `{ENTER}`，前缀保留、无追加回车、同 ID 不再输入。未访问正式身份、配对、历史或日常目标应用。与前述最终 135 项桌面自动测试、Clippy、包限定格式、构建、i18n/依赖检查共同满足阶段 3 门槛。

解锁后图片 UI 两项仍失败，已确认不能继续归因于锁屏：图片面板内容和详情提示的测试未找到预期节点，留待阶段 5 定位。它们不属于电脑核心阶段的验收，不将失败记作通过。阶段 4–6 仍未交付。

## 阶段 4 · Kotlin 图片准备与受保护传输

基线 `6124f1b`。图片操作复用 Repository 原有单 actor、连接代次、固定证书 HostClient 和恢复查询调度。图片 WSS 与控制 WSS 使用相同配对客户端、TLS 1.3/SAN/完整证书/绑定网络；验证 TLS 后才发送当前控制会话的 binding，不新建信任。至多四个未确认的 48KiB 块，严格累计 ACK；编码帧和明文载荷用后清除。单次传输总期限 300 秒、握手 8 秒、普通回复 12 秒、最终准备 60 秒。

图片 URI 仅在后台有界读取一次；检查容器、动画、尺寸和像素预算，规范化方向和 sRGB，移除 GPS/原 EXIF。PNG 保持透明度；JPEG 质量 92 重编码，不宣称原图无损。冻结的最终字节同时用于预览、摘要、Keystore 加密持久化和上传；不把来源 URI 当作持久载荷。首版超出预算明确拒绝，不增加隐藏缩图选项。API 28+ 使用拒绝 partial 的 ImageDecoder；API 26–27 BitmapFactory 回退的实际设备覆盖仍列为最终限制。

Room schema 3 增加加密图片引用和按 host/device 范围保存的图片草稿；资源置于 noBackupFilesDir，独立图片 AAD，原文字加密域保持不变。引用事务完成前回收失败/过期准备结果，启动清理孤儿；图片独立字节配额不占文字 64KiB，终态历史清理后才清除无引用图片。重启 uploading/saved 先 query，unknown 转 review，不自动再次上传或 submit。图片不允许旧引用 retry/release；暂停、切网、断开、取消、后台和过期票据不恢复未来粘贴授权。

实际验证：

- Android JVM 51 项、Debug 构建/Lint、隔离 imageTest/AndroidTest 构建通过。新 JVM 覆盖四块窗口与精确 ACK，以及容器/动画/长度前置拒绝。
- 4 项真实设备数据测试分别在回环与真实 LAN 重新运行并通过；真实 AndroidKeyStore、Room 2→3、八方向像素、透明度、篡改/换身份、固定证书上传、取消和进程恢复不重放均通过。每轮恰好一次 fake 输入。最新原始证据为 `desktop/target/image-android-fixture-2ecbfc4c-9870-439f-83c7-7920c28bc589` 和 `.../image-android-fixture-038c9d66-5d49-44da-b160-4a939d4a73a0`。
- 原有 TransportInstrumentedTest 在同一隔离包重新执行通过：配对/固定证书、文字精确载荷、held 主动到期、重连/进程恢复查询、手动地址维护和 uncertain 保留；总计恰好两次 fake 输入，没有重放。证据 `desktop/target/image-android-fixture-542bbc24-1898-4dc8-a551-12f029ba14ca`。
- `verify_image_transport.ps1` 校验隔离包 ID、测试报告和精确执行数，不以 adb 退出码判通过；新增 `-TransportOnly` 复用原有数据回归、`-SkipInstall` 复用本轮已安装隔离 APK，不修改日常应用。LAN 测试无 ADB reverse。

本阶段没有交付选图 UI。阶段 5 必须继续修复并验收面板、历史操作、双语及真实界面；阶段 6 仍需完整设备回归、生产性能和应用矩阵。

阶段 4 最终回归：完整桌面 `cargo test --workspace --all-targets --locked` 135 项通过、0 失败；Clippy `-D warnings`、四包限定格式检查、工作区构建、双语完整性、依赖/来源和 `git diff --check` 均通过。上述手机 JVM/构建/Lint 和隔离真机通信均在当前阶段重跑。选图界面文件和电脑图片历史暂不纳入本阶段提交。

## 阶段 5 · 手机图片面板与电脑图片历史

基线 `86270a4`。完整说话页和快捷卡复用同一个系统选图/预览面板，图片发送须明确点击，不参与语音自动发送倒计时；打开图片面板撤销原倒计时。发送受当前接收范围、录音/文字请求状态与资源票据约束，关闭、取消、后台及断线不会在以后自动提交。预览使用 Repository 从冻结载荷生成的 256px 缩略图，不在 Compose 主线程解码原图。回执区分上传、等待接受与已提交粘贴指令；手机历史中旧图片 held 只可查询/取消，不能 release。

电脑历史继续使用原有日期分组和 1–3 列响应式布局。按当前页最多 30 张、单后台任务顺序加载 256px 缩略图；原图查看、DPAPI 读取、复制格式准备与导出均在后台。查看使用 Yue 对话框，关闭清除原图引用；复制只写系统剪贴板，另存为使用原生路径选择及原子文件写入，不额外粘贴。图片操作放在独立一行，避免多列卡片挤压设备/应用信息；操作反馈绑定原记录，不再错误显示在全部图片卡片上。删除后的记录不能再次读取/导出，保留原收据和去重语义。

新增真实设备 UI 3 项通过：明确点击才发送、暂停禁用发送、关闭取消、图片 held 不提供 release、详情文案与结果语义、中英/浅深色/1.5 倍字号。初次失败根因是测试宿主没有 Miuix Scaffold，随后发现文本定位同时命中标题和按钮；修正宿主和按可点击按钮定位，没有弱化发送/取消/禁用断言。语言测试显式注入原 ActivityResultRegistryOwner，使本地化 Context 不遮断系统选图宿主。浅色英文和深色中文大字号设备截图已逐张检查，无裁切；原图保存在已忽略的 `desktop/target/image-ui-stage5`。

新增 GPUI 真实控件测试通过：从独立 DPAPI/SQLite 测试收据加载两张预览，检查 560/1000/1540px 窗口的图片操作边界，打开并关闭 Yue 图片对话框、导出字节一致、删除后拒绝再次导出且不覆盖已有文件。测试直接构建收据状态，**不执行真实 NativeInput 或粘贴**；异步历史节流使用 GPUI 可控时钟，不以睡眠猜测 UI 完成。

最终桌面完整自动测试 **136 项通过、0 失败/忽略**，Clippy `-D warnings`、四包限定格式检查和工作区构建通过；Android JVM **51 项**、Debug 构建/Lint、隔离包/测试 APK 构建及 i18n 通过。阶段内相邻 Yue 仓库由其他工作从 `cb0fa6e` 更新到 `8526863`，已审阅 Alert 的 icon alignment/outline 变更及新增测试，没有依赖/后端变化；本阶段完整回归与构建使用新来源，再记录来源摘要，依赖检查通过。未修改或重置 Yue 仓库。

Windows 原生自动操作通道复查仍返回 native pipe 不存在；本阶段电脑界面使用 GPUI 真实控件/布局验证，不把它写成用户桌面的人工应用矩阵已通过。真实系统剪贴板复制及第三方目标应用的验收仍属于阶段 6。

原有设备完整回归最终通过：40 项（原有 37 项＋图片 UI 3 项）及独立图片数据 4 项，共 **44 项**，无失败。首次总套件发现旧测试只注册 1→2 迁移，已补上同生产的 2→3；首次把两个配对套件放在同一服务时，第二组正确拒绝已消耗邀请。验证脚本现为文字/图片各启动独立服务、身份、目录和随机端口，不通过重开旧邀请或解除旧接收闸门制造成功。两组分别恰有 2/1 次 fake 输入。证据目录 `desktop/target/image-android-fixture-bc636a91-bfdf-4eb8-b528-fd2b5c75c60c`、`.../image-android-fixture-b8873c11-92b3-4255-a78c-a45b4f0348ce`；付费云测试和专用发现测试仍按各自 fixture 单独运行。数据/界面断言未删减。

## 阶段 6 · 生产链路实测与最终验收

历史图片卡片越界/模糊已另以 `9d186a0` 提交推送；随后满页压力发现直接图片资源未移出 atlas，修复 `8cf6ca5` 也已独立完整验证并推送，主工作区已同步。两次回归修复没有夹带阶段 6 其余改动，详见 47、48。

基线 `4b28edf`，继续同一独立 worktree；本节记录最终首版验收，随阶段代码共同提交。详细数字、失败样本、复现方式与未验证边界见 [46](46-image-input-production-validation.md)。

新增隔离 `ImagePerformanceInstrumentedTest` 和 `verify_image_performance.ps1`，用用户 refer/image-test 中的真实样本走生产 Kotlin 选图准备/Keystore/Room/固定证书双 WSS。电脑 Release fixture 只向自己启动且核对 HWND/PID 的 RichEdit 子窗口执行 NativeInput；流式统计 RTF 中的图片数量，避免把完整大图 RTF 保留内存。修改前/后正式各 15 次真实 LAN 粘贴通过，无 Enter、无重放，身份与完整证书不变；后三张超 10MiB 的源文件拒绝，不冒充已经支持 56MiB。

校验路径此前会生成随后被丢弃的 PNG/DIBV5；修正提交 `15ff3ea` 拆出共享完整解码，持久化只验证像素、执行前才构造格式。损坏压缩数据、MIME/尺寸不符等仍在两条路径拒绝。实测没有观察到整体延迟/峰值内存显著改善，不能把这次结构修正宣传为确定提速。发送到测试目标确认的混合 p50/p95/p99 为 1869.8/3433.0/3433.0ms，样本数仅 15，包含额外 RTF 确认而非纯网络或生产目标显示承诺。

三次中间原生复测失败如实保留：一次未观察到预期图片，另两次明确目标失去前台；没有解除检查、重放或把失败计入通过数据。用户确认保持前台后，最终独立一轮 15 次通过。生产 injected 仍只表示事件提交。

真实历史复制/导出 opt-in example 已通过，PNG/DIBV5、历史/云排除标记、原图导出一致、删除后拒绝访问均验证；复制不调用输入适配器。Chromium 157 的本地页面实际读取系统 PNG 剪贴板，纯文本粘贴不增加文字/Enter、无 form submit；此快捷键由 Playwright 驱动，不冒充 NativeInput 到浏览器。

完整 137 项 Rust、Clippy、包限定格式、工作区构建、依赖/来源、双端 i18n，51 项 JVM/Debug/Lint、隔离包构建、44 项常规设备回归均通过。发现回归脚本改为隔离包/目录/随机端口，loopback 恢复、撤销及真实 LAN NSD 三组均通过；各一次 fake 输入，旧请求不重放且暂停保留。

真实 LAN 图片负载下另完成 5 次暂停/断线重连验证，无输入或重放；手机 marker 到 paused 的 p50/p95/p99 为 106.37/134.55/134.55ms（含测试轮询与双向控制路径），Windows 本地生产 pause 持久完成为 3.05/3.45/3.45ms。不是单向网络耗时或长期尾延迟。

用户确认浏览器和 Word 测试正常；独立账本有 3 次 clipboard_image 输入。完整 GPUI 的查看/复制/另存为功能也获用户确认；期间实际暴露竖图越界/模糊，修复后用户复验「显示正常」。本次 3 图查看完整 Debug App 的 PeakWorkingSet 约 170.7MiB，不含 GPU/VRAM，不能代表满页压力内存。根因、原生渲染和阶段 5 独立回归修复见 [47](47-desktop-image-history-rendering-fix.md)。

补测受控慢速链路 5 张首轮和 25 张连续负载均通过，后者约 6 分 30 秒；慢速首 ACK 后显式取消和电脑暂停各 5 次，草稿/身份保留、重连不重放、输入执行数 0。转发器不终止 TLS、仅精确绑定独立 LAN 端点，不能冒充真实差 Wi-Fi 或共享瓶颈。

满 30 条独立合成历史、60 次原生 D3D 原图开关暴露 atlas 资源未释放；补上明确释放后相同负载复验通过，关闭态 Private Bytes 不再持续增长，直接 key 断言和前后数字见 48。137 项桌面回归包含此新增用例；先前 RTF 计数的额外断言并不另加测试数。

平台补验确认原 minSdk 26 与系统 TLS 1.3 前提矛盾：API 26 实际缺少 TLS 1.3。平台修正提交 `590bfd8` 在开发期直接把最低版本改为 Android 10/API 29，同步 NDK 链接 API，删除不再适用的旧解码分支，不降级 TLS、不添加第二套 provider。API 26 新包实际拒绝安装；API 29 端到端固定证书回归通过。API 29 窄屏大字号标签拆词和 IME insets 分发问题在原测试中暴露并修复，原有安全断言保留，详见 [49](49-android-platform-support-and-validation.md)。

最终 51 项 JVM、Debug 构建/Lint、默认 arm64 应用/测试构建，API 29 与 API 33 各 44 项隔离回归、API 37 系统权限两进程测试及独立图片 4 项、三组独立发现回归均通过。桌面 137 项完整测试、Clippy、包限定格式、构建和双端 i18n/依赖检查通过。默认日常 APK 实际核对 minSdk 29、仅 arm64，不含测试权限 Activity；x86_64 只能显式构建隔离 imageTest。

用户已针对聊天软件草稿、透明 PNG 与无误发回复「已测试，没问题」；浏览器/Word、历史查看/复制/导出与卡片渲染已有通过反馈，不再重复要求操作。首版六阶段任务完成。精确应用版本未记录、真实差 Wi-Fi/共享瓶颈、多小时负载、16MP 满页/VRAM、真实多网卡及 LAN 换 IPv4 等仍是明确的证据边界；API 37 撤销只证明进程重启后行为。有限测试不扩写为所有环境兼容或性能保证，见 46、49。

最终入口收敛：审阅发现旧 `verify_android_transport.ps1` 仍构建/安装日常包，而图片回归入口已独立隔离。现用已验证的隔离实现替换旧入口，删除重复 `verify_image_transport.ps1`；统一命令为 `scripts/verify_android_transport.ps1 -FullSuite`，默认不再触及日常 UID。历史阶段中的旧脚本名称仅为当时记录。入口合并后另完整重跑 44 项，证据在 46。
