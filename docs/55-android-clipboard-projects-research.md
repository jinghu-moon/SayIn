# 55 · Android 剪贴板项目源码调研

日期：2026-11-15。状态：**源码调研完成；本文记录调研时的事实与选择**。后续限定 API 33 arm64 的 C++ 模块采集已实施并真机验证，见 [60](60-root-helper-native-clipboard-collection.md)；App 读取及保活尚未实现。

后续补充：用户要求同一模块另含应用保活，统一架构、设置与恢复规则见 [56 模块设计](56-root-module-clipboard-and-keepalive-design.md)。独立采集不依赖保活；计划中的保活恢复待命服务，不拉起界面、不恢复旧发送授权。当前进度以 60 为准，下文的“未实施”指本次调研发生时。

## 1. 结论

SayIn 要解决的是：用户在不同应用复制几段文字，稍后能在同一个面板找到，并粘贴进草稿。它包含三个独立问题：**取得内容的权限、采集进程的生命周期、历史的可靠保存**。Root、监听器或列表界面都不能单独解决全部问题。

三个项目的主要价值不同：

- **cliphistory-android：最值得借鉴历史存储与采集隔离设计。** 通过 Shizuku 或特定 Sui 分支提供的 shell 进程采集，不修改全局 ClipboardService；加密、敏感内容、队列边界和停止语义较完整。
- **Zygisk-ClipboardWhitelist：可作为已有 Root/Zygisk 用户的后台访问增强。** 它只放开指定应用的读与监听权限，不负责采集进程存活或保存历史。安装后，当前 SayIn 不会自动获得多条历史。
- **AClipBoardManager / ClipVault：可参考写入侧 Hook，但不能照搬其 Root/priv-app 方案。** 后者依赖的权限授予假设与核对的 AOSP 定义不符；Hook 路径则存在无界队列、敏感标记丢失等问题。

用户在本次调研后明确选择：**Root 手机使用模块独立采集、持久保存，SayIn 读取并管理历史；非 Root 手机保持现状，只展示当前剪贴板，不新增普通模式历史。** 不采用「白名单模块＋保活 SayIn」作为目标架构，不依赖 SayIn 进程持续运行才能记录。

ClipHistory 的有界队列、加密和生命周期处理仍可借鉴，但不再预先指定 Shizuku/Sui 为产品依赖。Magisk/KernelSU 模块中的读取身份、采集入口及安全 IPC 需要最小原型确认；是否需要 Zygisk、使用哪种 Hook 或 shell 身份仍未选定。“具有 root 权限”不足以证明读取、SELinux、监听和重启恢复均正确。

## 2. 调研范围与证据

本次以本地目录的实际内容为准，不假定它们与 GitHub 当前主分支相同。版本来自构建文件；未取得可验证的上游 commit ID，不能把目录名 `main` 当成固定 revision。

| 本地项目 | 本地版本及系统目标 | 主项目许可 | 核心实现 |
| --- | --- | --- | --- |
| `refer/clipboard/AClipBoardManager-main` | 1.3.4 / code 9；min 26、target 35 | CC0-1.0 | Kotlin；二选一编译 Root 或 LSPosed 采集；Room 历史 |
| `refer/clipboard/cliphistory-android-main` | 1.4.7 / code 17；min 30、target 37 | MIT | Kotlin；shell UID helper；加密双快照 |
| `refer/clipboard/Zygisk-ClipboardWhitelist-main` | v27 / code 27；min 29、target 34 | GPL-3.0 | Zygisk 注入及 Java Binder 代理；白名单管理 |

上游定位：[AClipBoardManager](https://github.com/kaduvert/AClipBoardManager)、[ClipHistory](https://github.com/saroo98/cliphistory-android)、[ClipboardWhitelist](https://github.com/MangoTCF/Zygisk-ClipboardWhitelist)。其中第三个项目由本地构建文件的更新地址确认，源于 Kr328/Riru-ClipboardWhitelist。

完整本地文件 SHA-256 清单、固定 AOSP tag 的权限定义摘录及本次 JVM 输出保存于本机 `docs/research/clipboard-projects-20261115/`。该目录按仓库既有规则被 Git 忽略；本篇保留可复核的路径、符号、外部链接和结论，不依赖这些临时产物才能阅读。

本文区分三种证据：源码可以证明的机制、本次实际运行的纯 JVM/静态检查、上游自述的设备表现。**本次没有安装模块、改动手机权限、启用 Hook 或进行 Root 真机采集验收。**

## 3. 平台边界：先排除错误前提

### 3.1 普通应用与输入法

Android 的公开 `ClipboardManager.getPrimaryClip()` 只返回当前内容，不提供系统历史。官方文档说明，应用既非默认输入法又没有输入焦点时返回 `null`；`hasPrimaryClip()` 同样受限。公开监听回调也不是旧内容快照。

所以，在其它应用连续复制 A、B、C，再回到 SayIn，普通模式通常只能看到 C。应用技术上可以持久保存此前合法读到的内容，但本项目按用户最新决定不为普通模式增加这类历史。

输入法有自己的历史并不意味着 SayIn 能读取该输入法的私有数据库。可以保留当前输入法作为用户手动选取历史文字的兜底；不能把“默认输入法可读”移植成“普通 SayIn 可读”。也不为这个单独需求把 SayIn 改成输入法。

### 3.2 Root/priv-app 权限假设需要纠正

AClipBoardManager 的模块将 APK 放入 `system/priv-app`，并在 `privapp-permissions` 中允许 `READ_CLIPBOARD_IN_BACKGROUND`，注释把它视为 `signature|privileged`。

但本次直接核对 AOSP 固定 tag：

| AOSP tag | `READ_CLIPBOARD_IN_BACKGROUND` 的 protectionLevel |
| --- | --- |
| `android-10.0.0_r1` | `signature` |
| `android-13.0.0_r1` | `signature|role` |
| `android-16.0.0_r1` | `signature|role` |

它们**都没有 `privileged` 标志**。因此，普通签名的 APK 仅搬入 `priv-app` 并增加 allowlist，不能据此获得这项权限；权限白名单不是平台签名或系统角色授予机制。某些改过权限策略的 ROM 是否放行，必须在该 ROM 独立证明。

这不是“所有系统应用方案都不可能”：平台签名、合适的系统角色或定制系统可能具备权限。结论是**这份本地模块源码不足以证明一套可移植的免 Hook 授权方案**，不能作为 SayIn 已确定可用的实现基础。

### 3.3 无障碍、root UID 与 shell UID

- 常规无障碍服务不是 AOSP 通用剪贴板读取豁免，不能因为某项目使用无障碍就推断它能完整采集。
- ClipHistory 明确要求 helper 是 shell UID 2000，拒绝 root UID 0。`su` 成功不等于它的读取协议可用；包名与 Binder 身份必须一致。
- ClipHistory 关于“某 Android 16 构建中 root 读取会导致剪贴板清空”的描述属于上游报告，本次没有复现，不能扩大为所有 ROM 的必然行为。

## 4. AClipBoardManager / ClipVault

### 4.1 两条互斥采集路径

`app/build.gradle.kts` 的 `clipvault.useRoot` 选择 `src/root` 或 `src/xposed`，默认 Xposed。不是两条路径同时兜底。

**Root 路径：** `root/ClipboardWatcherService.kt` 启动 specialUse 前台服务，注册普通 `OnPrimaryClipChangedListener`，回调立即读取当前 primary clip，然后协程保存；开机接收器尝试恢复，服务为 `START_STICKY`。模块提供 APK 系统挂载和权限 allowlist，授权可行性受 3.2 限制。

**LSPosed 路径：** `xposed/ClipboardHook.kt` 和 `ClipboardHookModern.kt` 在 `system_server` 查找 `ClipboardService` 及其内部类的 `setPrimaryClip` 重载，在原调用返回后从参数抽取 `ClipData`，经单线程队列写入应用的 `ClipboardProvider`。后者是受自定义 signature 权限保护的写入入口。

写入参数比事后再读当前剪贴板更接近每次复制事件，能减少“回调时 A 已被 B 覆盖”的问题。但“方法没有抛异常”不等于一定完成系统写入：原服务仍可能静默拒绝。多重重载 Hook 的重复采集、不同 Android/OEM 实现也需要设备验证。

### 4.2 可借鉴

- 把平台相关采集与历史 Repository 分开；UI 不直接拥有采集进程。
- 不使用 `coerceToText()`，避免在系统写入路径同步打开其它应用的 ContentProvider。
- Hook 已加载与确实收到内容分别表达，不能仅凭模块开关就显示“采集正常”。

### 4.3 不应照搬

| 源码事实 | 对 SayIn 的影响 |
| --- | --- |
| `ClipCapture.kt` 使用 `Executors.newSingleThreadExecutor` 的无界等待队列 | 存储阻塞时可能无限积压敏感正文，尤其不应发生在 `system_server` |
| 采集只传文字和时间，没有传敏感标记、原 user/profile 身份 | 无法在持久化入口可靠过滤敏感内容；多用户隔离未得到证明 |
| 文字为空时将 URI 转为字符串入库 | URI 不等于可粘贴文字，不适合直接作为 SayIn 草稿 |
| `ClipDatabase.kt` 使用普通 SQLite driver | 已查看路径未提供应用层历史加密；系统文件加密不等于本应用加密库 |
| 仅按条数裁剪，设置允许 0 表示无限 | 缺单项和总字节边界，不适合持续采集 |
| `ClipDao.upsertCapture` 重复内容只激活原位置，时间不更新 | 与“最近复制在顶部”的历史直觉不同，需要明确产品语义 |
| `clearHistory()` 只清表，没有使队列中旧采集失效 | 清除前已排队的内容可能稍后重新入库 |
| 暂停检查仅保存最近一次开关的墙钟区间 | 不能替代串行状态机和采集 generation；多次切换、迟到任务需额外验证 |
| `post-fs-data.sh` 清理整个系统 package cache | 不为 SayIn 剪贴板功能引入这种全局维护操作 |

Root 监听路径使用 `lastSeenContent` 在落盘前去重。落盘失败或暂停时未保存的相同文本，之后也可能被抑制；它不能当作正确的“清空后不复活”机制。

本地未发现该项目自动化测试源码，本次没有构建 APK 或运行模块。

## 5. cliphistory-android

### 5.1 采集机制

普通界面应用通过 Shizuku UserService 启动独立 helper。`daemon/PlatformClipboardBridge.kt::start` 强制校验 shell UID，通过隐藏 `IClipboard` 注册回调；隐藏接口仅出现在 helper 内，不由普通 Activity 越权调用。

它校验系统回调 UID 1000、清除继承的 Binder 调用身份后立即读当前内容，再交给持久化工作队列。只匹配明确支持的接口签名，未知签名报错，不猜测 transaction number。

这种立即读取缩短了快速复制的覆盖窗口，但回调仍不携带每次复制的不可变正文，**不能承诺零漏采**。注册前先记住当前内容，避免把历史遗留剪贴板误当成新复制；时间戳与 HMAC 指纹用于识别分类更新/重复通知。

Root 场景 README 指明的是 [XiaoTong6666/Sui 的 Shell 模式](https://github.com/XiaoTong6666/Sui)，而非“任意 Sui/Root 模式均可”。该分支还需要 Zygisk 实现。因此“没有修改全局 ClipboardService”不等于“Root 自动启动完全不依赖注入框架”。

### 5.2 生命周期与存储

| 已查代码 | 可借鉴的具体做法 |
| --- | --- |
| `ClipboardUserService.kt` | 单个存储工作者，队列容量 256；溢出计数，不无限扩张；AIDL 操作校验 owner UID |
| 同上及 `OwnerStopGuard.kt` | helper 生命周期绑定 Shizuku server，UI 关闭不等于停止；明确停止/force-stop 不应自行复活 |
| `HistoryKeys.kt` | 应用 UID 使用 Keystore 包裹随机 256-bit 数据密钥；helper 只在内存持有解包后的数据密钥 |
| `PrivateHistory.kt`、`FdAccess.kt` | 数据在应用 noBackup 私有文件；通过受检 PFD 交给 helper，检查文件属主及 inode；文件解除链接后停止旧写入 |
| `EncryptedSlot.kt` | AES-GCM；AAD 绑定应用、存储身份、槽编号和版本，避免不同槽/库简单互换 |
| `HistoryRepository.kt` | 持久化成功后再发布状态；双槽损坏时报错，不默默重建空库 |
| 同上 | 清空/删除/缩容更新两个快照；备份清理失败单独报告，避免从旧备份悄悄恢复已删内容 |
| `PlatformClipboardBridge.kt` | 默认跳过敏感标记；只处理单项直接文本，不读取 URI/provider；输入长度提前设限 |

当前实现默认 100 条，可设置 20–500 条，单项最多 65536 UTF-8 字节；快照编码也有上限。默认按完整原文去重并把非连续重复移到最前，不做大小写/空白归一化。

这套上限不适合直接搬进 SayIn：500 条大正文的满量快照约 32.8MB，每次加密与同步写入都应计算成本。SayIn 输入只需要小规模文字历史，不需要复制其所有设置、迁移和恢复框架。

### 5.3 仍然存在的边界

- helper 的 `attachEncrypted` 明确要求 `userId == 0`。代码不能支持“工作资料、第二用户已经覆盖”的结论。
- 队列容量有限，饱和时会丢弃；项目有丢弃计数，但不是永不漏采。
- 注册前记忆指纹可能使该次订阅间隙的内容不入库；这是一种避免旧内容复活的取舍。
- `captureEpoch` 用于采集实例/文件替换隔离，不能因为存在这个字段就认定清空操作已把所有在途回调纳入同一个 barrier。SayIn 仍需验证清空与排队、在途读取的竞态。
- 双快照清理不是闪存物理安全擦除承诺，不能防御已取得运行中 helper 权限的攻击者。
- 部分串行操作超时后仍可能完成，接口已提示这种不确定性；不能把超时显示为“确定没有执行”。

## 6. Zygisk-ClipboardWhitelist

### 6.1 它究竟改变了什么

模块只注入 `system_server`，在 clipboard 服务注册时包上 Binder 代理。`ClipboardProxy.java::markAsDefaultIME`：

1. 核对请求包名确实属于调用 UID。
2. 查该 user 的包白名单。
3. 找到该 user 当前默认输入法的包名和 UID。
4. 以替换后的 Binder 身份和包名调用原 clipboard 服务，替换包名时丢弃原 attribution tag。

代理覆盖读取、描述、是否有内容及监听注册/注销，并显式处理多个 Android 参数形态。它**没有把 SayIn 设置为默认输入法，也没有真实授予背景读取权限**，而是在系统服务调用中使用默认输入法身份。

### 6.2 对 SayIn 有什么帮助

适合验证“保持既有输入法，让已在运行的 SayIn 采集器在后台仍能读取”。但是：

- 当前 SayIn 只在面板获焦后读取，没有持续监听采集器；仅安装模块不足以生成历史。
- 模块不会让 SayIn 进程自动存活，也不处理重启、明确停止、存储加密、敏感过滤或队列边界。
- 包白名单按用户保存，但仍需真机验证跨资料隔离，不能照 README 推断当前设备都正常。
- 依赖 Zygisk；KernelSU 本体不等于已提供 Zygisk 实现。
- 隐藏 Binder 签名与身份替换依赖系统内部实现，系统升级后要重新验收。

### 6.3 不应沿用的行为与许可边界

白名单变更会 force-stop 对应应用，开机也会停止名单应用。SayIn 可能正在录音、持有草稿/冻结请求，不应将其设计成无提示的常规切换行为。模块安装脚本还有管理 APK 安装失败后卸载重装的路径，不可照搬到 SayIn 本体。

该项目主代码是 GPL-3.0，内置 MagicLibrary/ZygoteLoader 的 MIT 许可不能覆盖主代码。SayIn 当前采用 MIT：不能把 GPL 主代码复制改名后仅以 MIT 分发。独立外部模块联用与复制其代码是不同情况；如决定分发衍生模块，应单独落实对应源码、许可与分发义务。当前仅做机制研究。

本地未发现该项目自动化测试源码。本次没有编译或启用它；“Android 16 + KernelSU/NeoZygisk 已测试”是作者自述。

## 7. 对 SayIn 的落地方向

### 7.1 用户只面对一个剪贴板列表

保留当前剪贴板/常用语组合面板，不增加 Root 剪贴板、Shizuku 剪贴板等业务标签。普通模式显示当前一条；仅在用户启用且模块可用时，同一个剪贴板标签显示模块历史。已有条目操作保持一致：

- 点条目：粘贴进草稿，成功后关闭面板。
- 菜单「粘贴」：同样编辑草稿，面板保持打开。
- 菜单「复制」：复制到系统剪贴板，面板保持打开。
- 清除全部：确认后只清当前标签。普通模式保持既有系统剪贴板清除语义；模块模式向模块请求清空历史，不顺带清除系统当前剪贴板。常用语仍独立处理。

以上动作不直接向电脑发送。剪贴板记录无需携带配对令牌或通信权限，桌面端和 WSS 协议无需为本地历史增加新路径；最终发送仍使用既有草稿、Outbox 和回执规则。

### 7.2 非 Root 手机保持现状

普通模式继续仅在面板取得焦点并获用户操作授权后读取当前系统剪贴板，不监听后台复制、不持久保存剪贴板历史；SayIn 内复制也不因此新增历史入库。常用语仍按既有规则保存。

不增加输入法身份、无障碍、Shizuku 或普通模式后台保活要求。用户可以继续使用原输入法的历史功能，SayIn 不读取输入法的私有历史。

检测到 Root 本身不等于开启采集。模块未安装、未启用或不可用时保留普通模式和现有操作；失败时明确提示状态，不清掉模块已经保存的数据，也不要求安装模块才能使用 SayIn。能力检测不等于已授权采集，不静默安装模块或授予权限。

### 7.3 模块历史的必要约束

这些是后续设计建议，**不是本次已实现或已确认的产品参数**：

- 首轮建议 50 条，单条沿用当前草稿可接收规则：4000 Unicode 码点、规整后 UTF-8 16384 字节；原始正文另外限制 UTF-8 16384 字节，总正文不超过 512KiB。不截断后伪装成完整复制；沿用既有 `PhraseRules`，不要再写第二套正文校验。
- 默认不保存 Android 敏感标记内容；没有标记也不能保证不敏感。手动临时读取和持久保存必须分开处理，不因用户愿意粘贴一次就默认允许长期存储。
- 同文去重、最近复制优先。重读同一次系统内容不刷新排序；明确的新复制与晚到分类通知分开处理。需区分 SayIn 自己复制后的监听回声，避免多次入库。
- 使用模块拥有的私有加密存储、串行写入、条数/字节/队列三重边界；坏库和写失败明确报告。模块是唯一历史写入者，SayIn 不维护第二个可独立修改的历史库。
- 清空和暂停进入同一串行所有者，使旧 generation 的在途读取和队列项失效；记录当前 clip 指纹，避免重新开面板立刻恢复刚清掉的条目。用户再次明确复制同文仍应可记录。
- 停止增强采集或授权撤销时，注销采集入口、停止采集任务；退出模块模式回到普通单条视图。模块历史的保留或删除由明确的清空操作处理，不顺带清掉草稿/配对/Outbox。
- 采集 IPC 只接收有界文本及必要事件信息，校验调用身份和用户，不允许任意路径/命令/URL。特权部分不持有电脑令牌、不直接发网、不执行输入。

### 7.4 增强方案的实施顺序

1. 读取目标手机实际 ROM、Root 框架与版本，用最小模块原型验证独立采集。原型在 SayIn 进程不运行时仍能记录外部复制；不能只用 App 后台存活证明独立性。
2. 确认采集入口后，再完成模块加密存储、鉴权 IPC、停止/清空代次和资源边界。一次只选定一条经验证的采集路径，不同时引入多种注入框架。
3. SayIn 接入同一面板的模块历史列表和管理操作，完成完整页/快捷卡回归，同时验证非 Root 的单条行为未改变。模块不接入桌面通信与自动发送。

现有 [02 Root 辅助后续计划](02-voicelink-root-assistant-plan.md) 的快捷唤出/工作会话原型与本次剪贴板能力分别管理；该文已追加本次决策引用，但没有据此选择系统 Hook。[52 设计](52-android-clipboard-and-quick-phrases-design.md) 与 [53 实施](53-android-input-assist-implementation.md) 仍描述当前已实现的单条行为；模块历史是用户已确认方向，不是已实施功能。

### 7.5 已确认架构与进程边界

```text
系统复制事件 → 模块采集入口 → 模块独立采集进程 → 私有加密历史
                                      ↑              ↓
                                 管理命令 ← 鉴权 IPC → SayIn 组合面板
```

- **模块负责采集和持久化。** SayIn 界面关闭或进程退出不打断已明确启用的模块采集；模块不为采集反复拉起 SayIn，不维持其模型、录音或电脑连接。首次安装默认不采集，用户明确启用后才开始。
- **SayIn 负责显示与用户操作。** 获取有界分页/正文，粘贴进入唯一草稿；删除、清空、暂停和恢复请求交给模块执行，不直接改模块文件。仅模块确认持久成功后才显示清空成功；IPC 失败不能假报完成。
- **文件可作底层存储，不作公共交换目录。** 推荐模块持有加密文件，SayIn 通过受控 Binder 或本地 socket 读取；若验证采用只读 FD，则检查属主、类型和版本并限制大小。不放共享存储，不使用全局可读写权限，不让 App 轮询明文文件。具体 IPC 选型留待原型验证。
- **独立存续需要独立密钥策略。** 不能声称模块在重启后可自行恢复，却把唯一解密条件放在未启动 App 的内存里。实施前验证 Android 用户解锁、模块存储访问与密钥恢复；首版不采集锁屏/未解锁状态的内容，密钥不可用时停止采集并报状态，不降级明文。
- **停止语义清楚可见。** 关闭界面不停止独立采集；启用保活后，可确认的系统回收才触发有界待命恢复，强行停止或退出原因不明时不拉起 App。上述事件不自动撤销此前明确启用的独立采集，停止采集以明确关闭或模块停用为准。最新跨重启与独立开关设计见 [56](56-root-module-clipboard-and-keepalive-design.md)，不继承 02 的限时语音工作会话。
- **采集入口只做必要工作。** 如果使用系统 Hook，仅提取有界数据并转交独立进程；不在系统调用线程写文件、加密或等待 App。敏感标记与用户身份必须随事件保留，不能只传一个正文字符串。

本次只确定上述职责和普通模式边界。采集入口、密钥保存方式、IPC、支持的 Root/ROM 组合和跨重启策略均需原型与真机证据，尚未实现。

## 8. 本次实际验证

| 项目 | 实际执行 | 结果与限度 |
| --- | --- | --- |
| 三个本地目录 | 阅读关键调用链、Manifest、构建配置、模块脚本、存储及许可，保存文件摘要 | 源码核查；未运行其安装脚本 |
| AOSP 权限 | 直接获取 Android 10/13/16 固定 tag 的 Manifest，另核对当前 ClipboardService 和 Shell Manifest | 确认权限不是 `signature|privileged`；ROM 自定义未验证 |
| ClipHistory 静态检查 | `python refer/clipboard/cliphistory-android-main/tools/audit_source.py` | 75/75；属于项目自带规则，不等于独立安全审计 |
| ClipHistory CoreSuite | 原样编译 `core/*.kt` 与 `CoreSuite.kt`，运行 `app.cliphistory.CoreSuite` | 55/55；真实核心代码，非 Android 采集行为 |
| ClipHistory 全部本地 JVM 测试 | 原样编译 `core/*.kt` 与 `src/test`，JUnitCore 运行 12 个测试类 | 100/100，含加密、复制指纹、敏感策略、撤销、恢复等；其中一个测试再执行上述 55 场景，不能声称 155 个独立用例 |

JVM 环境为项目指定的 Temurin 25.0.5+8，缓存 Kotlin embeddable compiler 2.4.21，字节码目标 17，JUnit 4.13.2 / Hamcrest 1.3。未修改上游源码，输出在被忽略的 `android/build/clipboard-research/`，副本在研究证据目录。这里直接编译纯 Kotlin 测试，没有使用 Java 重写采集程序。

本次只新增研究文档和本机研究产物，不修改产品行为，因此没有运行 SayIn 的完整 Android/桌面构建、Lint 或业务回归，也没有以这些 JVM 测试代替真实后台采集验证。工作区中已有的 Android UI 与 54 文档改动未纳入本任务。

## 9. 后续必须在真实设备验证

选择增强实现前，至少验证以下场景，不用模拟 clipboard 对象代替：

1. 在两个外部应用依次复制 A/B/C，SayIn 不在前台时能否采到；增加快速连续复制，测实际漏采与饱和丢弃，不承诺绝不遗漏。
2. 前台/后台、熄屏、进程被杀、明确停止、重启未解锁/已解锁、Shizuku/Sui 服务死亡与授权撤销；明确停止后不得自行恢复。
3. 清空前排队/读取中的旧条目、清空后分类通知、同文重新复制、手动复制监听回声、暂停再恢复；旧内容不能自动复活。
4. 敏感标记、超长 Unicode、URI/Intent、多项目 clip、受管资料和第二用户；不把不支持的输入悄悄转为字符串或跨用户记录。
5. 加密库损坏、密钥失效、空间不足、清除应用数据、helper 仍持有旧 FD；不得覆盖坏库或恢复已清除数据。
6. 同一完整页/快捷卡的插入、菜单复制/粘贴、撤销、翻译、语音与原 Outbox/uncertain 安全语义；被动采集不产生任何自动发送。

当前尚未完成上述设备验证，不能交付“Root 模块已可用”或“外部全部复制都能记录”的结论。

## 10. 主要代码及官方依据

- [Android ClipboardManager](https://developer.android.com/reference/android/content/ClipboardManager)：当前 clip、输入焦点及默认 IME 限制。
- AOSP 权限固定版本：[Android 10](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/res/AndroidManifest.xml)、[Android 13](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/res/AndroidManifest.xml)、[Android 16](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/res/AndroidManifest.xml)，搜索 `READ_CLIPBOARD_IN_BACKGROUND`。
- [AOSP ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/clipboard/ClipboardService.java)：`clipboardAccessAllowed` 及监听分发权限判断；[Shell Manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/Shell/AndroidManifest.xml)。main 为可变分支，仅辅助解释，权限结论以固定 tag 为据。
- 本地 AClipBoardManager：`app/src/xposed/java/com/clipvault/app/xposed/ClipCapture.kt:85`、`app/src/root/java/com/clipvault/app/root/ClipboardWatcherService.kt`、`app/src/main/java/com/clipvault/app/data/ClipRepository.kt:36`、`ClipDao.kt:57`、`module/system/etc/permissions/privapp-permissions-com.clipvault.app.xml`。
- 本地 ClipHistory：`app/src/main/java/app/cliphistory/daemon/PlatformClipboardBridge.kt:39`、`ClipboardUserService.kt:21`，`core/HistoryRepository.kt:56`、`EncryptedSlot.kt:11`、`ClipboardCopyTracker.kt:9`，`client/HistoryKeys.kt:28`、`PrivateHistory.kt`，以及 `SECURITY.md`、`app/src/test/`。
- 本地 Zygisk 项目：`module/src/main/java/com/github/kr328/clipboard/ClipboardProxy.java:228`、`WhitelistService.java`、`Main.java`、`DataStore.java`、`module/src/main/assets/service.sh` 及根 `LICENSE`。

以上源码路径相对于各自本地项目根。`refer/` 不随 SayIn 提交，未来正式复用时应固定来源 revision、保留适用许可并执行重新验证。
