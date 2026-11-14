# 本地 ASR 下载模型核查

核查日期：2026-11-13。范围：`assets/models` 的四个 `.tar.bz2` 压缩包和 `Qwen3-ASR-0.6B` 目录。本文记录文件检查与官方资料核对结果，尚未进行模型加载、音频推理或手机性能测试。

## 结论与接入顺序

1. **X-ASR 480ms**：优先试验的默认引擎候选，原生流式中英识别，带标点和大小写。当前下载包只有 FP32 文件，部署文件约 615.0 MB；先验证正确加载与识别质量，再测试官方 INT8 导出。
2. **Zipformer 2023 标准版**：作为成熟的流式基线，与 X-ASR 共用 sherpa-onnx OnlineRecognizer 的 Transducer 接入路径。不能预先承诺手机上的延迟或精度。
3. **Zipformer Small**：作为轻量基线，完整 INT8 图及词表约 49.8 MB。是否省电应通过实际测试确定。
4. **Streaming Paraformer**：保留作中文、中英混合识别的对照候选，不因架构名称直接定义为“精准模式”。
5. **Qwen3-ASR 0.6B**：保留原始权重；当前下载内容不能直接用于 sherpa-onnx Android，需要对应 ONNX 导出。后续作为高精度候选评估，暂不进入首轮接入。

界面仍然只有“本地识别”和“输入法输入”两个来源，进入同一草稿、确认、发送流程。模型是本地识别内部的引擎配置，不增加五套输入交互，也不提前承诺“默认 / 精准 / 省电”三个性能档位。

## 已下载文件与部署组合

以下 MB 按 1,000,000 字节计，仅表示所列文件大小，不代表峰值内存或 APK 大小。压缩包还包含 FP32、量化版本、示例音频等内容，不能以压缩包大小作为实际部署大小。

| 模型目录名（即同名压缩包去掉后缀） | 建议试验的文件组合 | 合计 |
| --- | --- | ---: |
| `sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-2026-06-05` | `encoder.onnx`、`decoder.onnx`、`joiner.onnx`、`tokens.txt` | 615.0 MB |
| `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` | `encoder-epoch-99-avg-1.int8.onnx`、`decoder-epoch-99-avg-1.onnx`、`joiner-epoch-99-avg-1.int8.onnx`、`tokens.txt` | 199.1 MB |
| `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16` | 根目录的 `encoder-epoch-99-avg-1.int8.onnx`、`decoder-epoch-99-avg-1.int8.onnx`、`joiner-epoch-99-avg-1.int8.onnx`、`tokens.txt` | 49.8 MB |
| `sherpa-onnx-streaming-paraformer-bilingual-zh-en` | `encoder.int8.onnx`、`decoder.int8.onnx`、`tokens.txt` | 237.2 MB |
| `Qwen3-ASR-0.6B` | 当前为 `model.safetensors` 原始 BF16 权重；还不是 Android ONNX 部署组合 | 权重 1,876.1 MB |

补充约束：

- Zipformer 标准版采用官方示例的 INT8 encoder / FP32 decoder / INT8 joiner 组合。Small 完整 INT8 组合仍需加载验证；若使用 FP32 decoder，对应组合约 60.1 MB。
- Small 压缩包同时有根目录、`64/`、`96/` 多组模型。每次选取同一组完整文件，不能跨目录混搭；不把所有组都打包进应用。
- BPE 文件已包含在相关压缩包中；启用需要 BPE 的热词等功能时，应按实际 API 配置加入模型清单，不能仅根据上述基础组合推导完整功能依赖。
- X-ASR 当前包内没有 `.int8.onnx`。官方导出脚本支持 INT8 encoder 和 joiner，decoder 保留 FP32。当前 480ms 包不能直接当作包含其他分块规格的合集。
- Paraformer 不使用 Transducer 的三文件配置，需要独立的 encoder / decoder 配置。
- Qwen3 的 safetensors 头记录 938,008,576 个 BF16 存储元素，文件长度与头部偏移一致，不是 Git LFS 指针；这不是与上游权重摘要的密码学比对。

## 对模型介绍的修正

- **480ms 是音频分块规格**，不是实测首字延迟，更不是端到端落字延迟。
- 不存在当前项目已验证的“精度最高”“90ms 出字”或“某手机完全能跑”的结论。应分别测首个非空结果、停止到最终稿、长句跟随速度、峰值内存、发热及连续使用表现。
- Qwen3-ASR 官方说明为 **30 种语言、22 种中文方言**，不能合并表述成“52 种语言加方言”。
- sherpa-onnx 已支持转换后的 Qwen3-ASR；不支持的是把当前 `safetensors` 原始权重直接交给 Android ONNX 加载。官方另有 `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`，使用 `conv_frontend.onnx`、`encoder.int8.onnx`、`decoder.int8.onnx` 和 tokenizer。其 OfflineRecognizer / VAD 分段或模拟流式方案与 Zipformer 原生流式不同。
- 当前目录没有 SenseVoice 和独立 VAD 模型。X-ASR 自带标点，无需为了标点强制增加 SenseVoice 二次识别。最小手动开始 / 结束识别链路也无需先接入独立 VAD。
- 用户资料中的目标日常手机为 REDMI K90 Pro Max；此前连接的测试手机为 Redmi K40。不能把两台手机的硬件条件或测试结果混用。

## 验证证据与后续验收

本次完整顺序扫描了四个 bzip2 tar 包，未出现读取错误，核对了模型、词表和说明文件。四包字节数均与官方发布资产一致；X-ASR 还与官方公开 SHA-256 一致，其余三个资产没有公开 digest，不能把尺寸一致表述成上游摘要验证通过。摘要记录见 `docs/research/30-local-model-download-audit-2026-11-13.json`（本地验证记录目录，不纳入 Git）。

尚未解压部署、引入 AAR、增加录音权限、运行推理或操作手机。接入时先用官方测试音频验证模型加载，再用相同的中文、英文、中英混合、数字、专名、静音和长句样本对照；在目标手机上记录首字延迟、结束延迟、内存与持续运行表现后，才能确定默认模型。没有上游音频真值的测试不能直接计算或宣称 CER/WER。

模型与工具包许可证分别核查。标准 Zipformer、Paraformer 包说明及 Qwen3 README 有 Apache-2.0 声明；X-ASR 包提供原项目链接；Small 包内没有根级许可证说明。正式分发前应固定各模型来源、版本、文件摘要及对应权重许可证。

## 官方参考

- [X-ASR 项目与模型说明](https://github.com/Gilgamesh-J/X-ASR)
- [sherpa-onnx X-ASR 流式导出脚本](https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/zipformer-transducer/x-asr/export-streaming.sh)
- [sherpa-onnx 在线 Transducer 模型](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/index.html)
- [sherpa-onnx Streaming Paraformer](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/paraformer-models.html)
- [Qwen3-ASR 原始模型](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)
- [sherpa-onnx Qwen3-ASR ONNX 模型](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html)
