# 74 · Android AI 润色实施记录

父设计：[73 · Android AI 润色设计方案](73-android-ai-polish-design.md)；提示词：[73a · 提示词模板](73a-android-ai-polish-prompt-templates.md)。

## 1. 实施范围

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P1 | 单条加密配置、Chat Completions / Anthropic Messages、连接测试、两套内置提示词、手动润色、选区、取消、单步撤销、错误映射、单次 POST、本机统计、设置页、首页工具行入口 | 已实现 |
| P2 | 自动润色、按来源/应用场景、显式列表/正式化/精简/自定义改写、任意 BCP-47 AI 翻译、本地翻译互斥、润色后显式发送 | 已实现 |
| P3 | 流式 partial 预览、术语表注入、修改前后对比 | 已实现 |

## 2. 代码结构

- `android/app/src/main/java/seeyuer/sayin/polish/PolishConfig.kt`
  - 预设（DeepSeek / 千问 / OpenAI / Claude）与自定义配置。
  - `polish-config.bin`，AndroidKeyStore AES-GCM，alias `sayin.ai.polish.v1`，AAD `SayIn/ai-polish/v1`。
  - 配置指纹、HTTPS 校验、Key 必填、Messages API 版本。
- `PolishPrompts.kt`
  - `builtin.voice`、`builtin.text`、`rewrite.translate-2`、列表/正式化/精简/自定义模块。
  - 场景模块（聊天/邮件/代码/文档/搜索/剪贴板）和按应用显示名的模块。
  - `<input_text>` XML 转义、prompt SHA-256、输出边界检查。
- `PolishClient.kt`
  - 每次动作最多一次 POST；禁止 OkHttp 重试和重定向。
  - OpenAI Chat 字段按档案：DeepSeek/千问 `max_tokens`，OpenAI `max_completion_tokens` + `reasoning_effort:none`，不发送 temperature。
  - Anthropic Messages：顶层 `system`、`max_tokens`、`anthropic-version`、Haiku `thinking.disabled` + `output_config.effort=low`。
  - 严格 UTF-8、64 KiB 响应上限、stop/end_turn 完整性、拒绝截断/refusal/tool/未知结束。
  - SSE 流式解析（Chat 与 Messages），partial 回调，完整结束后才校验并应用。
- `PolishStats.kt`
  - 最近 100 条本机统计：provider/model、输入/输出 token、耗时、结果、HTTP 状态。
  - 未配置模型单价时费用来源明确显示为未知。
- `PolishManager.kt`
  - 配置/统计/测试状态；默认目标语言、应用场景开关、自定义指令、术语表、流式预览。
  - 自动润色与配置指纹绑定；配置端点/协议/鉴权/模型变化时自动关闭。
- `ui/PolishControls.kt`
  - 首页模式面板：语音整理/文本校对/列表/正式化/精简/自定义改写/AI 翻译。
  - 目标语言 BCP-47 输入 + 常用语言快捷项；运行、取消、结果状态、预览、前后对比。
- `ui/settings/PolishSettingsPage.kt`
  - Miuix 组件：`WindowDropdownPreference`、`SwitchPreference`、`TextField`、`Button`、`WindowDialog`。
  - 服务商/协议/地址/模型/Key、测试连接、保存、移除、统计、自动润色、应用场景、自定义指令、术语表、流式预览、默认语言。
- `ui/SpeakSession.kt`
  - 独立润色 ticket（draftId/version/range/promptHash/config fingerprint）。
  - 结果仅在票和草稿仍匹配时应用；选区只替换选区。
  - 通过 `changeDraft` 形成独立撤销步；编辑/清空/离页/本地翻译使结果失效。
  - `polishConfirmationPending` 阻止自动倒计时；显式发送后清除。
  - 自动润色仅在“空草稿开始、未混入键入、ASR final 进入 KeepDraft、配置指纹仍绑定”时触发。
- `ui/SpeakPage.kt`
  - 工具行在本地翻译和复制之间加入 `Automatic` 星标；空草稿/翻译显示时禁用。
  - 状态行显示运行/失败/已润色需确认；成功后可打开前后对比。

## 3. 关键行为

- 配置/Key 只在 AndroidKeyStore 加密文件中；不写日志、不进入桌面协议。
- 换地址/协议/鉴权时设置页清空 Key；仅改模型保留。
- AI 润色/翻译结果不自动发送；自动发送策略会被降级为手动。
- 本地中译英仍是工具行独立图标；AI 翻译在模式面板内，二者互斥。
- 自动润色默认关闭，只处理新语音首段；不会覆盖续写/混合草稿。
- 应用场景只决定写法，不改变发送目标；同名应用共享显示名映射。
- AI 翻译目标只来自显式选择/设置，不来自用户文本；非法 BCP-47 拒绝。

## 4. 验证

已执行：

```powershell
.\scripts\gradle.ps1 :app:testDebugUnitTest `
  --tests "seeyuer.sayin.polish.*" `
  --tests "seeyuer.sayin.ui.PolishAutoTest" `
  --tests "seeyuer.sayin.ui.settings.PolishEditorStateTest"
python scripts/check_i18n.py
.\scripts\gradle.ps1 :app:lintDebug
.\scripts\gradle.ps1 :app:assembleDebug
.\scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin
adb -s dc39c31d install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

结果：

- 30 项 AI 润色 JVM 测试通过，0 失败；覆盖协议字段/headers、响应校验、截断/拒绝/tool、单请求、重定向不跟随、严格 UTF-8、流式 SSE、prompt 注入边界、配置/预设、编辑状态、自动润色条件。
- i18n 检查通过（en、zh-Hans，占位符一致）。
- lintDebug 通过。
- assembleDebug 通过；P3 APK 已覆盖安装到 Redmi K40 (`dc39c31d`)。
- Android UI 测试已编译；`connectedDebugAndroidTest` 尝试启动时被 MIUI 安装确认拦截，报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，测试未执行。
- 已在 Redmi K40 上人工验证：
  - 设置 → AI 润色入口显示“未配置 · 点此设置”；
  - 服务商/协议/地址/模型/Key、测试连接、保存、自动润色、应用场景、流式预览、目标语言、自定义指令、术语表、移除配置均可显示；
  - 输入临时 Key 保存后显示“已保存”，自动润色开关从禁用变为可用；
  - 首页输入草稿后 AI 星标启用，点击打开模式面板，显示语音整理/文本校对/结构化列表/正式化改写/精简改写；
  - 移除临时配置后自动润色重新禁用；测试草稿已清空。

未验证：

- 没有真实 API Key，未对真实 DeepSeek/千问/OpenAI/Claude 端点发请求；模型字段和错误映射按 73 文档固定，MockWebServer 只验证协议实现。
- 未在真实目标应用上验证润色后的落字、自动发送抑制和翻译 RTL 显示。
- 仪器化 UI 测试因 MIUI 安装限制未执行；需允许安装 `seeyuer.sayin.test` 后再跑 `PolishUiInstrumentedTest`。
- 自动润色的真实 ASR final 触发未在真机录音链路上验证。

## 5. 提交

- `d597647 feat: add manual AI polish P1`
- `e59ea56 feat: add AI polish P2 automation and rewriting`
- `c59f291 feat: add AI polish P3 term hints and compare view`
- `113e72b feat: stream AI polish previews`

均已推送到 `origin/main`。

## 6. 后续风险

- 应用规则当前是“显示名自动映射 + 总开关”，没有逐应用手动覆盖列表；同名应用共享映射，符合当前界面说明，但复杂同名软件需要后续补充手动选择。
- 自定义改写是用户自填 system 指令，需保持“不执行用户文本”的边界；P3 已用固定 XML 包装隔离用户文本。
- 统计费用来源为未知，未接入厂商单价表；Token 与耗时已记录。
- 流式预览只在 MockWebServer 固定事件流上验证，真实厂商 SSE 事件差异仍需真机/真实服务验收。
