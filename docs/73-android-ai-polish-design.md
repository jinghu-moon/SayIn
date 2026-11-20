# 73 · Android AI 润色（Polish）设计方案

日期：2026-11-19。2026-11-20 补充模型与价格目录设计。AI 润色已实现的范围见 [74 · Android AI 润色实施记录](74-android-ai-polish-implementation.md)。§5.9 的目录与定期刷新属于后续设计。电脑通信沿用现有协议。

参考：SayIt 的客户端 AI 链路（`refer/SayIt-main`），只借鉴其客户端部分；不采用其自建服务器模式。协议与模型能力以 2026-11-19 可访问的官方文档为准；具体账号、区域、模型权限必须在实施前用真实凭据复核。

### 本版修订（按 2026-11-19 审核与来源讨论）

保留：手机直连、统一草稿、用户确认后发送。

- 明确四类文本来源（本地 ASR、云端 ASR、输入法、剪贴板）与两套提示词（语音整理、文本校对）。
- 新增子文档 [73a · AI 润色提示词模板](73a-android-ai-polish-prompt-templates.md)，包含基础模板、场景/应用模块和改写模块。
- 自动润色改为在 ASR final 后统一判断本轮意图，只有“留在草稿”才允许；自动范围收敛为空草稿首段。
- Key 与服务配置合并为单条加密记录，绑定规范化端点/协议/鉴权；换端点必须重新确认凭据。
- 按厂商与模型档案生成可选字段，每次动作最多发一次 POST。
- 增加响应字节限额、协议完成判定、工具/思考块拒绝、4000 码点/16 KiB 上限；保留草稿原始排版。
- 撤销改为独立事务；明确持久化、离页取消、配置代次和“润色后必须显式确认发送”。
- 补齐 SayIt 的 XML 转义、AGPL-3.0 边界，以及首批模型与真实账号/区域复核要求。
- 根据 Codex Proxy RS 源码明确协议、厂商预设、端点鉴权和模型参数的职责，补齐 JSON 响应终态及重发验证。
- P1 增加本机调用统计，包括 Token、费用来源、未知计费、耗时和请求结果。

## 1. 目标与边界

### 目标

- 把四类文本润色成可直接发送的干净文本：本地/云端 ASR 用“语音整理”，输入法/剪贴板/选区用“文本校对”；默认保守，不做激进改写。
- 可选 AI 翻译：用户显式选择任意符合格式的 BCP-47 目标语言后，用同一云端模型翻译草稿或选区；现有本地中译英保留，不作为默认自动步骤。
- 使用用户自己的云端 LLM Key，支持 OpenAI 兼容 `chat/completions` 与 Anthropic `messages`。
- 润色结果进入统一草稿，可编辑、可撤销、可发送；失败不阻断发送。
- 不改变电脑协议、Outbox、回执与输入语义：电脑仍然只接收用户确认发送的文字。

### 非目标（第一期明确不做）

- 不自建服务器、不代理、不提供共享 Key。
- 不改电脑端、不新增 WSS 消息、不让电脑做改写。
- 不读取电脑端编辑器上下文（光标前后文/选区），因为手机协议不承载这些数据。
- 不做“选中文本 → 口述编辑指令”。
- 不做 Ollama/局域网模型：只支持 HTTPS 云端的 OpenAI 兼容与 Anthropic Messages 接口。
- 不做流式 partial 预览；第一期一次性返回。
- 不做“润色后自动发送”。
- 不在 P1 做自动润色、自定义模式和按应用规则；这些放到 P2。

## 2. 关键决策

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 执行位置 | 手机端 | 草稿和 Key 都在手机；电脑只做忠实输入；避免协议与回执复杂化 |
| 结果形态 | 原地替换草稿，提交为独立撤销事务 | 不引入第二套候选表示；发送逻辑不变；撤销即对比原文 |
| 文本来源 | 本地 ASR、云端 ASR、输入法、剪贴板四类都可手动润色 | 用户最需要处理的是长口述、病句和粘贴正文；不扩大自动范围 |
| 提示词 | 只维护两套：语音整理、文本校对 | 四类来源共用两套规则，减少维护和选择；选区/混合草稿默认文本校对 |
| 触发方式 | P1 仅手动按钮；P2 才考虑自动润色（默认关） | 语音输入要快；润色有延迟和费用，必须由用户选择 |
| 自动润色授权 | 只在 ASR final 成功、且本轮意图为“留在草稿”时考虑 | “结束并发送”、语音发送、语音翻译、自动发送、取消/断网/尾包失败均跳过 |
| 自动润色范围 | P2 仅覆盖“从空草稿开始的一段语音” | 避免把草稿里已有的键入、粘贴、选区内容一并外发 |
| 默认风格 | 保守：去口癖、修断句、纠明确听写错误；不主动列清单/换语气 | 自动发送场景下，结构改写和正式化风险更高 |
| 与本地翻译关系 | 互斥：本地译文显示/生成时不润色、不 AI 翻译 | 避免两个译文候选并存；本地中译英继续走离线模型 |
| AI 翻译 | 显式选择任意符合格式的 BCP-47 目标语言，P2；不自动 | 不局限于中/英；云端翻译会外发原文，必须由用户选择 |
| 与自动发送关系 | 自动发送优先；润色后必须显式确认一次，不再进入倒计时 | AI 改写不能绕过用户确认 |
| 服务接入 | 厂商预设负责默认值，协议负责报文，模型档案负责可选参数 | 同一家服务可以支持多种协议，同一种协议也可供多家服务使用 |
| 协议选择 | 预设填入已核验的协议，自定义显式选择，单次调用冻结选择 | 模型名和 URL 无法可靠推断协议，试探可能产生第二次付费请求 |
| 用量与费用 | 记录上游用量和单次调用结果，费用附来源与币种 | 超时、取消、截断也可能产生费用，需要保留未知状态 |
| 凭据绑定 | Key 与规范化端点、协议、鉴权方式原子保存 | 换域名后沿用旧 Key 会把凭据发给新端点 |
| 请求次数 | 一次用户动作只发一次付费 POST | 关闭 OkHttp 自动重试/重定向；用测试锁死 |
| 结果完整性 | 只接受协议明确“完成”的纯文本；字节限额先于解析 | 截断、拒绝、工具调用、未知结束原因都保留原文 |
| 隐私 | 只发用户明确要润色的文本；日志只记证据不记正文 | 与 ASR/翻译一致 |

## 3. 架构与触发授权

### 3.1 架构

```
SpeakPage / QuickActivity
        │ 手动点击（P1）；ASR final 的“留在草稿”分支（P2 自动）
        ▼
SpeakSession（唯一任务所有者）
        │ 1. 本轮意图 + 配置代次 + 草稿修订检查
        │ 2. 冻结 PolishTicket，快照要发送的文本
        │ 3. 调用 PolishEngine（OkHttp，IO）
        ▼
HttpPolishEngine
        │ 冻结端点、鉴权、协议、模型档案和价格 → 构造一次 HTTPS POST
        ▼
用户配置的云端 LLM
        │ 有界读取 → 协议解析正文终态与用量 → 独立结束调用记录
        ▼
SpeakSession：ticket/配置代次/草稿修订仍匹配？
        │ 是 → 独立撤销事务写入草稿；标记“需显式确认发送”
        │ 否 → 丢弃
        ▼
草稿卡：“已润色 · 可撤销” / 有限错误原因
```

不新增 Gradle 模块。实现放在 `seeyuer.sayin.polish`（纯 Kotlin + OkHttp）与 `SpeakSession` 集成；配置存储在 `seeyuer.sayin.data`。

### 3.2 本轮意图：自动润色的唯一授权来源

```kotlin
internal enum class FinalIntent {
    KeepDraft, SendNow, AutoSend, Translate, SendCommand, Cancelled, Failed,
}
```

当前 `SpeakSession` 在 ASR final 后按优先级分派（[SpeakSession.kt:227](../android/app/src/main/java/seeyuer/sayin/ui/SpeakSession.kt)）：

1. `VoiceCommand.Translate` → 翻译；
2. `direct`（“结束并发送”且接收范围仍有效）→ 立即发送；
3. `recordingMode == Automatic` → 自动发送；
4. `VoiceCommand.Send` → 语音发送；
5. 其他 → 留在草稿。

设计把第 5 种定义为 `FinalIntent.KeepDraft`，**只有它**允许 P2 自动润色：

- 必须在 `result.finished && explicitFinish && transcript` 非空且本轮成功定稿之后判断；取消、断网、尾包失败、partial 都不触发。
- 判断使用录音开始时冻结的 `recordingMode`、`recordingScope`、`recordingDestination`，不得用 final 之后的当前设置重算。
- 只要出现立即发送/自动发送/语音发送/语音翻译中的任一意图，就完全跳过自动润色；不取消、不排队、不“润色后再发”。
- 手动润色不受意图限制，但仍在发送、请求锁定、草稿恢复、译文显示时禁用。
- 键盘输入永远不触发自动润色。

### 3.3 上传范围

- **P1 手动润色**：有选区时只发送选中文本；无选区时发送当前草稿文本。首次使用在设置页明确说明会发送到哪个服务和范围。
- **P2 自动润色**：只处理“从空草稿开始的一段语音”。录音开始时草稿非空（续写、在选区插入、草稿里已有键入/粘贴内容）则跳过自动润色；只发送本轮新增的语音片段，用户仍可手动润色完整草稿。
- 以后若支持续写：只发送本轮语音片段，并按冻结的插入范围把结果应用到该片段；不整篇外发。

### 3.4 与 SayIt 的取舍

- 借鉴：ASR final 后统一策略；用 runId/ticket 隔离过期结果；失败可继续用原文；对 `<input_text>` 内的正文做 XML 转义；把“是否调用”和“结果判据”分层。
- 不照搬：SayIt 的 8 秒前端 race 只结束前端等待，底层 HTTP 仍可能继续；本设计直接 `Call.cancel()`，并用测试锁死“一次动作一次 POST”。SayIt 的客户端派发只有 OpenAI 兼容与 Ollama；本设计独立实现 Anthropic Messages。SayIt 依赖自建服务器；本设计不使用。
- 许可证：SayIt 为 AGPL-3.0（[LICENSE](../refer/SayIt-main/LICENSE)）；只参考行为、Prompt 边界和协议差异，按官方 API 文档独立实现，不复制源码。

### 3.5 四类文本来源、何时润色、用哪套提示词

| 来源 | 什么时候值得润色 | 默认提示词 |
| --- | --- | --- |
| 本地离线 ASR | 长口述、明显口癖、卡顿重复、断句影响阅读时收益最大；本地模型已带标点，重点是句子是否读得通 | 语音整理 |
| 云端 ASR（豆包流式） | 当前配置下长口述同样适合；云端已做标点/ITN，重点去口癖、修断句、纠正上下文能明确判断的听写错误 | 语音整理 |
| 输入法文本 | 用户觉得措辞不顺、想改错字或病句时 | 文本校对 |
| 剪贴板粘贴文本 | 用户明确想编辑粘贴进来的正文时 | 文本校对 + 结构保护 |
| 选区 | 只处理选中部分；选区可能来自键入、粘贴或语音，无法可靠区分 | 文本校对 |
| 混合草稿（既有语音又有键入/粘贴） | 用户手动润色整段时 | 文本校对 |

- 输入法自带的语音输入也按“输入法文本”处理：SayIn 只能看到键盘/IME 上屏，无法可靠判断文字来自键入、语音还是粘贴。
- 翻译不是来源默认，而是用户显式选择的改写模式（见 6.5）；本地离线中译英继续使用现有本地翻译。
- 两套提示词覆盖四类来源，不为每个来源维护四份 Prompt。语音整理和文本校对都必须保留否定、语气、人名、术语和事实；正式化、缩写、列清单、换语气只在用户明确选择改写模式时做。
- 例子：`嗯，周五先别上线，等张伟确认` → `周五先别上线，等张伟确认。`。可删的是“嗯”；“先别”“张伟”“周五”必须保留。

### 3.6 豆包流式识别的现有后处理

SayIn 当前使用双向流式 `bigmodel_async` 与 `volc.seedasr.sauc.duration`，请求只显式设置：

- `enable_itn = true`：数字、金额、日期等文本规范化；
- `enable_punc = true`：自动标点；
- 未设置 `enable_ddc`：语义顺滑（删除/修正停顿词、语气词和重复表达）沿用官方默认关闭；
- 未设置 `enable_nonstream`：不做“先流式、再非流式”的二遍识别（该参数用于提高最终准确率）。

官方参数默认值（[豆包流式语音识别 WebSocket 文档](https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)）：`enable_punc` 默认 true、`enable_itn` 默认 true、`enable_ddc` 默认 false。含义：

- 云端文本已有标点和 ITN，语音整理的 Prompt 不再要求“补标点/转数字”，而是保留已识别格式、去口癖、修断句。
- `enable_ddc` 关闭时，停顿词和重复仍可能保留，所以语音整理仍有价值；如果以后开启语义顺滑，应先用同一批录音比较“识别侧顺滑”和“LLM 语音整理”的效果，再决定是否还跑一次模型。
- `enable_nonstream` 只影响识别准确率，不属于润色；是否开启另做识别质量评估。

### 3.7 SayIt 的入口划分与 SayIn 的取舍

SayIt：

- SayIt 自己的“AI 整理”默认开启，录音结束后按统一策略自动运行；本地与云端 ASR 共用同一条润色流程。
- 内置模式默认“意图整理”，会删口癖、处理自我纠正，遇到多点陈述主动列清单；应用规则可换模式或追加约束。
- 键入/粘贴没有自动润色入口；“上下文感知写作”默认关闭，只有用户选中文字并口述编辑指令时才处理选区。
- 豆包实时请求同样只开标点/ITN，未开 `enable_ddc`；千问 Omni 这类识别引擎自带整理时会跳过独立 LLM。
- 参考：[defaults.ts](../refer/SayIt-main/client/src/services/defaults.ts)、[aiPolicy.ts](../refer/SayIt-main/client/src/services/transcription/aiPolicy.ts)、[promptRouter.ts](../refer/SayIt-main/client/src/services/personalization/promptRouter.ts)、[asr_doubao_stream.rs](../refer/SayIt-main/client/src-tauri/src/providers/asr_doubao_stream.rs)。

SayIn 的差异：

- P1 只做手动润色；自动润色 P2、默认关闭，且只处理本轮新增语音片段。
- 默认风格比 SayIt 保守：去口癖、修断句、纠正明确听写错误；不主动列清单或改成书面语（自动发送场景下，结构改写风险更高）。
- 四类文本都能手动润色；键入、粘贴和选区默认“文本校对”，语音默认“语音整理”。
- 不做电脑端上下文读取和选区编辑指令，因为协议不承载电脑编辑器内容。

## 4. 数据模型与持久化

### 4.1 配置（单条加密记录）

Key 不能与服务配置分离保存：地址或鉴权方式一改，旧 Key 就可能被发给新端点。因此把整条配置作为**一个 AtomicFile 加密记录**保存：

```kotlin
internal enum class PolishProtocol { ChatCompletions, AnthropicMessages }
internal enum class PolishAuth { Bearer, XApiKey, ApiKeyHeader }
internal enum class OutputBudgetField { MaxTokens, MaxCompletionTokens, None }
internal enum class ReasoningPolicy { None, EffortNone, EffortLow, ThinkingDisabledLowEffort, ThinkingEnabledLow }

internal data class PolishModelProfile(
    val outputBudgetField: OutputBudgetField,
    val supportsTemperature: Boolean,
    val reasoning: ReasoningPolicy,
)

// 刻意不是 data class：凭据不能进入自动生成的 toString/日志。
internal class PolishConfigRecord(
    val version: Int,
    val presetId: String,
    val protocol: PolishProtocol,
    val auth: PolishAuth,
    val endpointUrl: String,        // 规范化后的完整请求 URL
    val anthropicVersion: String?,  // 仅 Messages 保存
    val model: String,
    val apiKey: String,              // 只在内存与加密文件
)
```

- 文件：`noBackupFilesDir/polish-config.bin`；AndroidKeyStore AES-GCM，别名 `sayin.ai.polish.v1`，AAD `SayIn/ai-polish/v1`；实现复用 [CloudConfigurationStore.kt](../android/app/src/main/java/seeyuer/sayin/asr/CloudConfigurationStore.kt) 的 AtomicFile 模式。
- 记录不是 JSON 明文的“地址+Key”两段；解密后作为整体校验。版本不匹配、GCM 失败、字段缺失都进入 `Corrupt`。
- 配置状态：`Missing`（未配置）、`Corrupt`（坏文件/Key 不可用，禁止静默重建）、`Unauthorized`（已配置但服务拒绝凭据）、`Ready`。
- **换端点、协议或鉴权方式必须重新输入 Key**。以 `protocol + auth + endpointUrl` 绑定凭据，比较规范化后的完整地址，包括端口和路径。仅换模型可沿用同一绑定内的 Key。模型档案由应用按本次生效配置推导，配置文件只保存选择。
- **自动润色授权绑定服务配置版本**。端点、协议、鉴权、协议版本、模型或凭据变化时撤销旧授权与在途应用资格。用户重新开启后才恢复自动润色。
- 非机密的 Prompt 预设可继续存 `presentation` SharedPreferences（`polish_presets`、`polish_preset`）；坏 JSON 回落到内置模式。
- “清除配置”删除加密记录与自动润色开关；不删除草稿、配对和 ASR 配置。

### 4.2 任务状态

```kotlin
internal enum class PolishEnd {
    Complete, Cancelled, Timeout, Network, Unauthorized, RateLimited,
    ServerError, InvalidRequest, InvalidResponse, EmptyOutput, Truncated,
    Refused, ConfigMissing, ConfigCorrupt, InputTooLong, LocalStorage,
}

internal enum class PolishOrigin { Manual, AutoAfterFinal }

internal data class PolishTicket(
    val id: String,
    val draftId: String,
    val version: Long,
    val source: String,
    val presetId: String,
    val promptHash: String,
    val configGeneration: Long,   // 每次配置变更 +1
    val providerId: String,
    val model: String,
    val origin: PolishOrigin,
)

internal data class DraftPolish(
    val ticket: PolishTicket? = null,
    val running: Boolean = false,
    val end: PolishEnd? = null,
    val httpStatus: Int? = null,   // 仅状态码，不保存服务端正文
    val elapsedMs: Long = 0,
)
```

- **不再保留 `errorDetail` 原文**：界面只显示有限错误码与 HTTP 状态；服务端错误正文不落盘、不展示、不进日志。
- 结果只在 `draftId + version + source + configGeneration` 全部匹配时应用。
- 结果本身不持久化；进程死亡即丢弃。完成后的草稿由现有 `SpeakingDraftStore` 按 150ms 合并落盘恢复。
- 同时只允许一个在途任务；新请求、发送、配置变更、新录音立即作废旧 ticket。

### 4.3 本机调用统计

独立的 Room 表按调用 ID 保存一条记录，归 `PolishEngine` 的调用生命周期管理。统计与草稿能否应用分开结算。记录包括起止时间、单调时钟耗时、用途（润色或连接测试）、预设、协议、请求模型、可选上游实际模型、HTTP 状态、请求结果、Token 和费用。用户主动重试生成新 ID。同一 ID 的终态写入幂等。

- 通过配置/输入校验后，在提交网络调用前持久化待完成记录。完成前的本地取消按已知发送状态结算。记录提交后遇到进程中断且未取得终态，恢复为「结果和计费未知」。记录建不起来时按本地错误结束，用户可继续使用原稿。
- 取消、超时、文本被拒收、结果过期或草稿已编辑时，仍保留已取得的用量；已发出或可能发出且缺失用量的调用标为未知。只有能确定请求未发送时，才记「未发送」并排除在可能计费调用之外。统计落盘失败可提示记录缺失，不改变已取得的模型文本或授权结果。
- 默认保留最近 90 天，支持清空；清空需要处理在途记录，迟到结果不得复活已清除的行。统计不随配对或 Outbox 同步。记录排除 Key、端点地址、Prompt、草稿、响应正文和服务端错误详情。

## 5. 多协议接入

### 5.1 Codex Proxy RS 的请求链路

| 环节 | 源码行为 | 源码入口 |
| --- | --- | --- |
| 入站 | 路由以路径区分 `/v1/responses`、`/v1/chat/completions` 和 `/v1/messages`。Chat 先把 `messages`、工具和输出参数转换成 Responses 正文，再经 Responses 解码和共用执行入口。Messages 保留 Anthropic 正文和协议标记。 | [router.rs](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/router.rs)、[chat HTTP](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/chat_completions/http.rs)、[Chat 请求转换](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/chat_completions/request.rs)、[Messages 入口](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/messages.rs) |
| 共用执行 | API 准备认证和请求快照，把动作交给 Core。`Operation` 和 `ProtocolPayload` 携带能力需求、当前正文协议及上下文。执行协调器追踪上游发送状态与下游提交状态，按候选范围、能力和执行状态决定是否允许恢复。 | [共用 HTTP 执行](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/generation/http.rs)、[operation.rs](../refer/codex-proxy-rs-main/backend/crates/gateway-core/src/operation.rs)、[coordinator.rs](../refer/codex-proxy-rs-main/backend/crates/gateway-core/src/engine/coordinator.rs) |
| 上游 | OpenAI 和 xAI 是两个 Provider。OpenAI Provider 的 API Key 账号各自配置 Responses 或 Messages，上游格式在选定账号后决定。Responses → Messages 按目标模型能力转换，请求 event stream。Messages → Responses 同步更新正文和协议标记。跨协议时过滤来源协议专属头，由出站实现添加目标认证头。同协议保留原生报文作为交付依据。 | [账号配置](../refer/codex-proxy-rs-main/backend/crates/providers/openai/src/credential/api_key.rs)、[协议分派](../refer/codex-proxy-rs-main/backend/crates/providers/openai/src/provider/messages/mod.rs)、[纯转换](../refer/codex-proxy-rs-main/backend/crates/gateway-protocol/src/anthropic/request.rs)、[上游请求头](../refer/codex-proxy-rs-main/backend/crates/providers/openai/src/transport/headers.rs) |
| 响应 | 跨协议转换器按单次响应持有状态，从 `message_stop`、`response.completed`、`response.incomplete` 等事件完成转换。Chat HTTP 入口装配自己的响应投影，输出客户端请求的 JSON 或 SSE。终态、文本、工具和用量各有映射。 | [事件转换](../refer/codex-proxy-rs-main/backend/crates/providers/openai/src/provider/messages/translator.rs)、[Chat 交付](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/chat_completions/delivery.rs)、[Chat 结果映射](../refer/codex-proxy-rs-main/backend/crates/gateway-api/src/openai/chat_completions/response.rs)、[跨协议合同测试](../refer/codex-proxy-rs-main/backend/crates/providers/openai/tests/provider/contract/messages/mod.rs) |

示例路径是 `Chat 入站 → Responses 正文 → Core 选定 Messages 账号 → Anthropic 请求 → Responses 结果事件 → Chat 响应`。入站协议、内部正文协议和上游协议可以不同。另一路 `Messages 入站 → Messages 账号` 则保留 Messages 形态。SayIn 用手机直接创建生成请求，只需把同一个润色任务编码成用户配置的上游协议。网关的账号调度、透明转发、协议互转、SSE 事件和续接属于服务器职责。参考项目按 Apache-2.0 发布，SayIn 按官方 API 独立实现。 [参考项目架构](../refer/codex-proxy-rs-main/docs/architecture.md) · [LICENSE](../refer/codex-proxy-rs-main/LICENSE)

### 5.2 SayIn 的模块职责和调用顺序

```text
PolishInput(systemPrompt, userText)
      + 已冻结的配置（协议、端点、Key、模型）
      + 已匹配的模型档案和价格规则
              ↓
协议编解码器构造请求（Chat Completions / Anthropic Messages）
              ↓
共用 HttpPolishEngine 发出一次 POST，读取有界 JSON
              ↓
协议编解码器返回文本终态、用量和可选上游模型
              ↓
引擎结算调用统计 → SpeakSession 核对 ticket 后应用草稿
```

- 预设只填入已验证的端点、协议、鉴权和默认模型，可让不同服务复用同一编解码器。预设、协议、鉴权与模型分别建模，自定义配置根据协议得到鉴权默认值。
- 一个编解码器的契约是 `encode(input, frozenTarget): Request`、`decode(json): PolishReply`。`PolishReply` 分别承载 `TextOutcome`（完整文本或类型化失败）和 `UsageSnapshot?`。协议实现拥有各自的请求字段、必要 HTTP 头、结束判定、文本和用量提取。`HttpPolishEngine` 统一超时、取消、HTTP 状态、读取上限和统计生命周期；两个实现由编译期穷举选择。
- 预设路径使用经验证的完整地址。自定义输入接受 Base URL 或与所选协议一致的完整路径，保存前展示计算后的完整地址。Base URL 保留用户输入的路径前缀并追加 `chat/completions` 或 `messages`，不擅自加 `/v1`。完整路径只使用一次；若地址以另一已知协议的操作路径结尾，则在保存前报错。使用 `HttpUrl` 解析，要求 HTTPS、有效主机、无 userinfo/query/fragment，保留非默认端口和路径大小写。校验和实际连接使用同一个解析结果。
- 鉴权与协议组合由预设或自定义默认值约束。P1 Chat 默认 `Authorization: Bearer`，Messages 默认 `x-api-key` 加 `anthropic-version`。自定义 Messages 默认 `2023-06-01`，具体预设可固定使用已核验的版本。`api-key` 头只由经验证的预设启用。每次 POST 只发送目标所需的一套鉴权头。支持双协议的网关可用用户提供的完整 HTTPS 地址和该网关的 Key 作为一个自定义服务，连通性按选定协议单独验证。
- 模型档案按预设、协议、鉴权、经确认的端点范围和准确模型名匹配。编辑地址或模型后重新匹配，未命中时仅发送该协议的必需字段。切换协议或端点从设置层产生一份新的配置，单次调用期间禁止探测、试错和切换上游。测试连接复用生产链路，输入为固定样句。
- Responses 的扩展点是第三个编解码器。沿用相同的文本输入、执行器和计量接口，增加原生 `/responses`、`instructions`、`input` 和 `max_output_tokens`，对官方端点显式使用 `store: false`。响应校验 `status == completed` 后提取 `output` 中的助手 `output_text`，分别处理 `incomplete`、`refusal` 和工具项。首期仍交付 Chat Completions 与 Messages。Responses 按需要在独立批次用官方端点和 Responses-only 端点验收。 [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)

### 5.3 首批验证的服务预设

模型名、区域/工作空间地址和套餐权限必须用真实账号复核后再写默认值；下表是 2026-11-19 官方文档可核对到的起点，不是“永久默认”。

| 预设 | 协议 | Base URL（起点） | 候选模型 | 关键参数 | 证据状态 |
| --- | --- | --- | --- | --- | --- |
| `deepseek` | OpenAI 兼容 | `https://api.deepseek.com` | `deepseek-flash` | Bearer；`max_tokens`；不发思考字段 | 官方文档确认 OpenAI/Anthropic 双格式与模型名 |
| `qwen` | OpenAI 兼容 | 按套餐/区域/工作空间填写，例如 `…/compatible-mode/v1` | `qwen3.8-flash` | Bearer；`max_tokens`；Key 与套餐/主机绑定 | 官方“其他 AI 工具”页确认 OpenAI/Anthropic 地址与区域差异 |
| `openai` | OpenAI 兼容 | `https://api.openai.com/v1` | `gpt-6-luna` | Bearer；`max_completion_tokens`；`reasoning_effort: none`；档案省略 temperature | GPT-6 Luna 模型页 + Chat Completions 参数说明 |
| `anthropic` | Anthropic Messages | `https://api.anthropic.com/v1` | `claude-haiku-5-5` | `x-api-key` + `anthropic-version: 2026-10-21`；`max_tokens` 必填；**不发 temperature/top_p/top_k**；`thinking: {"type":"disabled"}` + `output_config.effort: low` | 版本页确认采样参数已移除；Effort/迁移指南确认 Haiku 5.5 可关思考 |

后续批次（不作为 P1 验收）：火山方舟（模型 ID 与推理接入点 ID 两种形态）、智谱 GLM-5.3（始终思考，只能 `thinking.enabled` + `reasoning_effort: low/high/max`，禁用会失败；是否推荐由实测延迟决定）、MiMo（`api-key` 头、`max_completion_tokens`）、OpenRouter（下游路由与数据策略需单独验收）。

- P1 预设只验收上述四家；`custom` 在 P1 可用，但按最小字段发送，不承诺模型效果。
- 每个预设绑定一个 `PolishModelProfile`；用户修改模型时，若离开已验证档案，自动降级为“最小参数集”并提示。
- 自定义端点：协议必选；OpenAI 兼容只发 `model + messages`；Anthropic 发 `model + max_tokens + system + messages`；不发采样、思考、输出预算等可选字段。
- 协议不做自动探测；`https` 之外拒绝，禁止重定向。

### 5.4 请求构造

OpenAI 兼容：

```json
{
  "model": "<model>",
  "messages": [
    { "role": "system", "content": "<system prompt>" },
    { "role": "user", "content": "<input_text>…</input_text>" }
  ],
  "max_completion_tokens": 1024
}
```

Anthropic Messages：

```json
{
  "model": "<model>",
  "max_tokens": 1024,
  "system": "<system prompt>",
  "messages": [
    { "role": "user", "content": "<input_text>…</input_text>" }
  ],
  "thinking": { "type": "disabled" },
  "output_config": { "effort": "low" }
}
```

规则：

- **不发送同一语义的两个字段**：`max_tokens` 与 `max_completion_tokens` 二选一。OpenAI 官方 GPT-6 与 MiMo 用后者；OpenAI 官方 `max_tokens` 已弃用，推理模型会直接 400；DeepSeek/千问等预设用各自验证过的字段。
- **OpenAI 官方档案省略 temperature**：GPT-6 在 reasoning effort 非 `none` 时要求省略采样参数；本设计统一不发，减少模型/区域差异导致的 400。
- **Anthropic 不发采样参数**：API 2026-10-21 已移除 `temperature/top_p/top_k`，任何携带都 400。
- **按档案决定思考**：Claude Haiku 5.5 默认自适应思考，必须显式 `thinking.disabled` + 低 effort；Opus/Sonnet 5.5 不能关思考，不列入 P1 预设；GLM-5.3 只能开启并设 `reasoning_effort`；GPT-6 Luna 用 `reasoning_effort: none` 避免思考延迟。
- **温度由档案决定**：支持且已验证的预设固定 `temperature: 0.2`；OpenAI 官方、Anthropic 和自定义端点省略；不把温度当作可调项暴露给用户。
- **不盲发“四种关闭写法”**：每个预设只发自己验证过的字段；自定义端点发最小字段。P1 不做“被拒绝后自动降级重试”，避免同一动作产生第二次付费请求；字段不兼容属于预设缺陷，实施前用测试锁定。
- 输出预算：`min(4096, max(256, ceil(inputCodePoints * 1.6) + 128))`，由档案选择字段；Anthropic 的预算包含 thinking token。
- 输入上限 4000 码点（与发送一致）；超出 `InputTooLong`，不截断。

### 5.5 一次动作最多发一次 POST

- OkHttp 使用独立客户端，设 `retryOnConnectionFailure(false)`、`followRedirects(false)`、`followSslRedirects(false)`，禁用服务和代理认证跟发（`Authenticator.NONE`），POST 请求体声明 `isOneShot() == true`。连接预算 8 秒、整次调用 20 秒，取消调用 `Call.cancel()`。
- 不实现应用层重试；不回退到第二个端点；不因 401/429/5xx 自动重试。
- OkHttp 5.5.1 的 503 + `Retry-After: 0` 跟发分支独立于连接重试开关。一次性请求体同时限制 503 和复用 HTTP/2 连接收到 421 时的重发。验收以服务端实际收到的 POST 数为准，发送前失败允许 0 次。App 只约束自身发出次数，服务端或用户配置的中间网关按其规则计费。 [OkHttp 5.5.1 跟发实现](https://github.com/square/okhttp/blob/parent-5.5.1/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)
- 测试连接也走同一客户端，每次使用固定样句。

### 5.6 响应完整性与结果校验

有界读取优先于反序列化：

1. 最多读取 64 KiB 响应字节；超过 → `InvalidResponse`，不解析。
2. 严格 UTF-8 解码，要求单个 JSON 对象。SSE、HTML、坏 JSON 都结束为 `InvalidResponse`。JSON 已解析时独立读取可用的计量字段。
3. 协议完成判定：
   - OpenAI：`finish_reason == "stop"`；`length` → `Truncated`；`content_filter` → `Refused`；`tool_calls`/`function_call`/null/未知 → `InvalidResponse`。
   - Anthropic：P1 请求不设置 `stop_sequences`，只接受 `end_turn`；`max_tokens`/`model_context_window_exceeded` → `Truncated`；`refusal` → `Refused`；`tool_use`/`pause_turn`/`stop_sequence`/未知值 → `InvalidResponse`。
4. 只接受纯文本：
   - Chat 只接受一条助手候选，提取 `message.content` 字符串或纯 `type=text` 数组。明确的 `message.refusal` 产生 `Refused`，工具和函数调用结构产生 `InvalidResponse`。
   - Anthropic 按返回顺序拼接 `type=text` 块；独立的 `thinking` 或 `redacted_thinking` 块不进入正文；出现工具调用、其他未知块类型或缺少助手正文时按类型失败。
5. 空文本 → `EmptyOutput`；结果 > 4000 码点或 > 16384 UTF-8 字节 → `InvalidResponse`。
6. 不静默剥离字面标签。仅当结果以完整的 `<think>…</think>` 块开头（自定义端点可能默认思考）或仍带 `<input_text>` 包装时，判 `InvalidResponse`；正文中段的用户字面标签保留。
7. 应用前调用现有 `normalizedText` **只做校验**（Unicode、控制字符、4000 码点、16 KiB），但插入草稿时保留模型返回的原始换行/制表符排版，不把排版规范化后写回。
8. 结果与原文相同（忽略首尾空白）→ `Complete` 但不产生撤销步骤，界面显示“未发现需要修改的内容”。

### 5.7 错误映射

| 条件 | PolishEnd | 界面 |
| --- | --- | --- |
| 401/403 | `Unauthorized` | 已配置，但服务拒绝凭据 |
| 429 | `RateLimited` | 请求过多，稍后重试 |
| 413 | `InputTooLong` | 请求过大，已保留原文 |
| 400 | `InvalidRequest` | 请求被拒绝，检查服务、模型或地址 |
| 404/405 | `InvalidRequest` | 检查 API 地址、所选协议和模型 |
| 5xx / 529 | `ServerError` | 服务暂时不可用 |
| DNS/TLS/连接失败 | `Network` | 网络不可用 |
| 读超时 | `Timeout` | 润色超时 |
| 坏 JSON/超限/未知结束原因 | `InvalidResponse` | 返回格式异常 |
| 空内容 | `EmptyOutput` | 未返回内容 |
| 截断 | `Truncated` | 结果被截断，已保留原文 |
| 拒绝 | `Refused` | 服务拒绝了这次润色 |
| 未配置 | `ConfigMissing` | 去设置中配置 |
| 坏文件/Key 不可用 | `ConfigCorrupt` | 配置不可读，请重新配置 |
| 调用记录无法初始化 | `LocalStorage` | 无法记录用量，已保留原文 |

- 错误体只用于判断类型和 HTTP 状态；正文、请求头、Key、Prompt、草稿内容都不进日志。
- 日志字段：`provider`、`protocol`、`model`、`chars`、`elapsedMs`、`end`、`httpStatus`。

### 5.8 Token、费用和耗时口径

Codex Proxy RS 分别表示上游上报的 `Usage`、上报金额和按价格计算的金额。它在 Anthropic 转换时将普通输入、缓存读取和缓存写入归并到总输入，并保留分项。它也区分最终响应的用量与已经发生的上游尝试费用。SayIn 针对单次调用保留同样的事实边界。 [计量类型](../refer/codex-proxy-rs-main/backend/crates/gateway-core/src/metering/mod.rs) · [Anthropic 用量转换](../refer/codex-proxy-rs-main/backend/crates/gateway-protocol/src/anthropic/usage.rs) · [网关统计口径](../refer/codex-proxy-rs-main/docs/architecture.md#请求记录与交付)

| 来源 | 转为 SayIn 统计字段 |
| --- | --- |
| Chat Completions | `prompt_tokens` 是总输入，`completion_tokens` 是总输出。`prompt_tokens_details.cached_tokens` 属于输入子集，`completion_tokens_details.reasoning_tokens` 属于输出子集。DeepSeek 的 `prompt_cache_hit_tokens` 是缓存读取的同义字段，两处一致时只计一次。 |
| Anthropic Messages | `input_tokens` 是普通输入。总输入为普通输入、`cache_read_input_tokens` 和 `cache_creation_input_tokens` 之和，`output_tokens` 是总输出。对已确认省略即无的缓存字段使用 0，其他缺失字段保持未知。缓存写入按 TTL 单独定价时，估价需要相应分项。 |
| Responses 扩展 | `input_tokens`、`output_tokens` 取总量，`input_tokens_details.cached_tokens`、`output_tokens_details.reasoning_tokens` 取子项。 |

计数只接受非负整数，检查溢出和分项关系。总 Token 优先保留服务明确上报的总数。未上报时可在两项都已知且语义确定时用总输入加总输出，并标记为计算值。接口只返回部分字段时保留可确认的字段。缺失或矛盾的计费分项显示未知。通用 Chat 字段按协议解析，省略字段或扩展字段的计费含义由已验证的服务档案解释。 [DeepSeek Chat](https://api-docs.deepseek.com/api/create-chat-completion/) · [百炼 Chat 缓存](https://www.alibabacloud.com/help/en/model-studio/context-cache) · [Anthropic 缓存口径](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)

费用用十进制定点和币种保存，保留低于最小货币单位的单次金额，汇总完成后才按展示精度取整。优先采用已验证端点明确上报的单次金额和币种。否则按请求时冻结的公开价格估算，匹配实际模型、端点套餐或区域、服务档位和上下文价格区间。模型回显与预期不符且没有对应价目时，费用显示未知。自定义地址可统计 Token，只有具备已核验金额来源或费率时才显示费用。

估算公式为 `Σ(计费分项 Token × 该分项每百万 Token 单价) / 1,000,000`。普通输入、缓存读取、缓存写入和输出按互斥分项计算，推理 Token 已包含在总输出时只作明细。缺价格、缺必要计费分项、区间或币种无法确定时记录未知。明确上报的零费用保留为 0。历史估值保存当时的金额、目录版本与规则标识。汇总按币种显示上报金额、估算金额和未知费用调用数。估价不包含账号优惠、赠送额度、税费或换汇。

例如 Chat 返回总输入 1,000、缓存命中 200、总输出 300、其中推理 80。总量为 1,300，按普通输入 800、缓存读取 200、总输出 300 三项估价。

### 5.9 模型与价格目录（后续设计）

**随包快照。** 每次发布内置一份已核验的 basellm 目录，记录来源 URL、取数时间和原始 JSON 的 SHA-256。以内容哈希作为目录版本，文件格式版本独立记录。上游明确提供价格生效时间时随规则保留，取数时间只表示数据采集时间。保留上游 Apache-2.0 许可及适用的归属说明。首次启动或本地缓存不可用时直接使用随包快照，离线也可选模型。 [basellm 目录](https://basellm.github.io/llm-metadata/api/all.json) · [上游许可](https://github.com/basellm/llm-metadata/blob/main/LICENSE)

**定期更新。** 有已保存的官方服务配置时，用 WorkManager 注册唯一的联网周期任务，默认每 24 小时检查一次，实际执行时间由 Android 调度。服务页也可立即检查，手动和后台请求共用同一刷新逻辑，同一时刻只发起一次。刷新仅向固定的 basellm HTTPS 地址发公开 GET，无需用户 Key、服务地址或草稿。目录读取和润色调用均直接使用本地可用版本。 [Android 周期任务](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

**版本切换。** 有当前可用的远程缓存时发送该缓存的 `ETag`。`304` 只更新成功检查时间。收到 `200` 时先限制解压后的大小、校验 UTF-8 和目录结构，再将目录、校验值、响应 ETag 和采集时间原子保存。内容哈希相同时复用现有目录内容，只更新检查元数据。断网、请求失败、校验失败或写入失败时继续使用当前版本。缓存记录刷新时使用的随包版本。升级后如果随包版本变化，先启用新随包快照，下次检查重新获取目录。随包版本相同时继续使用可验证的缓存。

**适用范围。** 由内置预设把已核验的官方端点和区域映射到 basellm 厂商 ID。目录只提供文本模型候选和参考费率，协议、鉴权和请求参数仍由本地已验证的档案控制。候选始终允许手填，更新后仍保留已保存的模型 ID。自定义中转站在支持时通过该站的模型列表接口（如 `GET /v1/models`）获取候选，费用按该站的用量、上报金额或已核验费率计算。

**计费时点。** 请求开始时冻结目录版本和请求时间，以实际返回模型及用量匹配同版本的费率。区域、套餐、档位、币种或时段不能唯一匹配时显示「未知」。例如上游规则只给出工作日和时段，无法覆盖同厂商的法定节假日例外时，受影响的调用保留 Token，费用显示「未知」。目录更新只用于新调用，历史金额和规则版本保留。

## 6. Prompt 体系

具体可直接使用的模板、场景/应用/改写模块见子文档：[73a · AI 润色提示词模板](73a-android-ai-polish-prompt-templates.md)。

### 6.1 两套内置提示词

P1 只维护两套，不按四类来源各写一份：

| id | 名称 | 适用 | 规则 |
| --- | --- | --- | --- |
| `builtin.voice` | 语音整理 | 本地/云端 ASR | 删除无意义的停顿词、语气词、结巴和重复；修正因口述造成的断句；只纠正上下文能明确判断的听写错误。保留数字、日期、单位、人名、术语、否定和语气。不主动列清单、不改成书面语。 |
| `builtin.text` | 文本校对 | 输入法、剪贴板、选区、混合草稿 | 保留原意、语气、语序和结构；只修改明确的错字、病句和格式问题。剪贴板额外保护引用、代码、命令、链接和 Markdown；原文已经通顺时直接返回原文。 |

- 两套都固定为中文，不随 UI 语言变化；自定义模式 P2 再做。
- 两套都只读；“复制为自定义”在 P2 提供。
- 正式化、缩写、列清单、换语气是**用户明确选择的改写模式**，不属于默认语音整理或文本校对；P2 再提供。
- 例子：`嗯，周五先别上线，等张伟确认` → `周五先别上线，等张伟确认。`。删除“嗯”，保留“先别”“张伟”“周五”。

### 6.2 来源路由与组装

```
system = 选中的内置/自定义模式
       + (P2) appRule.append
```

来源路由（P1）：

- 有选区：只处理选区，使用 `builtin.text`；选区可能来自键入、粘贴或语音，无法可靠区分。
- 无选区、整段草稿只由本轮/本次 ASR 产生且没有键入或粘贴：使用 `builtin.voice`。
- 其它情况（混合草稿、输入法文本、IME 自带语音、剪贴板粘贴）：使用 `builtin.text`。
- 用户可在运行前切换模式；界面显示当前模式，不要求用户先判断来源。

状态实现：草稿维护 `draftHasNonAsrContent` 标记；键入、粘贴、剪贴板/分享插入、选区编辑都置位；清空、新草稿、纯 ASR 新会话复位。该标记只决定默认提示词，不改变发送语义。

- P1 只有这两套全局模式。
- P2 应用规则按电脑前台应用显示名匹配；**同名应用共享规则**，必须写进界面说明。规则只决定写法，不改变发送目标。
- 热词注入为 P3。

### 6.3 用户消息与注入防护

- 用户消息固定为 `<input_text>…</input_text>`，不拼祈使句。
- **必须对正文做 XML 转义**：`& < > " '` 按实体替换。SayIt 用 `<asr_text>` 验证过这一边界（[prompt.rs](../refer/SayIt-main/client/src-tauri/src/providers/prompt.rs)）；本设计统一改为 `<input_text>`，因为键入/粘贴文本不是 ASR。否则用户原话中的 `</input_text>`、`<text>`、代码片段会逃出标签。
- 系统提示词位置：OpenAI 兼容放到 `messages[0].role = "system"`；Anthropic 放到顶层 `system`。
- 测试必须覆盖：用户字面输入 `</input_text>`、`<text_context>`、`&lt;`、代码、Markdown、`最终答案`、`<think>`，确保模型输出不被这些字面内容劫持或误剥离。

### 6.4 排版与发送语义

当前发送链路 `normalizedText` 会把换行、制表符转成空格（[Protocol.kt:78](../android/app/src/main/java/seeyuer/sayin/data/Protocol.kt)），因此：

- 润色结果可以保留原始换行写入草稿，但发送到电脑时会变成单行。
- P1 两套提示词都不主动生成列表或强制书面语；验收按“电脑端收到单行文本”编写，不把列表排版当作可用特性。
- 以后若用户明确选择“结构化/正式化”改写模式，也必须在单行发送语义下验收；要支持多行输入需另立协议需求。

### 6.5 AI 翻译（可选改写模式，P2）

- 目标语言：不限定中/英，支持任意符合格式的 BCP-47 语言标签（如 `en`、`ja`、`fr`、`de`、`es`、`ko`、`ru`、`ar` 等）；由用户显式选择，不做自动检测/自动翻译。
- 目标语言来自应用内的语言选择器：常用语言列表 + 自定义 BCP-47 标签；标签必须匹配 `^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$` 且长度 ≤ 35，不能含空白/换行，避免提示词注入。
- 输入语言由模型判断；目标语言固定，不由用户文本改变。输入已经是目标语言时原样返回。
- 现有本地翻译保留：离线中译英继续用本地模型；AI 翻译覆盖任意目标语言，或用户明确选择云端模型。
- 与本地译文互斥：本地译文显示或生成时，AI 翻译按钮禁用；启动本地翻译时取消在途 AI 翻译。
- 只处理当前草稿或选区；有选区时只发送选中文本；结果写入草稿、独立撤销、标记“需显式确认发送”。
- 上传范围：AI 翻译会把原文发送到配置的云端模型；离线 ASR 文本同样会上传，设置页和首次使用必须说明。
- 模板见子文档 73a 的 `rewrite.translate`；目标语言由代码注入 `system`，不接受用户文本参与语言选择。
- 输出可能是 RTL（阿拉伯语、希伯来语等），草稿按纯文本/双向文本显示，不强制 LTR；输出校验、超时、取消、票据和失败回退与其他润色一致；翻译结果不自动发送。

## 7. 交互与界面

### 7.1 设置入口

设置根页“语音与输入”分组，在“发送策略”之后新增：

- `ArrowPreference`「AI 润色」；
- 摘要：`DeepSeek · 自动选择模式` / `未配置` / `配置不可读` / `凭据被拒绝`。

### 7.2 AI 润色页

- 服务：`ArrowPreference` → Provider 页。
- 用量：设置内次级入口，默认最近 30 天，可切换今日或最近 7 天。按本机时区划分日期。概览显示调用次数、取得完整文本的次数、已知输入/输出 Token、费用和耗时中位数。耗时从发起调用到取得结果或结束等待。按服务或模型查看明细，可区分润色与连接测试。缺用量或费用的调用显式计数，金额按币种查看。单条明细显示时间、结果、Token 分项、费用来源及耗时。
- 模式：P1 显示两套内置模式（只读）；单次运行按来源自动选择，草稿卡可在运行前切换。P2 增加“翻译为任意目标语言”和显式改写模式；目标语言在草稿卡的模式选择器中切换。
- 自动润色：P2 再显示；P1 不出现死开关。
- 页面说明：「润色会把当前文本发送给你配置的 AI 服务；结果可撤销，不会自动发送。」
- 首次进入或首次使用时展示一次服务名与上传范围，包括：本地 ASR 文本即使识别在本机完成，使用云端模型时也会上传。
- 剪贴板/文本校对说明：保留引用、代码、命令、链接和 Markdown 结构；原文通顺时可能原样返回。

### 7.3 Provider 页

- 服务下拉（预设）与协议（预设填入；自定义时选择 Chat Completions 或 Anthropic Messages）。
- API 地址、模型输入框。
- 后续支持保存多份服务配置，切换时恢复各自的模型。模型输入框提供可搜索候选和手动输入。官方服务使用 [模型与价格目录](#59-模型与价格目录后续设计)，服务页提供「刷新目录」和上次成功检查时间。中转站有可用模型列表时展示该站结果。
- API Key：密码输入。
  - 首次配置必填；
  - 修改地址/协议/鉴权方式后必须重新输入，界面提示“端点已变化，请重新确认凭据”；
  - 仅改模型可沿用 Key；
  - 单独「清除配置」删除整条加密记录。
- 「测试连接」使用固定样句与生产链路，最多发一次 POST，计入用量并标记为连接测试。界面注明可能消耗额度，显示结果、HTTP 状态和耗时。
- 保存校验：HTTPS、model 非空、Key 存在、配置指纹一致；不满足时保存按钮禁用并说明缺什么。

### 7.4 草稿卡

- 工具行新增「AI 润色」按钮（复用 `VoiceLinkIcon.Automatic`），与翻译/复制/清空同级。
- 未配置时点击直接进入设置，而不是禁用成死按钮。
- 运行中：按钮转为取消；状态行「正在润色…」。
- 成功且变化：草稿原地替换，独立撤销事务，状态行「已润色 · 可撤销」。
- 无变化：状态行「未发现需要修改的内容」。
- 失败：状态行显示有限原因，原文不动；可再次点击。
- 有选区时只润色选区；无选区且草稿含键入/粘贴/IME 文本时默认文本校对；纯 ASR 草稿默认语音整理。
- 按钮旁显示当前模式（语音整理/文本校对/翻译为 <目标语言>），用户可在运行前切换；目标语言来自语言选择器（常用列表 + 自定义 BCP-47 标签）；IME 自带语音按输入法文本处理。
- 剪贴板/粘贴来源使用文本校对的保护规则；不在结果中重排或改写代码、命令、链接。
- 本地译文显示/生成时按钮与 AI 翻译模式禁用；润色运行中切本地翻译先取消润色。
- 快捷卡与完整页共享同一 `SpeakSession`；快捷卡空间不足时入口放入“更多”。

#### 7.4.1 首页接入位置

当前首页草稿卡工具行已有：剪贴板、图片、本地翻译、复制、清空。AI 润色作为**第 4 个图标**插在本地翻译与复制之间，使用星标/闪光图标；不新增浮动按钮、胶囊或额外高度，避免压缩编辑区和输入法空间。

- 空草稿或只有空白：AI 图标禁用（与当前本地翻译的禁用态一致）；有文本或选区时启用。
- 未配置服务：点击直接进入「AI 润色」设置，不显示死按钮。
- 点击打开模式面板：语音整理 / 文本校对 / AI 翻译；默认按来源选中，面板里可切换。
- 不设独立翻译页：AI 翻译不占用底部导航，也不新增页面；目标语言在同一个首页模式面板内选择，翻译结果直接回草稿。本地中译英继续走工具行的翻译图标。
- 运行中：图标显示进度，再点取消；状态行显示「正在润色…」。
- 成功：草稿原地替换，顶部撤销按钮可用，状态行「已润色 · 可撤销」；翻译结果显示「已翻译 · 可撤销」。
- 失败：状态行显示有限原因，草稿不动；图标恢复可点。
- 选区：只处理选区；混合草稿默认文本校对；纯 ASR 默认语音整理。
- 本地译文显示/生成时 AI 图标禁用；启动本地翻译取消在途 AI 任务。本地翻译仍保留独立图标，AI 翻译在模式面板内。
- 卡片右上角「自动」是发送策略，不是 AI 自动润色；两者不得共用一个标签。
- 润色/翻译后的草稿沿用 8.4：任何发送策略下都不进入自动倒计时，必须显式点发送。
- 首页底部的键盘/说话/发送三区结构不变；AI 入口只改工具行，不改语音栏高度和发送语义。

### 7.5 自动润色（P2）

- 默认关闭；只在 `FinalIntent.KeepDraft` 的语音 final 后触发；键盘输入、粘贴、剪贴板、IME 自带语音不触发。
- 录音开始草稿必须为空；非空草稿跳过自动润色（手动仍可用）。
- 只处理本轮新增的语音片段，使用语音整理，不整篇外发。
- 配置指纹匹配、无请求锁定、无草稿恢复、无译文、非输入法 composition。
- 取消、断网、尾包失败、partial 不触发。
- 自动润色期间新录音/编辑/发送：取消旧任务；新 final 重新判断，不排队旧结果。
- 自动润色完成只改草稿，并标记“需显式确认发送”。
- 启用自动润色时明确提示：即使语音识别在本地完成，草稿文本仍会发送到所选云端模型。

## 8. 生命周期：撤销、持久化、取消与发送确认

### 8.1 独立撤销事务

- 应用润色结果时先 `history.breakGroup()`，再写入并再次 `breakGroup()`，保证它与前后 900ms 普通编辑组不合并，一次撤销恰好回到润色前。
- 润色结果不写入 `Translation` 候选，不改变发送表示；发送仍读取当前草稿。

### 8.2 持久化

- 完成后的草稿按现有 `SpeakingDraftStore`（150ms 合并）恢复；运行状态、错误、撤销栈只在会话内。
- 进程死亡时在途结果丢弃；不会出现“半份润色结果”。
- 重启恢复后不自动重试润色。

### 8.3 取消与离页

- 实际离页（完整页与快捷卡都不可见超过现有 500ms 交接窗口）取消在途请求；页面与快捷卡切换不清空任务。
- 发送、配置变更、新录音、编辑/输入法上屏、撤销/重做、清空、翻译开始、草稿恢复都立即作废旧 ticket。
- 发送优先：润色中允许直接发送原文；发送取消润色，绝不等待润色完成。

### 8.4 润色后必须显式确认发送

- 润色或 AI 翻译成功写入草稿后设置 `polishConfirmationPending`。
- 只要该标记存在，**任何发送策略下都不会进入自动发送倒计时**；用户点击发送按钮即直接提交一次（等价手动确认）。
- 标记只在发送成功、清空草稿或建立新草稿时清除；普通编辑、撤销、再次录音都不清除，避免把润色过的文本带进后续自动发送。
- 该规则不改变 Outbox、接收范围和回执语义。

## 9. 安全与隐私

- Key 只存在于加密记录与调用瞬间的内存；不进入 SharedPreferences、状态快照、日志、历史、Outbox 或电脑端。
- 端点/协议/鉴权方式变更必须重新确认凭据；自动润色绑定配置指纹。
- 只允许 HTTPS；禁止明文 HTTP、禁止重定向、禁止自动重试；一次动作最多一次付费 POST。
- 日志只记 provider/protocol/model/字数/耗时/结果码/HTTP 状态；失败正文、Key、Prompt、草稿都不落盘。
- 手动润色是显式动作；P2 自动润色在设置页明确说明会发送草稿，并在切换服务后要求重新开启。
- 仅发送本次授权的文本与对应提示词。剪贴板文本仅在被纳入本次润色范围时上传。统计、账号、设备、配对、历史和图片均不随请求传输。
- 结果按纯文本插入，不做 Markdown/HTML 渲染。

## 10. 测试与验收

### JVM（MockWebServer）

- 配置：加密记录原子读写、指纹变化清 Key、坏文件不重建、自动开关随指纹重置。
- 端点：Base URL 与同协议完整路径结果一致；保留区域/网关路径前缀和非默认端口；拒绝 http/query/fragment/userinfo、不同协议的完整路径、重复操作路径。改路径、协议或鉴权后旧 Key 不得进入捕获请求。
- 鉴权：Bearer、`x-api-key` + `anthropic-version`、`api-key`，三者不串用。
- 请求：按档案选择 `max_tokens`/`max_completion_tokens`；OpenAI 官方不发 `max_tokens`；Anthropic 不发 temperature/top_p/top_k；Claude Haiku 5.5 发 `thinking.disabled` + `output_config.effort`；GLM-5.3 发 enabled + low；自定义端点只发最小字段。
- 一次 POST：307/308、401/407、408、429、503 + `Retry-After: 0`、连接重置、复用 HTTP/2 下的 421、`REFUSED_STREAM`。有请求到达的场景只记录一次，重定向目标零次。无法发送的场景零次。
- 响应：字节超限、坏 UTF-8、`finish_reason`/`stop_reason` 全枚举、tool/thinking 块、以 `<think>` 开头的输出、空内容、截断、拒绝、超长输出。
- 输入：XML 转义后的 `</input_text>`、标签、代码、`最终答案` 不被劫持；输出字面标签不被误删。
- 来源路由：本地 ASR / 云端 ASR → 语音整理；输入法 / 粘贴 / 剪贴板 / 选区 → 文本校对；IME 自带语音 → 文本校对；混合草稿 → 文本校对；有选区只处理选区。
- 语料：`嗯，周五先别上线，等张伟确认` 的删除/保留项；语音整理不主动输出列表/书面语；文本校对保留语气、语序、引用、代码、命令、链接和 Markdown 结构；原文通顺时返回原文。
- AI 翻译：目标语言由代码注入 system，用户文本不能改变方向；任意 BCP-47 标签（en/ja/fr/ar）都能构造合法请求；非法标签（空白、换行、超长、非法字符）被拒绝；已有目标语言时原样返回；中英混说时代码/命令/标识符保持原样；未选择翻译时不自动翻译。
- 竞态：润色中发送、切换服务后旧结果返回、编辑后旧结果返回、润色后立即撤销、进程恢复。
- 触发矩阵：结束并发送、语音发送、语音翻译、自动发送、取消、断网、尾包失败、留在草稿。
- 计量：Chat 缓存/推理子项、DeepSeek 同义字段去重、Anthropic 三项输入、部分字段、未知/零费用、超大或矛盾计数。覆盖截断/拒绝携带用量、有正文无用量、取消与迟到结果、进程恢复，以及正常文本与坏计量相互独立。
- 价格：上报金额优先；验证价格档位、缓存写入分项、缺价、实际模型变化、不同币种、历史快照和定点计算。连接测试单独标记；重复终态不重复计数，清空后旧记录不复活。
- 目录：验证首次离线可用、`200` 新版本、`304`、内容未变、超限或格式错误、写入中断、缓存损坏及应用升级的版本选择。检查刷新不带服务凭据，手动与周期刷新合并，更新期间的在途调用和历史费用仍沿用原版本。

### 仪器化（fake PolishEngine）

- 未配置点击进入设置；端点变更后保存被阻止。
- 运行→成功→草稿替换→单步撤销；失败保留原文。
- 有选区时只替换选区；混合草稿默认文本校对；纯 ASR 草稿默认语音整理；按钮旁模式标签与来源一致。
- 剪贴板/粘贴内容显示“结构保护”说明，结果不改写代码、命令和链接。
- 润色后发送不进入倒计时，直接提交一次。
- 本地译文显示时按钮与 AI 翻译模式禁用；AI 翻译结果同样标记“需显式确认发送”。
- 用量页验证日期范围、按服务/模型查看、测试调用分类、失败/未知状态及金额币种展示。
- 翻译模式标签显示所选目标语言，不与语音整理/文本校对混淆；RTL 目标语言按纯文本/双向文本显示。
- P2 自动润色只在“空草稿 + KeepDraft + 新语音片段”触发；不产生自动发送票据。

### 真机与真实服务（人工、显式 opt-in）

- 首批 DeepSeek、百炼、OpenAI 官方、Claude 四家各用真实账号验证：成功、401、429、断网、超时。连通成功只验证配置可用，模型是否适合做默认值由下面的质保/延迟结果决定。
- 固定语料覆盖人名、专有名词、数字、日期、否定、中英混说、代码、长草稿；记录短句/长句端到端 p50/p95、超时率、token 用量。
- 核对各正式配置返回的 `usage`、模型和缓存字段，并验证价格规则匹配相应区域、套餐和模型。接入自定义网关时单独核对用量透传。缺费率或字段不足时仍可完成润色，费用显示未知。
- 如开启豆包 `enable_ddc`，先用同一批录音比较识别侧语义顺滑与 LLM 语音整理，再决定是否保留自动/手动润色链路。
- 质量人工核对姓名、数字、否定和专业术语；不以“有返回”当作质量通过。
- AI 翻译固定语料覆盖中→en/ja/fr/ar、en→zh、已是目标语言、中英混说、代码/命令、人名/数字/否定、RTL 输出；核对是否漏译、增译或改写代码。
- 确认日志无正文、Key 不在 `shared_prefs`、升级/重启后配置与自动开关行为正确。

## 11. 分阶段实施

**P1（最小可用）**

- 单条加密配置、Chat Completions 与 Anthropic Messages 编解码器、服务设置和连接测试；
- 两套内置提示词（语音整理、文本校对），按来源自动选择，只读；
- 四类文本手动润色、选区处理、剪贴板保护、取消、单步撤销、有限错误原因；
- 按模型档案发字段，一次动作最多一次 POST；
- 本机调用统计、Token 分项、费用来源、耗时与统计页；
- JVM 测试 + 草稿/设置 UI 测试。

**P2（可用性）**

- 自动润色（KeepDraft + 空草稿首段 + 只处理本轮新语音片段）；
- 自定义模式、按应用规则（同名共享规则）、用户明确选择的结构化/正式化改写；
- AI 翻译（任意 BCP-47 目标语言，显式选择，与本地译文互斥）；
- 润色后确认发送的完整状态提示。

**后续服务目录增量**

- 多服务配置、模型候选、内置版本快照与周期更新。验收覆盖关闭界面后由系统调度、离线回退、切换服务恢复配置及更新后保留手填模型。

**P3（可选）**

- 流式 partial 预览；
- 热词注入；
- 结果对比视图。

已有范围见 [74 · Android AI 润色实施记录](74-android-ai-polish-implementation.md)。后续依赖、任务与验收见 [75 · Android AI 润色后续任务](75-android-ai-polish-follow-up-tasks.md)。

## 12. 开放问题

- 百炼的套餐/区域/工作空间地址如何在 UI 中减少选择（预设 + 可编辑地址，还是让用户粘贴完整 Base URL）。
- Claude 除 Haiku 5.5 外，是否值得为 Opus/Sonnet 5.5 提供“接受思考延迟”的预设；P1 不提供。
- 自动润色是否要扩展到“续写”场景；P2 明确只做空草稿首段。
- 是否需要“润色前后并排对比”；当前用独立撤销事务代替。
- AI 翻译与现有本地翻译是否合并成一个“翻译”入口（本地中译英 vs AI 翻译），还是保留两个入口；当前设计保留两个，P2 再评估。
- 目标语言选择器的范围：常用语言列表 + 自定义 BCP-47 标签；是否需要“最近使用”和搜索。

## 13. 参考资料

- [73a · AI 润色提示词模板](73a-android-ai-polish-prompt-templates.md)
- [50 · Android 本地中译英实施与验证](50-android-local-translation-implementation.md)
- OpenAI GPT-6 Luna：<https://developers.openai.com/api/docs/models/gpt-6-luna>；Chat Completions 参数迁移：<https://developers.openai.com/api/docs/guides/latest-model>
- DeepSeek API（OpenAI/Anthropic 双格式）：<https://api-docs.deepseek.com/>
- Anthropic Messages API：<https://platform.claude.com/docs/en/api/messages>
- Anthropic API 版本（2026-10-21 移除 temperature/top_p/top_k）：<https://platform.claude.com/docs/zh-CN/api/versioning>
- Anthropic Effort / thinking：<https://platform.claude.com/docs/en/build-with-claude/effort>
- Anthropic 停止原因（refusal/max_tokens 等）：<https://platform.claude.com/docs/en/build-with-claude/handling-stop-reasons>
- Claude 5.5 迁移说明（采样参数移除、Haiku 5.5 自适应思考）：<https://openrouter.ai/docs/cookbook/evaluate-and-optimize/model-migrations/claude-5-5>
- 小米 MiMo OpenAI 兼容 API（api-key、max_completion_tokens）：<https://mimo.mi.com/docs/en-US/api/chat/openai-api>
- 千问 OpenAI/Anthropic 兼容地址与区域/套餐差异：<https://platform.qianwenai.com/docs/developer-guides/clients-and-developer-tools/other-tools>
- 智谱 GLM-5.3（始终思考）：<https://docs.bigmodel.cn/cn/guide/models/text/glm-5.3>
- 豆包双向流式语音识别 WebSocket（enable_itn/enable_punc/enable_ddc 参数与默认值）：<https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh>
- BCP-47 语言标签注册表（IANA）：<https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry>
- OkHttp Calls / 重试行为：<https://square.github.io/okhttp/features/calls/>
- SayIt 客户端策略、提示词与入口（本地参考，AGPL-3.0，仅按协议独立实现）：[defaults.ts](../refer/SayIt-main/client/src/services/defaults.ts)、[aiPolicy.ts](../refer/SayIt-main/client/src/services/transcription/aiPolicy.ts)、[promptRouter.ts](../refer/SayIt-main/client/src/services/personalization/promptRouter.ts)、[contextAware.ts](../refer/SayIt-main/client/src/services/contextAware.ts)、[asr_doubao_stream.rs](../refer/SayIt-main/client/src-tauri/src/providers/asr_doubao_stream.rs)、[prompt.rs](../refer/SayIt-main/client/src-tauri/src/providers/prompt.rs)
