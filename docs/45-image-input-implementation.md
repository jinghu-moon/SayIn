# 45 · 双 WSS 图片输入实施记录

日期：2026-11-15。共同基线：`f9b52bec3513515d14be3ebd75fac1946120e7b3`。用户已选定双 WSS，要求每阶段全部测试通过后提交、推送，再继续下一阶段。

## 范围与阶段

范围按 [38](38-image-input-design.md)：手机单张静态 PNG/JPEG，系统选图、明确预览发送、双 WSS、受保护资源与收据、一次当前光标图片粘贴、双端回执及电脑图片历史。拍照、分享入口、多图、OCR、公网和单图并行不在首版范围。已有文字、Enter、配对、草稿、Outbox、暂停和 uncertain 语义必须保留。

| 阶段 | 内容 | 当前状态 |
| --- | --- | --- |
| 1 | 选型定案、原有基线、本地图片剪贴板/真实粘贴原型 | 已完成并推送 `23146bb` |
| 2 | 双端严格图片类型与分块格式、会话/资源边界、受保护资源测试 | 全部阶段测试通过，提交推送后进入阶段 3 |
| 3 | 电脑双 WSS、持久接受与去重、生产输入与故障恢复 | 待实施 |
| 4 | Kotlin 图片准备、受保护 Outbox、传输和生命周期 | 待实施 |
| 5 | 手机选图/预览/回执、电脑历史、双语与响应式界面 | 待实施 |
| 6 | 完整自动回归、独立身份真机 LAN、真实应用与实施收尾 | 待实施 |

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

阶段 3–6 尚未完成。生产双 WSS、持久图片收据、UI 和真实手机到目标应用链路均不得宣称完成。
