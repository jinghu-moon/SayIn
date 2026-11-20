# 79 · Android AI 润色 miuix 改版实施任务

状态：待实施。范围：Android 原生界面、对应状态和 V2 提示词行为。沿用 75 号任务已交付的直连、多服务、模型目录与用量账本。

## 1. 实施依据

| 依据 | 用途 |
| --- | --- |
| [交互 Demo](../refer/sayin-ai-polish-settings-redesign.html) | 页面层级、控件职责、价格和统计的信息排序、草稿模式与应用写法交互 |
| [K40 设置首页](../refer/sayin-k40-settings-reference.png) · [K40 发送策略](../refer/sayin-k40-send-policy-reference.png) | 原生样式基准。分组卡片、开关、当前应用、规则选择器和缩进分隔线 |
| [70 设置实施](70-android-settings-implementation.md) · [72 发送策略](72-android-send-policy-settings.md) | 现有导航、布局和设备联动 |
| [73 AI 润色方案](73-android-ai-polish-design.md) · [73a V2 模板](73a-android-ai-polish-prompt-templates-v2.md) | 请求、票据、草稿处理沿用 73。提示词、槽位、风格映射、语言标签与验收语料以 V2 为准 |
| [75 后续任务](75-android-ai-polish-follow-up-tasks.md) · [76 服务与目录](76-android-ai-polish-batch1-implementation.md) · [77 用量](77-android-ai-polish-batch2-usage.md) · [78 复审与真机](78-android-ai-polish-device-validation.md) | 已落地能力、历史验证和待补真机用例 |
| [miuix 版本](../refer/miuix-main/build-logic/src/main/kotlin/BuildConfig.kt) · [项目版本目录](../android/gradle/libs.versions.toml) | 本地参考源码与 SayIn 声明均为 0.9.4。编译以项目解析出的 Android artifact 为准 |

`refer/` 中的 Demo、截图和组件库是本机设计参考。离开本机后可用本节的源码路径和任务验收表实施。

### 现状与改版范围

| 区域 | 当前代码 | 目标 |
| --- | --- | --- |
| 设置 | `SettingsHost` 已有润色、服务、用量路由。`PolishSettingsPage` 内联应用总开关、语言标签、改写指令和热词 | 服务摘要、润色行为、文本偏好、本机用量统一为设置分组。应用写法、术语表、补充要求各有入口 |
| 服务 | `PolishEditorState`、`PolishServicesPage` 已支持多份配置、模型搜索、手填、官方目录和自定义服务模型发现 | 保留现有数据源和配置身份，分开切换和管理，增加价格摘要与详情 |
| 统计 | `PolishUsagePage` 顶部连续下拉。`PolishUsageStore` 已在 Room 按条件汇总、分页 | 周期分段、三项指标、独立费用组、筛选面板、按日明细、菜单清除 |
| 应用 | `PolishManager.appRulesEnabled` 配合 `PolishScene.forApplication` 做内置匹配 | 借发送策略布局，展示真实前台应用、生效值和可编辑覆盖规则 |
| 文本任务 | `SpeakSession.startPolish` 和 `PolishSheet` 共用旧模板、语言校验和全局自定义改写 | V2 独立润色、翻译两条链。目标语言与本次翻译风格在草稿操作处选择 |

## 2. miuix 组件对应关系

现有设置的正文宽度上限为 600dp，发送策略正文横向 20dp、纵向 12dp。继续用 `SettingsHost` 的页面背景、`SettingsTopBar`、返回和子页转场。共用 `SettingsCard`、`GroupHeader`、`startIcon`、`settingsSwitchColors` 以及可复用的应用图标绘制。它们当前为文件私有，只有跨文件使用的部分才移入同包文件并收窄可见性。前台卡片和规则行使用相同应用图标数据，并保留发送策略自身状态。

下表源码相对路径以 `refer/miuix-main/` 为根。UI 指 `miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/`，Preference 指 `miuix-preference/src/commonMain/kotlin/top/yukonga/miuix/kmp/`。

| 位置 | 首选组件与源码 | 组合方式 |
| --- | --- | --- |
| 页面标题和返回 | UI `basic/TopAppBar.kt` 的 `SmallTopAppBar`，`basic/IconButton.kt` | 继续通过 `SettingsTopBar` 渲染。统计更多和保存复用其动作槽 |
| 分组容器 | UI `basic/Card.kt` 的 `Card(insideMargin, colors)`，`basic/Divider.kt` 的 `HorizontalDivider` | 使用现有 `SettingsCard` 和灰色区标题。该组件的 `ColumnScope` 适合多行。规则分隔线参考发送策略从 start 侧 58dp 起 |
| 导航行 | Preference `preference/ArrowPreference.kt` | `title`、必要的 `summary`、`startAction` 图标、`endActions` 简短值。整行承担一个动作 |
| 信息和明细行 | UI `basic/Component.kt` 的 `BasicComponent` | 复用标题、摘要和图标槽。展开行额外提供状态语义，详情用普通 `Column` |
| 二元开关 | Preference `preference/SwitchPreference.kt` | 沿用现有主题开关配色。主设置只给应用写法入口，总开关放在应用写法页 |
| 短列表选择 | Preference `preference/WindowDropdownPreference.kt` | 规则、厂商、协议和筛选复用受控选项。字符串重载使用 `items`、`selectedIndex`、`onSelectedIndexChange`。带 Logo 或模型副标题的服务列表沿用 `DropdownEntry` 和 `DropdownItem`，默认展示当前值的规则行使用 `showValue` |
| 周期、币种 | UI `basic/TabRow.kt` 的 `TabRowWithContour` | 周期复用 `MobileHistoryPage` 的分段样式。币种使用同一组件的两项版本。窄屏放到费用组或价格页正文 |
| 模型、语言和筛选面板 | UI `overlay/OverlayBottomSheet.kt`、`basic/TextField.kt`。单选行可用 Preference `preference/RadioButtonPreference.kt` | 复用现有模型面板布局，搜索框与有界 `LazyColumn` 分开。现有 miuix 输入框可配标签、单行和键盘选项。面板只有一层，切换内容可返回上一级 |
| 菜单与确认 | Preference `menu/WindowIconDropdownMenu.kt`，UI `window/WindowDialog.kt`、`basic/Button.kt` | 用量更多参考 `MobileHistoryPage`。删除、清除和既有授权用原生确认。图标按钮与文字按钮的交互尺寸按项目的 48dp 规则核对 |
| 应用卡、价格表、指标组 | miuix `Card`、`Text`、`BasicComponent`、`HorizontalDivider` 加 Compose 布局 | 卡片容纳真实数据。价格和统计用 `Row`、`Column` 或按宽度换行的布局。仅在数据布局确有需要时组合，不引入额外组件库 |

`VoiceLinkTheme` 与 `YuePalette` 继续提供现有 neutral 色阶和品牌蓝 `#1f75db`。业务图标沿用 `VoiceLinkIcon` 与 Tabler 矢量，厂商 Logo 沿用 `ProviderLogo`，电脑应用图标取自现有连接状态。系统状态栏、手势区和边距由现有宿主处理。Demo 的双手机外壳、模拟应用、主题切换按钮和「演示来源」是评审控件。

## 3. 实施任务

### T0 · 固定基线与设计约束

- [ ] 记录待改分支、依赖解析和现有用例结果。用 `dependencyInsight` 核对两个 miuix Android artifact。对照本地源码确认控件签名。保持现有 Gradle 依赖。
- [ ] 记录当轮 K40 的设置、发送策略、AI 设置、模型选择和统计基线截图，补说话全屏和 QuickActivity。区分历史 78 号记录与当轮结果。
- [ ] 对照 V2 §2、§5–§7、§9–§12 列出替换的行为。先确认本地及云端 final、粘贴入口、选区和目标应用在当前调用链的位置。固定旧模板在 V2 §10 适用语料上的基线，记录模型、模型设置和版本。

**验收**　原有多配置切换、独立 Key、普通/SSE、目录更新、统计查询、手动发送路径均有本轮可复查基线。

### T1 · 设置首页、服务切换与配置

改动面为 `Settings.kt`、`PolishSettingsPage.kt` 和现有服务编辑状态。页面层级由 `SettingsRoute` 管理，顶栏返回和系统返回遵循同一规则。

- [ ] 「AI 服务」显示厂商 Logo、配置名、模型和适用的连接测试状态。「切换服务」只选择已保存配置，短列表沿用 `WindowDropdownPreference`，列表需要搜索时沿用模型面板样式。「管理服务」进入现有服务列表。零配置时给一个「添加 AI 服务」入口，有配置但未启用时给「选择服务」。
- [ ] 连接测试状态按 `testResultFor(config)` 的有效绑定显示。测试成功只表示匹配配置在本轮连接成功。任何相关字段改变后清除旧状态。保存和切换以持久化结果为准，写失败保留已生效配置并显示恢复入口。
- [ ] 「润色行为」放自动润色、按应用调整写法、流式预览。「文本偏好」放术语表、补充要求。「本机用量」仅显示近 30 天调用与 Token 摘要和入口。T4/T5 对应能力接通后再展示新入口。
- [ ] 服务编辑复用 `PolishEditorState`。常用区只展示名称、厂商、Key、模型。端点、协议与 API 版本按现有业务条件放到低频区。禁止保存明文 Key 到 `rememberSaveable`、导航参数、截图夹具或日志。校验失败定位到字段，返回或外部关闭保留原配置。
- [ ] 服务选择、模型选择和模型价格返回后保留本次表单草稿。沿用配置 ID、端点与协议绑定、加密持久化和测试取消机制。保留 `autoPolishBound` 的服务指纹绑定，并按 73 §7.5 给开启操作增加 `WindowDialog`，说明即使本地识别，符合条件的识别文本也会上传到所选服务。取消不打开开关，关闭直接生效。确认时重新核对服务指纹。

**验收**　两个同厂商账号可新增、分别编辑、切换、重启恢复和独立删除。切换到未授权配置不沿用自动润色。外部返回、系统返回、写失败和测试失败各验证一次。

### T2 · 模型价格与 USD／人民币

改动面为候选模型面板、价格详情、`PolishCatalog.kt`、`PolishPricing.kt` 及新增的报价数据边界。用 `officialCatalogProvider` 和模型 ID 匹配，区域端点沿用现有解析。目录 JSON、模型发现、请求费率账本仍各自通过现有入口。

- [ ] 现用服务卡增加「模型价格」行。模型候选展示输入和输出参考价，选中模型的详情从表单单独进入。模型选择本身仍是一键选中。对候选需要先看详细费率的场景，在同一面板提供独立的价格入口，并让 TalkBack 能分别聚焦两个动作。
- [ ] 详情展示每百万 Token 的基本输入、输出、缓存读、缓存写，以及目录提供的上下文分档、时段、时区和版本。未提供的费率显示未知或省略该项。分档阈值及起止时间来自目录解析结果。显示「参考价」，历史费用继续使用请求开始时冻结的目录规则。无法判断时段所需信息时保持未知。
- [ ] 中转站发现的候选和手填的未知模型显示「未提供单价」。服务名或模型名相同也必须核对适用端点，不能把官方费率当作中转站费率。有效的服务上报金额继续独立入账。
- [ ] 拟采用 Demo 的 Coinbase 公共 `GET https://api.coinbase.com/v2/exchange-rates?currency=USD`，只解析 USD 基准的 CNY 报价。需要换算且页面可见时首次进入刷新，报价在 60 秒后失效，在可见期间到期刷新。重叠请求合并，手动刷新复用同一入口。无须换算或页面不可见时停掉定时刷新。正式接入前在 K40 验证直连。若需更换来源，保留报价字段并同步来源标识与测试样本。
- [ ] 价格、候选和用量共用显示币种和报价状态。默认 USD。报价验证基准、正值、类型与有限性，网络调用有超时并运行在 IO。请求只包含币种，与厂商凭据、设备身份、模型、草稿和统计记录无关。界面显示报价来源及本机获取时间，说明其为参考换算。
- [ ] 首次获取失败时人民币换算显示不可用，保留原币价格可查。已有报价时刷新失败显示「上次汇率」及时间。计算使用十进制数和明确的舍入，已知零和未知分开。USD 按报价转 CNY，原生 CNY 保持 CNY，切换到 USD 时可按同一有效报价反算。其他币种按原币分组。账本仍保存原金额和币种，窗口内的参考换算不回写历史费用。

**验收**　覆盖普通价、上下文分档、时段、缺缓存写费率、中转站、手填 ID、原生 CNY、实时获取、刷新失败、错误报价、并发刷新和退出页面。目录更新能刷新当前参考价，旧费用的原金额和来源可复核。

### T3 · 调用统计

改动面为 `PolishUsagePage`、`PolishUsageStore` 与 `PolishManager.usageReport`。沿用 Room 查询与每页 30 条的基础设施，不从预截断列表计算总量。

- [ ] 顶部顺序为「今日／7 天／30 天」→ 调用数、Token、中位耗时 → 费用 → 调用明细。默认进入近 30 天，与上一级摘要一致。按现有本地日历边界查询，日期和分组按界面 locale 显示。窄屏、大字下指标可换行，时间和币种不会被折叠成不可读截断。
- [ ] Token 汇总标明已统计用量，全部缺失显示未知，部分缺失显示缺失计数。缓存及推理分项是已有汇总的子项，避免二次相加。无有效耗时显示占位。估算、服务上报、未知费用分行，同类费用按原币分桶，换算后仍保留来源和未知数量。
- [ ] 明细标题右侧筛选图标展示生效筛选数，面板内按用途、服务和模型编辑临时条件，应用后一起刷新，取消不改变生效条件。模型依赖服务和期间，失效选项清掉。列表稳定键使用 `callId`。服务按 `configId` 查询，补齐已删除配置的历史筛选入口。先复用现有字段，当前表没有历史配置显示名时，用「已删除的服务」加可辨别标识。只有需要准确保留历史显示名时才单独设计快照字段。
- [ ] 每日一组，默认行包含用途、状态、Token、费用和时间。展开时显示请求/响应模型、输入/输出、缓存、推理、耗时和已有价格来源。分页、筛选变化与空页需一致，费用单位变化保留当前筛选、可见位置与展开项。
- [ ] 清除放在顶栏更多，使用 `WindowDialog`。完成后更新页内数据与上一级摘要。复用清除后迟到请求不能复活记录的现有约束。加载中、空账本、筛选无结果和查询失败分别显示。

**验收**　复用 501 条数据回归覆盖全量汇总和分页。补同名服务、删除后的历史、无用量和未知费率、费用切换、日期边界、取消清除及清除与请求结束竞争。

### T4 · 应用写法与文本偏好

依赖 T1 的导航和 T5 的配置模型。主要改动位于 `SettingsHost`、应用写法子页及 `PolishManager`。业务映射由 V2 §5.7 和同一解析函数提供给页面与请求。应用写法和发送策略采用各自独立的配置键。

- [ ] 应用写法子页依序放「电脑当前前台」卡、总开关、「应用规则」组。当前卡显示真实应用名、图标和当前生效的润色写法。断连或无前台信息时使用现有连接状态，已保存的规则仍可管理。
- [ ] 规则项来源为当前前台、电脑提供的 `historyApplications` 和已保存覆盖规则。按去两侧空白、`Locale.ROOT` 大小写归一的显示名去重，同名共享规则。列表用原生选择器显示自动匹配、通用、聊天、邮件、代码／终端、文档、搜索。自动匹配删除对应覆盖，通用是显式覆盖且跳过应用调整。总开关关闭时保留规则，当前卡显示通用。
- [ ] 规则末尾「添加应用」先复用已知应用，允许补录显示名并检测重复。手动规则按现有项目的持久化机制落盘，失败保留先前值。展开查看内置匹配，在对应应用行配置覆盖。说明只保留必要的同名规则提示，所有应用来源为电脑连接状态或用户明确输入。
- [ ] 「术语表」子页使用 miuix 多行 `TextField`，显示格式 `源词=写法` 与条数，限制 20 行、每行 60 码点。按照 V2 §2.4 和 U06 校验与转义，按有效行计算数量和保存内容。ASR 热词继续由现有 ASR 设置管理。
- [ ] 「补充要求」子页使用 miuix `TextField`，单行最多 200 码点，拒绝换行、控制字符和超长输入。保存时显式提交，清空即移除。两页复用 `SettingsTopBar` 保存和现有的未保存返回确认，输入草稿及校验反馈在子页内管理。

**验收**　用真实电脑前台及历史应用核对图标。验证内置匹配、手动覆盖、恢复自动、同名去重、重启保持和关闭总开关。不连电脑时能管理既有规则。术语和补充要求只在保存成功后成为请求可用配置。

### T5 · V2 提示词、来源和请求校验

改动面为 `PolishPrompts.kt`、`PolishManager.kt`、`PolishClient.kt`、`SpeakSession.kt`、`PolishModels.kt`。先完成纯 Kotlin 和协议测试，再接 UI。与 T2/T3 的账本汇总改动可各自验证。

- [ ] 用类型化的请求输入表达来源、任务、润色改写、应用场景、目标语言和翻译风格。沿用 `voiceOrigin`、`typedSinceVoice`、录音起始和选区事实，将来源推导集中一处。纯本地或云端 ASR final 默认语音整理，键盘、IME 语音、剪贴板、混合草稿和选区默认文本校对。明确的产品粘贴入口在写草稿时携带来源，才可选 `scene.clip`。IME、恢复草稿或其他无法确认来源的编辑按普通文本处理，无需增加剪贴板监听或推断。
- [ ] 整理与校对使用 V2 基础模板。按约定添加场景、应用、改写、槽位。结构化列表、正式化、精简只由手动操作选择。固定应用补充文案来自内置映射，任意前台显示名不直接进入 system。剪贴板来源和目标场景同时出现时，保留剪贴板的保真要求。
- [ ] 翻译使用 `translate.core`，按纯语音来源添加语音行。按目标语言从代码表注入名称和规范化标签，按语言匹配示例。翻译手动风格优先，自动时有效应用映射为聊天→口语、邮件→正式、代码→技术，其余保留原文语气。任一请求最多一条风格行。术语使用翻译专用前导句，补充要求始终在最后。
- [ ] 旧全局目标语言字段和自定义改写模式随新草稿交互一起替换。当前润色配置、Key 和用量原记录继续使用。语言标签校验复用一个实现，UI 负责提示，`SpeakSession` 在调用前再次校验。严格执行 V2 §7.5：长度与字符、受限标签结构、大小写规范化、语言白名单。内置简繁分别展示，未知但合法的标签按 V2 规则回退为标签本身。
- [ ] `PolishTicket` 在创建时冻结草稿、选区、来源、服务版本、目标语言、风格、配置版本与模板版本。沿用既有响应复核和取消机制，配置、目标或草稿变化时旧结果不得覆盖新内容。请求使用 `<input_text>` 三字符转义。普通响应和 SSE 共用最终结果判据，按 V2 的顺序还原、检查长度比、保真集合、回显和翻译文字占比。SSE 预览与草稿相互独立，最终失败清掉预览、保留原文和选区。沿用一次动作最多一次 POST，失败不重试、不发送。
- [ ] 本阶段实施时按 V2 §12 同步 73 的旧翻译模板引用、标签校验、输出预算、组装和验收规则。输出预算及 guard 初始阈值按 V2 及语料校准。明确记录修订号、最终 system 哈希和断言结果，代码和日志不保存模板调用内容或用户文本。列表改写保留草稿换行，电脑端显示边界以现行发送实现验证。

**验收**　优先扩充 `PolishPromptsTest`、`PolishClientTest`、`PolishAutoTest`，覆盖 V2 U01–U08、双协议、普通/SSE、失效票据、失败保原文、显式翻译与自动润色隔离。V2 §10 的 S 类全套、模型风格人工抽检独立记录。D1–D4 与 D7 的决策按实测关闭，`voice.itn` 和翻译尾句初始关闭。

### T6 · 说话页与快捷卡

依赖 T5，改动集中在 `PolishControls.kt`、`SpeakPage.kt` 及共享 `SpeakSession`。复用已有 AI 工具按钮、`PolishSheet`、`PolishStatus` 和 QuickActivity 的共享会话。

- [ ] 打开现有 AI 入口后展示当前草稿或选区、默认处理模式和运行按钮。默认按来源，可显式选择语音整理、文本校对或三种改写。只有用户选择 AI 翻译时才进入语言选择，并在草稿操作区显示目标语言和风格。语言选择含常用语种、搜索及自定义合法标签，不增加底部导航入口。
- [ ] 完整页可把模式与短风格控件并排。快捷卡或文字较大时使用现有面板的下一行或者更多入口。目标语言列表沿用一层 `OverlayBottomSheet` 的内容切换，返回保留已确认选项。翻译风格默认自动，展示有效风格摘要，选择语言、模式或风格本身不执行请求。
- [ ] 用户显式运行后沿用现有忙碌、取消和结果确认。没有服务时复用 `onConfigurePolish`。完整页直达 `SettingsRoute.Polish`，快捷卡核对 `VoiceLinkScreens` 的展开流程是否到达同一路由，必要时在现有入口传递目的页。保留共享草稿及选区。AI 翻译与本地中译英的既有入口独立。AI 结果保留在草稿等待明确确认，进入 AI 面板继续撤销现有自动发送票据，流式 partial、最终完成及关闭面板都不新建发送票据。

**验收**　完整页和快捷卡的同一草稿只处理一次。覆盖返回、取消、改选范围、源文本变化、服务切换、录音 final 后自动润色和未确认结果不自动发送。用真机验证用户明确发送之后的完整回执链。

## 4. 验收与交付

| 类别 | 验收项 |
| --- | --- |
| 原生视觉 | Redmi K40 按设置首页及发送策略截图对比背景、圆角、图标、行距、顶栏、当前值和分隔线。覆盖浅色、深色、简中、English、跟随系统、320dp、大字体、手势/三键导航、横屏或较宽窗口。快照用测试配置，凭据保持遮蔽 |
| 输入与无障碍 | TalkBack 可区分导航、开关、选中值、展开与工具的功能。验证触控目标、start/end、长应用名、长模型名、RTL 文本、键盘弹出及返回焦点。图标有双语资源说明，状态不依赖颜色 |
| 设置与模型 | 0／1／多服务、还原目录弱网、端点切换、手填与发现、价格分档、测试标签、自动润色绑定、失败后表单保存和返回 |
| 费用与统计 | 目录变化时的在途冻结、USD/CNY 报价、首次/刷新失败、历史原币、成本来源、分页与过滤、清除竞态 |
| V2 和设备 | 真机纯本地与云端 ASR、IME 语音、IME 键盘、应用粘贴、选区、混合草稿、快捷卡。DeepSeek 普通/SSE 与实际中转站沿用 78 范围，新增模型质量证据按 73a 区分模拟、真实接口、实录和真实电脑输入 |

从仓库根目录按以下顺序执行。历史结果仅作为参照。

~~~powershell
$sayinDeviceSerial = '填入 adb devices 显示的设备序列号'
./scripts/gradle.ps1 --version
./scripts/gradle.ps1 :app:dependencyInsight --configuration debugRuntimeClasspath --dependency miuix
./scripts/gradle.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python scripts/check_i18n.py
python scripts/check_basellm_snapshot.py
git diff --check
./scripts/verify_android_transport.ps1 -DeviceSerial $sayinDeviceSerial -FullSuite
adb -s $sayinDeviceSerial install -r android/app/build/outputs/apk/debug/app-debug.apk
~~~

使用项目 JDK 25 和 Wrapper。AI 界面、配置、目录及用量定向仪器化测试先在隔离 `seeyuer.sayin.imagetest` 包执行，再运行完整通信套件。若本轮新增用例，更新脚本预期数量和隔离方式。记录套件结果、测试计数以及失败基线。完整套件的 loopback fixture 用于协议与 UI 回归，真实模型、实际 LAN 和电脑输入另录。日常包 `seeyuer.sayin` 使用覆盖安装，保留配对、草稿和历史。记录实际序列号、APK、安装结果和打开验证。

实施顺序为 T0 → T1 → T2/T3 → T5 → T4/T6。汇率和 V2 可分别用夹具验证。每批提交一组前后行为对照，包含已执行的命令、结果、真机截图和剩余验证边界。术语入口采用设置页文本框，翻译风格采用草稿操作区，这是对 V2 D5/D6 的本版交互选择。

## 5. 代码定位

- 原生路由、发送策略和设置分组见 [Settings.kt](../android/app/src/main/java/seeyuer/sayin/ui/settings/Settings.kt)。主题和图标见 [VoiceLinkTheme.kt](../android/app/src/main/java/seeyuer/sayin/designsystem/VoiceLinkTheme.kt) 与 [VoiceLinkIcon.kt](../android/app/src/main/java/seeyuer/sayin/designsystem/icon/VoiceLinkIcon.kt)。
- AI 服务、编辑器、模型和统计现有实现见 [PolishSettingsPage.kt](../android/app/src/main/java/seeyuer/sayin/ui/settings/PolishSettingsPage.kt)。分段、更多菜单与过滤样式参考 [MobileHistoryPage.kt](../android/app/src/main/java/seeyuer/sayin/ui/MobileHistoryPage.kt)。
- 管理、费率和账本见 [PolishManager.kt](../android/app/src/main/java/seeyuer/sayin/polish/PolishManager.kt)、[PolishPricing.kt](../android/app/src/main/java/seeyuer/sayin/polish/PolishPricing.kt) 与 [PolishUsageStore.kt](../android/app/src/main/java/seeyuer/sayin/polish/PolishUsageStore.kt)。
- 草稿模式和票据入口见 [PolishControls.kt](../android/app/src/main/java/seeyuer/sayin/ui/PolishControls.kt)、[SpeakSession.kt](../android/app/src/main/java/seeyuer/sayin/ui/SpeakSession.kt) 与 [PolishPrompts.kt](../android/app/src/main/java/seeyuer/sayin/polish/PolishPrompts.kt)。
