# 45 · 双 WSS 图片输入实施记录

日期：2026-11-15。共同基线：`f9b52bec3513515d14be3ebd75fac1946120e7b3`。用户已选定双 WSS，要求每阶段全部测试通过后提交、推送，再继续下一阶段。

## 范围与阶段

范围按 [38](38-image-input-design.md)：手机单张静态 PNG/JPEG，系统选图、明确预览发送、双 WSS、受保护资源与收据、一次当前光标图片粘贴、双端回执及电脑图片历史。拍照、分享入口、多图、OCR、公网和单图并行不在首版范围。已有文字、Enter、配对、草稿、Outbox、暂停和 uncertain 语义必须保留。

| 阶段 | 内容 | 当前状态 |
| --- | --- | --- |
| 1 | 选型定案、原有基线、本地图片剪贴板/真实粘贴原型 | 已完成并推送 `23146bb` |
| 2 | 双端严格图片类型与分块格式、会话/资源边界、受保护资源测试 | 已完成并推送 `040359f` |
| 3 | 电脑双 WSS、持久接受与去重、生产输入与故障恢复 | 已完成并推送 `6124f1b` |
| 4 | Kotlin 图片准备、受保护 Outbox、传输和生命周期 | 已验收；本节随阶段提交，提交号见 Git 历史 |
| 5 | 手机选图/预览/回执、电脑历史、双语与响应式界面 | 已有未提交实现；解锁后的两项 UI 测试需修复 |
| 6 | 完整自动回归、独立身份真机 LAN、真实应用与实施收尾 | 部分验证完成；真实目标应用、性能和界面验收未完成 |

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
