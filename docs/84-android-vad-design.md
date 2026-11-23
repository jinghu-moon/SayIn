# Android 本地语音识别与 VAD 重构方案

更新日期 2026-11-24。实施中；代码已分阶段提交，真机验收待完成。

SayIn 的语音输入以无感、省心、流畅为目标。用户点一下就能开口，流式引擎尽早上屏，允许自然停顿，收尾时保留首尾字。录音结束后草稿可继续编辑或续写，发送仍按用户设置执行。

项目处于未发布的开发阶段。实现可以重构采集接口、录音状态和调用链，也可以删除被替换的旧实现。以正确性和根因修复为先，兼容层和迁移逻辑不作为设计约束。上线前必须有**修改前基线、新功能测试和原功能回归**。用相同输入对比前后结果，记录有意变化、异常处理及性能数据。 [项目工程规则](../AGENTS.md)

## 实施状态（2026-11-24）

| 阶段 | 状态 | 已提交内容 |
| --- | --- | --- |
| 模型资产与端点计时 | 已完成 | `ffd48e1`：Silero int8/fp32、资产摘要校验、`VadEndpointTimer` 单元测试 |
| 点按智能结束 | 已完成代码与 JVM/构建验证 | `0758c63`：VAD 自动收尾、手动模式收尾、无语音/无有效文本超时、自动结束提示 |
| X-ASR 标点 | 已完成基础接入 | `5beffcc`：CT-Transformer INT8 资产、保守句末标点保护与单元测试 |
| 本地录音中出字 | Qwen 0.6B 已接入；1.7B/Fun-ASR 仍待性能验收 | `9f4bccf`：录音中分段预览、预览 RTF 降级 |
| 连续听写 | 已完成最小模式 | `e743cde`：连续录音到手动停止、云端 `end_window_size`/`max_sentence_silence` 调整 |
| 无文本/慢预览兜底 | 已完成代码 | `0654ca6`：识别文本停滞兜底、Qwen 预览 RTF 门控 |
| 底噪自动校准 | 已完成单元测试 | `5fe1d63` + `ff19c19`：底噪阈值估计；Silero 资产集成测试已加入，设备锁定未执行 |

尚未完成真机验收：设备已解锁，但 MIUI 仍拒绝 ADB 覆盖安装（`INSTALL_FAILED_USER_RESTRICTED`）；实际麦克风、VAD 模型推理、标点模型冷启动、连续录音功耗、音频路由/外放和 1.7B/Fun-ASR 预览仍需在放开安装权限后执行。当前状态不能描述为“全部阶段已通过真机验收”。


## 1. 首版范围

- 点按录音支持本地判停，覆盖手动确认和自动发送两种发送模式。长按录音由松手结束。现有语音栏承担录音、收尾及恢复反馈。
- VAD 与识别并行，从麦克风就绪后的第一批 PCM 开始观察。首版把采集得到的 PCM 按顺序交给识别器，保留首字、尾字和句间停顿。先建立现有 RMS 对照，再评估 Silero 的漏检、误停和开销。
- 应用复用固定版本的 sherpa-onnx AAR，Silero 模型由 APK 提供。模型格式、运行时调用方式和默认参数通过实验确认。VAD 不可用时，保留所选识别引擎和手动停止，简短提示自动结束暂不可用。录音时长上限仍生效。
- 本次重构同时交付 X-ASR 标点质量修复和本地非流式模型的录音中出字。普通点按和长按录音都受益。模型按规格分别验收。
- 连续听写单独验收。它保持录音直到用户停止或触及会话上限，允许识别器逐段更新同一草稿。普通录音的分段解码也可在采集期间运行，沿用点按和长按各自的结束方式。

## 2. 现状与根因

| 位置 | 当前实现 | 对设计的影响 |
| --- | --- | --- |
| 麦克风采集 | `AndroidMicrophone` 用 `VOICE_RECOGNITION` 读 16 kHz 单声道 PCM。单次阻塞读取最多 1,600 个样本，完整读块约 100 ms。 | 记录实际读块和路由。不能把 32 ms VAD 步长当成采集、解码或上屏的时间。 |
| 录音判停 | `SpeechPause` 根据 RMS 和已采样时长判断起声、连续 1.5 秒静音。`SpeakSession` 只允许自动发送模式下的点按录音触发，还要求 `recordingCanFinish`。 | 有效发送模式和是否已有识别文字会影响收尾。流式首字较慢时，静音信号可能在尚无文字时到达。把采集停止迁出发送策略和 partial 文本判断。 |
| 识别定稿 | `SpeechRecognition` 只有在完成收尾的路径上才接受成功终态。X-ASR 的原生端点在录音内部提交句子并重置 decoder 状态。 | 保持一个会话终态入口。识别内部可以多个句级定稿，采集层仅有一次会话结束所有权。 |
| X-ASR 标点 | 当前使用中英带标点模型。流式识别直接提交模型输出，合并函数只处理部分英文空格。已有四条主机推理结果含逗号或问号，四条都缺句末标点。 | 定位模型输出、端点重置和文本合并各自对漏标点、错标点的影响。用手机实录复现。 |
| 非流式本地模型 | Qwen3-ASR 0.6B／1.7B 和 Fun-ASR-Nano 共用 `OfflineSpeechDecoder`。它读取源直到 EOF，之后才按最多 20 秒窗口解码并回调文本。 | 把分段提交和可控预览移动到录音期间。按同一音频对比首字、结尾等待及识别准确率。 |
| 120 秒 | 本地批量 `OfflineSpeechDecoder` 和 R2T2 有 120 秒样本容量保护。 | 这是本地实现限制。X-ASR 及云端的持续时间、配额和结束协议分别核对。 |
| 草稿和回执 | `SpeakSession` 持有录音插入范围、当前会话及发送票据。提交后，等待电脑回执期间草稿被冻结。 | 续写和自动发送继续由会话所有者管理。收到新一轮录音意图时先取消旧倒计时。已提交请求走现有回执核对。 |

[麦克风](../android/app/src/main/java/seeyuer/sayin/asr/AndroidMicrophone.kt) · [RMS 判停](../android/app/src/main/java/seeyuer/sayin/asr/SpeechPause.kt) · [识别任务](../android/app/src/main/java/seeyuer/sayin/asr/SpeechRecognition.kt) · [语音会话](../android/app/src/main/java/seeyuer/sayin/ui/SpeakSession.kt) · [本地离线解码](../android/app/src/main/java/seeyuer/sayin/asr/LocalSpeechDecoder.kt) · [X-ASR 主机基线数据](research/31-x-asr-host-inference.json)

## 3. 采集、识别和发送

| 事件 | 唯一负责方 | 结果 |
| --- | --- | --- |
| 点停止、长按松手、VAD 确认静音、无语音超时或录音上限 | 采集会话 | 按会话 ID 停止采集一次，关闭麦克风。正常收尾前已读的 PCM 按序交给引擎，引擎按协议结束输入。 |
| 流式 partial、分句定稿 | 识别引擎 | 更新冻结插入范围内的当前草稿。相同结果或重复分句按引擎语义合并。 |
| 本次录音成功定稿 | 识别任务 | 排空本次解码和有界文本定稿，取最终转写及结束原因交给 `SpeakSession`。 |
| 判断发送 | `SpeakSession` | 识别成功结束、本次转写非空、当前草稿与目标仍有效时，根据录音开始时的有效策略及现有发送校验处理。手动确认保留草稿，显式「结束并发送」沿用现有条件。 |

### 3.1 职责与事件

使用现有 `PcmSource`、`SpeechRecognition` 和 `SpeakSession` 的职责边界重构。把 PCM 采集和端点判定放在同一会话控制路径，Kotlin 层以确定的样本位置维护起声和静音区间。识别器从有界的顺序音频路径取数据。已有分句合并逻辑可以复用。`SpeakSession` 仍负责草稿、续写、翻译、润色及发送策略。

采集结束使用一次性的命令与状态变更，区分手动结束、静音结束、无语音超时、时长上限、取消和错误。重复命令按会话 ID 忽略。手动动作先取得结束权时，按手动意图处理。采集已经结束时，后来的录音动作按续写流程处理。取消或生命周期失效可作废正在进行的收尾及其发送资格。

需要把 VAD 接入放在**采集侧**，使慢解码、网络抖动和 UI 调度无法改变被观察到的静音长度。模型推理可使用采集 worker 上稳定满足实时预算的路径，或有界的专用 worker，具体由真机测量决定。任何队列积压都须有显式错误或降级，音频仍按顺序处理。UI 状态采用可合并快照，结束边界按会话 ID 保证可达和幂等。移除被替代的 RMS 判停和发送模式门控。RMS 可继续用作音量电平和诊断。 [现有采集队列](../android/app/src/main/java/seeyuer/sayin/asr/StreamingRecognition.kt) · [Handy 音频 worker](https://github.com/cjpais/handy/pull/34)

### 3.2 采集状态

```text
准备中 → 正在采集 → 收尾中 → 完成
             │         │
             └─────────┴──→ 已取消或失败
```

「是否已听到语音」与「从最后一个有效语音帧起的静音时长」是采集会话的内部状态。语音恢复时清除本轮静音计时。若做拖尾提示实验，提示和停止共享同一段静音起点。分句边界单独由引擎或连续听写的分段器处理。

- **静音收尾**从确认听到语音后生效。确认起声不能阻止 PCM 上送。对于轻声和短词，以实际漏检决定起声阈值和最短持续时间。
- **空会话**从首批有效 PCM 开始计算。初始候选为 8–10 秒。到期后停止采集，若引擎尚有可定稿内容，完成一次正常收尾并保留结果供手动检查。最终为空则恢复原草稿和光标，显示「未识别到语音」。这类超时无自动发送资格。
- **时长上限**以产品限制和引擎可用限制中的较早者触发。首版可在本地以 120 秒为上限，采集层须在旧解码器容量溢出前正常停止并排空已接收音频。X-ASR 和云端先确认适用上限。上限触发时保留成功定稿的文本供确认。
- **等待或错误**区分未开麦、正在采集、正在识别及等待电脑回执。连接握手、麦克风停滞和引擎收尾使用各自的错误或超时。停滞使用单调时钟检测，静音使用已采集的样本计量。失败保留原草稿及可恢复的识别内容，阻止自动发送。

### 3.3 草稿、续写和自动发送

| 用户动作或状态 | 行为 |
| --- | --- |
| 自动结束后再次点录音 | 草稿仍可编辑时从当前光标或选区继续。新增录音冻结新的插入范围，保留已有文本和撤销历史。 |
| 倒计时内点录音 | 立即撤销旧票据，等待必要的资源释放，再启动新会话。以已有发送提交点裁决两者同刻到达的顺序。 |
| 收尾中点录音 | 只保留一次续写意图，绑定原识别会话、草稿版本和当前入口。立即清除旧发送资格。正常定稿后在条件仍有效时续写，收尾失败时保留草稿并提供重试。 |
| 润色或翻译尚未完成 | 取消或作废旧任务，使用原文继续录音。过期结果不得覆盖新输入。 |
| 切换入口、离页、锁屏、取消或草稿版本改变 | 作废挂起的续写意图和相关发送票据。迟到回调不得重新开麦。已开始录音的后台处理仍受前台服务及现有生命周期规则约束。 |
| 已提交、等待回执或待核对 | 按现有 Outbox、草稿冻结和电脑回执规则处理。原请求解除冻结后才恢复可编辑动作。 |

现有目标校验、草稿版本校验、IME 上屏要求和电脑端执行前核对仍适用。键盘、粘贴及输入法语音需先点发送。配置的发送等待时长从**本次完整识别定稿且发送条件满足**后开始，VAD 停采集、句级事件与无语音超时都不能启动倒计时。 [发送策略](72-android-send-policy-settings.md) · [当前发送会话](../android/app/src/main/java/seeyuer/sayin/ui/SpeakSession.kt)

## 4. 引擎接入

| 引擎 | 当前实时体验 | 首版处理 | 连续听写验证重点 |
| --- | --- | --- | --- |
| X-ASR | 实时 partial。原生端点合并已定稿句子。 | 原样尽快展示 partial。定稿时修复漏标点和错标点。保留 `finishInput()` 的 800 ms 补尾及排空作为对照，先测原生端点重置和文本合并。 | 保留权重，仅重置或替换流。根据对照修复有问题的句级端点，验证问号、句号、词边界和重复文本。 |
| Confucius4-R2T2 | 现有滑窗逐步预览，约 1.2 秒音频触发首轮推理，此后按窗口更新。 | 保留现有预览和归并机制。采集停止后使用剩余 PCM 做最终解码。 | 验证预览与最终文本一致性、长窗口衔接、解码积压及资源释放。真机单独统计它的首字和定稿时延。 |
| 本地 Qwen3-ASR 0.6B、1.7B | 结束录音后解码，现有实现按最多 20 秒的窗口处理。 | 在录音期间逐段解码。持续说话时，按设备能力生成当前段预览。收尾只处理未定稿音频。 | 两个规格分别测分段质量、队列等待、解码时延、峰值内存及发热。 |
| Fun-ASR-Nano 2512 INT8 | 与本地 Qwen 共用结束后解码路径。 | 复用同一录音中分段和预览调度。保留引擎原有转写及数字等文本归一化规则。 | 单独测准确率、上下文衔接、首字和长时热机。 |
| 豆包流式 2.0 | `bigmodel_async` 全文快照。当前客户端根据协议末包终结任务。 | 先保留已验证的请求参数和末包处理。正常停止时发送已读余包和结束标志，等待任务终态。 | 按官方分句字段验证稳定前缀与全文更新的合并规则，覆盖有无 `definite`、二遍识别、空尾包及错误。 |
| 云端千问 | 当前使用 `qwen-audio-3.1-asr-flash-streaming`。`sentence_end` 提交句子。 | 发送已读余包后发 `finish-task`，继续接收至同一 `task_id` 的 `task-finished`。 | 按句 ID 归并，处理 partial、重复、迟到和心跳，检查任务时长与音频用量。 |

[本地模型目录](../android/app/src/main/assets/local-asr-catalog.json) · [X-ASR](../android/app/src/main/java/seeyuer/sayin/asr/XAsrDecoder.kt) · [R2T2](../android/app/src/main/java/seeyuer/sayin/asr/R2t2Decoder.kt) · [豆包](../android/app/src/main/java/seeyuer/sayin/asr/DoubaoRecognition.kt) · [云端千问](../android/app/src/main/java/seeyuer/sayin/asr/QwenRecognition.kt)

### 4.1 云端分句对照

豆包实时接口文档列出 `end_window_size` 默认 800 ms，范围 300–5000 ms，推荐 800–1000 ms。`vad_segment_duration` 只影响语义分句，显式设置 `end_window_size` 后失效。`show_utterances` 控制分句字段返回，`definite` 表示该句稳定。SayIn 当前以 `result.text` 作为全文快照，未显式请求 `show_utterances`，也未开启二遍识别。`end_window_size` 设为 2000–3000 ms 作为单变量实验，记录标点、纠错、末包延迟及费用影响后再决定。 [火山引擎实时识别](https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket)

云端千问按当前 Streaming 接口核对参数。官方列出 `max_sentence_silence` 默认 800 ms，范围 200–6000 ms。近场 `vad_model` 作为实际手机麦克风和耳机的对照项。句级结果可以推进草稿更新，整场正常结束后的任务终态才提供发送所需的最终转写。持续听写优先沿用同一任务，按厂商能力确定时长和分句边界。厂商限制要求分任务时，先验证任务间无漏音、重复文字或错误补发。跨任务持续录制也只在用户结束整场后结算发送资格。断线保留已有草稿，沿用显式重试。 [阿里云 Streaming 客户端事件](https://www.alibabacloud.com/help/en/model-studio/qwen-audio-asr-streaming-client-events) · [阿里云 Streaming 服务端事件](https://www.alibabacloud.com/help/en/model-studio/qwen-audio-asr-streaming-server-events)

首版沿用各厂商已验证的余包和结束协议。额外合成尾静音只作为按厂商验证的实验，覆盖尾字、末包时延及上传时长。X-ASR 本地补尾的 800 ms 是输入给模型的样本长度，计时应使用其实际处理开销。

### 4.2 X-ASR 本地标点定稿

采用专用标点模型处理 X-ASR 的句段定稿。首选 sherpa-onnx 中英 CT-Transformer INT8，通过现有 `OfflinePunctuation.addPunctuation()` 在本机运行。模型接收识别文本，可根据上下文补充句内及句末标点。模型资产、真机质量和定稿耗时通过验收后启用。 [官方预训练模型](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html)

1. **先定位丢失位置。** 使用相同标注 WAV 记录模型 partial、端点重置前输出、`inputFinished()` 并排空后的输出，以及草稿里的结果。给疑问句、陈述句、数字和中英混说标注。分别对照原生端点开关、重置前排空和文本合并策略。模型本身没给标点时测文本补标点，端点或合并导致丢字、丢符号时修复对应实现。现有四条 Windows 推理结果只提供复现线索，当前手机路径另采基线。 [X-ASR 生产解码器](../android/app/src/main/java/seeyuer/sayin/asr/XAsrDecoder.kt) · [端点与文本合并](../android/app/src/main/java/seeyuer/sayin/asr/StreamingRecognition.kt)
2. **在稳定文本上补标点。** 官方文档中的 CT-Transformer INT8 `model.int8.onnx` 约 72 MB，现有 AAR 包含 `OfflinePunctuation` Kotlin 类。先做真机 JNI 初始化、资产校验和耗时测量。实时 partial 直接用 ASR 文本。在句段边界或录音收尾时，以带上下文的短文本窗口预测边界标点。前文可作为上下文，候选结果只应用于本次录音尚未冻结的尾部。官方 `online-punct-en` 面向英文，中英混说用上述双语候选。 [官方模型及文件说明](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html) · [v2.0.1 Kotlin 接口](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflinePunctuation.kt)
3. **用结构保护和质量对照决定采用范围。** 保留原有高质量符号。默认只插入遗漏的自然语言标点。替换逗号、句号、问号等已有标点前，先用标注语料证明替换规则改善结果。以字符对齐保证正文、大小写、空格及受保护片段保持原值。保护数字范围、小数、日期、版本号、网址、邮箱、路径、代码和命令，遇到无法可靠对齐的输出提交原识别文本。测试可分别对比原生标点、轻量句界规则和模型输出。问号等语义符号由文本上下文和验收数据决定。
4. **只在定稿路径等待一次。** 模型按需预热并复用。记录加载、推理和排队耗时，按实测设处理预算。超时、错误或被更晚的草稿版本取代时作废补标点结果。完整定稿时选定原文或校验通过的格式化文本，然后才交给翻译、润色及发送。定稿后编辑、撤销、新录音或等待电脑回执时，旧结果不能追加或覆盖草稿。

「`1~11件套餐配送`」作为数字范围回归样例。单独作为短语时保留原样。放进完整的陈述或疑问句时，可在句尾补对应的句号或问号。例如，`1~11件套餐都配送吗` 可补为 `1~11件套餐都配送吗？`。两种用法均须完整保留 `1~11件`。录音若转写成「1到11件」，保持识别出的数量、单位和范围含义。把口述数字改写成规范格式属于 ASR 文本归一化，由对应环节独立验证。

#### 标点模型选型与对照

本轮按 **X-ASR 原生输出 → CT-Transformer INT8 手机端验证 → 高质量模型对照** 的顺序推进。先用同一批转写保留原生标点基线，再跑 INT8 和同源 FP32。用 FunASR large 和 FireRedPunc 的官方权重做桌面质量对照。手机端启用以句末漏标点减少、受保护片段原样保留、首字和定稿时延达到第 8.3 节要求为准。FireRedPunc 的 ONNX 版在对照显示有收益时进入 Android 验证。

| 模型 | 核实的资产 | SayIn 用途 |
| --- | --- | --- |
| sherpa-onnx CT-Transformer 272727 INT8 | 官方下载包 64,717,756 字节（61.7 MiB），解包后推理只需要官方文档所列约 72 MB 的 `model.int8.onnx`。对应 FP32 下载包 279,028,058 字节（266.1 MiB）。 | Android 首选，复用 `OfflinePunctuation`。同源 FP32 用来检查 INT8 的句末标点、召回率和耗时。部署只随包提供通过验收的模型文件。 [官方模型文档](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html) · [下载 INT8](https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2) · [下载 FP32](https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12.tar.bz2) |
| FunASR `ct-punc`（471067 large） | 官方仓库的 `model.pt` 为 1,125,507,622 字节（约 1.13 GB）。`ct-punc-c` 指向较早的 272727 模型，sherpa 的中英 272727 候选由该系列转换。 | 用 471067 large 做桌面质量对照，验证大型模型对 SayIn 转写的实际提升。 [FunASR 模型映射](https://github.com/modelscope/FunASR/blob/main/funasr/download/name_maps_from_hub.py) · [官方权重](https://huggingface.co/funasr/ct-punc/tree/main) |
| FireRedPunc | 官方仓库提供约 407 MB 的 PyTorch 标点检查点 `model.pth.tar` 及 tokenizer 等依赖。第三方 `punc.q8w.onnx` 文件约 163 MB，有独立词表和标签映射。 | 先用官方检查点做质量对照。若同语料显著改善且符合时延目标，再核对 ONNX 资产、量化一致性、分词、标签顺序和 Android 运行时。 [官方权重](https://huggingface.co/FireRedTeam/FireRedPunc/tree/main) · [第三方 ONNX 模型卡](https://huggingface.co/jiangzhuo9357/fireredpunc-onnx) |

FireRedPunc 技术报告中的平均标点 F1 为 78.90%，FunASR-Punc 为 62.77%。中文子集分别为 82.96% 和 75.62%，英文子集为 74.83% 和 49.91%。评测使用作者的独立中、英文内部语料，整体统计排除感叹号。SayIn 另测 X-ASR 的中英混说、原有标点、数字范围、终端命令及实际 Android 定稿延迟。用同一批原始 ASR 文本和同一套标点保护、对齐规则比较各模型，分别报告漏标点、误加、错标点、正文变化及冷／热定稿 p95。 [FireRedPunc 技术报告](https://arxiv.org/html/2603.10420v1)

其他候选按需求评估。PaddleSpeech 的 Wudao p3／p7 官方配置面向中文，适合比较顿号、冒号等覆盖能力。`yeyupiaoling/PunctuationModel` 可用于需要自行训练或调整标签时的研究，规则方案可作为轻量对照。Horizon Labs base 模型卡列出 88 种语言和约 641 MB 的量化 ONNX，适合评估多语言需求。 [PaddleSpeech 模型说明](https://github.com/PaddlePaddle/PaddleSpeech/blob/develop/demos/punctuation_restoration/README_cn.md) · [PunctuationModel](https://github.com/yeyupiaoling/PunctuationModel) · [biaodian](https://github.com/Jeffrey0117/biaodian) · [Horizon Labs 模型卡](https://huggingface.co/Horizon-Labs/punctuation-restoration-base)

### 4.3 本地非流式模型在录音中解码

共用一条音频采集链路和每会话一个顺序解码 worker，复用当前加载的 Qwen3-ASR 或 Fun-ASR-Nano 权重。VAD 给出可解码的段边界，采集继续，已确认的文本随段追加。用户一直讲话时，用同一模型对当前尚未结束的有界句段做低频预览。界面显示稳定前缀和可替换的本段尾部，下一轮预览仅替换相同音频范围。官方 sherpa-onnx 的 Qwen3-ASR 麦克风示例提供了 VAD 加离线识别的分段路径。长段预览额外参考相邻推理结果一致后冻结部分文字的做法，先测准确率和重复推理成本。 [sherpa-onnx Qwen 麦克风示例](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html) · [Whisper-Streaming 的 LocalAgreement](https://github.com/ufal/whisper_streaming)

- **自然停顿提交整段。** 分段静音首轮从 0.60 秒试验。每段记录绝对样本区间 `[startSample, endSample)` 和可用时间戳。保留段内的自然停顿，约 350–450 ms 的 pre-roll 用于保护首字。重叠边界用样本范围、文本与必要的词时间戳校验。短语重复是可能的原话，禁止仅按字面相同删除。
- **一直说话也能出字。** 首轮试在累计约 2–3 秒有效音频后开始预览，再依据解码用时选间隔。仅保留一个最新的待处理预览请求，忙时合并过时预览请求。原始 PCM 与必须定稿的段按序保留。约 20 秒是现有窗口的对照值，到达窗口上限前优先沿已观察到的弱语音边界切分。通过连续讲话、跨段词和中英混说校验。
- **停止后只处理尾部。** 最终段优先于尚未开始的预览。正在运行的预览只有在运行时确认可安全中断时才取消 native 调用，否则作废结果并把等待计入尾延迟。冻结的段作为本场录音的稳定前缀，最终只解码尚未确认的尾段，按段顺序合并。自动发送仍等待本场最终稿。
- **按模型和设备控制积压。** 限制 PCM、待定稿段、内存和预览频率。如果预览挤占定稿或采集资源，优先降低预览频率，保留自然停顿的段落提交。必须定稿的队列满时显式收尾或报错，保留已取得的转写。各模型在连续讲话场景通过首字和停录延迟门槛后，才标记支持录音中转写。较慢的规格保留录完识别入口和明确提示，并记录未达标数据。
- **保持内容保真。** 有声占比 0.20 作为识别幻觉的离线对照参数。用短词、轻声和长停顿验证误删风险。对比段内和跨段 CER／WER、标点、重复及漏词。10 秒片段的 `RTF p95 ≤ 0.20` 可作为筛选数据，开放资格看完整录音的端到端延迟、温升和准确率。

本节也应用于常规点按与长按录音。连续听写增加的是更长的会话和分段边界管理。R2T2 已有原生实现里的录音中预览和尾段收敛。本轮用它作体验基准，复用其已有路径。

## 5. Silero 资产与运行时

| 候选 | sherpa 模型表所列体积 | 用途 |
| --- | ---: | --- |
| K2-FSA `silero_vad.int8.onnx` | 约 208 KB | 首选量化候选。固定文件后核对 16 kHz 下的输入及状态约定。 |
| K2-FSA `silero_vad.onnx` | 约 629 KB | 同发布族浮点候选。A/B 前确认具体导出、权重来源和量化关系。 |
| 上游 Silero v5 ONNX | 约 2.22 MB | 独立模型格式参照。在真机上与量化候选比较质量和运行开销。 |

使用固定 `sherpa-onnx-2.0.1.aar` 做验证。`Vad.compute()` 直接转发模型计算，调用方负责阈值、起声持续时间和静音时长。v2.0.1 源码对上游 v5 在 16 kHz 下以 512 样本推进，输入窗口为 512 加 64 样本。K2-FSA 导出的图由运行时按其输入输出签名识别。按**选定文件 + 固定 AAR**确认实际模型窗口、步长、上下文来源、首尾填充和跨会话重置规则。 [sherpa 模型表](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) · [v2.0.1 Silero 源码](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/csrc/silero-vad-model.cc) · [compute JNI 变更](https://github.com/k2-fsa/sherpa-onnx/pull/2433)

采集块可以少于或多于模型步长，使用有界缓冲保留残余样本并按顺序推进。连续帧共享模型状态，新录音和不连续输入重置状态。测试固定 WAV 拆成多种读块时的概率、语音样本范围和结束事件。结束时残余 PCM 仍进入 ASR，只有 VAD 模型按其输入约定处理不足窗口。选用 `compute()` 时，`minSilenceDuration`、`minSpeechDuration` 和 `maxSpeechDuration` 不承担产品计时。若实验表明使用 sherpa 内部分段更可靠，则由该路径独立负责对应边界，保证每个边界只计时一次。 [sherpa 检测器实现](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/csrc/voice-activity-detector.cc)

记录所选 ONNX 的下载地址、版本、字节数、SHA-256、图输入签名、许可证及构建变体。沿用项目资产校验方式，构建检查实际文件并附 MIT 许可证。Debug 可提供实测候选，Release 纳入通过验收的一份模型。TEN 目前只作备选调研，采用前另行核对许可证附加条款。 [现有 ASR 资产清单](../android/asr-artifacts.json) · [Silero 许可](https://github.com/snakers4/silero-vad/blob/master/LICENSE) · [TEN 许可](https://github.com/TEN-framework/ten-vad/blob/main/LICENSE)

## 6. 参数、反馈与真机音源

| 项目 | 首轮对照 |
| --- | --- |
| 句尾静音 | 固定 1.5 秒，用于和现有 RMS 对照。0.8／1.2／1.5 秒自适应作为后续候选，尤其验证短句开头接思考停顿的情况。 |
| 起声与静音阈值 | 0.45 及 150 ms 可作首轮样本起点。比较 0.35／0.60 与实验阈值，观察弱语音、短词、噪声持续触发及阈值附近抖动。 |
| 空会话 | 首轮 8–10 秒，从首批 PCM 计时。单独统计启动后立即说话、输入静音和无识别结果。 |
| 下探底噪 | 300–500 ms 环境估计是实验。已有可信静音时才更新估计，用户立即开口时照常采集和转写。 |
| 分句静音 | 连续听写首轮约 0.60 秒，用于产生解码段或推进草稿。 |
| 设置 | 用户可以控制是否使用点按录音的自动结束。噪声档位与调参值只供调试，发布默认依据真机数据。 |

沿用说话页和快捷卡语音栏。云端握手或模型初始化期间显示准备状态，实际开始录音后再显示正在聆听。单独测立即开口的首字保留率。录音显示电平与文本，真正进入收尾后显示「正在识别…」，自动结束可短暂提示「点录音继续」。生成结果后才展示适用的发送倒计时。等待电脑回执有独立状态。保持常用操作可触达，优先验证大字号和中英排版。`Trailing` 动画、呼吸灯和轻震动单独做体验对照，确认有帮助后再决定是否加入。UI 反馈 100 ms 可作为从事件发出到画面变化的目标，另测真实音频到交互可见的完整路径。

维持 `VOICE_RECOGNITION` 作为对照。比较 `VOICE_COMMUNICATION`、`MIC` 和设备实际支持的 `UNPROCESSED`，记录真实路由、AEC/NS 是否可用并实际启用。`UNPROCESSED` 在不支持时会表现为 `DEFAULT`。`VOICE_COMMUNICATION` 的 AEC 按设备实际能力启用和核验。重点用内置麦、蓝牙、视频或游戏外放同时录制轻声和短词，结合弱语音漏检、CER 和句尾完整性判断是否切换音源。 [输入音源](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) · [Android 音频预处理](https://source.android.com/docs/core/audio/implement-pre-processing) · [AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler) · [AudioRecord 路由](https://developer.android.com/reference/android/media/AudioRecord)

外放含可懂人声时，VAD 和 ASR 都可能把它当成输入。用录音上限防止无限录音，实测 AEC 改善并保持手动停止可用。识别文字 3.5–4 秒未增长仅用于诊断，因为网络等待、出字慢和等长修订也会产生这个现象。有可靠的外放依据且确实影响体验时，可试一次简短的耳机提示。 [SayIt 无结果诊断](../refer/SayIt-main/client/src/services/recorder/RecorderOrchestrator.ts)

## 7. 同类项目对照

| 来源 | 观察结果 | 对 SayIn 的取舍 |
| --- | --- | --- |
| SayIt | 本地识别前用 Silero 找边界，保留句子中间停顿。无结果时区分采集到静音和已有音频但未产出文本。 | 保留原始语音与句间停顿，分别记录采集、端点和 ASR 故障。 [VAD 源码](../refer/SayIt-main/client/src-tauri/src/models/local_vad.rs) · [录音流程](../refer/SayIt-main/client/src/services/recorder/RecorderOrchestrator.ts) |
| Handy | 音频由独立 worker 重采样和做 Silero VAD 平滑。录音的停止仍遵循按住松手或切换手势。 | 借鉴 worker 隔离和长按语义，先保持音频连续。 [源码变更](https://github.com/cjpais/handy/pull/34) · [项目说明](https://github.com/cjpais/handy) |
| sherpa-onnx 示例 | 分别提供纯 VAD、VAD 加非流式和 VAD 加实时识别的 Android 示例。 | 用示例交叉核查模型窗口和生命周期。SayIn 的草稿、发送和续写由应用层决定。 [Android 示例](https://k2-fsa.github.io/sherpa/onnx/vad/apk.html) |
| Deepgram | 文档区分语音间隙、稳定文本和用户已经说完一段意思。停顿后仍可能继续讲话。 | 自动收尾参数以人工标注的意图测量，优先保护思考停顿，保留一键续写。 [结束检测](https://developers.deepgram.com/docs/understanding-end-of-speech-detection) · [Utterance End](https://developers.deepgram.com/docs/utterance-end) |

## 8. 修改前后验证

### 8.1 基线先跑

1. 用当前产品版本记录代码版本、未提交改动、设备和系统、输入路由、采样率、引擎／模型、云端配置类型及相同 WAV 的标注和摘要。测试日志省略凭据、音频正文和草稿。
2. 使用同一回放夹具测当前 RMS 1.5 秒、手动停止、已有的发送和回执状态。测试组合覆盖手动确认、自动发送、长按及快捷入口。记录当前手动确认模式下点按不会因 RMS 自动结束。
3. 先运行现有 `SpeechPauseTest`、`StreamingRecognitionTest`、`SpeakInteractionTest`、`DoubaoRecognitionTest`、`QwenRecognitionTest`。回归沿用 `UnifiedSpeakInstrumentedTest`、`XAsrInstrumentedTest`、`TranslationSessionInstrumentedTest` 和现有润色测试。保留测试命令、通过／失败数及真机数据。 [Android 单元测试](../android/app/src/test) · [设备测试](../android/app/src/androidTest)

### 8.2 增加测试并使用同样条件复测

| 场景 | 修改前记录 | 修改后验收 |
| --- | --- | --- |
| 点按录音，手动确认 | 测手动停止、草稿定稿和现有 RMS 门控 | 有语音后的静音可收尾，结果留草稿。录音开麦到首字和首尾字完整性满足指标。 |
| 点按录音，自动发送 | 测 1.5 秒静音、非空定稿后倒计时及目标变更 | 整场成功定稿才允许倒计时，空会话、限时结束、失败、迟到 final 和目标变化均不会自动发送。 |
| 长按、主动停止和取消 | 测松手、快速点停、离页与取消恢复 | 长按自然停顿仍可继续。手动停止可立即收尾，即使流式首字尚未来。手动与 VAD 同帧触发时只出现一次结束动作。取消不提交正文。 |
| 续写、倒计时竞争和冻结草稿 | 测已有撤销与草稿范围，记录等待回执状态 | 可编辑草稿续写一次。旧票据撤销。已提交请求保持冻结。用户连续点按或切换入口时无重复录音、发送或迟到开麦。 |
| X-ASR 标点 | 保存原始 partial、重置前结果、末尾排空结果和草稿。记录每类标点正确、漏加、乱加和替换数据 | 同一语料对照应用修复前后。使用按字符位置和类别对齐的标点 F1，分别报告句号与问号的精确率、召回率。正文 CER／WER、受保护片段及首字时延保持在验收范围内。 |
| 非流式本地录音中出字 | 分别测三种模型的首字、停录至首字与完整定稿耗时 | 用同一段长录音断言在结束采集前可见识别文字。短录音立即停时仍可出完整最终稿。连续无明显停顿、快速停录和补录均无漏段、重复或迟到覆盖。 |
| 双云协议、原生端点及离线解码 | 记录各引擎 partial、分句、任务终态顺序及故障行为 | 新增分句先到、协议末包、空尾包、超时、断网和重复事件用例。分句可上屏，异常时草稿可恢复，发送保持安全。 |
| 音频分帧与模型生命周期 | 记录采集块长度、音量和内存 | 新测不等长块、残余样本、窗口上下文、预热、重复开关、重置、过载及设备切路。PCM 顺序与样本范围一致，无静默丢包。 |
| 键盘、IME、粘贴、翻译和润色 | 跑现有草稿、输入法及翻译、润色用例 | 仍能手动发送。保持译文、原文与撤销语义。迟到的翻译或润色结果不得覆盖续写。 |
| 设备与入口 | 真机记录完整页、快捷卡、悬浮球、磁贴和麦克风前台服务 | 重复同组场景，覆盖权限拒绝、来电录音中断、蓝牙切换、锁屏、服务回收。麦克风及时关闭，错误语义正确。 |

增加确定性用例覆盖 first partial 早于采集 EOF、一次 final 优先于未执行的预览、句段结果顺序、旧回调作废、末段排空和标点候选超时。补标点测试覆盖跨窗口疑问句、标点重复、数字范围 `1~11件`、版本号 `v1.2.3`、小数 `3.14`、混合文本和命令。追加连续听写队列满、跨段词、短词与长停顿、长轮次、双云分句顺序和资源上限测试。所有测试先建基线，再使用固定输入、环境和统计口径对比。同一断言只有在文档明确改变预期行为时才调整，分别登记变更原因和证据。

### 8.3 体验与性能验收

**时间戳**以单调时钟记录点按→可采集、第一段标注语音→首个可见文本、最后语音→停采集、停采集→整场定稿。另记 UI 反馈、发送倒计时起止和提交→电脑回执。预录回放与真机实录分别报告。冷启动、热启动、设备、输入路由、引擎和网络异常分开统计样本数、p50、p95、超时和失败率。

| 引擎 | 正常场景调参后的验收目标 | 额外检查 |
| --- | --- | --- |
| X-ASR | p50 ≤ 1.2 秒，p95 ≤ 1.8 秒 | 首字与队列等待。1.5 秒基线单独报告。候选参数达标还需通过抢话和首尾字检查。 |
| 豆包、云端千问 | 各自 p50 ≤ 1.5 秒，p95 ≤ 2.5 秒，网络正常时测量 | 单列握手、末包及失败样本。 |
| 本地 Qwen 两档、Fun-ASR-Nano | 各自对 ≤10 秒片段争取 p50 ≤ 2.0 秒、p95 ≤ 3.0 秒。另测超过 10 秒的长录音从开口到首字、停止后尾段的 p50／p95。 | 长录音在仍采集时可见文字，持续说话无停顿场景也须覆盖。分别登记预览、定稿、队列耗时和 RTF。 |
| R2T2 | 先建立真机基线，填入首字和最终定稿指标后验收 | 逐步上屏、尾字保留、长段积压、预览重算。 |

表中的延迟以最后语音到整场定稿计算。若延迟目标和抢话率冲突，先按停顿类型复查并保留语音完整性。

短句、300 ms 短词、轻声、中英混说、数字和专名、0.5／1／1.5／2 秒思考停顿、纯静音、风扇键盘、外放串音、蓝牙与长录音都要有标注。比较 CER／WER、标点、首尾字、重复或整段漏识别，并单列错误触发的发送次数。X-ASR 放行要有标点 F1 提升和漏标点减少的数据，同时复核问号、专名及命令的完整性。补标点的首字与最终定稿开销分别记录。非流式路径须报告录音中有文字的比例、首字 p50／p95、可见文本更新间隔及停录到完整稿的 p50／p95。用现有整段识别输出作质量对照，确认停录后等待缩短，并在目标设备长时间录制时保持有界积压。加入正常 CPU 竞争和热机条件，记录 PCM 队列、CPU、峰值内存、耗电、发热与 VAD 模型冷启动开销。

抢话率使用人工标注的有效续说机会为分母，记录意图未完成时被自动停录的比例。停录后 2 秒内再次录音作为辅助行为数据。30–50 段适合发现问题。若用零误停支持低于 2% 的目标，按独立样本计算，约需 150 个有效机会构造约 95% 的单侧上限。漏结束以最后标注语音到开始收尾超过 2.5 秒为初始诊断阈值，另记达到录音上限才结束的比例。分别统计噪声误触发和有声但无 ASR 文本。

调试时间线仅在本机有界保存帧级概率、样本位置、状态事件、参数、路由和设备信息，用户主动导出。音频及文本使用独立的测试夹具管理。

### 8.4 构建与交付证据

- 基线与实现后均运行 `scripts/gradle.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。UI 文案变更运行 `python scripts/check_i18n.py`。
- 真机执行 `scripts/verify_android_transport.ps1 -FullSuite` 的隔离回归。另用实际录音覆盖本文设备矩阵。云端用协议夹具跑常规回归，真实服务延迟和参数实测记录使用环境、请求范围和可能产生的费用。隔离回归证据与真实麦克风／目标应用证据分别记录。
- 实现完成后构建日常 Debug 包，用 `adb -s <设备序列号> install -r android/app/build/outputs/apk/debug/app-debug.apk` 覆盖安装并核验保留原配对、草稿和历史。记录中英文语音栏截图和实际操作结果。
- 验收记录包含前后命令、执行环境、测试通过和失败数量、关键行为差异、指标变化以及未覆盖场景。误发、漏录、重复提交、回执冻结或资源释放发生回归时，先修复原因，再重新执行受影响的验证。 [Gradle 包装脚本](../scripts/gradle.ps1) · [隔离回归脚本](../scripts/verify_android_transport.ps1)

## 9. 交付顺序

1. **基线与资产验证。** 对当前版本运行基线并留证。锁定模型和 AAR 组合。用同源 WAV 对照模型、PCM 分帧、模型状态与资源加载。
2. **点按自动收尾。** 解开采集结束与发送模式的绑定，加入空会话、时长和错误处理。复用引擎既有分句、余包和定稿路径。完成一键续写、草稿冻结和倒计时竞争测试后，按各入口真机数据决定默认开启。
3. **X-ASR 标点和本地录音中出字。** 两项都纳入本次重构验收。先修复端点和文本合并中已定位的问题，接入专用中英离线标点模型，通过同语料对照确认标点改善、数字范围完整和定稿延迟。为 Qwen 0.6B／1.7B、Fun-ASR-Nano 接入录音中分段解码和有界预览，真机逐档记录结果。
4. **连续听写。** 在常规录音的出字、收尾及质量达标后，验证长会话资源、分段和各引擎的任务上限。
5. **单项优化。** 根据前后数据分别验证短句自适应、底噪估计、云端分句、合成尾静音以及更换音源或录音反馈。每次记录实际收益和退化。

本轮记录的是设计。默认资产、首版参数、设备性能、厂商实测参数及各引擎连续听写放行状态待实施记录填入。
