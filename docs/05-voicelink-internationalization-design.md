# VoiceLink：国际化设计与首批实现

日期：2026-11-12。首批语言已由用户确认：**简体中文 + English**。

## 1. 决策与边界

国际化从资源、状态模型、持久化和协议边界共同设计，不在完成页面后才把文案挪出代码。Android使用原生资源与官方应用语言API；所有首批翻译随APK发布，语言切换不需要网络或运行时翻译服务。

| 项目 | 决定 |
| --- | --- |
| 语言选项 | 跟随系统、简体中文、English |
| 稳定语言标签 | 跟随系统为空列表；简体中文`zh-Hans`；英文`en` |
| 默认资源 | `res/values/strings.xml`为完整英文，作为最终兜底 |
| 简体资源 | `res/values-b+zh+Hans/strings.xml`；按文字系统区分，不把中文等同于中国地区 |
| 发布语言声明 | 手动`res/xml/locale_config.xml`，只声明`en`、`zh-Hans` |
| Android语言管理 | AppCompat1.8.0官方API；API33+与系统应用语言同步，API26–32由AndroidX管理 |
| 语言设置的唯一所有者 | Android平台/AppCompat；DataStore不另存相同的UI语言设置 |
| 界面技术 | Miuix继续负责Compose组件；AppCompat仅承担Activity和语言基础能力 |
| 其他平台 | 手机和电脑分别保存界面语言，遵循相同术语与协议代码语义 |

`values/`中的英文不表示中国用户首次打开会被强制切到英文。首次安装为空应用语言列表，Android按系统语言列表匹配简体中文或英文，最终才使用默认资源。繁体中文未作为已翻译语言发布，不把`zh-Hant`和`zh-Hans`当成同一个标签；不使用“所有zh都强制简体”的自定义资源匹配代码。

语言自称“简体中文”“English”始终保留原文，标记`translatable="false"`，便于误选语言后找到入口；“跟随系统”等说明随当前界面语言翻译。界面以文字列出语言，不使用国旗代替语言。

## 2. 三种语言概念必须分开

| 概念 | 影响范围 | 不得隐式影响 |
| --- | --- | --- |
| 界面语言 | 按钮、状态、设置、提示、通知、时间数字显示 | 用户原文、协议字段、输入目标 |
| ASR识别语言/模型 | 后续语音识别引擎支持的语言与模型选择 | 应用界面语言、已经识别并确认的文字 |
| 用户内容语言 | 草稿、常用语、历史和待发送文字本身 | 不由应用自动翻译、改大小写或转换繁简 |

英文界面可以输入中文，中文界面也可以输入英文、代码或混合语言。应用不强制切换系统输入法语言；部分输入法可能根据应用Locale调整自身行为，应在真机验证中区分输入法行为与应用对文本的修改。

UI语言切换不是文本整理操作，也不是ASR模型下载或重新识别命令。

## 3. Android资源与文案规则

### 3.1 资源键与所有权

页面、对话框、错误提示、通知、磁贴、权限说明以及可操作图标的无障碍文案都使用资源。Compose在展示时调用`stringResource`/`pluralStringResource`，避免在ViewModel中提前缓存当前语言的成品文案。

资源键描述稳定语义，例如`connection_offline`、`send_to_computer`；不以中文内容、英文整句、页面显示顺序或语言后缀命名。开发日志和协议字段不需要伪装成可翻译UI资源。

品牌名可以本地化：当前中文显示“声达”、英文显示“VoiceLink”；包名`seeyuer.voice.link`、设备标识、服务发现类型和协议版本保持不变。现有中文名称属于展示名，不影响用户已确认的包名。

### 3.2 完整句子、参数和复数

- 完整句子作为资源，不拼接“已发送”+数字+“条”这类片段；不同语言的语序不同。
- 动态参数采用编号和类型，例如`%1$s`、`%2$d`；给译者说明参数含义，不把用户文字直接当格式串执行。
- 数量语法使用`plurals`；英文通常需要`one`/`other`，中文通常只需`other`，不能要求不同语言复数类别完全一致。
- 单位、倒计时和相对时间也属于文案：避免硬编码`ms`、`s`、英文缩写或中文单位到字符串拼接中。
- 对不可翻译的协议名、技术缩写和语言自称显式标记`translatable="false"`；不为逃避漏翻检查滥用该标记。

例如“队列中有多少项”应保存整数，展示时按数量选择完整资源；不能把“1 item”和“2 items”事先写入数据库。

### 3.3 无障碍与布局

描述性图标若旁边已有同义文字，保持装饰性`contentDescription=null`，避免重复朗读；独立操作按钮必须提供本地化说明。语言选择项整体具备单选语义及至少48dp触控区域。

布局采用start/end、可换行、弹性宽高和sp字号；不用固定宽度假设“连接”与“Connect”长度相同。状态、失败原因和安全提示不能仅靠省略号隐藏。不要将可翻译文字嵌入图片。

保留`supportsRtl=true`，区分界面方向与用户文本方向。向状态提示插入设备名等混合方向内容时，可在**展示副本**上使用`BidiFormatter`；不把其方向控制字符写回Outbox或发送给电脑。方向性箭头按导航语义镜像，麦克风、键盘、发送纸飞机等图标不无条件整体翻转。

## 4. 应用语言切换与生命周期

### 4.1 实现路径

`MainActivity`采用`AppCompatActivity`，Compose和Miuix内容保持原有方式。主题使用AppCompat的无ActionBar宿主主题；这不引入第二套页面组件。

语言选择统一调用`AppCompatDelegate.setApplicationLocales`，在主线程执行：

```kotlin
// 简体中文
AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-Hans"))
// English
AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
// 恢复跟随系统：清空应用覆盖，而不是保存当前系统语言的副本
AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
```

通过`getApplicationLocales()`判断**用户设置的覆盖值**。不能仅用`Locale.getDefault()`或当前显示语言区分“显式英文”和“跟随英文系统”。系统设置改变应用语言后，界面重新读取相同来源，不用另一份DataStore值覆盖系统选择。

API26–32配置`AppLocalesMetadataHolderService`的`autoStoreLocales=true`，让AndroidX负责保存；API33+由平台保存并同步系统设置。默认Activity重建机制负责应用新Locale，不通过`Resources.updateConfiguration`、`Locale.setDefault`或手动拦截`configChanges=locale|layoutDirection`来绕过生命周期。

AndroidX自动保存会在较早系统上产生少量同步磁盘访问，官方文档已明确说明。当前选用单一官方路径，后续实测冷启动与StrictMode；不在没有证据时增加第二套异步语言存储或在识别/发送热路径读写语言偏好。

### 4.2 重建时的数据安全

| 状态 | 语言切换要求 |
| --- | --- |
| 未发送草稿 | 不清空、不翻译、不因重组自动发送；当前使用`TextFieldValue`和可保存状态 |
| IME组合输入 | 不强行跨Activity重建恢复输入法内部会话；保留可保存的草稿，切换前清除焦点，实测中英文组合输入 |
| 已发送Outbox项 | ID、内容、顺序和状态保持原样，不因重建创建新ID或重试 |
| WSS/会话 | 所有权归应用核心，不随页面或Activity重建销毁；后续核心实现必须遵循 |
| 自动发送倒计时 | 重建/切换中停止草稿的自动触发；不以语言变化触发一次新提交 |
| 后续录音/ASR | 应用内切换在录音结束后应用，或经用户明确结束录音；外部系统强制重建时安全停止/释放资源，不静默重启录音或发送未确认片段 |

当前只实现编辑页及语言设置，尚无Outbox、WSS、自动发送和录音，不能把上表后半部分描述成已经验证的行为。语言切换后的光标/草稿、系统设置同步、重启持久化及API26–32行为仍需运行验收。

### 4.3 Activity以外的文案

API32及以下，普通Application Context不保证具有AppCompat覆盖的语言配置。以后生成通知、磁贴或服务消息时，使用Core的`ContextCompat.getContextForLanguage(context)`取得语言感知Context，再读取资源；不要在Application启动时缓存一份`Resources`或成品字符串。

Locale变化后刷新仍在显示的状态通知和应用拥有的渠道名称/说明；渠道ID保持稳定，不能为了翻译生成新渠道、重置用户通知设置。该工作随通知模块实现，不在当前没有通知服务时添加空服务。

## 5. 协议、状态与数据库边界

协议v2现有`st`、`code`、`reason`即为正确边界：例如`held`、`AUTH_REVOKED`、`focus_changed`均是机器代码。**协议不发送“已暂停”“授权已撤销”等翻译后的状态作为事实来源**，也不依赖双方界面语言相同。

建议后续数据流为：

```text
v2代码/类型化参数 → 协议与状态模型 → 展示层本地资源 → 当前语言的界面文字
用户原始文字    → 草稿/Outbox/发送载荷          → 保持原样
```

Room存储状态码、原因码和必要的类型化参数，不存储用来替代状态的成品翻译。历史的**状态标签**随语言变化，历史的**用户原文**不变。用户自定义的设备名、窗口标题和常用语也不自动翻译。

未知错误码显示本地化通用提示，保留脱敏代码供诊断；不直接把服务端任意`message`或异常的`localizedMessage`展示给用户。安全提示必须保持原语义：例如`injected`只能表述事件已提交，不能翻译成“目标应用已收到/文字已显示”。`uncertain`不能翻成普通可重试失败。

语言切换不需要新增协议字段。连接恢复、可靠性与状态机继续依据[协议v2](01-voicelink-protocol-v2.md)，禁止按本地化显示文本判断分支或数据库状态。

## 6. 数字、时间、排序与Unicode

| 数据 | 存储/协议 | 展示 |
| --- | --- | --- |
| 时间点 | 按协议约定的数值或结构化时间；不存本地化日期字符串 | `java.time`结合当前界面Locale与用户时区格式化 |
| TTL/计时/延迟 | 按协议的数值单位；耗时使用单调时钟 | 本地化单位和数字；不能因时区/语言变化重置TTL |
| 数量/大小 | 整数/数值；JSON由序列化库输出标准数字 | `NumberFormat`和数量资源，支持不同小数/分组习惯 |
| 协议ID/版本/主机地址 | 固定机器格式 | 不按Locale改变大小写、数字或分隔符 |
| 历史顺序 | 按持久顺序/时间等业务字段 | 不以翻译后的日期或状态字符串排序 |

展示Locale优先来自当前资源配置，时区使用用户当前时区；语言选择不等于地区或时区选择，不因选择英文就把时区强制设为美国。

协议的UTF-8字节数、Unicode标量数、用户感知字符数是三种度量。不要用Kotlin `String.length`冒充Unicode标量数，也不要按UTF-16索引粗暴截断emoji、代理对、组合字符和ZWJ序列。协议限额按v2执行，若UI另显示“字数”，必须明确其度量。不得因为国际化而自行进行NFC/NFKC、去音标、繁简转换或大小写变换；文本整理是独立显式功能。

协议/文件名的机器处理需要大小写折叠时采用明确的Locale无关规则，不能使用用户Locale改变标识符；尤其避免土耳其语I/i问题。用户名或标题的语言排序属于展示层，不能反向改变协议顺序。

## 7. 电脑端与翻译协作

Android使用XML资源，Rust电脑端界面已确定采用用户指定的Yue-GPUI-Component（基于GPUI Kit/Base）。桌面本地化资源加载方式在桌面模块开始实现时确定库和版本；不把Android的资源ID暴露到协议，也不现在添加没有消费者的跨平台翻译运行库。

两个客户端遵守同一术语含义。桌面端可以是英文、手机是中文，显示各自语言即可。开始桌面文案时建立显式术语/消息映射，避免仅靠下划线与点号替换生成存在碰撞的键。

首批术语约束：

| 概念 | 简体中文 | English |
| --- | --- | --- |
| 应用内语言 | 界面语言 | App language |
| 无覆盖语言设置 | 跟随系统 | Follow system |
| 普通文字操作 | 发送到电脑 | Send to computer |
| accepted | 已接收并保存 | Received and saved |
| held | 等待处理 | On hold |
| injected | 已提交输入事件 | Input events submitted |
| uncertain | 输入结果待核对 | Input outcome needs review |

产品文案可以润色，但不能扩大协议保证。新增翻译提供上下文、参数说明及必要截图；安全和恢复提示需要双语语义复核，不只验证语法。

## 8. 质量门禁与验收

已加入：

1. `scripts/check_i18n.py`：检查默认/简体资源键完整、类型和编号参数一致、语言选择项与LocaleConfig一致，并补充常见Compose硬编码文案检查。脚本不是Kotlin解析器，不能代替代码审查。
2. Android Lint将`MissingTranslation`、`ExtraTranslation`、`StringFormatInvalid`和`StringFormatMatches`设为Fatal；不为漏翻加baseline或忽略项。
3. Debug开启`en-XA`/`ar-XB`伪语言生成，用于长文本及RTL压力验证。发布语言列表仍只有两种，Release不生成伪翻译；真实资源打包情况应核查APK。

从仓库根目录执行：

```powershell
python scripts/check_i18n.py
.\scripts\gradle.ps1 :app:assembleDebug :app:lintDebug :app:assembleRelease
```

设备验收矩阵：

| 场景 | 验收重点 |
| --- | --- |
| API26–32，首次运行与重启 | 跟随系统、AndroidX保存选择、无AppCompat主题异常 |
| API33+，应用内和系统设置分别切换 | 同一设置来源，返回应用后正确语言 |
| 显式英文与跟随英文系统 | 二者可区分；系统改中文后仅跟随模式变化 |
| 未支持系统语言、语言优先列表、简体地区变体 | Android资源匹配及完整英文最终兜底 |
| 编辑混合中英/代码/emoji时切换或旋转 | 文本、选择范围及编辑状态安全，不触发发送 |
| Debug伪语言、200%字号、窄屏、横屏、TalkBack | 不遮挡操作，不丢失关键信息，单选语义和朗读正确 |
| 接入Outbox/WSS之后切换 | ID/状态/连接所有权及待处理记录不受Activity重建影响 |
| 后续接入ASR/通知 | 录音重建安全、语言感知Context及通知刷新 |

## 9. 本次实施与验证边界

当前已落地双语资源、AppCompat宿主、官方语言存储与系统声明、Miuix语言选择控件、翻译门禁及Debug伪语言配置。尚未实现的业务模块遵循本设计，不虚构其国际化运行结果。

国际化实现时设备拒绝普通USB安装，未在该阶段修改设备限制。后续已按用户要求通过手机现有Root授权安装并启动应用，见[06设备安装记录](06-android-device-installation.md)；详细语言切换、重建恢复和语言持久化验收仍待进行。构建与资源检查的实际结果记录在[国际化核查记录](research/05-i18n-verification-2026-11-12.json)。

本次Debug、Release（含R8和资源压缩）及Lint已通过。Lint为0错误、2个已说明警告：AGP有更新版本，以及`localeConfig`仅在API33+使用；API26–32使用本方案的AppCompat路径，没有屏蔽规则。

已用AAPT2检查实际APK：Debug包含`en-rXA`/`ar-rXB`伪语言，Release不含伪语言；打包的LocaleConfig只声明`en`与`zh-Hans`。翻译检查脚本通过，并以临时夹具验证了漏键、错误参数类型会被拒绝，以及中英复数类别不同可正常通过。以上不代替设备语言切换和布局验收。

## 10. 官方依据

- [Android应用语言与Compose/AppCompat接入](https://developer.android.com/guide/topics/resources/app-languages)：Activity类型、系统同步、自动保存、主线程I/O及重建行为。
- [Android本地化](https://developer.android.com/guide/topics/resources/localization)、[语言与RTL](https://developer.android.com/training/basics/supporting-devices/languages)、[字符串和复数](https://developer.android.com/guide/topics/resources/string-resource)。
- [AGP9.3的localeFilters](https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/dsl/ApplicationAndroidResources)：使用当前DSL过滤库语言资源。
- [AppCompat官方Maven元数据](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/maven-metadata.xml)：查阅日最新稳定版1.8.0，POM已核对。
- [Core的语言感知Context API](https://developer.android.com/reference/androidx/core/content/ContextCompat#getContextForLanguage(android.content.Context))；本次也核对了Core1.19.1发布源码中的对应方法。
