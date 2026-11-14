# 45 · 双 WSS 图片输入实施记录

日期：2026-11-15。共同基线：`f9b52bec3513515d14be3ebd75fac1946120e7b3`。用户已选定双 WSS，要求每阶段全部测试通过后提交、推送，再继续下一阶段。

## 范围与阶段

范围按 [38](38-image-input-design.md)：手机单张静态 PNG/JPEG，系统选图、明确预览发送、双 WSS、受保护资源与收据、一次当前光标图片粘贴、双端回执及电脑图片历史。拍照、分享入口、多图、OCR、公网和单图并行不在首版范围。已有文字、Enter、配对、草稿、Outbox、暂停和 uncertain 语义必须保留。

| 阶段 | 内容 | 当前状态 |
| --- | --- | --- |
| 1 | 选型定案、原有基线、本地图片剪贴板/真实粘贴原型 | 全部阶段测试通过，提交推送后进入阶段 2 |
| 2 | 双端严格图片类型与分块格式、会话/资源边界、受保护资源测试 | 待实施 |
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

后续阶段尚未实施。生产双 WSS、图片持久化、UI 和真实手机到目标应用链路均不得宣称完成。
