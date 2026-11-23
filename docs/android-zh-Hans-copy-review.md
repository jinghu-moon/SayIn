# Android 简体中文文案审核

说明文字补充标题之外的状态、条件或影响。设置列表以一行为目标，关键条件需要时另起一句。权限、费用、数据去向、输入上限和不可撤销的后果要明确。

## 实施范围

1. **首批 · 设置页**。以首页和二级页的中英对照为准。覆盖约 30 个配置项或说明位置，含首页、语音识别、发送策略、悬浮球、后台运行、剪贴板、存储和关于。同步检查草稿工具栏说明以及从设置和草稿页共用的发送模式说明。清单中的状态变体属于同一个配置项。
2. **第二批 · 高频流程**。复核配对、连接状态、说话页、回执以及云端错误。逐条保留可否重试、草稿或图片是否保留、需要去哪里处理、权限和可能计费等信息。
3. **第三批 · 深层字段与旧资源**。复核 AI 润色参数、模型详情和云端凭据。旧资源另查动态查找、测试、构建变体、组件及外部引用，再决定保留、复用或删除。静态扫描结果只用于排查。

逐条对照是候选清单。首批采用两张中英表和对应的行为说明。其余候选进入相应批次复核。

实施时，模型列表未读完先显示读取中，读取完成后再区分未导入与未选择。等待时长保持现有启用条件。切换到手动确认时显示如何开启，其他原因导致控件暂不可用时沿用时长说明。

## 首批实施记录（2026-11-23）

- 设置首页已接入动态摘要：语音识别为「离线识别 · {模型名}」或「云端识别 · {厂商}」，并覆盖离线读取中／未选择／未导入、云端读取中／未配置／无法读取；翻译为「中译英 · {翻译模型}」，模型未就绪时显示模型不可用；另含零规则与有规则、AI 润色已配置／未配置／无法读取。
- 二级页已更新语音识别说明、热词可用条件、自动发送与等待时长、应用规则空态、悬浮球开关／完整图标／手势／全屏、后台运行、剪贴板、存储及关于摘要。
- 中英文资源同步修改；新增的动态摘要模板使用编号占位符，规则数量使用复数资源。
- 验证：`python scripts/check_i18n.py`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 通过；日常 Debug 包覆盖安装。
- 真机已检查中文设置首页、发送策略、语音识别（本地／云端未配置）、悬浮球、后台运行，以及英文设置首页和发送策略；检查后已恢复“跟随系统”语言。
- 未覆盖：云端已配置及验证流程（无测试凭据）；应用规则空态（本机已有规则数据）；英文悬浮球页（英文首页与发送策略已检查）。
- 保留一处与文档表格不同的处理：剪贴板“采集控制”仍显示“开发中”，因为当前控件不可操作；避免让用户误以为可以在本机开关。


## 术语

| 场景 | 简体中文 | English |
|---|---|---|
| 录音来源 | 离线识别／云端识别。窄摘要可用「离线／云端」 | Offline recognition / Cloud recognition. Compact labels use Offline / Cloud |
| 默认发送模式 | 手动确认／自动发送。倒计时只用于说明发送前的等待状态 | Manual confirmation / Auto-send. Use countdown for the wait before sending |
| 语音服务 | 验证连接／验证中／验证通过 | Verify connection / Verifying / Connection verified |
| AI 服务 | 测试连接／测试中／测试通过 | Test connection / Testing / Test passed |
| 数量 | 模型、热词、按钮用「个」。规则用「条」。省略零条规则，只在空间紧张的状态徽标显示纯数字 | Use singular and plural forms. Omit the rule count when it is zero |
| 状态与动作 | 「已配置」表示已保存配置。「验证通过」或「测试通过」表示本次连接检查通过 | Configured is the saved configuration state. Verified or test passed is the latest connection check result |

## 设置首页

花括号表示运行时数据。原始列记录现在的界面输出，优化列包含实施时需绑定的新状态分支。默认发送模式使用实际设置值，规则数只统计已保存的应用规则。服务商名称和版本来自应用数据。

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| 语音识别 · 离线，已选用模型 | 离线识别 · {云端服务}已配置／未配置 | 离线识别<br>Offline recognition |
| 语音识别 · 离线，模型列表读取中 | 离线识别 · {云端服务}已配置／未配置 | 离线 · 读取模型中<br>Offline · Loading models |
| 语音识别 · 离线，已有模型待选择 | 离线识别 · {云端服务}已配置／未配置 | 离线 · 尚未选择模型<br>Offline · No model selected |
| 语音识别 · 离线，未导入模型 | 离线识别 · {云端服务}已配置／未配置 | 离线 · 尚未导入模型<br>Offline · No model imported |
| 语音识别 · 云端，读取中 | 云端识别 · {服务商}未配置 | 云端 · 读取配置中<br>Cloud · Loading settings |
| 语音识别 · 云端，已配置 | 云端识别 · {服务商}已配置 | 云端 · {服务商简称}<br>Cloud · {provider} |
| 语音识别 · 云端，未配置 | 云端识别 · {服务商}未配置 | {服务商简称}未配置<br>{provider} not configured |
| 语音识别 · 云端，读取失败 | 云端识别 · 配置读取异常 | 云端 · 配置无法读取<br>Cloud · Can't read settings |
| 发送策略 · 0 条应用规则 | 默认{方式} · 0 个应用规则 | {手动确认／自动发送}<br>{Manual confirmation / Auto-send} |
| 发送策略 · 有应用规则 | 默认{方式} · {数量} 个应用规则 | {手动确认／自动发送} · {数量} 条规则<br>{mode} · {count} rule / rules |
| 翻译 | 本地 · 中译英 | 中译英<br>Chinese to English |
| AI 润色 · 已配置 | {服务商} · {模型 ID} | {服务商}已配置<br>{provider} configured |
| AI 润色 · 未配置 | 未配置 · 点此设置 | 未配置<br>Not configured |
| AI 润色 · 配置无法读取 | 配置损坏 · 请重新配置 | 配置无法读取<br>Can't read settings |
| 悬浮球 | 尺寸、贴边、图标、颜色与双击动作 | 外观与手势<br>Appearance and gestures |
| 控制中心快捷入口 · 默认 | 添加 SayIn 快捷入口 | 添加磁贴<br>Add a tile |
| 控制中心快捷入口 · 手动添加 | 在系统控制中心编辑磁贴，添加 SayIn。 | 编辑控制中心，添加 SayIn 磁贴。<br>Edit Quick Settings and add the SayIn tile. |
| 后台运行帮助 | 自启动与电池设置 | 自启动与电池限制<br>Autostart and battery restrictions |
| 草稿工具栏 | 完整页 {数量} 个 · 快捷卡 {数量} 个 | 完整页 {数量} 个 · 快捷卡 {数量} 个<br>Full page {count} · Quick card {count} |
| 外观 | 跟随系统／浅色／深色 | 跟随系统／浅色／深色<br>System default / Light / Dark |
| 应用语言 | 跟随系统／简体中文／English | 跟随系统／简体中文／English<br>System default / 简体中文 / English |
| 使用时保持亮屏 | 仅在 SayIn 界面可见时生效 | 仅 SayIn 界面可见时生效<br>Only while SayIn is visible |
| 存储管理 | 缓存与本地数据 | 查看占用、清理缓存<br>Check storage and clear cache |
| 剪贴板增强 | 查看模块说明 | 查看模块状态<br>Check module status |
| 隐私说明 | 本机数据与云端处理 | 本机数据与云端处理<br>Local data and cloud processing |
| 关于 SayIn | 版本 {版本号} | 版本 {版本号}<br>Version {version} |

## 二级页的状态与条件

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| 语音识别 · 离线 | 本机处理，语音不会上传 | 音频留在本机。<br>Audio stays on this device. |
| 语音识别 · 云端已配置 | 录音将上传至所选云端服务 | 录音上传至所选服务。<br>Audio is sent to the selected service. |
| 语音识别 · 云端读取中 | 请先配置云端服务，再开始录音 | 读取云端配置中…<br>Loading cloud settings… |
| 语音识别 · 云端读取失败 | 配置读取异常 | 无法读取云端配置。<br>Can't read cloud settings. |
| 语音识别 · 云端未配置 | 请先配置云端服务，再开始录音 | 配置云端服务后才能录音。<br>Set up a cloud service to record. |
| 云端服务 · 读取中／保存中／删除中 | 正在读取加密配置…／正在保存…／正在删除配置… | 读取配置中…／保存中…／删除中…<br>Loading settings… / Saving… / Deleting… |
| 云端服务 · 已配置／有未保存修改／未配置 | 已配置／待保存／未配置 | 已配置／未保存／未配置<br>Configured / Unsaved / Not configured |
| 云端服务 · 验证连接说明 | 发送 1 秒静音，可能计费 | 发送 1 秒静音，不开麦，可能计费。<br>Sends 1 second of silence without using the mic. Charges may apply. |
| 云端服务 · 验证进行中 | 正在验证流式服务… | 验证中…<br>Verifying… |
| 云端服务 · 验证通过 | 流式服务已响应。请实际说话验证识别效果。 | 连接验证通过。录音可检验识别和热词效果。<br>Connection verified. Try a recording to check recognition and hotwords. |
| 云端服务 · 验证失败 | {失败原因} | {失败原因}<br>{reason} |
| 热词 · 有可用配置／未配置 | {数量} 个／未配置 | {数量} 个／配置云端后可用<br>{count} hotword / hotwords / Set up cloud recognition to use hotwords |
| 热词 · 输入限制 | 每行一个词；最多 20 个去重词条，总长度 80 字符，单词条不超过 40 字符。 | 每行一词。去重后最多 20 个，共 80 字符，每词最多 40 字符。<br>One hotword per line. Up to 20 unique hotwords, 80 characters total and 40 per hotword. |
| 自动发送开关 | 开启后按下方等待时长自动发送 | 语音识别完成后自动发送。<br>Sends automatically after speech recognition finishes. |
| 等待时长 · 可调整 | 语音识别结束后等待时长 | 识别结束后等待发送。<br>Wait time before sending a finished transcript. |
| 等待时长 · 默认自动发送关闭 | 语音识别结束后等待时长 | 开启默认自动发送后可调整。<br>Turn on auto-send by default to adjust this delay. |
| 应用规则 · 空态 | 连接电脑并发送一次后，这里会出现可配置的应用。 | 连接电脑后，切到目标应用即可添加规则。<br>Connect to your computer and switch to the app there to add a rule. |
| 应用规则 · 单项 | 跟随默认／自动发送／手动确认 | 跟随默认／自动发送／手动确认<br>Use default / Auto-send / Manual confirmation |
| 草稿工具栏 · 操作 | 拖动左侧把手调整顺序。关闭开关只会从草稿卡上隐藏按钮；完整页与快捷卡分别配置，按钮较多时自动换行。 | 拖动排序，关闭开关可隐藏按钮。完整页和快捷卡分别设置。<br>Drag to reorder and turn off to hide. Set up each view separately. |
| 悬浮球 · 开关 | 按需开启，需要悬浮窗权限并显示常驻通知，不会自行开启麦克风。 | 需悬浮窗权限。开启后显示常驻通知，不会自动开麦。<br>Requires overlay permission. Shows an ongoing notification and keeps the mic off. |
| 悬浮球 · 权限未授权 | 未能开启悬浮球。请允许悬浮窗权限后重试。 | 允许悬浮窗权限后重试。<br>Allow overlay permission and try again. |
| 悬浮球 · 贴边保持图标完整 | 仅在贴边且需要时缩小图标。 | 贴边空间不足时缩小图标。<br>Shrinks the icon to fit at the screen edge. |
| 悬浮球 · 手势 | 拖动可移动，单击展开，双击执行所选动作，长按打开快捷菜单。 | 拖动移动，单击展开，双击执行所选动作，长按打开菜单。<br>Drag to move, tap to expand, double-tap for your action, or touch and hold for the menu. |
| 悬浮球 · 全屏 | 全屏视频或游戏时自动隐藏未启用：不申请无障碍或使用情况访问权限时，Android 不会向悬浮窗提供可靠的全屏信号。 | 全屏时不会自动隐藏。系统未向悬浮窗提供可靠的全屏状态。<br>The floating button doesn't auto-hide in full screen. Android doesn't reliably tell overlays when an app is in full screen. |
| 后台运行帮助 | SayIn 只在说话页或正在录音时需要保持连接，不会常驻后台。如果手机终止录音或快捷入口，请在系统设置中允许自启动并取消电池限制。 | 界面可见或识别进行中会维护连接。录音或快捷入口被系统中断时，可检查自启动与电池限制。<br>SayIn keeps the connection active while visible or recognizing speech. If Android interrupts recording or quick access, check autostart and battery restrictions. |
| 后台运行 · 系统设置说明 | 这些设置属于系统，SayIn 不会自动修改。 | 在系统设置中调整自启动和电池限制。<br>Change autostart and battery restrictions in system settings. |
| 剪贴板 · 普通模式 | 普通模式只在你粘贴时读取当前一条剪贴板，Android 不向应用提供剪贴板历史。 | 粘贴时只读取剪贴板中的当前内容。<br>Reads only the current clipboard item when you paste. |
| 剪贴板 · Root 模块 | 可选 Root 模块由你在模块管理器中明确开启采集；敏感内容和锁屏状态有门禁，数据加密保存在本机。 | 在模块管理器开启采集。锁屏时暂停，跳过系统标记的敏感内容。历史在本机加密保存。<br>Enable capture in the module manager. Capture pauses on the lock screen and skips items marked sensitive. History is encrypted on this device. |
| 剪贴板 · 尚未检查模块 | — | — |
| 剪贴板 · 检查结果 | 采集就绪 · {时间}／采集未就绪 · {时间}／连接暂不可用 | 采集就绪 · {时间}／采集未就绪 · {时间}／模块无法连接<br>Capture ready · {time} / Capture not ready · {time} / Can't connect to module |
| 剪贴板 · 采集控制 | 开发中 · 当前请在模块管理器操作 | 在模块管理器开启或关闭采集。<br>Turn capture on or off in the module manager. |
| 存储管理 · 缓存清理 | 这里只清理应用缓存；加密草稿、历史、模型和配对数据不会删除。 | 仅清缓存。草稿、历史、模型和配对保留。<br>Clears cache only. Drafts, history, models, and pairings are kept. |
| 存储管理 · 无缓存 | 暂无缓存可清理 | 暂无可清理的缓存<br>No cache to clear |
| 关于 · 开源许可 | 第三方组件与许可证全文 | 第三方组件与许可证<br>Third-party components and licenses |
| 关于 · 诊断信息 | 设备、权限与连接状态 | 设备、权限与连接<br>Device, permissions, and connection |

## 与实际行为的对应

- **自动发送**。离线识别和云端识别共用录音收尾。有效发送策略为自动、识别定稿非空、页面可见且目标仍可用时，语音才进入倒计时。打字、粘贴和输入法语音输入都要先点发送。在自动模式下，点发送也可进入倒计时，输入法候选仍在编辑时会先等候选上屏。手动发送的说明以「仍需点发送」描述用户动作。等待时长解释计时本身，取消提示由实际倒计时界面展示。 [语音会话](../android/app/src/main/java/seeyuer/sayin/ui/SpeakSession.kt#L294) · [发送按钮](../android/app/src/main/java/seeyuer/sayin/ui/SpeakPage.kt#L699) · [共用发送模式弹层](../android/app/src/main/java/seeyuer/sayin/ui/SpeakToolSheets.kt#L21)
- **应用规则**。列表由已接收的电脑前台应用、本机可见发送历史中的应用以及已保存规则合并。电脑已连接、选中此手机接收且接收未暂停时，前台应用上报后就可配置。空态给出一条最快的操作路径。 [发送策略](../android/app/src/main/java/seeyuer/sayin/ui/settings/Settings.kt#L437) · [前台应用上报](../android/app/src/main/java/seeyuer/sayin/data/VoiceLinkRepository.kt#L550)
- **悬浮球与后台连接**。悬浮窗没有接入可靠的全屏检测。现有页面可申请悬浮窗和通知权限。连接随完整页、快捷卡的可见状态及识别任务状态维护。 [悬浮球设置](../android/app/src/main/java/seeyuer/sayin/ui/settings/Settings.kt#L1242) · [连接维护](../android/app/src/main/java/seeyuer/sayin/ui/VoiceLinkViewModel.kt#L50)
- **云端语音验证**。以已保存的配置发送 1 秒生成的静音，可能计费，麦克风保持关闭。未保存修改会禁用验证。服务响应只确认连接，真实录音用于检查准确率和热词。使用中的文案是设置行的 `settings_verify_sub` 及结果状态的 `cloud_tested`。验证失败沿用现有类型化错误文案；旧 `cloud_test_help` 中的事实保留为这两处的审核依据，并供后续字段级复核。 [验证控件](../android/app/src/main/java/seeyuer/sayin/ui/CloudSettingsCard.kt#L178) · [验证请求](../android/app/src/main/java/seeyuer/sayin/asr/CloudSettings.kt#L96)
- **AI 润色状态**。首页用「已配置」表示有可读配置。请求认证失败由服务测试结果展示。首页的 `Unauthorized` 状态在现有代码中只有显示分支，未找到设置该状态的调用。二级页按实际测试结果显示错误。 [首页摘要](../android/app/src/main/java/seeyuer/sayin/ui/settings/Settings.kt#L890) · [AI 服务测试](../android/app/src/main/java/seeyuer/sayin/ui/settings/PolishSettingsPage.kt#L289)

## 后续候选中的关键修订

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| AI 润色 · 自动润色 | 从空草稿开始的新语音片段结束后自动润色一次，结果保留在草稿中等待确认。 | 手动确认模式下，从空草稿开始录音，识别完成后自动润色一次。点发送提交结果。<br>In Manual confirmation mode, SayIn rewrites the transcript once after recognition if you started with an empty draft. Tap Send to submit the result. |
| AI 润色 · 自动润色授权 | 即使语音识别在本机运行，符合条件的新语音片段文本也会上传到所选 AI 服务进行润色。 | 符合条件的转写文字会发往所选 AI 服务，离线识别也适用。<br>Eligible transcripts go to the selected AI service, including transcripts produced offline. |
| AI 润色 · 服务测试 | {模型 ID} · 当前配置尚未测试。／正在测试…／上次测试成功 · {耗时} ms／上次测试失败 · {原因} | {模型 ID} · 未测试／测试中…／上次测试通过 · {耗时} ms／上次测试失败 · {原因} |
| AI 润色 · 模型价格 | 输入 {价格} · 输出 {价格}／未提供单价 | 每百万 Token · 输入 {价格}／输出 {价格}。缺少价格时显示「暂无参考价」。 |
| AI 润色 · 人民币汇率不可用 | 人民币汇率不可用 | 暂无人民币换算汇率 |
| 本地模型 · 当前／已导入／可下载 | 已选用／已导入／未导入 | 已选用／已导入／未导入 |
| 本地模型 · 进度 | 正在扫描模型文件夹…／正在复制并校验文件／正在保存模型 | 扫描文件夹…／正在复制并校验／正在保存模型 |

自动润色在手动确认模式的录音收尾分支触发。需从空草稿开始，本段未改成混合输入，且自动润色已绑定当前服务。识别须成功结束并取得非空定稿。用户主动选「结束并发送」且该发送路径成立时，直接提交原文。自动润色结果需显式发送。 [录音收尾](../android/app/src/main/java/seeyuer/sayin/ui/SpeakSession.kt#L294) · [自动润色条件](../android/app/src/main/java/seeyuer/sayin/ui/PolishAuto.kt) · [服务绑定](../android/app/src/main/java/seeyuer/sayin/polish/PolishManager.kt#L92)

## 实施验收

- 同次提交修改英文兜底和简体中文。优先复用现有资源。仅在状态表达需要时增加模板，保留编号占位符和英文复数形式。
- 修改前核对现有行为与测试基线。实施后运行 `python scripts/check_i18n.py` 和 `scripts/gradle.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。只有文案本身属于被测试的行为时才更新对应断言。
- Debug 覆盖安装到手机。截取中英文、常规字号和大字号的首页和受影响二级页。核对较长的服务商名称、零规则／有规则、离线模型空态、云端未配置／读取失败、权限提示和验证结果。布局应让用户读全计费提示、数据去向和错误恢复步骤。
- 云端验证的请求语义用现有测试或隔离测试验证。真机排版可用状态数据检查费用提示及结果，真实服务验证可能计费。

## strings.xml 逐条对照

共有 1,128 个资源键，含 1,122 个字符串和 6 个复数资源。首批以中英对照表为准。此处保留原文和审核候选。〔移除说明〕表示建议省去副文案。〔旧资源候选〕表示静态扫描未找到引用，引用核查归入第三批。〔按状态显示〕指向页面状态模板。〈换行〉表示建议拆为短行。

### 连接、配对与发送回执

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `connection_offline` | 尚未连接电脑 | 未连接电脑 |

### 输入与配对

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `input_title` | 开始输入 | 〔旧资源候选〕 |
| `input_description` | 使用手机输入法输入，或点击输入法的麦克风说话。 | 〔旧资源候选〕 |
| `input_placeholder` | 在这里输入文字 | 〔旧资源候选〕 |
| `send_to_computer` | 发送到电脑 | 发送到电脑 |
| `connection_required` | 连接电脑后即可发送 | 〔旧资源候选〕 |
| `language_title` | 界面语言 | 〔旧资源候选〕 |
| `language_description` | 仅更改界面语言，输入的文字保持原样。 | 〔旧资源候选〕 |
| `language_system` | 跟随系统 | 跟随系统 |
| `pair_help` | 先在电脑上打开“配对手机”，再扫描二维码或输入地址和 8 位配对码。 | 〔旧资源候选〕 |
| `scan_qr` | 扫描二维码 | 扫描二维码 |
| `scan_prompt` | 扫描电脑上SayIn显示的配对二维码 | 扫描电脑上的 SayIn 配对二维码 |
| `use_pair_code` | 无法扫码？使用配对码 | 无法扫码？使用配对码 |
| `hide_pair_code` | 收起配对码表单 | 〔旧资源候选〕 |
| `ip_address` | 电脑 IPv4 地址 | 〔旧资源候选〕 |
| `port` | 端口 | 〔旧资源候选〕 |
| `pairing_code` | 8 位配对码 | 8 位配对码 |
| `pair_computer` | 配对电脑 | 配对电脑 |
| `approve_on_computer` | 保持此页面打开，并在电脑上批准这部手机。 | 保持此页打开，到电脑上批准连接。 |
| `cancel_pairing` | 取消配对 | 取消配对 |
| `computer_address` | 电脑：%1$s | 〔旧资源候选〕 |
| `enable_receiving_on_computer` | 请先在电脑端开启接收。 | 〔旧资源候选〕 |
| `not_active_device` | 当前输入设备是另一部手机。 | 另一台手机已被选为输入设备。 |
| `receiver_paused` | 电脑输入已暂停。 | 电脑已暂停接收。 |
| `receiver_ready` | 已就绪 · 文字跟随电脑当前光标 | 〔旧资源候选〕 |
| `resume_input` | 恢复输入 | 恢复输入〔仅测试或脚本静态引用〕 |
| `pause_input` | 暂停输入 | 〔旧资源候选〕 |
| `reconnect` | 重新连接 | 重新连接 |
| `unpair_computer` | 取消配对 | 取消配对 |
| `unpair_title` | 取消配对？ | 取消配对？ |
| `unpair_message` | 取消后需要重新配对才能连接这台电脑。电脑上的设备授权不会被撤销。 | 重新连接时需再次配对。电脑端的授权仍有效。 |
| `keep_computer` | 保留电脑 | 保留电脑 |
| `network_permission_denied` | 请在系统应用设置中允许访问本地网络，然后重试。 | 在系统设置中允许访问本地网络后重试。 |
| `normalization_preview` | 实际发送文字（换行和制表符将转换为空格）： | 〔旧资源候选〕 |
| `text_limit` | 每次最多 4,000 个字符 / 16 KiB，不自动追加回车。 | 〔旧资源候选〕 |
| `history_title` | 发送状态 | 〔旧资源候选〕 |
| `history_help` | 本机加密保留最近 100 条发送记录。回执表示输入已提交，仍以目标输入框的实际内容为准。 | 〔旧资源候选〕 |

### 回执与故障

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `operation_label` | 请求 %1$s | 请求 %1$s |
| `review_warning` | 暂未取得回执。请先查询原请求，不要重复发送同一段文字。 | 先查询原请求回执，避免重复发送。 |
| `retry_same_request` | 重试此请求 | 重试此请求〔仅测试或脚本静态引用〕 |
| `discard_request` | 丢弃本地请求 | 丢弃本地请求 |
| `release_request` | 继续输入这条文字 | 继续输入这条文字 |
| `cancel_request` | 取消请求 | 取消请求 |
| `uncertain_warning` | 请在电脑核对目标中的内容，并恢复接收。此请求不会重试。 | 在电脑核对输入并恢复接收。此请求不可重试。 |
| `loading_storage` | 正在打开本地存储… | 正在读取本机数据… |
| `connecting` | 正在连接… | 正在连接… |
| `pairing` | 正在安全配对… | 正在配对… |
| `waiting_approval` | 等待电脑批准… | 等待电脑批准… |
| `connection_status_loading` | 初始化 | 初始化 |
| `connection_status_connected` | 已连接 | 已连接 |
| `connection_status_connecting` | 连接中 | 连接中 |
| `connection_status_discovering` | 查找电脑 | 查找电脑 |
| `connection_status_syncing` | 同步中 | 同步中 |
| `connection_status_pairing` | 配对中 | 配对中 |
| `connection_status_approval` | 待批准 | 待批准 |
| `connection_status_paused` | 已暂停 | 已暂停 |
| `connection_status_review` | 待核对 | 待核对 |
| `connection_status_inactive` | 未选中 | 未选中 |
| `connection_status_unavailable` | 暂不可用 | 暂不可用 |
| `connection_status_storage` | 存储异常 | 存储异常 |
| `connection_status_permission` | 需要授权 | 需要授权 |
| `connection_status_offline` | 未连接 | 未连接 |
| `connection_status_first` | 连接电脑 | 连接电脑 |
| `connection_status_description_action` | 连接状态：%1$s，点按打开连接面板 | 连接状态：%1$s。点按查看。 |
| `connected` | 已连接电脑 | 已连接电脑 |
| `error_pairing` | 配对失败，请核对地址和配对码，或在电脑上重新打开配对窗口。 | 配对失败。核对地址和配对码，或在电脑上重新打开配对。 |
| `error_connection` | 连接已关闭。请检查 Wi-Fi、电脑端及防火墙许可，然后重连。 | 连接已断开。确认电脑 SayIn 正在运行，并检查网络和防火墙。 |
| `error_storage` | 无法打开或保存受保护的数据，已停止发送。请保留应用数据以便恢复。 | 受保护数据不可用，发送已停止。保留应用数据以便恢复。 |
| `error_text` | 文字包含不支持的字符，或超出单次请求限制。 | 文字含不支持的字符，或超出发送上限。 |
| `error_quota` | 本地待处理队列已满（16 条 / 64 KiB），请先处理已有请求。 | 待处理队列已满（16 条或 64 KiB）。先处理已有请求。 |
| `error_not_ready` | 请在电脑端开启接收，再点击需要输入的文本框。 | 在电脑开启接收，再将光标放入目标输入框。 |
| `error_protocol` | 请求未能完成，请检查电脑，再重新连接查询回执。 | 请求未完成。检查电脑，重连后查询回执。 |
| `error_tls` | 此设备未提供 TLS 1.3，默认安全提供程序需要 Android 10 或更高版本。 | 此设备缺少 TLS 1.3 支持，无法安全连接。 |
| `receipt_saved` | 手机已保存，等待回执 | 等待电脑回执 |
| `receipt_accepted` | 电脑已接收 | 电脑已接收 |
| `receipt_held` | 等待输入 | 等待输入 |
| `receipt_executing` | 正在提交输入 | 正在提交输入 |
| `receipt_injected` | 已提交输入 | 已提交输入 |
| `receipt_cancelled` | 已取消 | 已取消 |
| `receipt_expired` | 已过期 | 已过期 |
| `receipt_accepted_help` | 已接收，尚未输入。按顺序等待先前请求处理；接收后 30 秒过期。 | 排队等待输入，接收后 30 秒过期。 |
| `receipt_expired_help` | 未输入，已超过有效期。旧文字不会补打；请在目标输入框放好光标后重新发送。 | 已过期，未输入。将光标放到目标位置后可重新发送。 |
| `receipt_held_help` | 尚未输入，前台检查未通过或接收已暂停。请检查电脑并处理这条记录；接收后 30 秒过期，不会自动重试。 | 输入受阻。到电脑处理，接收后 30 秒过期。 |
| `receipt_receiver_focused` | 发送时SayIn在前台，未输入。请切到目标输入框。 | 电脑前台是 SayIn。切到目标输入框。 |
| `receipt_target_missing` | 未找到可用的前台窗口，未输入。请点击目标输入框。 | 没有可用的前台窗口。点击目标输入框。 |
| `receipt_elevated_target` | 目标窗口权限高于SayIn，未输入。请使用普通权限目标窗口，或以相同权限启动SayIn。 | 目标应用权限高于 SayIn。改用普通权限的目标窗口。 |
| `receipt_modifier_held` | 检测到 Ctrl、Shift、Alt 或 Windows 键按下，未输入。请松开按键。 | 松开 Ctrl、Shift、Alt 或 Windows 键后再发送。 |
| `receipt_session_unavailable` | 当前会话或输入桌面不可用，未输入。请解锁并返回当前用户桌面。 | 解锁电脑并返回当前用户桌面。 |
| `receipt_failed` | 失败 | 失败 |
| `receipt_uncertain` | 输入结果待核对 | 输入结果待核对 |
| `receipt_review` | 暂未取得回执 | 暂未取得回执 |
| `camera_permission_denied` | 相机权限未获允许，可在系统应用设置中开启，或使用配对码。 | 允许相机权限，或改用配对码。 |
| `zxing_app_name` | SayIn扫码 | 〔旧资源候选〕 |
| `zxing_button_ok` | 关闭 | 〔旧资源候选〕 |
| `zxing_msg_camera_framework_bug` | 无法打开相机，请关闭扫码后使用配对码，或重试。 | 〔旧资源候选〕 |
| `zxing_msg_default_status` | 扫描电脑上的配对二维码。 | 〔旧资源候选〕 |

### 导航与全局状态

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `nav_speak` | 首页 | 首页 |
| `nav_history` | 记录 | 记录 |
| `nav_connection` | 连接 | 连接 |
| `nav_settings` | 设置 | 设置 |
| `mobile_connection_title` | 连接电脑 | 连接电脑 |
| `mobile_pairing` | 配对与授权 | 〔旧资源候选〕 |
| `mobile_privacy` | 文字通过局域网发送到已配对的电脑。本地识别在手机上完成；选择云端识别时，音频与热词会直接发送给对应服务商。 | 〔旧资源候选〕 |
| `mobile_local_asr` | 本地识别 | 〔旧资源候选〕 |
| `mobile_keyboard_help` | 使用现有输入法及其语音功能 | 〔旧资源候选〕 |
| `mobile_open_keyboard` | 打开输入法 | 打开输入法 |
| `mobile_draft` | 草稿 | 草稿 |
| `mobile_manual_send` | 检查文字后，点发送 | 〔旧资源候选〕 |
| `mobile_send_explanation` | 确认后发送到电脑光标处 · 不追加回车 | 〔旧资源候选〕 |
| `mobile_connect_action` | 连接电脑 | 连接电脑 |
| `mobile_cursor` | 目标：电脑当前光标位置 | 〔旧资源候选〕 |
| `mobile_history_empty` | 还没有发送记录 | 还没有发送记录 |
| `mobile_history_empty_help` | 发送文字后，在这里查看回执与处理结果 | 〔旧资源候选〕 |
| `mobile_input_settings` | 输入与发送 | 〔旧资源候选〕 |
| `mobile_default_mode` | 当前输入来源 | 〔旧资源候选〕 |
| `mobile_send_mode` | 发送方式 | 〔旧资源候选〕 |
| `mobile_keep_awake` | 保持屏幕常亮 | 〔旧资源候选〕 |
| `mobile_keep_awake_help` | 仅在SayIn位于前台时生效 | 〔旧资源候选〕 |
| `mobile_planned` | 后续计划 | 〔旧资源候选〕 |
| `mobile_asr_plan` | 离线识别为后续计划，当前未安装模型。 | 〔旧资源候选〕 |
| `mobile_quick_entry` | 快捷唤出 | 〔旧资源候选〕 |
| `mobile_quick_entry_plan` | 悬浮入口与常驻通知为后续计划。 | 〔旧资源候选〕 |
| `receipt_held_cause_help` | %1$s 接收后 30 秒过期，不会自动重试。 | %1$s 接收后 30 秒过期，不会重试。 |
| `receipt_expired_cause_help` | %1$s 此请求已过期，不会补打；如需输入，请重新发送。 | %1$s 请求已过期。核对目标后可重新发送。 |
| `mobile_reconnect_title` | 与电脑的连接已断开 | 电脑连接已断开 |
| `mobile_first_connection` | 连接电脑，开始发送 | 〔旧资源候选〕 |
| `mobile_offline_draft` | 连接前也可以编辑草稿 | 〔旧资源候选〕 |
| `mobile_connection_details` | 详情 | 〔旧资源候选〕 |
| `mobile_recovering` | 正在恢复发送状态… | 正在恢复发送状态… |
| `mobile_invalid_draft` | 最多 4,000 字，且不能包含控制字符 | 最多 4,000 字符，且不能含不支持的控制字符 |

### 说话与本地识别

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `speak_send_manual` | 手动发送 | 〔旧资源候选〕 |
| `speak_send_smart` | 智能发送 | 〔旧资源候选〕 |
| `speak_change_send_mode` | 发送方式：%1$s。点按切换。 | 〔旧资源候选〕 |
| `speak_source_local` | 本地识别 | 本地识别 |
| `speak_source_keyboard` | 输入法 | 输入法 |
| `speak_draft_placeholder` | 按住下方麦克风说话，或直接输入、粘贴内容 | 按住麦克风说话，或打字、粘贴 |
| `speak_preview_title` | 发送预览 | 〔旧资源候选〕 |
| `speak_preview` | 发送预览 | 〔旧资源候选〕 |
| `speak_edit` | 编辑 | 〔旧资源候选〕 |
| `speak_finish_input` | 完成输入 | 完成输入 |
| `speak_start_local` | 开始说话 | 〔旧资源候选〕 |
| `asr_ready` | 在本机识别中文和英文，点“开始说话”即可。 | 〔旧资源候选〕 |
| `asr_no_speech` | 未识别到语音，原有草稿已保留。 | 未识别到语音，草稿已保留。 |
| `asr_loading` | 正在准备识别，可以取消… | 正在准备识别… |
| `asr_listening` | 正在听，说完后点“结束说话”。 | 〔旧资源候选〕 |
| `asr_finishing` | 正在完成识别… | 正在完成识别… |
| `asr_cancelling` | 正在停止识别… | 正在停止识别… |
| `asr_cancel` | 取消 | 取消 |
| `asr_finish` | 结束说话 | 结束说话 |
| `asr_permission_denied` | 请允许麦克风权限以使用本地识别，也可以使用输入法。 | 允许麦克风权限，或改用输入法。 |
| `asr_open_settings` | 打开应用设置 | 打开应用设置 |
| `asr_model_error` | 识别加载失败，请重试或使用输入法。 | 识别加载失败。重试或改用输入法。 |
| `asr_microphone_error` | 麦克风不可用，请检查权限或其他正在录音的应用。 | 麦克风不可用。检查权限或关闭其他录音应用。 |
| `asr_too_slow` | 识别未能跟上录音，已停止。请检查草稿后发送。 | 识别未跟上录音，已停止。发送前核对草稿。 |
| `asr_recognition_error` | 识别意外停止，请检查草稿或重新录音。 | 识别已停止。核对草稿或重录。 |
| `speak_keep_editing` | 继续编辑 · 取消自动发送 | 〔旧资源候选〕 |
| `speak_composing` | 请先确认输入法候选词。 | 〔旧资源候选〕 |
| `speak_smart_hint` | 点“完成输入”后，符合条件的短句将自动发送。 | 〔旧资源候选〕 |
| `speak_keyboard_hint` | 可以打字，也可以使用输入法的语音输入。 | 〔旧资源候选〕 |
| `speak_review_ready` | 检查文字后即可发送。 | 〔旧资源候选〕 |
| `speak_review_long` | 内容较长，请确认后发送。 | 〔旧资源候选〕 |
| `speak_review_latin` | 含英文内容，请确认后发送。 | 〔旧资源候选〕 |
| `speak_review_edited` | 内容已修改，请确认后发送。 | 〔旧资源候选〕 |
| `speak_review_offline` | 草稿已保留，连接电脑后手动发送。 | 〔旧资源候选〕 |

### 云端识别

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `speak_source_cloud` | 云端 | 云端 |
| `cloud_title` | 豆包流式语音识别 2.0 | 〔旧资源候选〕 |
| `cloud_disclosure` | 选择「云端」并开始说话后，音频与热词会直接发送至火山引擎识别。凭据仅加密保存在本机，SayIn 不保存录音；服务商可能收取费用。保存配置不会开始录音。 | 〔旧资源候选〕 |
| `cloud_app_key` | API Key | API Key |
| `cloud_app_id` | APP ID | APP ID |
| `cloud_token` | Access Token | Access Token |
| `cloud_use_legacy` | 使用 APP ID + Access Token | 〔旧资源候选〕 |
| `cloud_use_key` | 使用 API Key | 〔旧资源候选〕 |
| `cloud_hotwords` | 热词（可选） | 〔旧资源候选〕 |
| `cloud_hotwords_help` | 每行一个，最多 20 条、合计 80 字符。热词会发送给豆包，但不保证识别正确；流式服务限制 100 token，仍可能截断。 | 〔旧资源候选〕 |
| `cloud_setup_hint` | 请先在设置中配置云端识别。 | 先在设置中配置云端识别。 |
| `cloud_ready` | 点按开始说话 · 音频将发送至豆包 | 〔旧资源候选〕 |
| `cloud_configure` | 配置云端 | 〔旧资源候选〕 |
| `cloud_start` | 开始说话 | 〔旧资源候选〕 |
| `cloud_loading` | 正在读取加密配置… | 读取配置中… |
| `cloud_saving` | 正在保存… | 保存中… |
| `cloud_clearing` | 正在删除配置… | 删除中… |
| `cloud_testing` | 正在验证流式服务… | 验证中… |
| `cloud_tested` | 流式服务已响应。请实际说话验证识别效果。 | 连接验证通过。录音可检验识别和热词效果。 |
| `cloud_saved` | 已保存在本机。回到说话页，选择「云端」即可使用。 | 配置已保存。 |
| `cloud_configured` | 已配置 · 小时版服务 | 〔旧资源候选〕 |
| `cloud_save` | 保存配置 | 〔旧资源候选〕 |
| `cloud_test` | 验证服务 | 〔旧资源候选〕 |
| `cloud_test_help` | 使用已保存的凭据发送 1 秒生成的静音，不使用麦克风，可能产生少量费用。此验证不代表识别准确率或热词效果；修改后请先保存。 | 〔旧资源候选〕保留费用、麦克风、保存条件和验证范围作为现用文案的复核依据。 |
| `cloud_clear` | 删除云端配置 | 删除云端配置 |
| `cloud_error_configuration` | 请检查凭据与热词（最多 20 条、合计 80 字符）。 | 检查服务凭据与热词格式。 |
| `cloud_error_storage` | 无法读取或保存加密配置。请删除云端配置后重新填写，不影响电脑配对。 | 配置无法读写。重置云端配置后重新填写。 |
| `cloud_error_network` | 云端连接中断。请检查互联网连接后重新开始说话，现有草稿已保留。 | 云端连接中断。检查网络后重录，草稿已保留。 |
| `cloud_error_authentication` | 凭据认证未通过。请检查 API Key 或 APP ID + Access Token 是否完整、有效且未禁用。 | 认证失败。检查凭据是否完整有效。 |
| `cloud_error_quota` | 已达到服务限制，请检查服务商账户与配额。 | 服务额度受限。检查账号与配额。 |
| `cloud_error_timeout` | 等待云端响应超时。草稿已保留，未自动发送。 | 云端响应超时。草稿已保留，未发送。 |
| `cloud_error_protocol` | 云端响应不完整或格式异常。草稿已保留，可重新开始说话。 | 云端响应异常。草稿已保留，可重录。 |
| `cloud_error_service` | 云端服务未能完成本次识别。请检查服务配置，或稍后重新开始说话。 | 识别服务异常。检查配置或稍后重试。 |
| `cloud_connecting` | 正在连接识别服务… | 正在连接识别服务… |
| `cloud_too_slow` | 网络传输跟不上录音。草稿已保留，请检查网络后重新开始说话。 | 上传跟不上录音。草稿已保留，检查网络后重录。 |
| `cloud_test_error_network` | 验证过程中连接中断，请检查互联网连接后重试。 | 连接中断。检查网络后重试。 |
| `cloud_test_error_timeout` | 等待服务响应超时，请重试。 | 服务响应超时，重试连接。 |
| `cloud_test_error_protocol` | 服务返回异常响应，验证未完成，请稍后重试。 | 服务响应异常，稍后重试。 |
| `cloud_error_access_denied` | 访问被拒绝，请检查所选业务空间或项目是否有权使用该识别模型。 | 无调用权限。检查业务空间或项目授权。 |

### 连接状态与恢复

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `connection_computer` | 电脑 | 电脑 |
| `connection_pair_saved` | 配对已保存 | 〔旧资源候选〕 |
| `connection_storage_label` | 记录异常 | 记录异常 |
| `connection_review_label` | 需在电脑核对 | 需在电脑核对 |
| `connection_review_title` | 请先在电脑核对 | 〔旧资源候选〕 |
| `connection_unavailable_label` | 接收未就绪 | 接收未就绪 |
| `connection_first_title` | 让文字到达电脑 | 〔旧资源候选〕 |
| `connection_first_help` | 打开电脑 SayIn 的接收页，扫描二维码即可连接。 | 打开电脑 SayIn 的「接收」，扫描配对二维码。 |
| `connection_pairing_help` | 正在建立安全连接。扫码和配对码均需在电脑上批准。 | 在电脑端批准本次配对。 |
| `connection_reconnecting_help` | 正在使用已保存的配对，不需要重新扫码。 | 使用已保存的配对重新连接。 |
| `connection_offline_help` | 正在等待电脑。手机 App 在前台时会自动重连，草稿会保留；重连不会触发发送。 | 应用可见时自动重连，草稿保留。 |
| `connection_recovering_help` | 正在核对此前的发送结果，完成后才能发送新文字。不会自动重发旧文字。 | 旧回执核对完成后可发送新内容。 |
| `connection_review_help` | 连接正常。请到目标软件核对上一条文字，再在电脑 SayIn 确认，避免重复输入。 | 在目标应用核对内容，再到电脑 SayIn 确认。 |
| `connection_inactive_help` | 连接正常。请在电脑接收页选择这台手机后，再发送文字。 | 在电脑接收页选中这台手机。 |
| `connection_unavailable_help` | 连接正常。请到电脑 SayIn 查看接收状态，准备好后再发送。 | 到电脑 SayIn 检查接收状态。 |
| `connection_paused_help` | 连接正常。请在电脑 SayIn 的接收页恢复接收后，再发送文字。 | 到电脑接收页恢复接收。 |
| `connection_ready_help` | 文字将输入电脑当前光标所在的位置，无需打开电脑 SayIn。 | 发送到电脑光标处。保持电脑 SayIn 运行即可。 |
| `connection_help` | 连接详情与帮助 | 〔旧资源候选〕 |
| `connection_network_help` | 保持电脑 SayIn 运行，手机与电脑需处于可互通的局域网。无需更改 Windows 公用或专用网络分类；防火墙或访客网络隔离仍可能阻止连接。 | 手机和电脑需在可互通的局域网内。连不上时检查防火墙或访客网络隔离。 |
| `connection_update_address` | 更新地址 | 更新地址 |
| `connection_save_address` | 保存并重连 | 保存并重连 |
| `connection_address` | 电脑地址（IPv4:端口） | 电脑地址（IPv4:端口） |
| `connection_back` | 返回 | 返回 |
| `connection_close` | 关闭连接面板 | 关闭连接面板 |

### 说话页状态与快捷入口

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `speak_auto_send` | 说完自动发送 | 〔旧资源候选〕 |
| `speak_model_loading` | 正在加载本地模型… | 正在加载本地模型… |
| `speak_model_warming` | 模型已加载，正在预热… | 正在预热模型… |
| `speak_local_ready` | 本地识别已就绪 | 本地识别已就绪 |
| `speak_local_listening` | 正在本机识别… | 本机识别中… |
| `speak_cloud_listening` | 正在进行云端识别… | 云端识别中… |
| `speak_cloud_idle` | 云端识别 · 录音时上传音频 | 录音会上传云端 |
| `speak_keyboard_idle` | 使用输入法输入 | 使用输入法输入 |
| `speak_character_count` | %1$d 字；%1$d 字 | %1$d 字；%1$d 字 |
| `speak_recording` | 正在聆听 | 〔旧资源候选〕 |
| `speak_clear` | 清空 | 〔旧资源候选〕 |
| `speak_send` | 发送 | 〔旧资源候选〕 |
| `speak_preparing` | 准备中… | 准备中… |
| `speak_model_retry` | 重新加载 | 〔旧资源候选〕 |
| `connection_endpoint` | %1$s:%2$d | %1$s:%2$d |
| `speak_auto_compact` | 自动 | 〔旧资源候选〕 |
| `speak_local_cold` | 开始说话时加载本地模型 | 录音前加载模型 |
| `speak_more` | 更多 | 〔旧资源候选〕 |
| `speak_abort` | 放弃本次录音 | 放弃本次录音 |
| `speak_clear_draft` | 清空草稿 | 清空草稿 |
| `speak_source_title` | 识别方式 | 〔旧资源候选〕 |
| `speak_continue` | 继续说话 | 〔旧资源候选〕 |
| `speak_send_to_computer` | 发送到电脑 | 发送到电脑 |
| `speak_cancel_send` | 取消发送 | 取消发送 |
| `speak_query` | 查询状态 | 查询状态 |
| `speak_keep_draft` | 保留为草稿 | 保留为草稿 |
| `speak_offline_saved` | %1$s · 草稿自动保留 | 〔旧资源候选〕 |
| `speak_view_connection` | 查看连接 | 〔旧资源候选〕 |
| `speak_cleared` | 草稿已清空 | 草稿已清空 |
| `speak_undo` | 撤销 | 撤销 |
| `quick_collapse` | 收起，保留当前会话 | 收起并保留会话 |
| `quick_expand` | 在 SayIn 中编辑 | 在 SayIn 中编辑 |
| `quick_recording_channel` | 语音录制 | 语音录制 |
| `quick_recording_notification` | 正在聆听 · 点按回到当前会话 | 录音中 · 点按返回 |
| `quick_tile_summary` | 打开当前说话会话 | 打开当前说话会话 |
| `quick_entry_title` | 快捷入口 | 快捷入口 |
| `quick_entry_notification` | 点按打开说话草稿 | 〔旧资源候选〕 |
| `quick_disable` | 关闭悬浮球 | 关闭悬浮球 |
| `quick_open` | 打开快捷说话卡 | 打开快捷说话卡 |
| `quick_entry_help` | 完整页与快捷卡共用录音和草稿。录音时可在系统通知中结束说话。 | 〔旧资源候选〕 |
| `quick_add_tile` | 添加控制中心磁贴 | 〔旧资源候选〕 |
| `quick_tile_help` | 在系统控制中心编辑磁贴，添加 SayIn。 | 编辑控制中心，添加 SayIn 磁贴。 |
| `quick_ball` | 悬浮球 | 〔旧资源候选〕 |
| `quick_overlay_help` | 按需开启，需要悬浮窗权限并显示常驻通知，不会自行开启麦克风。 | 需悬浮窗权限，开启时不开麦。允许通知后显示常驻通知。 |
| `quick_overlay_denied` | 未能开启悬浮球。请允许悬浮窗权限后重试。 | 开启失败。允许悬浮窗权限后重试。 |

### 历史列表

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `history_all` | 全部 | 全部 |
| `history_submitted` | 已提交 | 已提交 |
| `history_attention` | 待处理 | 待处理 |
| `history_search` | 搜索记录 | 搜索记录 |
| `history_no_results` | 没有匹配的记录 | 无匹配记录 |
| `history_today` | 今天 | 今天 |
| `history_yesterday` | 昨天 | 昨天 |
| `history_details` | 记录详情 | 记录详情 |
| `history_copy` | 复制 | 复制 |
| `history_text_unavailable` | 这条记录的原文不可用 | 原文不可用 |
| `history_local_storage` | 最近 100 条记录加密保存在本机，不同步到云端。 | 最近 100 条记录加密存于本机。 |
| `speak_new_draft` | 开始新草稿 | 开始新草稿 |
| `speak_draft_storage_issue` | 草稿存储不可用，请保持应用打开以保留本次文字。 | 草稿无法保存。保持应用打开并复制文字。 |
| `speak_listening_time` | 聆听 %1$d:%2$02d | 〔旧资源候选〕 |
| `speak_source_local_short` | 本地 | 〔旧资源候选〕 |
| `speak_restoring_draft` | 正在恢复草稿… | 正在恢复草稿… |
| `error_discovery` | 未找到已配对电脑。请确认手机与电脑在同一局域网，也可在连接帮助中更新地址。 | 找不到已配对电脑。检查局域网，或在连接帮助中更新地址。 |
| `error_candidate_connection` | 已发现候选电脑，但未能完成连接认证。配对信息已保留，请检查电脑或更新地址。 | 连接认证失败。配对保留，可检查电脑或更新地址。 |
| `discovering_computer` | 正在查找电脑… | 正在查找电脑… |

### 图片发送

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `image_title` | 发送图片 | 发送图片 |
| `image_choose` | 选择图片… | 选择图片… |
| `image_preview` | 图片预览 | 图片预览 |
| `image_metadata` | %1$d × %2$d · %3$d KiB | %1$d × %2$d · %3$d KiB |
| 图片格式与大小限制 `image_normalization` | 支持 PNG/JPEG，最大 10 MiB、1600 万像素；自动校正方向并移除元数据，JPEG 以质量 92 重编码。 | PNG/JPEG，最多 10 MiB、1,600 万像素。自动校正方向并移除元数据。 |
| `image_clipboard_notice` | 粘贴会替换电脑剪贴板，不会按下回车。请在目标应用确认结果。 | 〔旧资源候选〕 |
| `image_preparing` | 正在准备图片 | 正在准备图片 |
| `image_awaiting_receipt` | 正在等待电脑回执 | 正在等待电脑回执 |
| `image_progress` | 正在传送 · %1$d%% | 正在传送 · %1$d%% |
| `image_cancel_upload` | 取消传送 | 取消传送 |
| `image_paste_submitted` | 已提交粘贴指令，请查看目标应用。 | 已提交粘贴，结果以目标应用为准。 |
| `image_path_copied` | 图片已保存，路径已复制 | 图片已保存，路径已复制 |
| `image_path_paste_help` | 图片已保存，路径已复制。可在电脑上的目标应用中粘贴。 | 图片已保存，路径已复制。可粘贴到电脑应用。 |
| `image_export_failed` | 图片保存失败，请检查电脑端 SayIn 设置中的文件夹。 | 图片保存失败。检查电脑端的保存目录。 |
| `image_saved_path_unavailable` | 图片已保存，路径复制失败。 | 图片已保存，路径复制失败。 |
| `image_saved_paste_unavailable` | 图片已保存，粘贴指令提交失败。 | 图片已保存，粘贴指令提交失败。 |
| `image_invalid` | 图片格式不支持、内容损坏或超出上限，请选择静态 PNG/JPEG。 | 选择有效的静态 PNG 或 JPEG，并检查大小限制。 |
| `image_not_ready` | 电脑暂时无法粘贴。图片已保留，请查看连接或电脑接收状态。 | 图片已保留。检查连接或电脑接收状态。 |
| `image_unsupported` | 此电脑尚不支持图片输入，请更新电脑端 SayIn。 | 此电脑尚不支持图片输入，请更新电脑端 SayIn。 |
| `image_connection_error` | 图片传送已中断，图片已保留。再次发送前请查看历史回执。 | 传送中断，图片已保留。再次发送前先查回执。 |
| `image_quota` | 还有图片等待处理，或已达到存储上限，请先查看历史记录。 | 有图片等待处理或空间已满。查看历史记录。 |
| `image_cancelled` | 已取消传送，图片已保留。 | 传送已取消，图片已保留。 |
| `image_send` | 发送图片 | 发送图片 |
| `image_remove` | 移除图片 | 移除图片 |
| `image_close` | 关闭 | 关闭 |
| `image_history` | 图片 | 图片 |
| `image_uploading` | 正在传送图片 | 正在传送图片 |
| `image_held` | 图片尚未粘贴。可取消或等待过期，再明确发送一次。 | 等待处理。取消或过期后可重新发送。 |

### 本地翻译

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `translation_action` | 翻译成英文 | 翻译成英文 |
| `translation_original` | 原文 | 〔旧资源候选〕 |
| `translation_english` | 英文译文 | 〔旧资源候选〕 |
| `translation_send` | 发送译文 | 〔旧资源候选〕 |
| `translation_manual_hint` | 检查或编辑译文后手动发送，原文将保留。 | 〔旧资源候选〕 |
| `translation_generating` | 正在手机本地翻译… | 正在离线翻译… |
| `translation_verifying` | 正在校验模型完整性：%1$d%% | 正在校验模型：%1$d%% |
| `translation_loading` | 正在加载本地模型：%1$d%% | 正在加载本地模型：%1$d%% |
| `translation_stopping` | 正在停止本地翻译… | 正在停止本地翻译… |
| `translation_complete` | 翻译完成，请核对人名、数字、代码及含义后发送。 | 翻译完成。发送前核对译文。 |
| `translation_cancelled` | 翻译已取消，原文已保留。 | 翻译已取消，原文保留。 |
| `translation_truncated` | 译文已达到输出上限，内容不完整，不能发送。 | 译文达到长度上限，无法发送。 |
| `translation_input_limit` | 原文超过模型的 1,024 token 限制，请缩短后重新翻译。 | 原文超过 1,024 Token。缩短后重试。 |
| `translation_timeout` | 翻译超时，原文已保留，请手动重试。 | 翻译超时，原文保留。可重试。 |
| `translation_model_unavailable` | 本地模型缺失或完整性校验失败，请重新准备，原文已保留。 | 模型缺失或校验失败。重新导入模型，原文保留。 |
| `translation_error` | 本地翻译失败，原文已保留，未调用云服务。 | 离线翻译失败，原文保留。 |
| `translation_prepare_title` | 准备离线翻译 | 〔旧资源候选〕 |
| `translation_prepare_description` | 下载所选模型并按固定 SHA-256 校验完整性。准备完成后在手机本地翻译，不上传正文。下载不会翻译或发送当前草稿。 | 〔旧资源候选〕 |
| `translation_prepare_download` | 下载并准备模型 | 〔旧资源候选〕 |
| `translation_import_development` | 导入已核验的本地模型（开发用） | 〔旧资源候选〕 |
| `translation_preparing` | 正在准备翻译模型：%1$d%% | 〔旧资源候选〕 |
| `translation_preparing_verify` | 正在校验下载的模型：%1$d%% | 〔旧资源候选〕 |
| `translation_prepare_failed` | 模型准备失败，请检查存储空间和连接后重试，原文已保留。 | 〔旧资源候选〕 |
| `speak_redo` | 重做 | 重做 |
| `speak_copy_draft` | 复制文本 | 复制文本 |
| `speak_count_short` | %1$d 字 | %1$d 字 |
| `speak_start_short` | 说话 | 说话 |
| `speak_release_cancel` | 松开取消 | 松开取消 |
| `speak_review_short` | 到电脑核对 | 到电脑核对 |
| `speak_finish_send` | 结束并发送 | 结束并发送 |
| `speak_recognition_settings` | 识别设置 | 〔旧资源候选〕 |
| `speak_offline_recognition` | 离线识别 | 〔旧资源候选〕 |
| `speak_cloud_recognition` | 云端识别 | 〔旧资源候选〕 |
| `speak_offline_description` | 在手机上处理 | 〔旧资源候选〕 |
| `speak_cloud_description` | 使用已配置的云服务 | 〔旧资源候选〕 |

### 剪贴板与常用语

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `assist_open` | 剪贴板与常用语 | 剪贴板与常用语 |
| `assist_close` | 关闭面板 | 关闭面板 |
| `assist_tab_clipboard` | 剪贴板 | 剪贴板 |
| `assist_tab_phrases` | 常用语 | 常用语 |
| `assist_add_phrase` | 新增常用语 | 新增常用语 |
| `assist_back` | 返回列表 | 返回列表 |
| `assist_editor_add_title` | 新增常用语 | 新增常用语 |
| `assist_editor_edit_title` | 编辑常用语 | 编辑常用语 |
| `assist_preview_title` | 完整内容 | 完整内容 |
| `assist_phrase_label` | 常用语内容 | 常用语内容 |
| `assist_phrase_placeholder` | 输入要复用的内容 | 输入要复用的内容 |
| `assist_counter` | %1$d / %2$d | %1$d / %2$d |
| `assist_save` | 保存 | 保存 |
| `assist_cancel` | 取消 | 取消 |
| `assist_clear` | 清除 | 清除 |
| `assist_clear_all` | 清除全部 | 清除全部 |
| `assist_clear_clipboard_title` | 清除剪贴板？ | 清除剪贴板？ |
| `assist_clear_clipboard_text` | 将清除当前剪贴板内容。草稿和常用语不会改变。 | 清除系统剪贴板。草稿和常用语保留。 |
| `assist_clear_phrases_title` | 清除全部常用语？ | 清除全部常用语？ |
| `assist_clear_phrases_text` | 将删除全部 %1$d 条常用语，无法撤销。草稿和剪贴板不会改变。 | 删除全部 %1$d 条常用语，无法撤销。 |
| `assist_discard_title` | 放弃修改？ | 放弃修改？ |
| `assist_discard_text` | 对这条常用语的修改不会保存。 | 此常用语的修改将丢失。 |
| `assist_discard_keep` | 继续编辑 | 继续编辑 |
| `assist_discard_confirm` | 放弃 | 放弃 |
| `assist_no_phrases` | 还没有常用语 | 还没有常用语 |
| `assist_no_phrases_help` | 保存经常输入的内容，下次点一下即可插入。 | 保存常用文字，下次点按插入。 |
| `assist_clipboard_empty_title` | 剪贴板暂无文字 | 剪贴板暂无文字 |
| `assist_clipboard_empty_help` | 复制一段文字后，重新打开此标签。 | 复制文字后重新打开此页。 |
| `assist_clipboard_notext_title` | 剪贴板中没有文字 | 剪贴板中没有文字 |
| `assist_clipboard_notext_help` | 图片请使用草稿工具栏的图片入口。 | 通过草稿工具栏发送图片。 |
| `assist_clipboard_unavailable_title` | 暂时无法读取剪贴板 | 暂时无法读取剪贴板 |
| `assist_clipboard_unavailable_help` | 请回到应用后重试，草稿不会改变。 | 回到应用后重试，草稿已保留。 |
| `assist_retry` | 重试 | 重试 |
| `assist_clipboard_toolong_title` | 剪贴板内容过长 | 剪贴板内容过长 |
| `assist_clipboard_toolong_help` | 请复制较短的内容后重新打开此标签。 | 复制较短的文字后重新打开此页。 |
| `assist_clipboard_invalid_title` | 剪贴板文字无法使用 | 剪贴板文字无法使用 |
| `assist_invalid_too_long` | 超过上限（4000 字符 / 16 KiB）。 | 超过上限（4000 字符 / 16 KiB）。 |
| `assist_invalid_characters` | 包含不支持的控制字符。 | 包含不支持的控制字符。 |
| `assist_invalid_unicode` | 包含不完整的 Unicode 字符。 | 包含不完整的 Unicode 字符。 |
| `assist_sensitive_hidden` | 敏感剪贴板内容已遮住 | 敏感剪贴板内容已遮住 |
| `assist_show` | 显示 | 显示 |
| `assist_multiple_note` | 仅显示剪贴板第一项。 | 仅显示剪贴板第一项。 |
| `assist_copy` | 复制 | 复制 |
| `assist_paste` | 粘贴 | 粘贴 |
| `assist_save_as_phrase` | 保存为常用语 | 保存为常用语 |
| `assist_view_full` | 查看全文 | 查看全文 |
| `assist_edit` | 编辑 | 编辑 |
| `assist_delete` | 删除 | 删除 |
| `assist_undo` | 撤销 | 撤销 |
| `assist_more_for` | 更多操作：%1$s | 更多操作：%1$s |
| `assist_insert_action` | 插入草稿 | 插入草稿 |
| `assist_reorder` | 拖动排序 | 拖动排序 |
| `assist_reorder_for` | 拖动排序：%1$s | 拖动排序：%1$s |
| `assist_move_up` | 上移 | 上移 |
| `assist_move_down` | 下移 | 下移 |
| `assist_moved` | 已移至第 %1$d 位，共 %2$d 位 | 已移至第 %1$d 位，共 %2$d 位 |
| `assist_copied` | 已复制 | 已复制 |
| `assist_copy_failed` | 复制失败，请重试。 | 复制失败，请重试。 |
| `assist_pasted` | 已粘贴 | 已粘贴 |
| `assist_insert_failed` | 无法在这里插入这段文字。 | 无法在此处插入文字。 |
| `assist_draft_changed` | 草稿已变化，请重新选择插入位置。 | 草稿已变化。重新选择插入位置。 |
| `assist_not_editable` | 当前草稿不可编辑。 | 当前草稿不可编辑。 |
| `assist_saved` | 已保存常用语 | 已保存常用语 |
| `assist_save_failed` | 保存失败，请重试。 | 保存失败，请重试。 |
| `assist_phrase_empty` | 请输入文字。 | 请输入文字。 |
| `assist_phrase_duplicate` | 这条常用语已存在。 | 这条常用语已存在。 |
| `assist_phrase_too_long` | 超过上限（4000 字符 / 16 KiB）。 | 超过上限（4000 字符 / 16 KiB）。 |
| `assist_phrase_invalid` | 包含不支持的控制字符。 | 包含不支持的控制字符。 |
| `assist_phrase_full` | 最多保存 100 条常用语。 | 最多保存 100 条常用语。 |
| `assist_deleted` | 已删除常用语 | 已删除常用语 |
| `assist_delete_failed` | 删除失败，请重试。 | 删除失败，请重试。 |
| `assist_restored` | 已恢复常用语 | 已恢复常用语 |
| `assist_restore_failed` | 无法恢复常用语。 | 无法恢复常用语。 |
| `assist_reorder_failed` | 保存新顺序失败。 | 保存新顺序失败。 |
| `assist_reordered` | 顺序已更新 | 顺序已更新 |
| `assist_cleared_clipboard` | 已清除剪贴板 | 已清除剪贴板 |
| `assist_clear_failed` | 清除失败，请重试。 | 清除失败，请重试。 |
| `assist_cleared_phrases` | 已清除全部常用语 | 已清除全部常用语 |
| `assist_changed` | 内容已变化，请重新确认。 | 内容已变化，请重新确认。 |
| `assist_library_issue` | 常用语库无法打开，现有数据已保留。 | 常用语无法读取，已有数据保留。 |
| `assist_loading` | 正在加载常用语… | 正在加载常用语… |
| `assist_reading_clipboard` | 正在读取剪贴板… | 正在读取剪贴板… |
| `speak_computer_current` | 电脑当前：%1$s | 电脑当前：%1$s |
| `speak_computer_offline` | 未连接电脑 | 未连接电脑 |
| `speak_computer_paused` | 电脑暂停接收 | 电脑暂停接收 |
| `speak_computer_unknown` | 当前应用未知 | 当前应用未知 |
| `speak_computer_connected` | 电脑已连接 | 电脑已连接 |
| `speak_computer_description` | %1$s。实际输入位置以电脑执行时的焦点为准。 | %1$s。输入位置以电脑执行时为准。 |
| `speak_selected_count` | 已选 %1$d 字；已选 %1$d 字 | 已选 %1$d 字；已选 %1$d 字 |
| `speak_limit_count` | %1$d / %2$d | %1$d / %2$d |
| `translation_cancel` | 取消翻译 | 取消翻译 |
| `translation_show_original` | 正在显示译文，点击恢复原文 | 切回原文 |
| `translation_show_cached` | 显示已有译文 | 显示已有译文 |

### Root 剪贴板与初次连接

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `root_clipboard_paused` | 采集已关闭或暂停，仍可查看已保存记录；可在模块 Action 中启停采集。 | 采集已关闭或暂停，仍可查看历史。可在模块管理器启停。 |
| `root_clipboard_empty` | 还没有记录。新复制的文字会显示在这里。 | 采集开启后，新复制的文字会显示在这里。 |
| `root_clipboard_clear_title` | 清空模块记录？ | 清空模块记录？ |
| `root_clipboard_clear_message` | 删除模块中的全部 %1$d 条记录，系统剪贴板和常用语不受影响。 | 删除模块的 %1$d 条记录。系统剪贴板和常用语保留。 |
| `root_clipboard_cleared` | 模块记录已清空。 | 模块记录已清空。 |
| `root_clipboard_changed` | 记录已变化，请刷新后再操作。 | 记录已变化，请刷新后再操作。 |
| `root_clipboard_denied` | 模块拒绝访问，请安装签名匹配的配套开发版本。 | 访问被拒绝。使用签名匹配的配套版本。 |
| `root_clipboard_locked` | 请解锁手机并切换到主用户后查看模块记录。 | 解锁手机并切换到主用户后查看。 |
| `root_clipboard_unavailable` | 模块未连接，仍可使用当前剪贴板。若清空操作超时，请刷新核对结果。 | 无法连接模块。仍可使用系统剪贴板。 |
| `connect_step_open` | 在电脑端 SayIn 打开「接收」 | 在电脑 SayIn 打开「接收」 |
| `connect_step_scan` | 用这台手机扫描电脑上的二维码 | 扫描电脑上的配对二维码 |
| `connect_step_approve` | 在电脑核对手机名称后批准连接 | 在电脑核对手机名称后批准连接 |
| `connect_approval_notice` | 首次连接需要在电脑上批准。 | 首次连接需要在电脑上批准。 |
| `connect_lan_title` | 需要局域网权限 | 需要局域网权限 |
| `connect_ready` | 已连接 · 可以输入 | 已连接 · 可以输入 |
| `connect_retry_wait` | 暂未连接，将自动重试 | 暂未连接，将自动重试 |
| `connect_allow_network` | 允许访问局域网 | 允许访问局域网 |
| `connect_open_settings` | 打开应用设置 | 打开应用设置 |
| `connect_camera_retry` | 允许使用相机 | 允许使用相机 |
| `connect_not_connected` | 未连接 | 〔旧资源候选〕 |
| `connect_more` | 更多连接选项 | 〔旧资源候选〕 |
| `history_delete` | 删除记录 | 删除记录 |
| `history_delete_help` | 仅隐藏本机记录，不取消请求、不撤回电脑输入，也不解除待核对。 | 仅隐藏本机记录，不取消请求、不撤回电脑输入，也不解除待核对。 |
| `history_target_unknown` | 未记录目标应用 | 未记录目标应用 |
| `history_image_unavailable` | 图片暂不可用 | 图片暂不可用 |
| `history_copied` | 已复制 | 已复制 |
| `history_submitted_help` | 仅表示输入指令已提交，不保证目标应用已显示内容。 | 仅表示输入指令已提交，不保证目标应用已显示内容。 |
| `history_image_submitted` | 粘贴指令已提交 | 粘贴指令已提交 |
| `history_copy_failed` | 复制失败，请重试。 | 复制失败，请重试。 |

### 设置、隐私与帮助

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `settings_title` | 设置 | 设置 |
| `settings_back` | 返回 | 返回 |
| `settings_group_voice_input` | 语音与输入 | 语音与输入 |
| `settings_group_quick_entry` | 快捷入口 | 快捷入口 |
| `settings_group_experience` | 使用体验 | 使用体验 |
| `settings_group_privacy` | 数据与隐私 | 数据与隐私 |
| `settings_group_about` | 关于 | 关于 |
| `settings_voice_recognition` | 语音识别 | 语音识别 |
| `settings_auto_send` | 自动发送 | 自动发送 |
| 自动发送开关 `settings_auto_send_hint` | 开启后按下方等待时长自动发送 | 语音识别完成后自动发送。 |
| 等待时长 `settings_auto_send_summary` | 语音识别结束后等待时长 | 识别结束后等待发送。默认自动发送关闭时显示「开启默认自动发送后可调整。」 |
| `settings_translation` | 翻译 | 翻译 |
| `settings_translation_summary` | 本地 · 中译英 | 中译英 |
| `settings_floating_ball` | 悬浮球 | 悬浮球 |
| `settings_control_center` | 控制中心快捷入口 | 控制中心快捷入口 |
| `settings_control_center_summary` | 添加 SayIn 快捷入口 | 添加磁贴 |
| `settings_background_help` | 后台运行帮助 | 后台运行帮助 |
| `settings_background_help_summary` | 自启动与电池设置 | 自启动与电池限制 |
| `settings_appearance` | 外观 | 外观 |
| `settings_appearance_summary` | 跟随系统 · 外观选择开发中 | 〔旧资源候选〕 |
| `settings_app_language` | 应用语言 | 应用语言 |
| `settings_keep_awake` | 使用时保持亮屏 | 使用时保持亮屏 |
| `settings_keep_awake_summary` | 仅在 SayIn 界面可见时生效 | 仅 SayIn 界面可见时生效 |
| `settings_draft_toolbar` | 草稿工具栏 | 草稿工具栏 |
| `settings_draft_toolbar_summary` | 完整页 %1$d 个 · 快捷卡 %2$d 个 | 完整页 %1$d 个 · 快捷卡 %2$d 个 |
| `settings_draft_toolbar_note` | 拖动左侧把手调整顺序。关闭开关只会从草稿卡上隐藏按钮；完整页与快捷卡分别配置，按钮较多时自动换行。 | 拖动排序，关闭开关可隐藏按钮。完整页和快捷卡分别设置。 |
| `settings_draft_toolbar_side` | 显示 3 个及以下按钮时的位置 | 最多 3 个按钮时的对齐方式 |
| `settings_draft_toolbar_side_left` | 靠左 | 靠左 |
| `settings_draft_toolbar_side_right` | 靠右 | 靠右 |
| `settings_draft_toolbar_surface_page` | 完整页 | 完整页 |
| `settings_draft_toolbar_surface_quick` | 快捷卡 | 快捷卡 |
| `settings_draft_toolbar_reset` | 恢复默认工具栏 | 恢复默认工具栏 |
| `settings_draft_tool_image` | 图片 | 图片 |
| `settings_draft_tool_translate` | 翻译 | 翻译 |
| `draft_tool_send_mode` | 发送模式 | 发送模式 |
| `draft_tool_engine` | 识别引擎 | 识别引擎 |
| `draft_tool_save_phrase` | 存为常用语 | 存为常用语 |
| `draft_tool_share` | 分享 | 分享 |
| `draft_tool_send_mode_note` | 只影响之后的发送，不会改动当前草稿。 | 此设置对之后的发送生效。 |
| `draft_tool_send_mode_override` | 当前应用有单独规则，会覆盖这里的设置。 | 此应用优先使用专属发送规则。 |
| `draft_tool_engine_configure` | 配置云端 | 配置云端 |
| `draft_tool_engine_note` | 切换引擎不会改动当前草稿。 | 切换引擎不会改动当前草稿。 |
| `draft_tool_phrase_saved` | 已存为常用语 | 已存为常用语 |
| `draft_tool_share_chooser` | 分享草稿 | 分享草稿 |
| `draft_tool_share_failed` | 没有可分享文字的应用 | 没有可分享文字的应用 |
| `settings_storage` | 存储管理 | 存储管理 |
| `settings_storage_summary` | 缓存与本地数据 | 查看占用、清理缓存 |
| `settings_clipboard` | 剪贴板增强 | 剪贴板增强 |
| `settings_clipboard_summary` | 查看模块说明 | 查看模块状态 |
| `settings_privacy` | 隐私说明 | 隐私说明 |
| `settings_privacy_summary` | 本机数据与云端处理 | 本机数据与云端处理 |
| `settings_about` | 关于 SayIn | 关于 SayIn |
| `settings_about_summary` | 版本 %1$s | 版本 %1$s |
| `settings_engine_local` | 离线识别 | 离线识别 |
| `settings_engine_cloud` | 云端识别 | 云端识别 |
| `settings_engine_cloud_configured` | %1$s已配置 | 〔按状态显示〕 |
| `settings_engine_cloud_missing` | %1$s未配置 | %1$s未配置 |
| `settings_engine_cloud_read_error` | 配置读取异常 | 配置无法读取 |
| `settings_engine_summary` | %1$s · %2$s | 〔按状态显示〕 |
| `settings_recognition_method` | 识别方式 | 〔旧资源候选〕 |
| `settings_recognition_note` | 切换引擎不会开麦、发送草稿或重置文字。已有请求继续使用开始时的配置。 | 〔旧资源候选〕 |
| `settings_offline_model` | 离线模型 | 〔旧资源候选〕 |
| `settings_offline_model_ready` | 模型已就绪 | 〔旧资源候选〕 |
| `settings_offline_model_loading` | 模型加载中 | 〔旧资源候选〕 |
| `settings_offline_model_warming` | 模型预热中 | 〔旧资源候选〕 |
| `settings_offline_model_failed` | 模型不可用 | 〔旧资源候选〕 |
| `settings_offline_model_idle` | 模型未加载 | 〔旧资源候选〕 |
| `settings_cloud_service` | 云端服务 | 云端服务 |
| `settings_cloud_service_summary` | 豆包 · %1$s | 〔旧资源候选〕 |
| `settings_hotwords` | 热词 | 热词 |
| `settings_hotwords_summary` | 豆包 · %1$d 个 | 〔旧资源候选〕 |
| `settings_hotwords_configure_first` | 请先配置云端服务 | 配置云端服务后可编辑热词 |
| `settings_hotwords_title` | 热词编辑 | 热词编辑 |
| 热词格式与字数限制 `settings_hotwords_help` | 每行一个词；最多 20 个去重词条，总长度 80 字符，单词条不超过 40 字符。 | 每行一词。去重后最多 20 个，共 80 字符，每词最多 40 字符。 |
| `settings_hotwords_count` | %1$d 个词 · %2$d 字符 | %1$d 个词 · %2$d 字符 |
| `settings_hotwords_save` | 保存 | 保存 |
| `settings_cloud_title` | 云端识别 | 云端识别 |
| `settings_translation_title` | 翻译与模型 | 翻译 |
| `settings_translation_ready` | 模型已就绪 | 〔旧资源候选〕 |
| `settings_translation_bundled` | 随应用内置 | 〔旧资源候选〕 |
| `settings_translation_bundled_note` | 已内置的模型随应用打包，安装后即可离线翻译。未内置的模型会联网下载并在使用前校验固定 SHA-256。 | 〔旧资源候选〕 |
| `settings_translation_missing` | 模型未准备 | 〔旧资源候选〕 |
| `settings_translation_failed` | 模型不可用 | 〔旧资源候选〕 |
| `settings_translation_prepare` | 下载模型 | 〔旧资源候选〕 |
| `settings_translation_prepare_desc` | 本地中译英在本机推理。该模型需要联网下载，并在使用前校验固定 SHA-256。 | 〔旧资源候选〕 |
| `settings_translation_progress` | 准备中 %1$d%% | 〔旧资源候选〕 |
| `settings_translation_note` | 翻译会从说话页应用到当前草稿。 | 〔旧资源候选〕 |
| `settings_background_title` | 后台运行帮助 | 后台运行帮助 |
| 后台运行帮助 `settings_background_body` | SayIn 只在说话页或正在录音时需要保持连接，不会常驻后台。如果手机终止录音或快捷入口，请在系统设置中允许自启动并取消电池限制。 | 界面可见或识别进行中会维护连接。录音或快捷入口被系统中断时，可检查自启动与电池限制。 |
| `settings_background_keepalive` | 这些设置属于系统，SayIn 不会自动修改。 | 在系统设置中调整自启动和电池限制。 |
| `settings_background_open_quick` | 打开快捷卡 | 打开快捷卡 |
| `settings_clipboard_title` | 剪贴板增强 | 剪贴板增强 |
| 剪贴板普通模式 `settings_clipboard_normal` | 普通模式只在你粘贴时读取当前一条剪贴板，Android 不向应用提供剪贴板历史。 | 粘贴时只读取剪贴板中的当前内容。 |
| 剪贴板 Root 模块 `settings_clipboard_module` | 可选 Root 模块由你在模块管理器中明确开启采集；敏感内容和锁屏状态有门禁，数据加密保存在本机。 | 在模块管理器开启采集。锁屏时暂停，跳过系统标记的敏感内容。历史在本机加密保存。 |
| `settings_clipboard_check` | 检查模块状态 | 检查模块状态 |
| `settings_clipboard_checking` | 检查中… | 检查中… |
| `settings_clipboard_control` | 采集控制 | 采集控制 |
| `settings_clipboard_control_summary` | 开发中 · 当前请在模块管理器操作 | 在模块管理器开启或关闭采集。 |
| `settings_privacy_title` | 隐私说明 | 隐私说明 |
| `settings_privacy_device_title` | 本机数据 | 本机数据 |
| `settings_privacy_device_body` | 草稿、历史、图片和凭据按现有加密机制保存在本机。隐藏记录不会删除内部请求账本。 | 草稿、历史、图片和凭据加密存于本机。隐藏历史时保留请求回执。 |
| `settings_privacy_cross_title` | 跨设备 | 跨设备 |
| `settings_privacy_cross_body` | 文字和图片通过已配对电脑的固定证书通道发送；发现只找地址，不建立新的信任。SayIn 不能保证目标输入框一定接受输入。 | 文字和图片经加密连接发送到已配对电脑。 |
| `settings_privacy_cloud_title` | 云端处理 | 云端处理 |
| `settings_privacy_cloud_body` | 云识别会把音频和所支持的热词上传到已配置的服务。本地识别和本地翻译在本机推理，下载模型仍需要网络。选择本地翻译不代表云识别音频不会上传。 | 云端识别上传音频和热词。使用 AI 润色或 AI 翻译时，待处理文字会上传至所选服务。 |
| `settings_about_title` | 关于 SayIn | 关于 SayIn |
| `settings_about_help` | 使用帮助 | 使用帮助 |
| `settings_about_homepage` | 项目主页 | 项目主页 |
| `settings_about_licenses` | 开源许可 | 开源许可 |
| `settings_about_licenses_summary` | 第三方组件与许可证全文 | 第三方组件与许可证 |
| `settings_about_diagnostics` | 诊断信息 | 诊断信息 |
| `settings_about_diagnostics_summary` | 设备、权限与连接状态 | 设备、权限与连接 |
| `settings_help_title` | 使用帮助 | 使用帮助 |
| `settings_help_body` | 配对：扫描电脑上的二维码并在电脑上批准。说话：按住麦克风或直接输入，完整结果可手动发送，也可开启倒计时自动发送。图片：在更多菜单中选择图片。若请求显示待核对或受阻，请在电脑上完成核对，SayIn 不会自动重发。连接维护仍在连接页完成。 | 配对：扫描电脑二维码，到电脑批准。〈换行〉发送：输入文字或从工具栏选图片。等待核对时，到电脑确认结果。 |
| `settings_clipboard_result` | %1$s · %2$s | %1$s · %2$s |
| `settings_clipboard_ready` | 采集就绪 | 采集就绪 |
| `settings_clipboard_not_ready` | 采集未就绪 | 采集未就绪 |
| `settings_clipboard_unavailable` | 连接暂不可用 | 模块无法连接 |
| `cloud_show_secret` | 显示 | 显示 |
| `cloud_hide_secret` | 隐藏 | 隐藏 |
| `settings_debug_build` | 开发版 | 开发版 |
| `cloud_clear_confirm_title` | 删除云配置？ | 删除云端配置？ |
| `cloud_clear_confirm_message` | 要从此设备移除当前服务的凭据吗？ | 只移除此服务在本机保存的凭据。 |
| `cloud_clear_confirm` | 删除 | 删除 |
| `settings_unsaved_title` | 放弃未保存的修改？ | 放弃未保存的修改？ |
| `settings_unsaved_message` | 当前编辑内容尚未保存。 | 离开后，本次修改会丢失。 |
| `settings_keep_editing` | 继续编辑 | 继续编辑 |
| `settings_discard_changes` | 放弃修改 | 放弃修改 |
| `settings_offline_model_sub` | 中英识别 | 〔旧资源候选〕 |
| `settings_cloud_service_name` | 豆包语音识别 | 豆包语音识别 |
| `settings_cloud_service_sub` | 火山引擎 · 流式 | 〔旧资源候选〕 |
| `settings_cloud_upload_note` | 录音将上传至所选云端服务 | 录音上传至所选服务。 |
| `settings_local_private_note` | 本机处理，语音不会上传 | 音频留在本机。 |
| `settings_cloud_need_config` | 请先配置云端服务，再开始录音 | 配置云端服务后才能录音。 |
| `settings_engine_cloud_configured_short` | 已配置 | 已配置 |
| `settings_engine_cloud_missing_short` | 未配置 | 未配置 |
| `settings_hotwords_count_only` | %1$d 个 | %1$d 个 |
| `settings_recognition_short_note` | 切换识别方式不会发送当前草稿 | 〔移除说明〕 |
| `settings_cloud_credentials_note` | 凭据仅加密保存在本机 | 凭据在本机加密保存。 |
| `settings_cloud_auth_method` | 鉴权方式 | 鉴权方式 |
| `settings_cloud_auth_token` | APP ID + Token | APP ID + Access Token |
| `settings_verify_title` | 验证连接 | 验证连接 |
| `settings_verify_sub` | 发送 1 秒静音，可能计费 | 发送 1 秒静音，不开麦，可能计费。 |
| `settings_verify_action` | 验证 | 验证 |
| `settings_cloud_dirty` | 待保存 | 未保存 |

### 图片格式与外观选项

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `image_format_toggle` | 格式与限制 | 格式与限制 |
| `image_empty_hint` | PNG / JPEG · 最大 10 MiB | PNG / JPEG · 最大 10 MiB |
| `image_size_mime` | %1$d KiB · %2$s | %1$d KiB · %2$s |
| `image_change` | 更换图片 | 更换图片 |
| `image_ready_status` | 准备发送 | 准备发送 |
| `image_ready_sub` | 发送后会替换电脑剪贴板 | 发送后会替换电脑剪贴板 |
| `image_dimensions` | %1$d × %2$d | %1$d × %2$d |
| `image_use_screenshot` | 使用最近截图 | 使用最近截图 |
| `image_preview_failed` | 无法加载预览 | 无法加载预览 |
| `appearance_light` | 浅色 | 浅色 |
| `appearance_dark` | 深色 | 深色 |
| `appearance_system` | 跟随系统 | 跟随系统 |
| `quick_notification_connected` | 已连接 · %1$s | 已连接 · %1$s |
| `quick_notification_paused` | 连接已暂停 | 连接已暂停 |
| `quick_notification_connecting` | 正在连接 | 正在连接 |
| `quick_notification_offline` | 电脑未连接 | 电脑未连接 |
| `quick_notification_computer` | 电脑 %1$s | 电脑 %1$s |
| `quick_notification_public` | 点击查看 | 点击查看 |
| `quick_notification_open` | 打开速记 | 打开速记 |
| `quick_show` | 显示悬浮球 | 显示悬浮球 |
| `quick_notification_expanded` | 可打开速记或切换悬浮球 | 可打开快捷卡或切换悬浮球 |

### 开源许可、存储与诊断

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `settings_licenses_count` | %1$d 个组件 | %1$d 个组件 |
| `settings_licenses_note` | 由 Gradle 直接依赖与传递依赖自动收集；非 Gradle 或未标注许可证的组件可能需要人工核对。 | 收录已识别的第三方组件及许可。 |
| `settings_licenses_no_content` | 未提供许可证全文 | 未提供许可证全文 |
| `settings_licenses_website` | 项目地址 | 项目地址 |
| `settings_storage_title` | 存储管理 | 存储管理 |
| `settings_storage_used` | 总计已用 | 总计已用 |
| `settings_storage_data` | 应用数据 | 应用数据 |
| `settings_storage_cache` | 缓存 | 缓存 |
| `settings_storage_clear_cache` | 清理缓存 | 清理缓存 |
| `settings_storage_cleared` | 缓存已清理 | 〔旧资源候选〕 |
| `settings_storage_open_system` | 打开系统存储设置 | 打开系统存储设置 |
| `settings_storage_note` | 这里只清理应用缓存；加密草稿、历史、模型和配对数据不会删除。 | 仅清缓存。草稿、历史、模型和配对保留。 |
| `settings_storage_calculating` | 计算中… | 计算中… |
| `settings_storage_refresh` | 刷新 | 刷新 |
| `settings_diagnostics_title` | 诊断信息 | 诊断信息 |
| `settings_diagnostics_app` | 应用 | 应用 |
| `settings_diagnostics_system` | 系统 | 〔旧资源候选〕 |
| `settings_diagnostics_connection` | 连接 | 连接 |
| `settings_diagnostics_permissions` | 权限 | 权限 |
| `settings_diagnostics_version` | 版本 | 版本 |
| `settings_diagnostics_build` | 构建 | 构建 |
| `settings_diagnostics_android` | Android | Android |
| `settings_diagnostics_device` | 设备 | 设备 |
| `settings_diagnostics_locale` | 语言环境 | 语言环境 |
| `settings_diagnostics_state` | 状态 | 状态 |
| `settings_diagnostics_target` | 目标应用 | 目标应用 |
| `settings_diagnostics_computer` | 电脑 | 电脑 |
| `settings_diagnostics_active` | 活动连接 | 活动连接 |
| `settings_diagnostics_paused` | 已暂停 | 已暂停 |
| `settings_diagnostics_storage` | 存储 | 存储 |
| `settings_diagnostics_overlay` | 悬浮窗 | 悬浮窗 |
| `settings_diagnostics_notification` | 通知 | 通知 |
| `settings_diagnostics_local_network` | 本地网络 | 本地网络 |
| `settings_diagnostics_copy` | 复制诊断信息 | 复制诊断信息 |
| `settings_diagnostics_copied` | 诊断信息已复制 | 诊断信息已复制 |
| `settings_diagnostics_open_settings` | 打开应用设置 | 打开应用设置 |
| `settings_diagnostics_yes` | 已允许 | 已允许 |
| `settings_diagnostics_no` | 未允许 | 未允许 |
| `settings_diagnostics_healthy` | 正常 | 正常 |
| `settings_diagnostics_unhealthy` | 已阻断 | 已阻断 |
| `settings_diagnostics_none` | 无 | 无 |
| `settings_diagnostics_na` | 不适用 | 〔旧资源候选〕 |
| `settings_diagnostics_true` | 是 | 是 |
| `settings_diagnostics_false` | 否 | 否 |
| `settings_storage_empty_cache` | 暂无缓存可清理 | 暂无可清理的缓存 |
| `share_text_locked` | 当前请求处理中，完成后再分享。 | 等发送请求处理完再分享。 |
| `share_text_loaded` | 已载入分享文本 | 已载入分享文本 |
| `share_text_title` | 分享的文本 | 分享的文本 |
| `share_text_conflict` | 当前草稿不为空。可以替换草稿、追加分享文本或取消。 | 选择如何将分享文本加入已有草稿。 |
| `share_text_replace` | 替换草稿 | 替换草稿 |
| `share_text_append` | 追加到草稿 | 追加到草稿 |

### 模型资源与悬浮球

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `settings_offline_model_title` | 离线模型 | 〔旧资源候选〕 |
| `settings_translation_model` | 模型 | 〔旧资源候选〕 |
| `settings_translation_file` | 文件 | 〔旧资源候选〕 |
| `settings_translation_size` | 大小 | 〔旧资源候选〕 |
| `settings_translation_version` | 版本 | 〔旧资源候选〕 |
| `settings_translation_status` | 状态 | 〔旧资源候选〕 |
| `settings_translation_incomplete` | 文件不完整 | 〔旧资源候选〕 |
| `settings_translation_redownload` | 重新下载并校验 | 〔旧资源候选〕 |
| `settings_translation_delete` | 删除模型 | 〔旧资源候选〕 |
| `settings_translation_deleting` | 正在删除模型… | 正在删除模型… |
| `settings_translation_delete_failed` | 无法删除模型文件，请重试。 | 模型删除失败，可重试。 |
| `settings_translation_delete_title` | 删除本地模型？ | 〔旧资源候选〕 |
| `settings_translation_delete_message` | 只删除当前模型文件；草稿、历史和已配对电脑都会保留。再次翻译需要重新下载。 | 〔旧资源候选〕 |
| `settings_translation_delete_confirm` | 删除 | 删除 |
| `settings_translation_switch_note` | 切换模型只影响之后的翻译；已完成的译文仍可查看和发送。 | 〔旧资源候选〕 |
| `settings_asr_model_spec` | 模型规格 | 〔旧资源候选〕 |
| `settings_asr_model_spec_summary` | 随应用打包的离线识别模型 | 〔旧资源候选〕 |
| `settings_asr_model_spec_value` | X-ASR 480ms | 〔旧资源候选〕 |
| `settings_asr_model_files` | 模型文件 | 〔旧资源候选〕 |
| `settings_asr_model_size` | 模型占用 | 〔旧资源候选〕 |
| `settings_asr_model_version` | 模型版本 | 〔旧资源候选〕 |
| `settings_asr_model_state` | 模型状态 | 〔旧资源候选〕 |
| `settings_asr_model_only_one` | 当前仅有内置 X-ASR 480ms 规格，尚未提供其他可下载规格。 | 〔旧资源候选〕 |
| `settings_asr_model_bundled` | 模型随应用打包并由构建校验，不能单独删除。 | 〔旧资源候选〕 |
| `settings_asr_model_spec_note` | 480ms 是音频分块规格，不是首字或端到端延迟。 | 〔旧资源候选〕 |
| `settings_storage_translation_models` | 翻译模型 | 〔旧资源候选〕 |
| `settings_storage_asr_model` | 离线识别模型 | 〔旧资源候选〕 |
| `settings_floating_ball_settings_summary` | 尺寸、贴边、图标、颜色与双击动作 | 外观与手势 |
| `settings_ball_preview` | 预览 | 预览 |
| `settings_ball_background` | 背景颜色 | 背景颜色 |
| `settings_ball_icon_color` | 图标颜色 | 图标颜色 |
| `settings_ball_color_offwhite` | 米白 | 米白 |
| `settings_ball_color_charcoal` | 深灰 | 深灰 |
| `settings_ball_color_brand` | 品牌蓝 | 品牌蓝 |
| `settings_ball_color_white` | 白色 | 白色 |
| `settings_ball_peek` | 贴边露出 | 贴边露出 |
| `settings_ball_peek_value` | %1$d%% · %2$d dp | %1$d%% · %2$d dp |
| `settings_ball_icon_size` | 图标大小 | 图标大小 |
| `settings_ball_icon_size_value` | %1$d%% | %1$d%% |
| `settings_ball_keep_icon` | 贴边时保持图标完整 | 贴边时保持图标完整 |
| `settings_ball_keep_icon_summary` | 仅在贴边且需要时缩小图标。 | 贴边空间不足时缩小图标。 |
| `settings_ball_preview_expanded` | 中间：展开 100% | 展开 |
| `settings_ball_preview_peek` | 右侧：露出 %1$d%% · 可见 %2$d dp · 可见度 %3$d%% | 贴边 · 露出 %1$d%%（%2$d dp）· 可见度 %3$d%% |
| `settings_ball_size` | 尺寸 | 尺寸 |
| `settings_ball_size_value` | %1$d dp | %1$d dp |
| `settings_ball_opacity` | 贴边可见度 | 贴边可见度 |
| `settings_ball_opacity_value` | %1$d%% | %1$d%% |
| `settings_ball_double` | 双击动作 | 双击动作 |
| `settings_ball_double_card` | 打开快捷说话卡 | 打开快捷卡 |
| `settings_ball_double_record` | 在快捷卡直接录音 | 打开快捷卡并录音 |
| `settings_ball_note` | 拖动可移动，单击展开，双击执行所选动作，长按打开快捷菜单。 | 拖动移动，单击展开，双击执行所选动作，长按打开菜单。 |
| `settings_ball_fullscreen_note` | 全屏视频或游戏时自动隐藏未启用：不申请无障碍或使用情况访问权限时，Android 不会向悬浮窗提供可靠的全屏信号。 | 全屏时不会自动隐藏。系统未向悬浮窗提供可靠的全屏状态。 |
| `quick_menu_open` | 打开快捷说话卡 | 打开快捷说话卡 |
| `quick_menu_pause` | 暂停电脑接收 | 暂停电脑接收 |
| `quick_menu_resume` | 恢复电脑接收 | 恢复电脑接收 |
| `quick_menu_close` | 关闭悬浮球 | 关闭悬浮球 |

### 历史筛选、导出与清理

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `history_date_all` | 不限日期 | 不限日期 |
| `history_date_today` | 今天 | 今天 |
| `history_date_week` | 7 天 | 7 天 |
| `history_date_month` | 30 天 | 30 天 |
| `history_app_all` | 全部应用 | 全部应用 |
| `history_manage` | 批量管理 | 批量管理 |
| `history_manage_cancel` | 完成 | 完成 |
| `history_select_all` | 全选 | 全选 |
| `history_select_none` | 取消全选 | 取消全选 |
| `history_delete_selected` | 删除（%1$d） | 删除（%1$d） |
| `history_export` | 导出 | 导出 |
| `history_cleanup` | 清理 | 清理 |
| `history_export_title` | 导出历史记录 | 导出历史记录 |
| `history_export_help` | 导出当前筛选的文字记录和元数据。图片文件不会嵌入；如需保留原图，请在对应记录中复制。 | 导出筛选后的文字和记录信息。原图可在详情中复制。 |
| `history_export_markdown` | Markdown 文件 | Markdown 文件 |
| `history_export_json` | JSON 文件 | JSON 文件 |
| `history_export_encrypted` | 加密备份… | 加密备份… |
| `history_export_encrypted_title` | 加密备份 | 加密备份 |
| `history_export_encrypted_help` | 备份使用你的口令加密（PBKDF2-HMAC-SHA256 + AES-256-GCM）。口令丢失后 SayIn 无法恢复。 | 用口令加密备份。丢失口令后无法恢复。 |
| `history_export_passphrase` | 口令（至少 8 个字符） | 口令（至少 8 个字符） |
| `history_export_passphrase_short` | 请使用至少 8 个字符。 | 口令至少 8 个字符。 |
| `history_export_encrypted_start` | 加密并导出 | 加密并导出 |
| `history_export_done` | 历史记录已导出 | 历史记录已导出 |
| `history_export_failed` | 导出失败，请检查目标位置后重试。 | 导出失败。检查保存位置后重试。 |
| `history_delete_selected_title` | 删除选中的记录？ | 删除选中的记录？ |
| `history_delete_multiple_help` | 仅在本机隐藏 %1$d 条记录；请求、回执和已配对电脑都不变。 | 在本机隐藏 %1$d 条记录，回执和待处理请求保留。 |
| `history_delete_selected_action` | 删除选中 | 〔旧资源候选〕 |
| `history_cleanup_title` | 清理历史 | 清理历史 |
| `history_cleanup_help` | 只清理本机历史列表；回执、请求身份和待处理操作都会保留。 | 清理本机历史列表，保留回执和待处理请求。 |
| `history_cleanup_week` | 7 天以前 | 7 天以前 |
| `history_cleanup_month` | 30 天以前 | 30 天以前 |
| `history_cleanup_quarter` | 90 天以前 | 90 天以前 |
| `history_cleanup_all` | 全部历史显示记录 | 全部可见记录 |
| `history_count` | %1$d 条 | %1$d 条 |
| `history_more` | 更多操作 | 更多操作 |
| `history_filter` | 筛选 | 筛选 |
| `history_filter_title` | 筛选记录 | 筛选记录 |
| `history_filter_help` | 状态在工具栏切换，这里只选择日期与应用。 | 日期和应用在此筛选，状态在外层工具栏切换。 |
| `history_filter_date` | 日期 | 日期 |
| `history_filter_app` | 应用 | 应用 |
| `history_filter_reset` | 重置 | 重置 |
| `history_filter_apply` | 查看 %1$d 条结果 | 查看 %1$d 条结果 |
| `history_filter_clear` | 清除 | 清除 |
| `history_filter_remove` | 移除筛选：%1$s | 移除筛选：%1$s |
| `history_clear_search` | 清空搜索 | 清空搜索 |
| `history_selected_count` | 已选 %1$d 条 | 已选 %1$d 条 |
| `history_confirm` | 确认 | 确认 |
| `history_cleanup_confirm` | 清理 | 清理 |

### 发送策略和倒计时

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `settings_send_policy` | 发送策略 | 发送策略 |
| 发送策略首页摘要 `settings_send_policy_summary` | 默认%1$s · %2$d 个应用规则 | 〔按状态显示〕 |
| `settings_policy_manual` | 手动确认 | 手动确认 |
| `settings_policy_auto` | 自动发送 | 自动发送 |
| `settings_policy_inherit` | 跟随默认 | 跟随默认 |
| `settings_send_policy_current` | 电脑当前前台 | 电脑前台应用 |
| `settings_send_policy_no_current` | 暂无前台应用 | 未获取到电脑前台应用 |
| `settings_send_policy_chip_auto` | 自动 | 自动 |
| `settings_send_policy_chip_manual` | 手动 | 手动 |
| `settings_send_policy_wait` | 等待时长 | 等待时长 |
| `settings_send_policy_seconds` | %1$d 秒 | %1$d 秒 |
| `settings_send_policy_apps` | 应用规则 | 应用规则 |
| 应用规则空态 `settings_send_policy_empty` | 连接电脑并发送一次后，这里会出现可配置的应用。 | 连接电脑后，切到目标应用即可添加规则。 |
| `settings_send_policy_clear` | 清除全部规则 | 清除全部规则 |
| `speak_listening_short` | 正在听 | 正在听 |
| `speak_send_after_delay` | %1$d 秒后发送 | %1$d 秒后发送 |
| `speak_confirm_ime` | 请先确认输入法候选词 | 先确认输入法候选词 |
| `speak_auto_target_unavailable` | 请将目标应用切到前台，再点发送 | 将目标应用切到前台后再发送 |
| `speak_auto_update_desktop` | 请更新电脑端 SayIn 以使用自动发送 | 更新电脑端 SayIn 后可用自动发送 |
| `receipt_automatic_target_changed` | 目标已变化，草稿已保留。 | 目标已变化，草稿已保留。 |
| `speak_policy_auto_short` | 自动 | 自动 |
| `speak_policy_manual_short` | 手动 | 手动 |
| `speak_policy_description` | 发送策略：%1$s | 发送策略：%1$s |
| `speak_countdown_number` | %1$d | %1$d |
| `speak_finish_auto_send` | 结束并自动发送 | 结束并自动发送 |

### AI 润色、服务、价格与统计

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `settings_polish` | AI 润色 | AI 润色 |
| `settings_polish_summary_missing` | 未配置 · 点此设置 | 未配置 |
| `settings_polish_summary_ready` | %1$s · %2$s | 〔按状态显示〕 |
| `settings_polish_summary_corrupt` | 配置损坏 · 请重新配置 | 配置无法读取 |
| `polish_title` | AI 润色 | AI 润色 |
| `polish_sheet_description` | 整理、校对、翻译或按明确选择改写当前草稿。结果不会自动发送。 | 加工草稿或选中文字。结果需手动发送。 |
| `polish_not_configured` | 请先配置 AI 服务。 | 先配置 AI 服务。 |
| `polish_open_settings` | 打开 AI 润色设置 | 打开 AI 润色设置 |
| `polish_scope` | 将处理 %1$d 个字符 | 将处理 %1$d 个字符 |
| `polish_mode_voice` | 语音整理 | 语音整理 |
| `polish_mode_voice_summary` | 去口癖、修断句、纠明确听写错误 | 去口癖、整理断句、纠正明显听写错误 |
| `polish_mode_text` | 文本校对 | 文本校对 |
| `polish_mode_text_summary` | 只改明确错误，保护代码和格式 | 修正明显错误，保留代码与格式 |
| `polish_run` | 开始 AI 润色 | 开始 AI 润色 |
| `polish_run_again` | 再次 AI 润色 | 再次 AI 润色 |
| `polish_cancel` | 取消 AI 润色 | 取消 AI 润色 |
| `polish_done` | 已润色 · 可撤销 | 已润色 · 可撤销 |
| `polish_cancelled` | 已取消 AI 润色 | 已取消 AI 润色 |
| `polish_send_note` | 润色后的草稿必须显式点发送，不会重新进入自动倒计时。 | 核对润色结果后手动发送。 |
| `polish_service` | 服务 | 〔旧资源候选〕 |
| `polish_provider` | 服务商 | 〔旧资源候选〕 |
| `polish_protocol` | 协议 | 协议 |
| `polish_base_url` | API 地址 | API 地址 |
| `polish_model` | 模型 | 模型 |
| `polish_key` | API Key | API Key |
| `polish_show_key` | 显示 Key | 显示 Key |
| `polish_hide_key` | 隐藏 Key | 隐藏 Key |
| `polish_save` | 保存 | 保存 |
| `polish_test` | 测试连接 | 测试连接 |
| `polish_clear` | 移除配置 | 〔旧资源候选〕 |
| `polish_confirm_clear` | 移除已保存的 AI 配置和 Key？ | 〔旧资源候选〕 |
| `polish_cancel_action` | 取消 | 取消 |
| `polish_remove` | 移除 | 〔旧资源候选〕 |
| AI 润色与翻译的数据去向 `polish_upload_note` | AI 润色和 AI 翻译会把当前文本发送到配置的云端模型。本地中译英仍离线运行，不上传。 | AI 润色和 AI 翻译会将待处理文字发送至所选服务。 |
| `polish_key_binding_note` | Key 绑定到当前地址、协议和鉴权方式；修改任意一项都需要重新输入 Key，仅修改模型可沿用。 | 改 API 地址、协议或鉴权方式时需重填 Key。 |
| `polish_saved` | 已保存 | 〔旧资源候选〕 |
| `polish_saving` | 正在保存… | 正在保存… |
| `polish_testing` | 正在测试… | 测试中… |
| `polish_test_ready` | 连接成功 · %1$d ms | 连接成功 · %1$d ms |
| `polish_test_failed` | 连接失败：%1$s | 连接失败：%1$s |
| `polish_config_missing` | 尚未配置 AI 服务。 | 〔旧资源候选〕 |
| `polish_config_corrupt` | 已保存的 AI 配置无法读取，请重新输入。 | 配置无法读取。重新填写服务信息。 |
| `polish_required` | 请输入 API Key。 | 填写 API Key。 |
| `polish_invalid_url` | 请输入完整的 HTTPS 地址。 | 填写完整的 HTTPS 地址。 |
| `polish_invalid_model` | 请输入模型名。 | 填写模型 ID。 |
| `polish_stats` | 本机调用统计 | 本机调用统计 |
| `polish_stats_empty` | 暂无调用。 | 所选范围内暂无调用 |
| `polish_stats_clear` | 清除统计 | 清除统计 |
| `polish_stats_tokens` | %1$s / %2$s Token | %1$s / %2$s Token |
| `polish_stats_duration` | %1$d ms | %1$d ms |
| `polish_stats_cost_unknown` | 费用：未知（未配置单价） | 费用未知 |
| `polish_stats_outcome` | 结果：%1$s | 结果：%1$s |
| `polish_error_missing` | 未配置 | 未配置 |
| `polish_error_unauthorized` | 凭据被拒绝 | 凭据被拒绝 |
| `polish_error_rate` | 请求过于频繁 | 请求过于频繁 |
| `polish_error_timeout` | 请求超时 | 请求超时 |
| `polish_error_network` | 网络不可用 | 网络不可用 |
| `polish_error_server` | 服务端错误 | 服务端错误 |
| `polish_error_invalid` | 响应格式无效 | 响应格式无效 |
| `polish_error_truncated` | 响应被截断 | 响应被截断 |
| `polish_error_refused` | 模型拒绝了请求 | 模型拒绝了请求 |
| `polish_error_too_large` | 响应过长 | 响应过长 |
| `polish_error_unknown` | 未知错误 | 未知错误 |
| `polish_provider_deepseek` | DeepSeek | DeepSeek |
| `polish_provider_qwen` | 千问 | 千问 |
| `polish_provider_openai` | OpenAI | OpenAI |
| `polish_provider_anthropic` | Claude | Claude |
| `polish_provider_custom` | 自定义 | 自定义 |
| `polish_protocol_chat` | OpenAI 兼容 | OpenAI 兼容 |
| `polish_protocol_messages` | Anthropic Messages | Anthropic Messages |
| `polish_stats_success` | 成功 | 成功 |
| `polish_stats_failure` | 失败 | 失败 |
| `polish_running` | 正在润色… | 正在润色… |
| `polish_confirm_send` | 已润色 · 需确认发送 | 已润色 · 需确认发送 |
| `polish_failed` | 润色失败：%1$s | 润色失败：%1$s |
| `polish_cancelled_short` | 已取消润色 | 已取消润色 |
| `polish_api_version` | API 版本 | API 版本 |
| `polish_stats_entry_title` | %1$s · %2$s | %1$s · %2$s |
| `polish_mode_translate` | AI 翻译 | AI 翻译 |
| `polish_mode_translate_summary` | 把草稿翻译成指定语言 | 翻译草稿或选区 |
| `polish_mode_list` | 结构化列表 | 结构化列表 |
| `polish_mode_list_summary` | 把明确的多点陈述转为有序列表 | 将多点陈述整理为有序列表 |
| `polish_mode_formal` | 正式化改写 | 正式化改写 |
| `polish_mode_formal_summary` | 改为正式书面语，不添加事实 | 改用正式书面语，保留事实 |
| `polish_mode_concise` | 精简改写 | 精简改写 |
| `polish_mode_concise_summary` | 压缩冗余表达，保留事实 | 压缩冗余表达，保留事实 |
| `polish_mode_custom` | 自定义改写 | 自定义改写 |
| `polish_mode_custom_summary` | 使用设置中保存的自定义指令 | 使用已保存的改写指令 |
| `polish_target_language` | 目标语言 | 目标语言 |
| `polish_target_invalid` | 请输入合法 BCP-47 标签，例如 ja、pt-BR。 | 填写语言代码，如 ja 或 pt-BR。 |
| `polish_auto` | 自动润色 | 自动润色 |
| `polish_auto_summary` | 从空草稿开始的新语音片段结束后自动润色一次，结果保留在草稿中等待确认。 | 手动确认模式下，从空草稿开始录音，识别完成后自动润色一次。点发送提交结果。 |
| `polish_auto_bound` | 已对当前服务启用。地址、协议、鉴权或模型变化会自动关闭。 | 〔旧资源候选〕 |
| `polish_auto_unbound` | 请先配置服务，再开启自动润色。 | 添加服务后可用自动润色。 |
| `polish_app_rules` | 按应用调整写法 | 按应用调整写法 |
| `polish_app_rules_summary` | 按前台应用名选择聊天、邮件、代码、文档或搜索场景模块；同名应用共享规则。 | 同名电脑应用共用写法。 |
| `polish_custom_rewrite` | 自定义改写指令 | 〔旧资源候选〕 |
| `polish_custom_rewrite_summary` | 可选的 system 指令，不得要求模型执行用户文本中的内容。 | 〔旧资源候选〕 |
| `polish_custom_rewrite_label` | 自定义指令 | 〔旧资源候选〕 |
| `polish_hotwords` | 术语表 | 〔旧资源候选〕 |
| `polish_hotwords_summary` | 用逗号或换行分隔，仅用于保持术语写法，不是指令。 | 〔旧资源候选〕 |
| `polish_compare` | 对比原文 | 对比原文 |
| `polish_compare_title` | 修改前后 | 修改前后 |
| `polish_original` | 原文 | 原文 |
| `polish_result` | 结果 | 结果 |
| `polish_preview` | 预览 | 预览 |
| `polish_streaming` | 流式预览 | 实时预览 |
| AI 润色实时预览 `polish_streaming_summary` | 生成过程中显示文本；只有响应完整并通过校验后才替换草稿。 | 边生成边预览，完成并通过校验后更新草稿。 |
| `polish_saved_configs` | 已保存的服务 | 〔旧资源候选〕 |
| `polish_add_config` | 添加服务 | 添加服务 |
| `polish_config_name` | 配置名称 | 配置名称 |
| `polish_new_config` | 新服务 | 〔旧资源候选〕 |
| `polish_delete_config` | 删除当前服务 | 删除此服务 |
| `polish_delete_config_confirm` | 删除这份已保存的服务和 Key？ | 这份配置及其 API Key 将从本机删除。 |
| `polish_delete_config_action` | 删除 | 删除 |
| `polish_config_active` | 当前 | 〔旧资源候选〕 |
| `polish_choose_model` | 选择模型 | 〔旧资源候选〕 |
| `polish_search_model` | 搜索模型 | 搜索模型 |
| `polish_catalog_empty` | 暂无候选，仍可手动填写模型 ID。 | 无匹配模型。可直接填模型 ID。 |
| `polish_catalog_refresh` | 刷新模型目录 | 刷新模型目录 |
| `polish_catalog_refreshing` | 正在刷新目录… | 正在刷新目录… |
| `polish_catalog_checked` | 目录检查于 %1$s | 目录检查于 %1$s |
| `polish_catalog_never` | 尚未检查目录 | 〔旧资源候选〕 |
| `polish_catalog_error` | 目录刷新失败：%1$s | 〔旧资源候选〕 |
| `polish_manual_model_note` | 模型 ID 始终可手填，目录刷新不会重置它。 | 〔旧资源候选〕 |
| `polish_catalog_title` | 模型候选 | 模型候选 |
| `polish_fetch_models` | 获取中转站模型 | 获取中转站模型 |
| `polish_fetching_models` | 正在获取中转站模型… | 正在获取中转站模型… |
| `polish_gateway_models_error` | 模型列表失败：%1$s | 〔旧资源候选〕 |
| `polish_stats_cost_estimated` | 费用：估算 %1$s %2$s | 预估费用：%1$s %2$s |
| `polish_stats_cost_reported` | 费用：服务上报 %1$s %2$s | 服务商上报费用：%1$s %2$s |
| `polish_usage_today` | 今日 | 今日 |
| `polish_usage_7d` | 7 天 | 7 天 |
| `polish_usage_30d` | 30 天 | 30 天 |
| `polish_usage_summary` | %1$d 次调用 · %2$s Token · 中位耗时 %3$d ms | %1$d 次调用 · %2$s Token · 中位 %3$d ms |
| `polish_usage_unknown_cost` | 未知费用：%1$d | %1$d 次费用未知 |
| `polish_usage_unknown_tokens` | ? | 未知 |
| `polish_usage_purpose_polish` | 润色 | 润色 |
| `polish_usage_purpose_translation` | 翻译 | 翻译 |
| `polish_usage_purpose_test` | 连接测试 | 连接测试 |
| `polish_usage_all` | 全部 | 全部 |
| `polish_usage_cost_total` | 费用：%1$s %2$s | 〔旧资源候选〕 |
| `polish_usage_group_title` | 按服务与模型 | 〔旧资源候选〕 |
| `polish_usage_group_line` | %1$s · %2$s：%3$d 次 · %4$s Token | 〔旧资源候选〕 |
| `polish_stats_unsent` | 未发送 | 未发送 |
| `polish_select_service` | 选择服务 | 选择服务 |
| `polish_services` | 管理服务 | 管理服务 |
| `polish_save_failed` | 无法保存服务设置 | 无法保存服务设置 |
| `polish_translation_language` | AI 翻译目标语言 | 〔旧资源候选〕 |
| `polish_usage_amount` | %1$s · %2$s %3$s | 〔旧资源候选〕 |
| `polish_usage_period` | 统计周期 | 〔旧资源候选〕 |
| `polish_usage_purpose` | 用途 | 用途 |
| `polish_usage_service` | 服务 | 服务 |
| `polish_usage_load_failed` | 无法读取用量 | 用量读取失败 |
| `polish_usage_loading` | 正在读取用量… | 读取用量中… |
| `polish_usage_previous` | 上一页 | 上一页 |
| `polish_usage_next` | 下一页 | 下一页 |
| `polish_stats_clear_summary` | 删除此手机上的全部 AI 用量记录？ | 删除本机全部 AI 调用记录。 |
| `polish_catalog_refresh_failed` | 无法刷新模型目录，仍可使用当前模型。 | 模型目录更新失败，已选模型仍可使用。 |
| `polish_models_refresh_failed` | 无法获取该服务的模型列表。 | 模型列表读取失败，可手填 ID。 |
| `polish_use_model_id` | 使用 %1$s | 使用 %1$s |
| `polish_auto_confirm_title` | 开启自动润色？ | 开启自动润色？ |
| `polish_auto_confirm_summary` | 即使语音识别在本机运行，符合条件的新语音片段文本也会上传到所选 AI 服务进行润色。 | 符合条件的转写文字会发往所选 AI 服务，离线识别也适用。 |
| `polish_reload_config` | 重新载入已保存的服务 | 重新载入已保存的服务 |
| `polish_test_last_success` | 上次测试成功 · %1$d ms | 上次测试通过 · %1$d ms |
| `polish_test_last_failure` | 上次测试失败 · %1$s | 上次测试失败 · %1$s |
| `polish_test_idle` | 当前配置尚未测试。 | 未测试 |
| `polish_advanced` | 高级 | 高级 |
| `polish_model_price` | 模型价格 | 模型价格 |
| `polish_price_reference` | 参考价 | 参考价 |
| `polish_price_input` | 输入 | 输入 |
| `polish_price_output` | 输出 | 输出 |
| `polish_price_cache_read` | 缓存读 | 缓存读 |
| `polish_price_cache_write` | 缓存写 | 缓存写 |
| `polish_price_per_million` | %1$s / 百万 Token | %1$s / 百万 Token |
| `polish_price_unknown` | 未提供单价 | 暂无参考价 |
| `polish_price_tiers` | 上下文档位 | 上下文档位 |
| `polish_price_schedule` | 时段 | 时段 |
| `polish_price_catalog` | 目录 %1$s | 目录 %1$s |
| `polish_currency` | 币种 | 币种 |
| `polish_currency_usd` | USD | USD |
| `polish_currency_cny` | CNY | CNY |
| `polish_exchange_source` | 汇率：%1$s · %2$s | 换算汇率：%1$s · %2$s |
| `polish_exchange_failed` | 人民币汇率不可用 | 暂无人民币换算汇率 |
| `polish_price_manual` | 手填模型或中转站模型没有官方目录价。 | 模型目录暂无此服务的参考价。 |
| `polish_price_tier_threshold` | > %1$d Token | > %1$d Token |
| `polish_price_schedule_line` | %1$s · %2$d 个时段 | %1$s · %2$d 个时段 |
| `polish_price_missing` | ? | 〔旧资源候选〕 |
| `polish_price_candidate_line` | %1$s / %2$s | %1$s / %2$s |
| `polish_app_rules_title` | 应用写法 | 应用写法 |
| `polish_app_current` | 电脑当前前台 | 电脑前台应用 |
| `polish_app_none` | 未连接电脑时没有前台信息；已保存规则仍可编辑。 | 暂无电脑前台应用。已保存规则仍可编辑。 |
| `polish_app_add` | 添加应用 | 添加应用 |
| `polish_app_name` | 应用显示名 | 应用显示名 |
| `polish_app_duplicate` | 该应用已有规则。 | 该应用已有规则。 |
| `polish_app_auto` | 自动匹配 | 〔旧资源候选〕 |
| `polish_app_general` | 通用 | 〔旧资源候选〕 |
| `polish_terms_title` | 术语表 | 术语表 |
| `polish_terms_format` | 每行一项：源词=写法 | 每行填「原词=指定写法」 |
| `polish_terms_invalid` | 请检查每行：最多 20 行、每行 60 字符，并使用源词=写法格式。 | 用「原词=指定写法」格式。最多 20 行，每行最多 60 字符。 |
| `polish_prompt_title` | 补充要求 | 补充要求 |
| `polish_prompt_summary` | 单行，最多 200 字符；清空即移除。 | 单行，最多 200 字符。清空即移除。 |
| `polish_prompt_invalid` | 请使用单行、无控制字符、最多 200 字符。 | 限单行、200 字符以内，且不含控制字符。 |
| `polish_text_preferences` | 文本偏好 | 〔旧资源候选〕 |
| `polish_scene_auto` | 自动匹配 | 自动匹配 |
| `polish_scene_general` | 通用 | 通用 |
| `polish_scene_chat` | 聊天 | 聊天 |
| `polish_scene_email` | 邮件 | 邮件 |
| `polish_scene_code` | 代码／终端 | 代码／终端 |
| `polish_scene_docs` | 文档 | 文档 |
| `polish_scene_search` | 搜索 | 搜索 |
| `polish_usage_calls` | 调用次数 | 调用次数 |
| `polish_usage_token_label` | Token | Token |
| `polish_usage_median` | 中位耗时 | 中位耗时 |
| `polish_usage_details` | 调用明细 | 调用明细 |
| `polish_usage_filter` | 筛选 | 筛选 |
| `polish_usage_filter_count` | 筛选（%1$d） | 筛选（%1$d） |
| `polish_usage_apply_filters` | 应用 | 应用 |
| `polish_usage_reset_filters` | 重置 | 重置 |
| `polish_translate_style` | 翻译风格 | 翻译风格 |
| `polish_style_auto` | 自动 | 自动 |
| `polish_style_chat` | 日常聊天 | 日常聊天 |
| `polish_style_formal` | 正式书面 | 正式书面 |
| `polish_style_tech` | 技术文档 | 技术文档 |
| `polish_usage_request_model` | 请求模型：%1$s | 请求模型：%1$s |
| `polish_usage_response_model` | 响应模型：%1$s | 响应模型：%1$s |
| `polish_usage_cache` | 缓存读/写：%1$s / %2$s | 缓存读/写：%1$s / %2$s |
| `polish_usage_reasoning` | 推理 Token：%1$s | 推理 Token：%1$s |
| `polish_usage_price_source` | 价格来源：%1$s | 价格来源：%1$s |
| `polish_usage_detail_hint` | 点按明细行查看请求详情 | 〔旧资源候选〕 |

### 千问与云端服务

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `cloud_qwen` | 千问 | 千问 |
| `cloud_qwen_workspace` | 北京业务空间 ID | 北京业务空间 ID |
| `cloud_qwen_api_key` | 百炼 API Key | 百炼 API Key |
| `settings_cloud_provider_summary` | %1$s · %2$s | 〔旧资源候选〕 |
| `cloud_streaming` | 实时语音识别 | 实时出字 |
| `cloud_reset_all_summary` | 要重置所有云端识别的凭据和热词，以恢复加密配置吗？ | 清除所有云端识别服务的凭据和热词。 |
| `cloud_error_doubao_access_denied` | 访问被拒绝，请检查已配置的项目或应用是否启用了豆包流式语音识别 2.0 小时版。 | 确认豆包项目已开通流式语音识别 2.0 小时版。 |
| `cloud_error_qwen_access_denied` | 请在百炼中为此北京业务空间开通千问 Audio 3.1 流式识别，并确认 API Key 有调用权限。 | 检查北京业务空间是否已开通千问流式识别，以及 Key 的调用权限。 |

### 本地模型管理

| 配置项 | 原始说明文本 | 优化后说明文本 |
|---|---|---|
| `local_model_title` | 本地模型 | 本地模型 |
| `local_model_direction` | 翻译方向 | 翻译方向 |
| `local_model_zh_en` | 简体中文 → 英语 | 简体中文 → 英语 |
| `local_model_switch` | 更换模型 | 更换模型 |
| `local_model_import` | 导入模型文件夹 | 导入模型文件夹 |
| 模型文件夹选择 `local_model_folder_hint` | 选择单个模型文件夹，或存放多个模型的上一级文件夹。 | 可选模型文件夹，或存放多个模型的上一级文件夹。 |
| `local_model_selected` | 已选用 | 已选用 |
| `local_model_recommended` | 推荐 | 推荐 |
| `local_model_hf` | HF 原站 | HF 原站 |
| `local_model_hf_mirror` | HF 镜像 | HF 镜像 |
| `local_model_scope` | ModelScope | ModelScope |
| `local_model_browser_missing` | 找不到可打开此链接的浏览器。 | 没有可打开链接的浏览器。 |
| `local_model_github_archive` | GitHub | GitHub |
| `local_model_files_count` | 模型文件（%1$d 个） | 所需文件（%1$d 个） |
| `local_model_choose` | 选择模型 | 选择模型 |
| `local_model_meta` | %1$s · %2$s | %1$s · %2$s |
| `local_model_total` | 模型占用 | 模型占用 |
| `local_model_legend` | %1$s %2$s | %1$s %2$s |
| `settings_language` | 语言 | 语言 |
| `settings_local_recognition_model` | 本地识别模型 | 本地识别模型 |
| `settings_local_translation_model` | 本地翻译模型 | 本地翻译模型 |
| `settings_translation_private` | 译文在本机生成 | 译文在本机生成 |
| `local_model_none` | 暂无已导入模型 | 尚未导入模型 |
| `local_model_delete` | 删除本地模型 | 删除本地模型 |
| `local_model_delete_summary` | 删除 SayIn 内的 %1$s 副本？ | 移除 SayIn 内的 %1$s 副本，保留源文件。 |
| `local_model_scan_title` | 文件夹扫描结果 | 文件夹扫描结果 |
| `local_model_unsupported` | 未找到受支持的模型文件。 | 文件夹内没有受支持的模型。 |
| `local_model_already_installed` | 已安装，跳过 | 已导入，跳过 |
| `local_model_duplicate` | 发现重复，只导入一份 | 重复文件夹，仅导入一份 |
| `local_model_invalid_files` | 缺少文件或文件版本不符 | 缺少文件或文件大小不符 |
| `local_model_choose_again` | 重选文件夹 | 重选文件夹 |
| `local_model_scanning` | 正在扫描模型文件夹… | 扫描文件夹… |
| `local_model_copying` | 正在导入 %1$s… | 正在导入 %1$s… |
| `local_model_read_error` | 无法读取此文件夹。请尝试直接选择模型文件夹。 | 文件夹无法读取。直接选择模型文件夹重试。 |
| `local_model_space_error` | 存储空间不足。请腾出空间后重试。 | 存储空间不足。腾出空间后重试。 |
| `local_model_integrity_error` | 文件校验失败。请核对版本并重新下载。 | 校验失败。核对版本后重新下载。 |
| `local_model_copy_error` | 导入失败，请重选文件夹后重试。 | 导入失败。重新选取模型文件夹。 |
| `local_model_asr_missing` | 导入模型后即可使用离线识别。 | 导入模型后可用离线识别。 |
| `local_model_recording_limit` | 此模型单次录音最多支持 2 分钟。 | 单次录音最多 2 分钟。 |
| `local_model_xasr` | 中英流式识别，边说边出字，支持标点。 | 中英实时出字，支持标点。 |
| `local_model_qwen` | 支持多语言和中文方言，停录后出字。 | 支持多语言与中文方言，停录后出字。 |
| `local_model_fun` | 支持中英日，停录后出字。 | 识别中文、英语和日语，停录后出字。 |
| `local_model_r2t2` | 流式识别，支持中英混说。 | 支持中英混说，录音时可预览。 |
| `local_model_plamo` | 本地中译英。 | 〔移除重复的中译英说明〕 |
| `local_model_hy` | 适合日常草稿和技术文本的中译英。 | 适合日常草稿和技术文本。 |
| `local_model_get` | 获取模型 | 获取模型 |
| `local_model_unselected` | 尚未选择模型 | 尚未选择模型 |
| `local_model_import_short` | 导入文件夹 | 导入文件夹 |
| `local_model_not_imported` | 未导入 | 未导入 |
| `local_model_imported` | 已导入 | 已导入 |
| `local_model_sources` | 下载来源 | 下载来源 |
| `local_model_archive_file` | 模型压缩包 | 压缩包 |
| `local_model_gguf_file` | GGUF 模型文件 | GGUF 文件 |
| `local_model_file_list` | 模型文件列表 | 文件列表 |
| `local_model_extract_hint` | 解压压缩包后，选择包含所需文件的文件夹。 | 解压后，选择含所需文件的文件夹。 |
| `local_model_mode_translation` | 中译英 | 中译英 |
| `local_model_mode_live` | 实时出字 | 实时出字 |
| `local_model_mode_preview` | 实时预览 | 实时预览 |
| `local_model_mode_batch` | 停录后出字 | 停录后出字 |
| `local_model_copy_verify` | 正在复制并校验文件 | 正在复制并校验 |
| `local_model_verifying` | 正在完成文件校验 | 正在校验文件 |
| `local_model_publishing` | 正在保存模型 | 正在保存模型 |
| `local_model_operation_failed` | 模型操作失败 | 操作失败 |
| `local_model_close_result` | 关闭 | 关闭 |
| `local_model_progress_bytes` | %1$s / %2$s | %1$s / %2$s |
| `local_model_storage_count` | 模型占用 · %1$d 个 | 模型占用 · %1$d 个 |
| `local_model_import_complete` | 已导入 %1$d 个模型 | 已导入 %1$d 个模型 |
| `local_model_import_count` | 导入 %1$d 个模型 · %2$s | 导入 %1$d 个模型 · %2$s |
