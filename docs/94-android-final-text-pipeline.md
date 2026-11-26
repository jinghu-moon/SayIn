# Android 定稿文本后处理（热词 / ITN / 格式）

记录时间 2026-11-27。范围：本地识别最终文本的热词纠正、数字规范化（ITN）、格式清理，三阶段共用同一入口；云端识别不走这条路径。策略来源是 `refer/voice/` 的文本后处理测试资料（`hotword.tsv` 20 条、`itn.tsv` 110 条、`format.tsv` 34 条、`pipeline.tsv` 19 条、`hotwords.dict.tsv`、`protect.terms.txt`），产品政策见该目录 README 的 P1–P8。

## 1. 决定：单一 Kotlin 实现，不采用 sherpa/WeTextProcessing 的现成 ITN

起因是发现 sherpa-onnx 2.0.1 自带 `ruleFsts`（`OnlineRecognizerConfig` / `OfflineRecognizerConfig` 都有，AAR 的 native 库包含 kaldifst 与 `TextNormalizer`），官方提供 25 KB 的 `itn_zh_number.fst` 和流式 ITN 示例。于是实际评估了"让 sherpa 引擎在识别内部做 ITN"的方案：

- 用 pynini + WeTextProcessing 1.3.0 构建完整中文 ITN（tagger @ verbalizer，126,847 状态），按 `itn.tsv` 验收；
- 结果 **86/110**，其中 **6 条 MUST_KEEP 违规**：他十分感谢你→10分、十字路口→10字、二十四节气→24、三十而立→30、十年磨一剑→10年、三点一线→3.1线；另有 11 条格式不符合政策（`2026/11/24`、`3:30`、`24h`、`km`、`cm`、`°`、`2:1`、`3×4=12`）；
- 同一语料下当时的 `LocalItn` 为 **85/110**，但 **0 条 MUST_KEEP 违规**，失败全是漏转。

更关键的是结构问题：`ruleFsts` 只在 sherpa 识别器内部生效，R2T2 走 llama.cpp 拿不到该运行时；采用 FST 等于给 sherpa 引擎一套、R2T2 一套，两份策略同步维护。**后处理只处理最终文本，开销可忽略，没有理由为两个引擎维护两套实现。** 因此保持单一 Kotlin 实现。

## 2. 实现

入口是 `FinalTextPolish.apply(raw)`，顺序固定为 **热词纠正 → ITN → 格式清理**（与 `refer/voice/README.md` 的流水线一致），由 `SpeechRecognition` 在定稿时调用；partial 结果不处理。

### 2.1 热词纠正（`HotwordNormalizer`）

- 词典来自 `android/app/src/main/assets/hotwords.tsv`（由 `hotwords.dict.tsv` 落地，两列：错误写法、正确写法），生产与 JVM 测试读同一份文件；
- 大小写不敏感匹配；含 ASCII 的词条要求词边界（`kotlin` 不匹配 `kotlinx`，`x asr` 不匹配更长的词）；
- 长词条优先；URL、邮箱、路径、代码、版本号等保护段不参与替换（`chatgpt.com` 不会被改成 `ChatGPT.com`）；
- 重复执行结果不变（`ChatGPT` 再次匹配也替换为自身）。

### 2.2 ITN（`LocalItn`）

- 保护段：技术串 + `protect.terms.txt` 品牌/专名（一加、三星、九号、五粮液、二锅头、三只松鼠、七匹狼、一汽、万科、百度）；
- 年份、月日、钟点、百分比、带单位小数、秒级时长（保留原有规则）；
- 度+尾数（`三十六度五→36.5度`）、金额尾数（`三百二十五块五→325块5`）、`零点五→0.5`、裸小数（`三点一四→3.14`，`三点一线`不转）；
- 数量+单位仅在数值 ≥10 时转换（`十二个人`、`二十四小时`、`三千五百元`、`一百八十厘米`）；
- 裸数字仅在 ≥100 或含万/亿时转换（`一百二十三`、`两千`、`十万`、`两亿三千万`），排除百分数/比值、`千万` 副词、`三万五` 口语缩写；
- 连续数字串（`幺二三四…`、`六八二九四一`、`二零三`），不在小数点两侧生效（`二二点五三秒` 保持原文）；
- **标识上下文**：验证码、密码、房间号、编号、序号、尾号、工号、订单号、账号、手机号、门牌号、车牌号、身份证号、卡号、单号等词后面的数字，即使单个或小于 100 也转（`验证码是七→验证码是7`、`房间号五→房间号5`、`订单号是二零二五幺零零一→订单号是20251001`）；数字后面紧跟汉字时不转，避免 `密码是三个人` 被改成 `密码是3个人`，`编号第一` 也不受影响。

### 2.3 格式清理（`TextFormat`）

- 重复中文标点折叠：`。。→。`、`，，→，`、`？？→？`、`！！→！`；三个以上句号视为省略号转 `……`；`……`、`...`、`——`、`？！` 保持原样；
- 中文与中文、中文与英文/数字、中文标点两侧的空格删除（P7），英文单词之间的空格保留；
- 首尾空白删除，换行保留；
- 已规范文本再跑一遍不变。

## 3. 测试

语料固化在 `android/app/src/test/resources/voice/`（canonical 来自 `refer/voice/`，extended 为本次补充），由 JVM 用例逐条断言，MUST_CHANGE/MUST_KEEP 精确相等、EITHER 取集合，并额外验证幂等：

| 用例 | 语料 | 结果 |
| --- | --- | --- |
| `HotwordCorpusTest` | `hotword.tsv` 20 条 + `hotword-extended.tsv` 17 条 | 37/37，幂等通过 |
| `LocalItnCorpusTest` | `itn.tsv` 110 条 | 110/110，幂等通过 |
| `ItnExtendedCorpusTest` | `itn-extended.tsv` 60 条 | 60/60，幂等通过 |
| `FormatCorpusTest` | `format.tsv` 34 条 + `format-extended.tsv` 28 条 | 62/62 |
| `PipelineCorpusTest` | `pipeline.tsv` 19 条 + `pipeline-extended.tsv` 23 条 | 42/42，幂等通过 |

全量 `:app:testDebugUnitTest` **262 项通过**（0 失败）。ITN 基线为 85/110，本轮达到 110/110；热词与格式此前没有实现。

扩展语料（128 条）来源与取舍：

- **同类变体**：围绕既有用例补年份（一九八五、两千零二十五）、日期/时间边界（二月三十日、二十四点整、两点零五分）、百分比（含百/千、零点五）、数量与金额（岁/斤/公里/字/块/毛）、万/亿级、逐位号码、标识上下文（验证码/房间号/订单号）、技术串与坏空格；
- **GitHub 参考集**：参考 WeTextProcessing（Apache-2.0）的中文 ITN 测试数据（`itn/chinese/test/data`），选取与产品政策一致的用例并改写成本项目的输出格式；其日期（`2008/08/08`）、时间（`2:02`）、金额（`¥1.25`）、分数/数学式/车牌的期望与 P1 冲突，未纳入；
- **硬负例**：成语（七老八十、一五一十、万紫千红）、品牌（三一重工、五十度灰、十字路口）、品牌保护与词边界（kotlinx、chatgpt.com、邮箱）、幂等。

扩展语料同时暴露并修复了规则缺口：`两` 作为 2（两点零五分、两百块）、百分比含百/千、`字` 作为数量单位（与「十字路口」冲突，改为保护词）、`两` 不再参与连续数字串、单独的「千/百」不再当数值、新增「三一重工/十万火急/五十度灰/十字路口」保护词。

## 4. 未覆盖与下一步

- 语气词阶段（`filler.tsv`）按政策默认关闭，尚未接入；
- 语料是文本级验收；真实录音还要验证 ASR 输出切分、标点模型（CT）与后处理的顺序影响。当前顺序是 **热词 → ITN → 格式 → CT 标点**；
- 设备级验收：把 `refer/voice/synthetic` 的 WAV（转 PCM）回放到隔离包，按 `manifest.tsv` 的 `expected_final` 核对“ASR 原文 → 后处理”的最终文本；合成语料 163 条，可分批执行；
- 热词表目前随包内置；若产品需要用户自定义热词，应在同一入口上增加配置来源，而不是另建一条替换链路。

## 5. 复跑

```powershell
scripts/gradle.ps1 :app:testDebugUnitTest --tests "seeyuer.sayin.asr.HotwordCorpusTest" --tests "seeyuer.sayin.asr.LocalItnCorpusTest" --tests "seeyuer.sayin.asr.ItnExtendedCorpusTest" --tests "seeyuer.sayin.asr.FormatCorpusTest" --tests "seeyuer.sayin.asr.PipelineCorpusTest"
```

语料更新时，把 `refer/voice/` 下对应 TSV 覆盖到 `android/app/src/test/resources/voice/`，热词词典覆盖到 `android/app/src/main/assets/hotwords.tsv`，再复跑；不要在测试里读取仓库外的绝对路径。新增扩展用例时优先写进 `*-extended.tsv`（列格式同上，`\n`/`\t` 为转义，`<EMPTY>` 表示空串），并保证 MUST_KEEP 用例无需修改规则即可通过。

## 6. K40 合并版定向回放（2026-11-27）

在 Gemini 合并版 `54aad13` 的隔离包用生产目录结构装载经长度及 SHA-256 校验的 R2T2 Q4_K_M 主模型和 Q8_0 投影器，再用随包 X-ASR 独立复跑。16 kHz 单声道 PCM16 来自 `refer/voice/synthetic/manifest.tsv`。以下均为 v3 定稿结果。

| 样本 | 模式与判据 | R2T2 和 X-ASR |
| --- | --- | --- |
| Q-A06 | 连续听写。逐字匹配清单的最终文本。 | 均为「这个季度增长了20%。」 |
| Q-E05 | 连续听写。对照原句检查 1.5 s 句中停顿后是否保留后半句。 | 均为「我需要想一想，还是明天再决定吧。」 |
| Q-E09 | 连续听写检查最终文本为空。另跑自动结束，要求 `end=no-speech` 且最终文本为空。 | 两种模式均符合判据。 |

四次仪器运行均通过。日志位于 `desktop/target/asr-v3-merged/` 下的 `r2t2.log`、`x-asr-bundled.log` 及对应 `-noise.log`。R2T2 首轮模型载入期间隔离包一度在后台，该轮耗时不用于与 K90 性能基线对比。完整 163 条回放及真人麦克风到 Windows 落字仍待验收。
