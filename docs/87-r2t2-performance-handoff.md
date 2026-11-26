# SayIn Android · Confucius4-R2T2 性能问题交接

记录截至 2026-11-25。工作区为 `D:/100_Projects/110_Daily/SayIn`。任务范围是 Android 本地 R2T2 推理、录音期间上屏与停麦后的定稿。K40 是最近一轮的主测试机，K90 Pro Max 有较早的独立实测记录。

## 1. 用户要的体验

1. 点击「说话」后尽快可说话。通过预加载、预热和模型实例复用减少等待。冷启动时应保留已经采集的首音频，防止用户一开口就丢字。
2. 一边说一边识别、持续增量上屏。用户已经感受到提速，但 R2T2 仍隔几秒分批出字，明显不及 X-ASR 480 ms。长时间连续说话时也应持续更新。已确认前缀稳定，未确认尾部可以由最终结果校正，避免重复、闪烁和整段回改。
3. 说完后快速定稿。普通点按录音在最后有效语音后自动停麦，静音阈值可选 2、2.5 或 3 秒，默认 2.5 秒。长按松手结束，连续听写由用户结束。手动停麦也应快速排空已采集音频。
4. 快速和准确同时验收。保留弱起声、短词、真实尾词、中英混说和数字，裁掉确认无效的尾部静音或噪声，避免随机尾字和分段缺字。后处理经过现有 ITN、CT，分别度量原始 ASR 与最终上屏结果。自动发送只能由成功定稿及现有发送策略触发。
5. 项目处于开发期，允许直接重构核心路径，先固定基线，再用同一输入及设备验证改动、相关已有功能和边界。完成产品改动后构建 Debug 并覆盖安装到指定手机。

**计时口径。** 需要分别记录点按到开麦、开口到首字、录音中最长无更新、最后有效语音到停麦、停麦到定稿。静音等待和推理耗时分开。用户曾追问 16 秒音频编码为何仍超过 5 秒。研究记录将 **16 秒编码低于 5 秒**作为分项检查，完整体验另行验收。现有 V3 记录以停麦后 3 秒为收尾参照，R2T2 仍需按设备单独统计实际分布。X-ASR 是体验对照，其 `p95 ≤ 1.8 秒` 是 X-ASR 的首字目标。 [V3 用户行为及验收](85-android-asr-v3-refactor.md#1-用户可见行为) · [R2T2 体验度量](84-android-vad-design.md#83-体验与性能验收)

## 2. 问题如何出现

用户最先遇到的是录音时看不到文字，点击结束后还要等很久。接入 VAD 自然分段和流式预览后，R2T2 已能在录音中发布稳定文字，但计算跟不上音频采集时，会先长时间空白，再连续回调一批 token。用户进一步要求定位音频编码和前填各自的开销，分别在 K40、K90 Pro Max 和电脑上做对比，并研究官方 R2T2 与 `audio.cpp` PR #696。

早期 K40 上，20.106 秒重复混说从固定 16 秒窗口改为优先 VAD 分段，供帧停止至定稿从 **34.188 秒降到 23.382 秒**，两版都能在录音时出字。随后逐步优化 CPU 内核、预览优先级和缓存，同一类输入已有显著提速。长句仍经常出现接近 10 秒甚至更长的无更新间隔，短句也可能在录音结束后才首次出字。用户反馈「现在速度快了不少」，追问的是持续上屏的频率与停录后的剩余等待。 [早期复测](84-android-vad-design.md#真机复测) · [首窗时机最新对照](84-android-vad-design.md#k40-首窗预览时机对照)

## 3. 架构与当前运行路径

R2T2 基于 Qwen3-ASR-1.7B，用稳定前缀训练确定哪些词可以提交。它的音频编码器在一个注意力窗口内双向计算，活动窗口增长时，新的音频会改变已有位置的编码表示。X-ASR 480 ms 使用带流式状态的 Zipformer Transducer，保存编码和解码状态、随新块向前推进。R2T2 的稳定前缀和 X-ASR 的增量状态解决的是两个不同问题。官方 GGUF 示例默认 160 ms 的 chunk 只是送入或查询的节奏。官方示例 README 将 GGUF 的缓存复用与后缀验证列为后续工作，论文、小块参数和不同后端的实测要分别阅读。 [R2T2 论文](https://arxiv.org/abs/2611.06043) · [官方 GGUF 说明](../refer/Confucius4-R2T2-master/r2t2_llama/README.md) · [X-ASR 部署](https://k2-fsa.github.io/sherpa/onnx/x-asr/index.html)

**SayIn 当前路径。** 使用官方 `Confucius4-R2T2-Q4_K_M.gguf` 和 `mmproj-Confucius4-R2T2-f16.gguf`，两份约 1.75 GB，摘要由模型目录清单校验。K40 日常包走 llama.cpp/ggml ARMv8.2 CPU 内核。首轮约 1.2 秒样本触发，已有确认文字后新增约 2 秒样本可触发下一轮。采集和推理并行，编码、前填、生成在一次推理内串行。约 8 秒是原生音频编码窗口，16 秒是 Android 段长度兜底，两者用途不同。VAD 自然边界、16 秒封段或 EOF 到达时，过时预览让位于定稿；临近硬边界的最后 2 秒不发起新预览。 [模型清单](../android/app/src/main/assets/local-asr-catalog.json#L63) · [Android 调度](../android/app/src/main/java/seeyuer/sayin/asr/R2t2Decoder.kt#L89)

**一轮如何得到文字。** PCM → Log-Mel → 卷积、音频 Transformer、投影 → llama 解码器前填 → 逐词元生成。前填先处理模板头，再处理音频嵌入，最后处理模板尾和已确认文本。任一较早的音频嵌入发生变化，后续解码 KV 就依赖变化后的输入。相比已确认文本的词元数，音频位置往往多得多。当前 JNI 已按归一化 Mel 的内容、形状及位置精确复用音频嵌入与连续匹配的 KV。模型输出 `|` 时停止本轮，预览的一枚尾部 token 留待确认。 [JNI 缓存和生成](../android/translation/src/main/cpp/r2t2_jni.cpp#L145)

## 4. 做过哪些优化

| 方案 | 实测结果 | 当前状态 |
| --- | --- | --- |
| VAD 分段、定稿抢占、跳过硬窗口末端的新预览 | 提前提交自然停顿段，减少已经过时的计算。改动早期 20.106 秒停录等待 34.188 → 23.382 秒，仍曾出现「星期三昨天」的接缝。 | 已接入，后续沿用 V3 的尾部样本范围。 |
| ARM CPU 变体和 `GGML_LLAMAFILE` 矩阵乘法 | K40 固定 16 秒输入、同一计算图的独立编码中位数 15.07 → 12.00 秒。CPU 优化后仍需继续压缩编码与前填。 | 已进入日常 arm64 构建。 |
| K90 Pro Max 的 CPU、KleidiAI、Vulkan 对照 | 较早的 K90 CPU 对照快于 K40。KleidiAI 某些样本更快，RSS 多约 1 GiB，连续运行频率变化大。Vulkan 默认预热采满 256 token 报错，关闭整数点积后可回放，时延收益不稳定。首轮从 1.2 秒改成 0.8 秒也出现空首轮及弱起声退化。 | 日常包沿用 CPU 路径和 1.2 秒首轮。K90 记录早于最近的 K40 首窗实验。 |
| 按 `audio.cpp` #696 思路复用完整音频窗口与解码器 KV | Windows AVX2 上用社区 GGUF 对照，33.39 秒 `Q-E08` 结束到定稿 46.13 → 10.03 秒。K40 接入精确复用及更早的抢占后，10 秒混说同组 8.17 → 4.97 秒，正文一致。33 秒连续听写同组却为 11.46 → 13.62 秒。 | 已接入。收益取决于特征一致和首窗是否实际计算完成。PR 的 Apple Silicon Metal 结果是另一模型运行环境，SayIn 已固定预分配 2048 个上下文位置。 |
| 用同一 R2T2 音频权重导出 ONNX FP32／动态 INT8 | K40 固定 16 秒独立编码为 GGUF 9.50、FP32 3.96、INT8 2.30 秒。两个 ONNX 版本在弱起声或尾词出现漏识。10 秒真实节奏回放中，定稿更早但 1.2 秒预览无正文，首字推迟。活动窗口卷积块缓存的端到端收益未稳定重现。 | 编码性能原型。产品继续使用 GGUF。 |
| 调更短的预览步长，或把编码窗口从 8 秒改为 4／2 秒 | 1／1.5 秒步长在个别混说让第二批文字提前，长句没有稳定收益。4 秒分窗的单轮命中曾缩短编码，交错复测未复现整场加速。2 秒新增错词。 | 保留 2 秒步长和 8 秒编码窗口。 |
| 增加前填线程、复用文本 KV、合并前填批次 | K40 固定 8 秒输入，4 线程两次约 2.23–2.24 秒，6／8 线程无稳定改善。只有音频完全不变时，复用文本 KV 才在原型中把前填 1.00–1.23 秒降至 0.09–0.14 秒，实时回放缺少此类命中。合并批次的收益不稳定。 | K40 保留 4 前填线程；K90 实测前填 8 线程把音频前填从 8.8 降到 5.6–6.2 毫秒/位置，已作为默认，见 [93](93-r2t2-k90-scheduling-thread-baseline.md) 第 8 节。 |
| 推迟接近 8 秒的预览，等首窗拿到足量真实音频 | K40 两次 `Q-E08` 试验均只保留 9 个模板位置，首窗 KV 未赶上 16 秒定稿。最长无更新 14.70–19.10 秒，原策略 9.89–11.83 秒。 | 实验已撤回。 |

[K90 对照](84-android-vad-design.md#k90-pro-max-后端与首轮预览对照) · [Windows 与 audio.cpp](84-android-vad-design.md#windows-r2t2-增量推理对照2026-11-25) · [ONNX 真机编码](84-android-vad-design.md#k40-真机复测-r2t2-编码器2026-11-25) · [会话回放取舍](84-android-vad-design.md#k40-会话回放与方案取舍2026-11-25) · [PR #696](https://github.com/0xShug0/audio.cpp/pull/696)

## 5. 当前卡在哪里

1. **活动窗口的输入在变。** `Q-E08` 从 7.1 秒预览到 16 秒定稿，首窗表面都是 800 帧，但 7.1 秒时只有 711 个有效帧。11,704 个变化值中，11,392 个是新增真实音频填入原补零区，256 个在尾部反射边界，56 个分散在已有音频内部。单独以同一段 PCM 对比 9.6／10.4 秒到 16 秒，首窗逐位相同，可复用 104 个音频 KV 位置及 9 个模板位置。全局 Log-Mel 下限变化还能使已满窗失效，先轻声后突然变响的对照已复现。 [Mel 固定截点与回放轨迹](84-android-vad-design.md#k40-实时供帧下的前填与缓存命中)
2. **前填热点是音频。** 固定 16 秒输入的独立测试中，冷路径前填 9.81 秒，其中 221 个音频位置占 7.30 秒，模板尾及文本 2.18 秒，模板头 0.33 秒。完整音频 KV 命中后只剩约 2.14 秒文本前填，冷热输出逐字节一致。八组真实节奏诊断回放包含 38 轮，累计音频前填约 42 秒、模板及文本约 10.45 秒，均含中断批次。11 次过时预览由 6 次 VAD、2 次硬窗口和 3 次 EOF 触发。部分已完成计算仍可以被定稿复用。 [固定输入前填分项](84-android-vad-design.md#k40-前填分项与缓存失效定位) · [实时供帧轨迹](84-android-vad-design.md#k40-实时供帧下的前填与缓存命中)
3. **有足量 PCM 和已计算出 KV 是两个时刻。** 首窗等待实验的一次预览在段内 12.8 秒才轮到解码，16 秒封段时仅做了约 3.10 秒编码。另一次 8.3 秒就开算，编码及前填合计约 7.60 秒仍被边界中断。原策略命中时，16 秒定稿的编码／前填为 2.88／2.58 秒，同策略另一次未命中为 6.88／5.29 秒。命中与否直接关系到本轮重算范围；温度和 CPU 频率也会影响耗时，几次回放的总差值不能全归因于调度。短句常在首窗满前就由 VAD 或手动结束，缩短单轮编码及音频前填才会直接改善这类输入。 [最新调度对照](84-android-vad-design.md#k40-首窗预览时机对照)
4. **编码加速仍受识别质量约束。** 固定版 GGUF 的边界 STFT 和卷积补齐后参与注意力的有效位置，与官方参考不完全一致。曾测到 16 秒音频送入解码器的位置数为 221 对 208，5 秒为 78 对 65。ONNX FP32 对齐参考嵌入后，5 秒截点仍可能少「语音」，加入 0.2 秒真实后续音频后恢复。`Q-E08` 的「第二是本地」「第三是」在当前 GGUF、部分上游对照中也有错识。需要分开对照模型权重、提示与截点、编码输出及已提交前缀，记录每个错词首次出现在哪一轮。 [逐级对照与边界差异](84-android-vad-design.md#r2t2-编码器逐级对照与-onnx-原型2026-11-25)

### 最近的可用基线

| K40，预录 PCM 的实时节奏回放 | 首次文本回调 | 供帧停止至定稿 |
| --- | ---: | ---: |
| 标准包 R2T2，10.053 秒混说，同批对照 | 2.961 秒 | 4.332 秒 |
| 标准包 X-ASR，10.053 秒混说，同批对照 | 1.714 秒 | 0.285 秒 |
| 最新恢复标准包后，R2T2 同一混说另一次回归 | 3.493 秒 | 6.868 秒 |
| `Q-E08` 首窗实验的原策略，33.386 秒录音加 5 秒静音 | 3.077–3.119 秒 | 5.470–11.886 秒 |

同一输入的跨批波动可观。现有标准隔离包通过中英混说和缓存正确性，13 项流式 JVM 用例基线通过，最近的日常 Debug 在 K40 上覆盖安装并冷启动成功。以上是功能和预录回放证据。真人麦克风、可见文字绘制、长时间功耗、目标延迟分位数仍待测。 [标准包模型对照](84-android-vad-design.md#k40-实时供帧下的前填与缓存命中) · [最后一轮回归](84-android-vad-design.md#k40-首窗预览时机对照)

## 6. 交接入口与复跑

| 资料 | 路径 |
| --- | --- |
| 主测试记录，包含各轮数据、采用或撤回原因 | [84 Android VAD 方案](84-android-vad-design.md)，从「R2T2 性能复测」读到「K40 首窗预览时机对照」。下半部部分参数属于早期设计，当前行为对照 85 和代码。 |
| K90 Pro Max 的 8 秒窗、节奏门控、纯分段与线程数对照（2026-11-26） | [93 K90 调度与线程基线](93-r2t2-k90-scheduling-thread-baseline.md)：4 线程基线、6/8 编码线程、4 生成线程，含 10/20 秒混说、`Q-E08` 连续听写、`Q-A06`/`Q-E05`/`Q-E09` 语料与单轮分项。 |
| 实施范围、录音所有权、停麦、尾音、ITN／CT、整条链路验收 | [85 Android ASR V3](85-android-asr-v3-refactor.md) |
| Android 核心入口 | [R2t2Decoder.kt](../android/app/src/main/java/seeyuer/sayin/asr/R2t2Decoder.kt) · [R2t2Native.kt](../android/translation/src/main/java/seeyuer/sayin/translation/R2t2Native.kt) · [r2t2_jni.cpp](../android/translation/src/main/cpp/r2t2_jni.cpp) · [r2t2_mtmd.inc](../android/translation/src/main/cpp/r2t2_mtmd.inc) |
| 测试入口 | [ImportedAsrInstrumentedTest.kt](../android/app/src/androidTest/kotlin/seeyuer/sayin/asr/ImportedAsrInstrumentedTest.kt) · [StreamingRecognitionTest.kt](../android/app/src/test/kotlin/seeyuer/sayin/asr/StreamingRecognitionTest.kt) |
| 带标注的音频 | [refer/voice/synthetic/manifest.tsv](../refer/voice/synthetic/manifest.tsv)。`Q-E08` 长句、`Q-E05` 句中 1.5 秒停顿、`S-E10` 弱声且紧贴尾音结束。10.053 秒中英混说使用测试 APK 的 `x-asr-smoke.pcm`。 |
| 本地参考 | [R2T2 论文 PDF](../refer/R2T2/2611.06043v1.pdf) · [官方源码](../refer/Confucius4-R2T2-master/r2t2/r2t2_asr.py) · [audio.cpp R2T2 实现说明](../refer/R2T2/audio.cpp/docs/models/r2t2.md) · [Qwen3-ASR 参考实现](../refer/R2T2/Qwen3-ASR-main/qwen_asr/inference/qwen3_asr.py) |

原始日志和实验源码位于原测试机 `%TEMP%`。若另一 agent 在不同环境，传交接文档时可附上相关日志，参考源码和语料也要能访问。

| `%TEMP%` 下的目录 | 优先读取 |
| --- | --- |
| `sayin-r2t2-ready-window` | `long-1.log`、`long-4.log` 为原策略，`long-2.log`、`long-5.log` 为实验组。`long-3.log` 被另一次仪器化启动打断。`hard-*.log`、`short-*.log` 是边界对照，`clean-replay.log` 是恢复后回归。 |
| `sayin-r2t2-live-trace` | `all.trace`、`summary.txt`、`mel-split.log`、`clean-r2t2.log`、`clean-x-asr.log`。四个按场景拆分的 `.trace` 文件为空，使用 `all.trace`。 |
| `sayin-r2t2-prefill` | `normal.log`、`quiet.log`、`threads-all-cores.log`、`text-prefix.log` 及独立探针源码。 |
| `sayin-encoder-comparison/official` | `k40-split-bench`、逐层对齐脚本与转写 TSV，适合复核有效帧和 ONNX 模型分项。 |
| `sayin-r2t2-adoption`、`sayin-r2t2-windows-k40` | 真实节奏 ONNX／步长对照及 2／4／8 秒窗口实验。 |

常用复跑命令，K40 序列号为 `dc39c31d`。先通过应用的模型导入流程在隔离包准备 `r2t2` 模型。已用手机模型目录 `/storage/emulated/0/0-Store/model/ASR/r2t2`。`asrCorpus` 需要隔离包 `files/voice-corpus/` 中相应的 16 kHz 单声道小端 PCM16。

```powershell
scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest
adb -s dc39c31d shell am instrument -w -r -e class seeyuer.sayin.asr.ImportedAsrInstrumentedTest -e asrModelId r2t2 -e asrVad true seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner
scripts/gradle.ps1 :app:testDebugUnitTest --tests seeyuer.sayin.asr.StreamingRecognitionTest
```

连续听写可追加 `-e asrCorpus Q-E08 -e asrContinuous true` 并指定 `ImportedAsrInstrumentedTest#recognizesMixedSpeechFromModelFolder`。复跑另一组时，等上一条仪器化调用完全退出。最后一次「等待首窗」开关只存在于归档实验源码，当前主线已恢复原调度。

下一次对照优先固定同一段的 1.2／3.2／8 秒预览和 16 秒定稿输入。分开计时 Mel、卷积、Transformer／投影、音频前填、文本前填和生成，并检查首窗实际完成的时刻、定稿复用的位置数、转写与资源峰值。若确认优化有效，再回到前台隔离包的 100 ms 供帧回放，核对短句、持续讲话、弱起声、响度突变、VAD、手动停录和硬窗口。记录设备热状态及首字、最长无更新、定稿和质量，才能判断是否值得接入日常包。
