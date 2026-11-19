# Android 发送策略设置与首页接入

日期：2026-11-18。范围：设置页“按目标应用设置发送策略”，以及首页目标应用标签和发送圆环倒计时。

原型：[sayin-send-policy-demo.html](../refer/sayin-send-policy-demo.html)（本地参考，不纳入 Git）。

## 1. 入口与界面

设置根页“语音与输入”分组新增“发送策略”，位于“默认自动发送”与“翻译”之间：

- “默认自动发送”沿用原有全局偏好，并继续允许应用规则覆盖。
- “发送策略”摘要显示当前默认与规则数量，例如“默认自动发送 · 1 个应用规则”。

发送策略页复用 Miuix 组件：

- 页头：`SmallTopAppBar` + 返回。
- 当前电脑前台：`Card`，应用图标、名称、当前生效方式。
- 默认策略：`TabRowWithContour`，手动确认 / 自动发送。
- 应用规则：`Card` 列表，图标、名称、当前生效方式、规则标签、箭头。
- 编辑规则：`OverlayBottomSheet` + `RadioButton` + `Button`，选项为跟随默认 / 自动发送 / 手动确认。
- 清除全部规则：页内 `TextButton`，仅在有规则时出现。

应用候选来自当前电脑前台、历史快照中的目标应用，以及已存在规则的名称；不要求用户手填进程名。没有记录时显示空状态。

## 2. 数据与语义

新增 `AppSendPolicy`：

```kotlin
internal enum class AppSendPolicy { Inherit, Automatic, Manual }
```

持久化仍在现有 `presentation` SharedPreferences：

- 全局默认继续使用 `auto_send`（`SpeakSendMode`）。
- 应用规则保存在 `app_send_policies` JSON，只写入 `Automatic` / `Manual`；`Inherit` 即删除该应用键。
- 启动时只恢复合法枚举；坏 JSON 视为无规则，不阻止会话启动。
- 规则优先于默认；没有规则（或应用未知）回落到全局默认。

发送行为：

- 录音开始时绑定生效策略、完整接收范围和前台应用观测。键盘点击发送时绑定当时的目标，组合文字上屏后才建立发送倒计时。
- 手机观察到前台应用或接收范围变化时撤销本轮。Outbox 中的自动文本通过 `auto_text` 携带原应用显示名称。Windows 输入线程在执行前核对预检目标的显示名称，再走原生窗口身份复查和输入闸门。目标不匹配以 `automatic_target_changed` 终结，手机保留草稿。
- 现有应用规则以显示名称为键，可能合并同名应用。通用前台输入仍有 Win32 校验与提交之间的竞态。

## 3. 首页接入

首页布局与输入区不变，只增加与发送策略相关的可见反馈：

- 目标应用行名称后增加短标签：`自动` / `手动`，来自当前前台应用的生效规则；连接断开、应用未知时不显示。
- 文本选区或 3600 字上限提示出现时隐藏该标签，优先保留原有字符数信息。
- 自动发送在发送按钮上显示圆环：
  - 自动策略下，点按开始录音，收到约 200ms 的有声 PCM 和识别文字后，检测连续 1.5 秒低音量自动结束录音。长按只由松手或主动停止结束。静音判定使用采样数，本地和云端共用录音入口；
  - 倒计时从最终识别文字定稿开始，保留完整 3 秒。键盘输入由发送箭头触发，有候选时先等待上屏；
  - 箭头/发送图标位置显示剩余秒数；
  - 圆环按 deadline 连续递减（约 40ms 更新）；
  - 点击圆环取消自动发送，不会直接发送；
  - 倒计时期间点击测试语义仍为 `cancel-auto-send`，结束后恢复 `send-draft`；
  - 倒计时期间语音栏只在发送圆环内显示剩余秒数，中间留空。
- 手动策略下，键盘点击发送直接提交，录音中点击发送在 final 到达后提交。
- 低音量判停采用 PCM RMS，阈值需要在真实噪声和不同说话音量下继续调校。

## 4. 验证

```powershell
python scripts/check_i18n.py
scripts/gradle.ps1 :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --offline
scripts/gradle.ps1 :app:lintDebug :app:assembleRelease --offline
```

设备回归使用隔离包 `seeyuer.sayin.imagetest`：

```powershell
adb -s dc39c31d shell am instrument -w -r -e class seeyuer.sayin.ui.MobileShellInstrumentedTest seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`MobileShellInstrumentedTest` 24 项通过；`UnifiedSpeakInstrumentedTest` 12 项通过；`SpeakInformationInstrumentedTest` 1 项通过；JVM 单元测试通过；i18n 通过；lint 0 errors（172 warnings）；release 构建通过。

真机/仪器化验证：

- 设置根页显示“发送策略 · 默认自动发送 · 0/1 个应用规则”。
- 策略页显示当前前台 DeepSeek Harness、默认策略、历史应用列表。
- 设置 DeepSeek 为自动/手动后，当前卡片和行内“当前将自动发送 / 当前等待手动发送”即时变化。
- 清除全部规则后恢复全部“跟随默认”。
- 重启进程后规则数量仍保留。
- 首页目标应用行显示“自动/手动”短标签（`foreground-policy`）。
- 自动倒计时时发送按钮显示圆环和剩余秒数，中心显示“N 秒后自动发送”；断开连接取消并移除圆环。

截图：

- `refer/send-policy-settings-root.png`
- `refer/send-policy-settings-page.png`
- `refer/send-policy-settings-sheet.png`
- `refer/home-policy-word.png`
- `refer/home-countdown-ring.png`

### 倒计时起点回归（2026-11-19）

| 输入 | 修改前 | 修改后 |
| --- | --- | --- |
| 点按录音后停说 | 保持录音，直到主动停止 | 静音判停，final 后倒计时 |
| 长按录音 | 松手、final 后进入自动倒计时 | 录音时静音不会提前结束，松手、final 后进入倒计时 |
| 使用键盘的自动策略 | 发送箭头直接提交 | 发送箭头等待 IME 上屏，随后倒计时 |
| 切换电脑应用 | 倒计时只校验连接接收范围 | 手机观察到目标变化时撤销，Windows 执行前核对名称 |

- Android `testDebugUnitTest` 116 项通过，`lintDebug` 0 errors，Debug 构建和双语资源检查通过。
- Redmi K40 的隔离 `imageTest` 执行 `scripts/verify_android_transport.ps1 -FullSuite`，136 项文字/界面及 4 项图片测试通过，假输入次数分别为 2 和 1。新增用例覆盖点按、长按、final、IME 上屏、取消、前台变化和到期只提交一次。
- Windows 工作区 `cargo test --workspace --all-targets --locked`、Clippy `-D warnings`、格式、构建及 Yue 依赖检查通过。输入线程测试覆盖匹配目标执行、切换到不同应用时零输入。
- 当前未做真实麦克风口述到实际目标应用的端到端验收。判停阈值未覆盖所有环境音量和噪声，仪器化使用可控识别事件验证交互。

### 等待回执修复（2026-11-19）

手机升级后仍连接旧 Windows 进程。旧进程拒绝 `auto_text`，手机收到协议错误后未按请求查询回执，语音栏又把「暂未取得回执」显示为持续等待。

- Windows 在认证后的 `welcome.capabilities` 声明 `auto_text`。手机对未声明能力的连接显示更新提示，保留草稿。
- 收到带原请求 ID 的 `INVALID_SUBMIT` 时，手机查询同一个请求。电脑返回 unknown 后，首页显示「保留为草稿」，停止转圈。这个操作终结本地待核对请求并保留文字，继续发送由用户触发。
- `testDebugUnitTest`、`lintDebug`、Debug 构建、Windows 工作区测试、Clippy、格式及双端资源与依赖检查通过。Redmi K40 隔离套件 140 项通过，含认证后的能力验证。日常包覆盖安装后与新版 Windows 重新连接，查询原请求仍显示无回执，选择「保留为草稿」后原 9 字可编辑，发送按钮可用。

### 默认策略移入发送策略页与控件拆分（2026-11-19）

设置根页不再单独显示「默认自动发送」；该设置整体移入发送策略页，根页只保留「发送策略」入口。默认策略最终拆成两个独立 Miuix 控件：

- `SwitchPreference`「自动发送」：只决定默认策略是否自动发送，无图标；下方小字「开启后按下方等待时长自动发送」。
- `SliderPreference`「等待时长」：1–10 秒、整数步进，右侧显示「N 秒」，副标题「语音识别结束后等待时长」。滑杆只写等待时长，不改变开关；开关关闭时整行置灰、不可拖动，但保留数值显示。

拆分原因：把开关和时长合在一条 0–10 秒滑杆里会隐藏时长——默认拖到 0 后，规则为自动发送的应用仍按保存的秒数发送，卡片只能显示一个用户看不到的「自动 · 3 秒」。拆开后开关只表达是否默认自动，时长始终可见；关闭时置灰表明它当前不生效。

其它界面调整：

- 当前前台卡改为单行：36dp 图标、应用名、应用名下方小字「电脑当前前台」，右侧状态标签只显示「自动」或「手动」；没有前台应用时只显示「暂无前台应用」。删除了「应用名只决定发送方式…」说明和重复状态句。
- 应用规则由「点击行 → `OverlayBottomSheet` + `RadioButton` + 保存」改为每个应用一行 Miuix `WindowDropdownPreference`：跟随默认 / 自动发送 / 手动确认。选择即写入规则；行内只保留图标、名称和下拉值。清除全部规则的入口不变。
- 应用图标不再由 APP 裁剪圆角：原来 24dp 图标配 9dp 圆角，视觉上接近圆形；现在直接按电脑端下发的 48×48 PNG 等比缩放，形状完全来自原图。规则行取 24dp，当前前台卡取 36dp。
- 删除滑杆下方两条说明和页脚校验说明，页面说明文字只剩滑杆副标题。

数据与行为：

- `auto_send` 布尔值表示默认是否自动发送；`auto_send_seconds`（Int，1–10，默认 3）保存等待时长。两者独立读写，互不覆盖：拖动等待时长不会打开自动发送，关闭自动发送也不会清除时长；读取秒数时钳制到合法范围。
- 应用规则优先于默认：默认开关关闭时，规则为「自动发送」的应用仍然自动发送，并使用当前等待时长。因此电脑卡片只显示自动/手动，秒数不在卡片重复。
- `SpeakInteraction.finish` 增加 `delayMillis` 参数，`AutoSendCountdown` 记录本次 `durationMillis`；发送圆环比例与「N 秒后发送」文案都读取实际倒计时，不再引用固定 3 秒常量。
- 修改等待时长或开/关都会撤销已武装的自动发送票据；下一次明确完成输入才使用新值。滑杆拖动期间只更新页面预览，释放时才提交一次。

验证：

- `python scripts/check_i18n.py` 通过（en、zh-Hans 键与占位符一致）。
- `:app:testDebugUnitTest --offline` 117 项通过，含「配置时长决定 deadline 与圆环跨度」断言；`SpeakInteractionTest` 12 项。
- `:app:compileDebugAndroidTestKotlin --offline` 通过；依赖设置页开关的旧导航辅助改为直接设置会话的自动发送模式。
- `:app:lintDebug :app:assembleDebug --offline` 通过，lint 0 errors（175 warnings），Debug APK 覆盖安装到 Redmi K40 日常包 `seeyuer.sayin`。
- 真机抽查（Redmi K40）：本轮构建中开关关闭时滑杆整行置灰、数值仍显示「3 秒」，开关下方显示「开启后按下方等待时长自动发送」。
- 相同 `enabled = canChange && automatic` 条件的上一构建已真机验证：拖动置灰滑杆后数值和 `auto_send_seconds` 均保持 3 秒；开关开/关时电脑卡片标签在「自动/手动」间切换；滑杆拖动只写 `auto_send_seconds`，不改变 `auto_send`。本轮只调整置灰条件与说明小字，未重跑拖动手势和完整通信套件。
