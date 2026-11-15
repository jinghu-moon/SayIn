# Android 剪贴板与常用语面板实施记录

状态：原生已实现并通过本阶段 Android 回归。日期：2026-11-15。设计依据 [52 组合面板设计](52-android-clipboard-and-quick-phrases-design.md)；评审原型 `refer/SayIn 底部控制区对比.html` 与 [组合面板 demo](demos/sayin-input-assist.html)。

## 1. 交付范围

用户在说话页底部工具行点击剪贴板图标（Tabler `clipboard-text`，位于图片图标左侧）打开同一个组合面板：剪贴板标签展示一次用户授权的当前系统剪贴板读取，常用语标签展示应用自己的加密短语库。两者都通过唯一 `SpeakSession` 插入当前草稿，完整页与快捷卡行为一致。

本阶段不包含：后台剪贴板采集、剪贴板历史、电脑剪贴板同步、云同步、模板变量、分组/搜索、长按发送。剪贴板仍然只是“当前内容”，不是系统剪贴板历史。

## 2. 代码结构

| 责任 | 落点 |
| --- | --- |
| 插入票据、表示层修订、单事务撤销 | `ui/DraftAssist.kt`（纯规则）与 `ui/SpeakSession.kt`（唯一草稿所有者） |
| 当前剪贴板一次读取 | `data/ClipboardText.kt`（纯分类）与 `data/AndroidClipboard.kt`（平台适配） |
| 短语库 | `data/PhraseLibrary.kt`（规则/编解码/串行所有者）、`data/PhraseStore.kt`（Keystore AES-GCM + AtomicFile）、`SayInApplication.phrases` |
| 面板与入口 | `ui/assist/AssistPanel.kt`、`ui/SpeakPage.kt`、`ui/VoiceLinkScreens.kt`、`ui/VoiceLinkApp.kt` |
| 图标 | `android/design/tabler/{clipboard-text,plus,arrow-left,grip-vertical,photo}.svg` + 生成脚本与 `TablerVectors.kt` |
| 文案 | `res/values/strings.xml`（英文）与 `res/values-b+zh+Hans/strings.xml`（简体中文），各 82 个新增键 |

### 2.1 插入事务

- 打开面板时 `SpeakSession.beginAssistInsertion()` 冻结 `draftId + 表示（原文/译文）+ 表示层修订 + 选区 + epoch`；无有效编辑位置时在打开时确定末尾，不在提交时猜测。选区为 Kotlin UTF-16 下标，反向规范化，代理对中间位置拒绝。
- 原文修订使用 `interaction.version`，译文使用独立的单调 `translationRevision`；译文编辑虽然不推进原文 version，也会使旧票据失效。
- `insertAssist()` 依次校验：当前可编辑 → epoch/draftId/表示/修订/选区一致 → 完整结果通过协议一致的 `normalizedText`（4000 码点/16384 字节、控制字符、代理对）。任何不满足都整次拒绝，不截断、不追加。
- 成功时取消自动发送与结束发送票据、清可编辑终态的 `submitted`，以 `breakGroup → record → breakGroup` 形成唯一历史步；原文插入刷新 `draftId`、使旧译文失效，译文插入保留原文与译文来源；不重放、不释放、不恢复暂停。
- 编辑、撤销/重做、录音开始/结束、来源切换、失焦、提交状态变化、翻译任何变化、完成输入、清空、新草稿都会推进 epoch。

### 2.2 剪贴板

- 只在面板可见、窗口获得输入焦点且软键盘收起后读取一次；切回剪贴板标签、显式“重试”各一次；不注册监听、不轮询、不在启动/后台读取。
- 只读取首项显式 `Item.text`，不调用 `coerceToText()`、不解析 URI；先检查 64000 个 UTF-16 单元再复制第二份业务缓冲区。Android 13+ 敏感标记遮住正文，必须显式“显示”后才能插入，且不提供“保存为常用语”。
- 无可读内容、只有图片/URI/Intent、超长、非法字符/Unicode、内容变化各有独立提示；`清除全部` 在确认后重新读取并比对快照才清除，期间内容变化则拒绝并提示重新确认。

### 2.3 常用语

- 单条只保存正文，最多 100 条，每条 4000 码点/16384 字节，总计不超过 2 MiB；顺序就是持久顺序，不额外建排序表、分组、计数或索引。
- 写入串行化在库自己的 IO 调度器上，保存成功后才发布新修订；`reorder/clearAll` 绑定库 revision，旧确认不能覆盖新库。重复判断把 CRLF/CR 统一为 LF 后逐字符比较。
- 加密文件位于 `noBackupFilesDir/phrases.bin`，alias `sayin.phrases.v1`、AAD `SayIn/phrases/v1`；有文件无密钥、解密失败或结构损坏时拒绝读取且不覆盖。
- 增删改、6 秒单条撤销、确认后清空、拖拽/键盘排序都经过同一所有者；正文不写日志、不写入 SharedPreferences。

### 2.4 面板与页面

- Miuix `OverlayBottomSheet`（26dp 圆角、8dp 外边距、55%–60% 可用高度、内部列表滚动、固定底部按钮）；清除/放弃确认使用同面板之上的全窗口 `Dialog`，确认期间面板不可操作，默认焦点在安全按钮。
- 入口与关闭把系统无障碍/输入焦点返回入口；Miuix 浮层在独立窗口渲染，返回焦点需要跨若干帧重试。
- 打开面板先记录票据、撤销自动发送、清焦点并收起键盘；识别设置、连接面板、图片面板与本面板互斥，页面切换、入口形态切换、Activity 停止都会关闭并失效。
- 软键盘打开时由 Miuix 浮层窗口自行收缩到键盘之上（不再由页面传入 IME inset 重复预留），编辑器使用剩余高度并保证保存按钮在键盘上方；`editorSaveStaysAboveTheKeyboard` 设备测试断言按钮底边在键盘顶边之上。
- 320dp/大字号：五个必需工具始终 48dp；字数/原文译文切换独占一行，可选“规范化预览”开关在宽度不足 360dp 时进入“更多”菜单；极小高度（工具行收起）时“更多”菜单提供同一组合入口。

## 3. 与设计文本的差异

1. `清除全部` 剪贴板路径在确认窗口关闭、焦点恢复后才读取并比对，再清除；确认窗口内读取会被平台拒绝（同应用另一窗口不算可读焦点）。行为仍满足“确认期间变化则拒绝”。
2. 敏感剪贴板条目使用与普通条目相同的 ⋮ 菜单，仅移除“保存为常用语”；复制/粘贴仍可用。
3. 窄宽度下“规范化预览”开关进入更多菜单（设计只要求 5 个工具不被压缩，未指定该可选开关位置）。
4. 极窄高度下“更多”更早可用（可用高度 ≥120dp 即显示），否则工具行收起后没有入口。
5. 列表行默认单行预览（长内容通过 ⋮ →“查看全文”），行高 48dp；剪贴板与常用语一致。
6. 行菜单改用与页面右上角“更多”相同的 Miuix `WindowIconDropdownMenu`，不再自绘浮层，动效与页面菜单一致；菜单项为文本节点，测试按文案点击。
7. 面板高度按实际可用高度取比例：列表 58%、编辑器 94%、全文查看 90%。Miuix 浮层窗口本身已经缩到键盘之上，不再叠加页面的 IME 高度；编辑器输入框用剩余空间撑满，保存按钮固定在键盘上方。
8. 底部“清除全部”改为可见的 surface 药丸按钮并保留 16dp 底部内边距，不再贴着面板/导航栏边缘。

## 4. 本轮设备验证发现并修复的问题

1. 选中标签的下划线使用 `fillMaxWidth()`，把选中标签撑满整行并把另一个标签挤成 0dp，点击“常用语”实际点到关闭按钮导致面板消失。改为 `drawBehind` 绘制下划线，标签内容决定宽度。
2. 剪贴板读取用面板自身的 `View.hasWindowFocus()` 判断焦点，但 Miuix 浮层内容位于非聚焦覆盖窗口，导致每次打开都显示“无法读取剪贴板”。改为以 Activity 窗口焦点为准，并等待软键盘不再可见。
3. 确认窗口内读取剪贴板永远 `Unavailable`，导致清除始终被拒绝。改为确认后延迟到焦点恢复再校验快照并清除；确认回调不再携带无效的焦点布尔值。
4. 3 个字的常用语保存返回 `TOO_LONG`：`PhraseCodec` 对测试种子里的非 UUID id 抛出 `IllegalArgumentException`，被 `commitIfItFits` 误当成容量超限。新增专用 `PhraseLibraryOverflow`，只有容量预算映射为 `TOO_LONG`，结构错误不再被掩盖。
5. 确认框“默认焦点在取消”无法实现：Miuix `Button` 是 clickable 节点，触摸模式下不接受输入焦点。给安全按钮加显式 `focusable()` 与有界重试；面板入口返回焦点同理，并跨浮层拆除重试。
6. 编辑器保存按钮被软键盘完全遮住（真机截图确认）。浮层窗口收不到 IME inset，改为由 `VoiceLinkContent` 在 Scaffold 消费前读取原始 IME 高度传给面板，面板高度与底部内边距同时预留该高度；新增设备测试断言保存按钮底边在键盘顶边之上。
7. 极窄高度既没有工具行也没有“更多”入口。改为工具行收起时菜单也提供组合入口，并放宽“更多”出现条件到可用高度 120dp。
9. 首次真机确认后发现的面板细节：列表行改为单行；“清除全部”按钮与面板背景同色、底部内边距过小，看起来像在页面外；自绘行菜单与页面 Miuix 菜单动效不一致；编辑器把页面 IME 高度重复预留在浮层内，导致保存按钮被顶到顶部、输入框只剩一行。分别通过单行预览 + 药丸按钮与 16dp 内边距 + Miuix 菜单 + 去掉重复 IME 预留并让编辑区占满修复；均已用真机截图复核。
8. 测试基底：种子短语 id 改为合法 UUID；编辑器流程等待键盘与布局稳定；覆盖窗口的合成触摸不稳定处改用同一语义动作，几何可达性由专门测试断言。

## 5. 验证

### 5.1 修改前基线

Android 应用 JVM 58 项、translation 1 项通过；Debug 构建与 i18n 通过；工作区干净（阶段 3 开始时）。

### 5.2 修改后实际执行

| 检查 | 实际结果 |
| --- | --- |
| Android JVM | 应用 85 项、translation 1 项，失败/错误/跳过均为 0 |
| Debug 构建与 Lint | `assembleDebug`、`lintDebug` 通过；现有弃用提示不当作失败 |
| 双语键/占位符 | `python scripts/check_i18n.py` 通过（en、zh-Hans 完整且占位符一致） |
| 图标生成 | `python scripts/generate_tabler_icons.py` 生成 31 个向量，二次运行无差异 |
| 隔离 imageTest 构建 | `assembleImageTest`、`assembleImageTestAndroidTest` 通过 |
| Android 真机回归 | Redmi K40 / API 33：常规 93 项 + 独立身份图片 4 项，共 97 项通过；文字 fixture 恰好 2 次 fake 输入，图片 fixture 1 次，无重放 |
| 桌面回归 | `cargo test --workspace --all-targets --locked` 通过（未修改桌面代码，仅作既有回归） |

新增设备测试：插入事务 11 项、短语/剪贴板存储与真实系统剪贴板 4 项、面板 UI 25 项；新增 JVM：`DraftAssistTest` 8 项、`PhraseLibraryTest` 15 项、`ClipboardTextTest` 4 项。

执行命令（仓库根目录）：

```powershell
./scripts/gradle.ps1 :app:testDebugUnitTest :translation:testDebugUnitTest :app:assembleDebug :app:lintDebug
./scripts/gradle.ps1 -Psayin.instrumentationBuildType=imageTest :app:assembleImageTest :app:assembleImageTestAndroidTest
./scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -FullSuite -UseExistingRootInstall
python scripts/check_i18n.py
python scripts/generate_tabler_icons.py
```

### 5.3 关键行为对比

| 项目 | 修改前 | 修改后 | 结论 |
| --- | --- | --- | --- |
| 草稿编辑 | 只有键入/录音/翻译/清空 | 增加剪贴板与常用语插入，各为独立历史步 | 撤销/重做、取消录音、翻译失效规则保持 |
| 系统剪贴板 | 仅“复制草稿”写入 | 增加一次授权读取与确认后清除 | 无监听、无历史、无后台读取 |
| 常用语 | 不存在 | 加密原子库、100 条上限、排序与批量清除 | 坏库拒绝覆盖，写入串行 |
| 页面布局 | 4 个底部工具 | 5 个 48dp 工具 + 窄屏分行/更多回退 | 原语音栏、发送与紧凑布局回归通过 |
| 电脑协议 | — | 未改变 | 电脑只接收最终文字，不感知来源 |

## 6. 未覆盖与风险

- 连续在其它应用复制多条内容后回到 SayIn，只能读到**最后一条**系统剪贴板。这是 Android 10+ 的平台限制（仅默认输入法或当前焦点应用可读），不是本实现遗漏。用户于 2026-11-15 明确确认暂不做剪贴板历史：保留“只展示当前剪贴板一条”的方案，不引入无障碍服务、Shizuku/root 或输入法身份采集；本应用自身产生的复制仍按原“复制草稿”路径工作。
- 拖拽排序的真机手感（长按拖动、持续边缘自动滚动）只由自动化 90dp 拖动与边界断言覆盖，未做长时间人工拖拽验收。
- 自动化测试修改并恢复设备系统剪贴板；只有隔离测试包，不触碰日常包。
- 剪贴板清除比对基于 `ClipboardRead` 快照，两个不同的非文本内容（图片 A→B）都归类为 `NoText`，无法区分差异；文本与敏感/多项目标志可区分。
- 真实输入法合成、TalkBack 完整走查、极长文本滚动与 RTL（当前只提供 en/zh-Hans）留待后续人工验收。
- 面板顶部在编辑器模式接近状态栏，Miuix 自身绘制的拖拽手柄可见；未额外加入状态栏安全区内边距。
