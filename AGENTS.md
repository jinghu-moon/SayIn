# 开发阶段工程规则

当前项目处于**开发阶段，尚未正式发布**。

当前阶段的首要目标是：**解决根因、保持正确性、追求性能与合理架构，而不是维护历史实现。**

## 项目思考原则

- **第一性原理**：剥离经验、类比与既有假设，追溯至不可再还原的基本事实，再从这些事实出发推导答案。先明确用户目标、必要约束及可验证事实，不能把旧实现或同类软件的惯例当成必然需求。
- **奥卡姆剃刀**：如无必要，勿增实体。每个新增概念、状态、配置、抽象、控件和步骤都应有明确必要性；能够用现有事实与更简单模型正确解决时，不引入额外实体。
- 简单必须以正确性为前提；不得用隐藏状态、忽略错误或省略必要授权来制造表面简洁。

## 1. 兼容性

当前阶段**不考虑向后兼容性**，向后兼容不是当前阶段的设计约束。

允许并鼓励：

- 破坏性 API 修改；
- 删除旧实现；
- 重构数据结构；
- 调整模块边界；
- 重构调用链；
- 修改既有接口；
- 删除废弃代码；
- 重新设计不合理的抽象。

除非当前需求明确要求，否则不要为了兼容旧实现增加：

- Adapter；
- Compatibility Layer；
- Legacy API；
- Migration Logic；
- 临时兼容分支；
- 重复实现。

**可以破坏旧实现，但不能无意破坏当前已有功能。**

---

## 2. 根因优先

发现问题后，必须优先寻找**根本原因**，而不是只修复表面现象。

优先判断问题属于：

1. 局部实现错误；
2. 接口设计错误；
3. 数据结构错误；
4. 模块边界错误；
5. 调用流程错误；
6. 架构或抽象设计错误。

如果问题来自接口、数据结构、模块边界或架构，应优先修改对应设计，而不是在外层增加补丁。

禁止为了让当前代码“看起来能工作”而：

- 增加特殊判断绕过问题；
- 增加临时分支；
- 用包装层隐藏错误设计；
- 保留明显错误的抽象；
- 在错误的数据结构上继续堆功能。

---

## 3. 最小改动不是目标

**最小 Diff、最少改动和最少文件修改，不是目标。**

不要因为 Diff 更小、改动文件更少或短期风险更低，就默认选择局部修补方案。

如果：

- 方案 A 改动很小，但只是绕过问题；
- 方案 B 需要重构，但能够彻底解决根因；

并且方案 B 可以被充分验证时，**应优先选择方案 B**。

目标不是“少改代码”，而是：

> **用合理的设计，从根因上解决问题。**

---

## 4. 重构规则

如果现有设计明显不合理，允许直接：

- 删除；
- 重命名；
- 重构；
- 合并；
- 拆分；
- 重新设计；
- 替换整个实现。

不要因为：

- 旧代码已经存在；
- 改动范围较大；
- Diff 较大；
- 旧代码目前还能运行；

就默认保留错误实现。

不要为了减少改动范围而牺牲：

- 正确性；
- 性能；
- 架构质量；
- 简洁性；
- 可维护性。

### 决策优先级

多个方案都可行时，按以下优先级选择：

> **正确性 → 根因解决 → 性能 → 架构质量 → 简洁性 → 可维护性**

当前阶段**不以向后兼容性作为方案选择依据**。

### 性能要求

性能优化必须有明确依据，例如：

- 基准测试；
- Profiling / 性能剖析结果；
- 明确的性能热点；
- 明显的时间或空间复杂度问题；
- 已知的性能瓶颈；
- 可量化的资源占用问题。

不得为了臆测性能而增加不必要的复杂抽象、缓存、并发机制或数据结构。

---

## 5. 修改前分析

对于可能影响多个模块、公共接口、数据结构或核心流程的修改，实施前必须先确认：

- 当前实现是什么；
- 问题表现是什么；
- 问题根因是什么；
- 影响范围是什么；
- 是否属于设计问题；
- 是否需要重构；
- 修改后的目标设计是什么；
- 如何验证修改正确。

需要时，应先阅读相关代码、测试、配置以及调用方，理解完整调用关系后再修改。

**禁止在未理解关键代码路径的情况下直接修改核心实现。**

---

## 6. 测试与回归

**不考虑兼容性，不等于不需要回归测试。**

对于可能影响已有行为的修改，应按照：

> **修改前建立基线 → 实施修改 → 验证新行为 → 验证已有行为 → 对比结果**

进行验证。

至少确认：

- 新功能正常；
- 修改后的功能符合需求；
- 原有相关功能正常；
- 边界情况正常；
- 异常情况符合预期；
- 性能没有出现不可接受的退化；
- 无关功能没有被意外破坏。

已有测试必须优先复用并运行。

没有现成测试时，应通过可执行方式建立验证，例如：

- 单元测试；
- 集成测试；
- 构建检查；
- 静态检查；
- CLI 验证；
- 示例输入输出；
- 必要的人工验证。

### 验证必须真实有效

不得通过以下方式制造“验证通过”：

- 跳过测试；
- 注释测试；
- 弱化断言；
- 删除失败用例；
- 修改错误的测试预期以掩盖问题；
- 降低验收标准；
- 只运行无关测试；
- 只验证新增功能而不验证相关已有功能；
- 在没有实际执行验证的情况下声称验证通过。

如果测试因为当前设计发生合理变化，可以修改测试；但必须确认：

> **修改测试是因为预期行为发生了正确变化，而不是为了让错误实现通过测试。**

### 验证证据

完成修改时，应明确说明：

- 执行了哪些测试或验证；
- 使用了什么命令或步骤；
- 关键验证结果是什么；
- 修改前后的关键行为差异；
- 是否存在尚未覆盖的风险。

---

## 7. 禁止伪完成

以下情况不得认为问题已经解决：

- 只绕过错误；
- 只让单个用例通过；
- 只修改调用方而未处理根因；
- 未确认根因；
- 未进行必要测试；
- 留下临时兼容代码；
- 留下无效旧实现；
- 留下已知 TODO 却声称任务全部完成。

**“代码修改完成”不等于“任务完成”。**

任务完成必须满足：

> **根因解决 + 新功能正确 + 相关已有功能正常 + 验证通过**

---

## 8. 验收标准

一次修改只有同时满足以下条件，才视为完成：

1. 根因已经解决，而不是绕过问题；
2. 新需求已经正确实现；
3. 相关已有功能未被意外破坏；
4. 相关测试或验证已经通过；
5. 没有为了兼容旧实现而引入无意义复杂度；
6. 没有留下仅用于过渡的兼容代码；
7. 没有保留已知无效代码、重复实现或废弃接口；
8. 修改后的实现相比修改前，在正确性、性能、架构质量、简洁性或可维护性方面具有明确合理性。

**完成标准不是“代码改完”，而是“验证通过”。**

---

## 9. 关键行为对比

对于关键行为发生变化的修改，应记录修改前后的实际行为：

| 项目     | 修改前     | 修改后     | 预期结果                     |
| -------- | ---------- | ---------- | ---------------------------- |
| 原有功能 | 实际行为   | 实际行为   | 应保持正常，或按需求明确变化 |
| 修改功能 | 实际行为   | 实际行为   | 应符合新需求                 |
| 新增功能 | 不存在     | 实际行为   | 应符合需求                   |
| 边界情况 | 实际行为   | 实际行为   | 应正确处理或明确拒绝         |
| 性能表现 | 修改前指标 | 修改后指标 | 不应出现不可接受退化         |

---

## 10. Android 包名与构建工具链

用户已确认 Android 最终包名为 **`seeyuer.sayin`**。`namespace` 和 `applicationId` 均使用该值，Kotlin 源码、测试与 JNI 导出使用相同根包名。旧包 `seeyuer.voice.link` 仅作为历史记录保留；新包独立安装，需要重新配对，不自动迁移旧包数据。

本项目固定使用 **Eclipse Temurin JDK 25 LTS，25.0.5+8**，已由用户确认。

- 本机安装目录：`C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot`。
- 当前终端 PATH 默认仍指向 JDK 21，不应依赖默认 `java` 命令判断项目构建环境。
- Android 工程初始化时，将项目 Gradle JDK 明确设为上述 JDK，并用 Wrapper 的 `--version` 核对实际 Launcher/Daemon JVM；IDE与命令行配置保持一致。
- 配套版本为 Kotlin **2.4.21**、AGP **9.3.1**、Gradle **9.7.0**；Java/Kotlin 字节码目标初始均为 **17**，与构建 JDK 分开配置。
- Android 工程位于 `android/`；从仓库根目录使用 `scripts/gradle.ps1` 调用项目 Wrapper，明确使用已确认的 JDK。
- 当前最低系统为 Android 10/API 29，与平台 TLS 1.3 必需条件一致；不得重新声明不具备该能力的旧系统受支持、降级 TLS 或为此引入兼容 provider。根因、修改前后验证与 API 37 真实系统授权测试见 [49](docs/49-android-platform-support-and-validation.md)。模拟器 ABI 仅允许显式 `sayin.emulatorTests=true` 的隔离 imageTest variant，日常包默认仍为 arm64-v8a。
- 工程基础已完成首次 Debug 构建；后续状态以 `docs/04-android-foundation-implementation.md` 中的实际验证记录为准，不能将构建通过等同于业务链路或真机运行通过。

详细依据见 [Android 技术选型](docs/03-android-technology-selection.md)。

---

## 11. 国际化约束

- 首批界面语言为用户确认的简体中文与English；支持跟随系统。语言标签使用`zh-Hans`、`en`，跟随系统使用空应用Locale列表。
- `res/values/`为完整英文兜底，`res/values-b+zh+Hans/`为完整简体翻译。用户可见文案与无障碍说明必须使用资源，语言自称等明确不可翻译项例外。
- 使用AppCompat/Android应用语言API作为唯一设置来源，不另在DataStore存一份覆盖系统设置。Compose宿主保持`AppCompatActivity`。
- UI语言、ASR识别语言和用户文字互相独立；切换语言不得修改草稿、Outbox载荷、历史原文或协议代码，不能触发发送或重试。
- 数据层保存状态码和类型化参数，展示时解析本地资源，不缓存本地化成品作为业务状态。完整句子使用编号参数，数量使用复数资源。
- 修改UI文案或语言声明时执行`python scripts/check_i18n.py`和`:app:lintDebug`；不得屏蔽漏翻或格式错误。布局考虑长文本、大字号、start/end和RTL。
- 国际化完整方案与运行验收边界见[05国际化设计](docs/05-voicelink-internationalization-design.md)。

---

## 12. Windows电脑端组件库

- 用户指定电脑端使用本地 **Yue-GPUI-Component**：`D:/100_Projects/110_Daily/Yue-GPUI-Component`；组件crate为`crates/yue-component`。
- GPUI Kit/Base作为底层，应用组件采用Yue公开API与内置Tabler图标；以该仓库README和最小消费者示例为接入依据。
- 优先通过`yue_component::gpui_kit`复用相同GPUI类型，不另拉一套`gpui`或默认Kit样式组件/图标全集。
- 消费方Cargo工作区根必须显式配置`gpui-base`、`gpui-pre`、`gpui-pre-windows`三个本地补丁；依赖库的`[patch.crates-io]`不会传递继承。
- 本地库目前基于GPUI Kit0.8.0；框架升级必须与Yue及维护补丁一起验证，不单独改版本造成类型/API不匹配。
- `desktop/Cargo.toml` 显式保留 Kit `=0.8.0` 精确约束，否则 Cargo 会升级并绕过维护补丁。Rust 工具链固定 `1.99.1`，不修改系统默认工具链。
- 只执行 `cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport`，禁止 `cargo fmt --all` 遍历并修改相邻 Yue path 工作区。运行 `python scripts/check_desktop_dependencies.py` 检查实际依赖图及引用源摘要。
- 主题继续遵守`#1f75db`与neutral要求，界面语言支持简体中文、English及跟随系统。不能把库默认主题中其他彩色状态当成用户已同意的颜色。
- 具体路径与核查边界见[07电脑端Yue接入记录](docs/07-desktop-yue-component-integration.md)。

## 13. Windows 接收核心与跨设备通信

- `desktop/crates/receiver` 不依赖 GPUI；账本协调线程独占 SQLite，单独的输入线程串行执行。GUI 的 `ReceptionState` 只持有句柄、状态快照和本地命令任务，不在 UI 线程做数据库或 Win32 输入工作。
- 收据使用 WAL/FULL，接受和执行标记必须先提交；终态结果提交后才可作为持久回执。执行中崩溃恢复为 `uncertain`，待执行旧请求恢复为 `held`，不能自动重试不确定的输入。
- 暂停闸门带代次；较早的恢复不得越过后到的暂停。`locally_reviewed` 只能由电脑本地明确核对操作授权，绝不能映射到手机任意布尔字段。
- 设备令牌和 payload 指纹使用带域分离的 HMAC；待执行载荷和密钥用当前 Windows 用户 DPAPI 保护。缺失数据库/密钥/账本结构时拒绝静默创建新去重身份。
- 配额使用同事务触发器维护计数，不能在新接受路径上扫描整个历史表；持久收据不静默清除。实时通知只作有界合并信号，读取最新快照，不累积无限历史事件。
- 配对批准后自动待命，普通重启在网络许可有效时自动恢复；显式暂停和 uncertain 不自动越过。向当前光标输入，不再手动锁定窗口或倒计时。每次操作独立捕获并核对前台窗口、进程创建时间、活动会话、输入桌面、权限和修饰键；不抢焦点、不自动追加 Enter。通用全局输入仍有不可原子消除的前台窗口竞态。
- 已接入 CPace 代码配对、二维码邀请、固定证书 TLS 1.3/WSS `/v2`、本地批准与授权设备管理；Android 已接入 Keystore 加密、Room Outbox 与回执恢复。桌面端已接入原生托盘、关闭行为设置、DPAPI 全文历史和执行目标归属，历史支持复制原文。已支持 IPv4 有界 mDNS/NSD 恢复（见第 20 节）；仍无 IPv6；可选 C++ Root 模块实现 Android 13/17 arm64 独立采集/加密历史，默认关闭；App 已通过固定签名鉴权的本地 socket 读取记录，保活未实现，见 [62](docs/62-root-helper-app-clipboard-integration.md)。0.4.1 普通文本分类格式及面板加载修复见 [63](docs/63-root-clipboard-classification-and-loading-fix.md)；版本/ROM 验证范围见 [61](docs/61-root-helper-android17-validation.md) 和后续记录；图片剪贴板生产链路与验收边界见第 21 节；Android 本地 X-ASR 流式识别及验收边界见 [31 本地识别实施](docs/31-x-asr-local-recognition.md)。不得把 loopback fake adapter 测试或 Debug 构建描述为真实手机到电脑落字通过或延迟目标已达到。
- 修改后执行 `cargo test --workspace --all-targets --locked`、`cargo clippy --workspace --all-targets --locked -- -D warnings`、包限定格式检查、工作区构建和双端 i18n/依赖检查。含真实接收线程的 GPUI 测试启用 `allow_parking`；纯 UI/状态测试仍优先使用确定性调度。
- 当前具体实现与验证见[09接收核心实施记录](docs/09-desktop-receiver-core-implementation.md)和[13当前光标交互](docs/13-desktop-current-cursor-and-ui-redesign.md)。
- 最新四页原生界面、托盘生命周期、账本 schema 3 和验收边界见[16桌面端重构实施记录](docs/16-desktop-ui-refactor-implementation.md)。正文历史和执行时目标在协调线程保存；目标 metadata 先于输入执行持久化，不能在浏览历史时抓当前程序补填。旧配对/收据身份必须保留；缺失已安装的历史结构或设备名称列时先拒绝打开，不能先执行崩溃恢复写入。
- 当前历史页与账本 schema 4 见[25历史界面重构](docs/25-desktop-history-ui-refactor.md)：响应式卡片、后台应用图标、删除撤销和保留策略。清理仅影响展示历史，不删除收据、去重指纹或队列；默认永久保留。用户指定的 `win-icon-extractor =0.3.0` 为 AGPL-3.0-only，不得把含该依赖的桌面分发物全部标为 MIT。
- 当前账本 schema 5 与设备名称见[26历史去向与设备商品名称](docs/26-history-route-and-device-display-name.md)：Android 普通权限读取商品名，`hello.name` 必需且仅在 UUID/令牌认证成功后刷新同一设备。改名不改变接收闸门/身份、不补发；历史来源名称在接收事务中保存快照，不能关联当前设备名回写旧历史。软件可读名称与图标一并在后台从 exe 资源读取，完整路径留在详情。
- 接收后 30 秒期限覆盖 accepted 与 held，人工 release 不续期；协调线程必须在无查询/界面刷新时主动过期并通知。有效 held 队首仍阻塞后续，执行中及 uncertain 不由定时器改判，正文历史保留。根因、双端原因提示和回归证据见[19已接收但未落字修复](docs/19-received-but-not-typed-queue-expiry-fix.md)。
- Windows 输入权限按双方 `TokenIntegrityLevel` 比较，允许向相同或更低级别目标提交；不能仅凭目标管理员标志拒绝。查询失败仍拒绝，勿默认提权。held 到期保留原始阻断 reason，避免过期掩盖原因；真实 EDIT 进程验证工具及 tranvoice 对比见[20输入权限修复](docs/20-native-input-integrity-and-tranvoice-comparison.md)。
- 显式打开主窗口必须同时处理隐藏、最小化和已关闭三种状态，不能仅调用 `activate_window()` 假设窗口会显示。Windows 启动可见主界面使用普通可见模式，并核对主窗口而不只核对进程存活；真实窗口回归和退出销毁顺序见[17托盘打开修复](docs/17-tray-main-window-restore-fix.md)。

## 14. 配对与手机通信

- 协议权威说明见[01 v2 通信方案](docs/01-voicelink-protocol-v2.md)，代码/证据见[10 配对与 Android 通信实施记录](docs/10-secure-pairing-and-android-transport.md)。短码仅经固定版本的 1Password CPace 使用，扫码和短码均通过 TLS 主机固定且在电脑批准；不信任任意证书。
- Android Outbox 以 host/device 双重范围隔离。同连接查询先于旧请求处理；unknown 必须人工核对，相同请求 UUID 只在用户明确操作后重试，uncertain 永不自动重试。持久凭据/载荷丢失或加密校验失败时停止发送，不静默新建身份。
- 已支持代码协议和手工 IPv4/二维码；已支持有界 IPv4 mDNS/NSD，无空闲后台保活（用户主动录音期间的前台服务见第 19 节）。Windows 自动优先物理局域网及记住的可用路径，Private/Domain/Public 均不要求应用内网络许可；正常流程不提供网卡必选项。监听精确绑定网卡 GUID、IPv4、Windows 网络 GUID/类别集合，启动前及约每 2 秒核对，查询失败/超时或范围变化停止旧服务并暂停输入，再自动发现/重建。网络恢复只轮换新输入范围，不释放旧 held；主动暂停、待核对、存储阻断不得自动越过。首次直接展示邀请，已有手机只恢复监听，不重开配对。Windows 分类/防火墙不自动修改。手机先连保存地址，失败后通过有界 NSD 找回同一身份，完整认证后才持久更新 IPv4/端口；连接帮助内手动维护仍保留，详见 40。详见 [29 接收页重构](docs/29-desktop-receive-ui-refactor.md)；11 为历史策略。周期检测存在有界检测窗口，不声称网络变更与 socket 输入原子联动。
- 双端通信回归统一用 `scripts/verify_android_transport.ps1 -FullSuite`，只安装 `seeyuer.sayin.imagetest` 隔离包，当前包含 115 项常规界面/文字及另一个独立电脑身份的 4 项图片测试；旧的日常包测试实现和重复图片脚本已删除。运行前须手动解锁手机；默认普通安装，已授权的 Root 设备可加 `-UseExistingRootInstall`，`-SkipInstall` 仅复用本轮已安装隔离包。完整套件通过 `adb reverse` 连接 loopback 专用假输入服务，单独 `-Lan` 仅验证真实局域网图片数据，不能与 `-FullSuite` 同用。自动批准仅在测试 fixture，不能挪到产品；各组必须使用独立 host/device、数据目录与随机端口，不覆盖日常配对或 Outbox。
- 当前没有实测 p50/p95/p99、续航或普通目标应用落字证据。查看 [10 实施 JSON](docs/research/10-secure-pairing-verification-2026-11-12.json) 核对本机 Android 仪器化测试是否能安装运行。

---

## 15. SayIn 品牌

- 产品名称在简体中文与 English 中统一为 **SayIn**；采用用户确认的 `assets/sayin-icons/B-field`（光标入框）原始图标，F-yan 仅保留源方案。
- 通过 `python scripts/sync_brand_assets.py` 同步双端品牌资源及来源 SHA-256 清单，不重画或改色；业务功能图标继续使用 Tabler。
- Android Launcher 可按官方自适应图标规范等比调整内部留白：108dp 图层、中心直径 66dp 安全圆，当前 B-field 图形宽 48dp；前景与单色层几何一致。不得连带缩小背景底板或修改品牌颜色，详见 [35 Android 启动器图标](docs/35-android-launcher-icon-safe-zone.md)。
- Android 最终包名为 `seeyuer.sayin`，详见第 10 节；Windows app_id、既有数据目录、配对/历史身份及 Rust 内部 crate 名称保留。Windows 运行产物为 `sayin.exe`。
- 接入范围与验收证据见 [21 SayIn 命名与图标](docs/21-sayin-brand-name-and-icons.md)。

## 16. 交互简化与默认行为

用户明确要求：**尽可能减少用户选择和操作负担**，此规则适用于原生软件、方案与 demo。

- 能可靠自动判断的技术配置由软件处理，提供合理默认行为；不能把内部状态、网络接口或实现步骤直接变成必填选择。
- 仅在用户意图无法推断、存在实质不同的结果，或必须获得用户授权时才要求选择。不得增加重复确认、重复配对或无意义的流程。
- 主流程优先完成任务，低频设置与技术细节按需展开；异常时说明问题并给出最直接的恢复操作。
- 首次配对与日常重连分开：已有设备复用身份，网络恢复不能要求重新扫码；保留用户主动暂停及待核对状态。
- 评审控件仅用于 demo，不得直接照搬到产品。改动安全或网络策略仍需修改完整调用链并验证，不能只隐藏原有授权控件。

## 核心原则

扫码与二维码回归补充见 [12 配对二维码与扫码方向修复](docs/12-pairing-qr-and-scanner-orientation-fixes.md)。二维码使用完整颜色图像、静区与整数物理像素模块，不得改回 SVG icon alpha mask 或线性缩放；Android `CaptureActivity` 显式采用 `fullUser`，不继承依赖的横屏默认值。二维码原生解码回归使用 `scripts/verify_pairing_qr.ps1`，原有 Android 仪器化回归为 7 项；新增手机端 UI 测试后总计 11 项，最新完成情况以 [14手机端重设计](docs/14-mobile-four-tab-ui-redesign.md) 为准，不能把测试编译通过当作真机运行通过。

> **开发阶段不被历史实现束缚。**
>
> **不考虑向后兼容，但必须保证当前功能正确。**
>
> **可以破坏旧实现，但不能无意破坏现有功能。**
>
> **根因优先，重构优先于妥协。**
>
> **最小 Diff 不是目标，根因解决才是目标。**
>
> **性能优化必须有依据，禁止为了臆测性能而过度设计。**
>
> **代码改完不是完成，验证通过才是完成。**

手机端四页 UI、输入法入口和保持亮屏实现与验证状态见 [14手机端重设计](docs/14-mobile-four-tab-ui-redesign.md)。说话页使用统一输入来源与明确完成输入后的可选智能发送（默认手动），见 [29统一说话交互](docs/29-mobile-unified-speak-interaction.md)。本地识别已接入 sherpa-onnx 2.0.1 + X-ASR 480ms 中英带标点 FP32：AudioRecord、partial 更新、结束定稿与生命周期取消共用同一草稿 / 发送入口。AAR 与模型摘要固定于 `android/asr-artifacts.json`，先执行 `python scripts/prepare_x_asr.py` 准备资源；构建自动验证摘要，JNI 类名与字段必须保留。25 项 JVM 测试及 1 项 Redmi K40 非 UI 原生推理测试已通过；麦克风实录、完整 UI / 通信回归、长期功耗和目标日常手机性能尚待验收，详见 [31 本地识别实施](docs/31-x-asr-local-recognition.md)。31 为当时阶段记录；当前共享会话、可选快捷卡/磁贴/悬浮球和录音前台服务见第 19 节，仍无独立 VAD。不得将文件推理验证描述成实录到电脑落字通过。


## 17. Android 云端流式识别

- 首期为豆包流式语音识别 2.0 小时版，固定官方 WSS `bigmodel_async` 与 `volc.seedasr.sauc.duration`；不静默探测资源套餐、切换引擎、重连重传音频或重复发送。
- `SpeakSession` 持有统一 `RecognitionEngine`，`SpeechRecognition` 管理本地/云端麦克风与识别资源；页面只展示共享会话；流式全文快照覆盖冻结的草稿选区，仅用户明确结束且收到最终结果后才能进入现有完成输入/发送判断。partial、断网、取消、尾包超时不得授权自动发送。
- 手机直连云服务，电脑协议仍只承载用户选择发送的文本。APP Key 或 APP ID + Access Token 只在 AndroidKeyStore AES-GCM 加密配置中持久化，独立于配对密钥、存于 noBackupFilesDir；缺失密钥/篡改拒绝读取，不写入日志、实例状态或桌面端。
- 配置由 Application 的 ViewModelStore 唯一持有，后台串行读写；仅显式选择云端并点击录音才上传真实音频。验证服务只发送 1 秒生成静音、不访问麦克风、不触发 LAN 发送，明确说明可能计费及不能验证准确率/热词。
- PCM 为 16kHz/mono/16bit、约 200ms 分包；未压缩 PCM socket 队列预算 64KB，超过即停止报错，不静默丢包。最终结果等候 10 秒，握手等候 12 秒。云端错误仅保留类型化状态，不输出服务响应或请求凭据。
- 本轮基线 25 项 JVM 测试，新增协议与 WebSocket 模拟测试后 38 项；完整仪器化套件预期从 19 增至 23 项，编译通过不能描述为完整真机套件通过。实际执行证据与未覆盖范围见 [32 豆包云端流式识别](docs/32-doubao-cloud-streaming-asr.md)。SayIt 为 AGPL 参考工程，本次协议代码基于官方资料独立实现，不复制其源码。


### 豆包结束包判定补充

- 最终结果由消息 flags 的 bit 1 判定，bit 0 只表示有 sequence；不得要求最终序号必须为负数。真实 `bigmodel_async` 已复现 `flags=3, sequence=+5`，与官方 message-flow 示例一致；正序号最终包、负序号最终包均需接受，非最终标记不能因负序号被当成最终包。
- 2026-11-13 的实测修复与凭据模式边界见 [33 云端结束包误判修复](docs/33-doubao-final-packet-fix.md)。`DoubaoLiveInstrumentedTest` 是显式 `-e liveCloud true` 才执行的付费云服务测试，默认假设跳过；完整通信验证脚本排除该类，常规套件在该阶段为 23 项，另有 3 项 opt-in 实际云服务测试；当前手机重构后为 27 项，见下节。凭据只在手机测试进程内加载，不导出到电脑或日志。

## 18. 手机说话页与连接联动

- 原生说话页按 [34 手机端重构](docs/34-android-speak-and-connection-refactor.md) 实现；顶栏连接入口只展开状态面板，不能误触断开。扫码/代码首次连接均需电脑批准；已有身份自动重连，地址维护只改路由，不替换证书、主机/设备 ID、token 或 Outbox。
- 手机读取真实 `pause_reason`，`outcome_review_required` 与主动暂停分开；手机不提供恢复/核对电脑输入操作。前台 WSS 失败按 1/2/5/10 秒退避；当前无可见 Activity 且未录音时断开，主动录音的前台服务可维持 LAN。只查询旧收据，不补发。已支持有界 mDNS/NSD，仍无空闲后台保活。
- 发送范围包含连接代次和端口，快速恢复到同一地址/target/revision 也不能复用旧自动发送票据。草稿冻结到对应请求，收到该请求 injected 终态后才清空；受阻时解释原因并保留草稿。
- 本地模型真实加载、独立静音流预热、就绪后再允许用户录音；进程共享会话复用权重，段间替换流；收起保留已开始录音，进程会话关闭时释放。预热不打开麦克风、不触发云请求或发送，不用定时器伪装就绪。
- 34 阶段 JVM 42 项，常规仪器化 27 项；当前结果见第 19 节；付费云服务测试仍需显式 opt-in。手机 UI 验证使用隔离偏好与 debug-only 空 Activity，不更改用户后台启动权限。loopback fixture 预期恰好两次 fake 执行（一次 injected、一次 uncertain），与旧的一次执行基线分开；不能当作真实 Wi-Fi/目标应用输入证明。


## 19. 完整说话页、快捷卡与桌面联动

- 当前实施见 [37 手机统一说话界面与桌面联动](docs/37-android-unified-speak-and-desktop-coordination.md)，替代 29/31/34 中页面独占录音、无快捷入口及旧智能发送的阶段设计。
- 完整页和 QuickActivity 使用 Application 唯一 SpeakSession/VoiceLinkViewModel/CloudSettings。页面、入口、语言与形态切换不得创建第二次录音、第二份草稿或重复提交；AppCompat floating decor 安装完成后设置快捷卡尺寸，避免窗口布局覆盖。
- 说完自动发送默认关闭。只有显式结束、ASR final、可见说话页、有效完整 ReceptionScope 和合法非组合文字才授权三秒倒计时；编辑/更多/连接/离页/收起/入口/形态/范围变化撤销。后台结束不因重新打开而恢复授权；不按语种、长度或旧编辑记录猜测。
- microphone 前台服务获系统接受后才启动录音，START_NOT_STICKY；收起可继续已开始录音，取消准备不能延迟开麦。磁贴只打开快捷卡；悬浮球默认关闭、按需授权，specialUse 空闲服务不开麦、不发送。不擅自设置后台/悬浮窗权限。
- 草稿与请求身份用独立 Keystore AES-GCM/AtomicFile，150ms 合并落盘；恢复完成前禁止录音/发送，坏文件不静默覆盖。不能把草稿合并写入描述为同步持久化。Room schema 2 仅追加加密 transcript，终态清 payload 不清展示正文，保留当前用户配对与账本；旧终态原文缺失就如实显示。
- saved/accepted/held/executing/review/uncertain 冻结当前请求，不新 ID 重发；回执查询允许终态 payload=null。uncertain 必须电脑本地核对后才能显式开始新草稿，手机不解除接收闸门。
- 双端 injected 统一「已提交输入」，不证明目标框实际显示文字。电脑核对授权绑定请求 ID + state_rev，状态变化清除旧勾选，过期确认不能恢复接收；协调线程再校验原版本。
- 37 阶段为 42 项 JVM、37 项常规设备测试和 108 项 Rust 工作区测试；40 阶段为 45 项 JVM、37 项常规设备测试、3 组独立发现测试及 111 项 Rust 测试，实际边界以相应实施记录为准。常规 fixture 必须恰好两次 fake 输入尝试（injected、uncertain），查询/重连/进程恢复不得重放；付费云测试仍显式 opt-in。真实麦克风、Wi-Fi 落字和长期功耗不能用 fake UI 或 loopback 证明。


## 20. 已配对电脑地址发现

- 实施与边界见 [40 自动发现与重连](docs/40-paired-host-discovery-and-reconnect.md)。Windows 原生 DNS-SD 在当前有效绑定接口发布 `_voicelink._tcp`，请求/实例接口索引非零；SRV 指向系统实际 mDNS 主机名，TXT ipv4 仅为当前绑定地址；不能用自定义 TLS 名替代实际可解析的发现 DNS 名。监听停止、范围变化或超时取消发布，不修改防火墙/Windows 分类。
- Android 保存端点优先；失败后浏览 2 秒、最多 8 个实例/4 个去重 IPv4 候选、单解析 2 秒、发现总预算 12 秒、候选认证 6 秒，统一回合间 1/2/5/10 秒退避。仅当前 LAN IPv4 前缀、与 TXT ipv4 一致的解析地址进入候选；名称、host ID、版本、IP/端口均不建立信任。
- 必须使用原完整固定 DER、tls_name、host/device UUID/token；TLS Open 之前不发 token，完整 welcome 验证与认证成功后才加密保存新路由。旧请求先 query；不自动 submit/release、重配对、换证书或解除暂停/存储阻断/uncertain，不复用旧连接自动发送票据。
- target37 检查 ACCESS_LOCAL_NETWORK；无许可不连接/发现，有连接需求期间监测撤销；退出需求、切网及取消关闭任务并使 generation 失效。API34+ 注销 ServiceInfoCallback；不支持 stopServiceResolution 的旧 Android 仅允许一个在途 OS 解析，等待其回调期间不叠加，迟到结果禁止使用。
- `verify_android_discovery.ps1` 独立身份/数据/随机端口，LAN 模式没有 ADB reverse，输入为 fake；不能据此声称真实目标应用输入/换真实 IPv4/切换多网卡/Android37 授权界面或长期功耗均已验证。常规通信套件排除独立发现类及付费云测试。


## 21. 双 WSS 图片输入

- 用户选定双 WSS：`/v2` 控制、按需 `/v2/images` 顺序二进制块；复用同一完整固定证书、TLS 名称、端口、精确网卡与网络范围。图片连接只绑定当前已认证控制会话，不能建立配对或信任。
- 图片资源先有界校验、DPAPI 加密同步落盘，再以 WAL/FULL 接受事务绑定 schema 6 的收据、资源与历史。每设备一张、主机两张、完整解码并发一；10MiB 编码、1600 万像素、单边 8192、块正文 48KiB。
- 图片意图受当前连接、范围、暂停、撤销约束；重连先 query，不自动上传、submit 或 release。旧图片 held 不支持 release，不能复用旧光标意图。原有文字与 Enter 保持原语义。
- 生产 NativeInput 在最终目标捕获前完成资源读取和解码；持久 executing 后发布 PNG/DIBV5/历史与云排除标记，只发一次 Ctrl+V、不追加 Enter。部分提交进入 uncertain，只补释放本次仍按下的键。提交成功不证明目标应用已显示图片。
- Android Room schema 3 使用 noBackupFilesDir/Keystore 图片文件，冻结载荷与独立图片配额；完整说话页/快捷卡共用明确选图、预览、发送和回执面板，不参与语音自动倒计时。电脑历史后台加载有界缩略图，提供 Yue 原图对话框、复制和原生另存为；历史操作不额外粘贴。
- 阶段和实际验收见 [45 图片实施](docs/45-image-input-implementation.md)。15 次 LAN 临时 RichEdit 粘贴、受控慢速 25 张、取消/暂停、51 项 JVM、44 项常规设备、3 组发现和 137 项桌面回归通过；首版有限验收已完成，用户确认浏览器/Word、聊天草稿、透明 PNG、历史操作和渲染正常；最低系统改为 Android 10/API 29，API 29/33 各 44 项、API 37 系统权限及图片数据补验通过，见 [49](docs/49-android-platform-support-and-validation.md)。精确应用版本、真正差 Wi-Fi/长期负载、真实多网卡/LAN 换 IPv4 及 VRAM 仍有证据边界，见 [46](docs/46-image-input-production-validation.md)。30 张历史/60 次原图开关发现并修复显式 atlas 释放，见 [48](docs/48-desktop-image-render-resource-lifecycle.md)，不能只用工作集或 CPU 引用推断渲染资源已回收。不把有限 fake/合成负载、旧网络测速或构建通过冒充整链路验收。图片及发现设备测试使用独立 `seeyuer.sayin.imagetest`，文字和图片使用不同临时电脑身份，绝不替换日常应用。

## 22. Android 本地中译英

- 实施、三模型实测及主线整合记录见 [50 本地翻译](docs/50-android-local-translation-implementation.md)。默认仅 Hy-MT2-1.8B Q4_K_M；制品长度、摘要、模板与 llama.cpp revision 由 `android/translation-artifacts.json` 固定。执行 `python scripts/prepare_translation.py --source-archive <固定来源ZIP>` 准备被忽略的源码；构建会重新核验完整源码树，不随 worktree 自动复制。
- 产品使用 Kotlin + NDK/CMake/C++ 最小 JNI，minSdk 29、字节码 17，后台串行所有者负责推理和释放。新请求不复用上一条上下文；取消置标志并等待安全停止后释放，不能在主线程等待析构。CPU 基线之外的加速需另有实测依据。
- 翻译是统一草稿的后处理；三输入来源保持不变。任务绑定草稿 ID/修订，原文与完整译文分别保存；编辑、取消、清空、新录音使旧任务失效。翻译撤销自动发送票据，partial/取消/截断/错误不可当完整译文发送；手动发送仍走原 Outbox、接收范围与收据恢复，不静默回退云端或记录正文/提示词。
- APK 不内嵌 GGUF。公开模型只在用户明确操作后下载；真实进度、完整长度/SHA-256、私有 pending 和同目录原子替换必需，失败保留原稿和旧模型。Debug 提供同摘要本地导入；正式模型分发的完整 HTTPS 验收由用户接手，不得记为已通过。
- Debug 真 JNI 使用隔离 `imageTest`；构建统一选择项为 `sayin.instrumentationBuildType`。普通 Debug/JVM 构建与 imageTest 仪器化构建分别执行。Release 页面验证使用显式 `sayinVerification=true` 的独立包及外部 Kotlin 驱动，不扩大产品 R8 keep，不替换日常包；设备脚本共用命名互斥、真实测试串行执行。K40 证据不代表 K90 Pro Max 或真实麦克风→翻译→Wi-Fi 落字验收。


## 23. Android 三区语音栏

- 当前界面与回归见 [51 三区语音栏](docs/51-android-three-zone-voice-bar.md)。完整页/快捷卡共用草稿卡、顶部更多、卡内撤销/重做及翻译/复制/清空、独立键盘/取消钮、固定三区语音栏。语音引擎选择移入识别设置，不再在主页面展示三来源分段；图片入口仍在更多。极小横屏 IME 下收起工具和提示并使用 48dp 控制区，不能挤没完整编辑行。
- 波形来自实际 PCM RMS；准备/预热/就绪来自真实引擎，收尾只有超过 450ms 才显示等待指示，无人为最短识别时间。界面内部图标/波形可动画，外部正常语音栏固定 56dp；尊重系统动效尺度。
- 明确「结束并发送」只授权本次录音的 final，绑定原完整 ReceptionScope 与可见性；切网、离页、取消、更多/连接面板使意图失效。它不修改自动发送偏好；可选自动发送仍为原三秒可撤销票据。不得在 final 前发送 partial 或在恢复连接后复活意图。
- 文本撤销最多 60 步，连续键入 900ms 合并、IME composition 与整段口述各为一个事务，翻译完成和清空单独成步；取消恢复录音前文本/选区/完整译文/时长且不入历史。识别/翻译中、冻结请求及待核对时禁止撤销，injected 后清空旧撤销历史，不能用撤销重放旧请求。

## 24. 手机连接页展示

- 最新实现与验证见 [65 连接页重构](docs/65-android-connection-page-refactor.md)。连接页页头为非交互徽标；首次扫码／短码仍需电脑批准。手机连接页与说话页连接面板共用状态卡和维护表单。
- 真实连接／有界发现期间不提供第二个重试入口；退避可轻量重试。授权恢复保留身份与草稿，不重新配对。暂停、待核对、存储阻断和旧请求查询语义不变。更多只维护路由或明确忘记，不接受发现中的新证书。

## 25. 当前主线组合与说话页补充

- 分支和工作区归并依据见 [66 主线整合](docs/66-main-branch-consolidation.md)。旧分支/worktree 为审查证据而保留，不代表其中未提交快照比当前 main 新；继续开发前先核对共同基线，不能直接覆盖回主线。
- [54 信息微调](docs/54-android-speak-page-information-polish.md) 包含草稿字数、翻译按钮和认证后的前台应用名称/图标。前台数据仅内存展示，不是目标锁定或输入授权；暂停、非活动、断连及过期清除整个快照，原输入时的即时目标校验不变。
- [58 排序动画](docs/58-android-phrase-reorder-animation.md) 记录六份必要 Reorderable 源码及本地修正；修改后同步 `android/reorderable-sources.json` 摘要与许可说明。排序预览与持久化分开，取消不落盘，失败恢复，外部 revision 改变使旧拖动失效。

## 26. Android 历史记录卡片

- 设计、实现和验证见 [67 记录页](docs/67-android-history-card-refactor.md)。手机历史目标只能使用原请求执行时的 status.application 快照；实时 foreground 不补历史。名称最多 96 码点、PNG 48×48/12KiB，禁止把路径、窗口标题或进程身份发给手机。
- Room schema 4 加密保存绑定 host/device/request 的快照；historyHidden 只影响记录页。内部状态和 pending 查询继续看见隐藏行，不能借删除取消请求、解除 uncertain、释放 held 或重放。
- 图片历史按需解密采样；64dp Fit 缩略图，详情有界 1600px。复制通过不可导出、只读、系统 URI grant 的提供者与有界解密管道，不产生明文缓存、不触发电脑输入。隐藏记录不再允许新读取。
