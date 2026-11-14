# X-ASR 本地流式识别实施

日期：2026-11-13。Android 包名仍为 `seeyuer.sayin`。

## 目标与实际接入

此前本地识别只是禁用入口，没有录音权限、模型、推理库或识别会话。本轮使用官方 **sherpa-onnx 2.0.1 Android AAR**，接入用户下载的 **X-ASR 480ms 中英带标点 FP32** 模型。框架和模型均已实际加载验证；麦克风实录、完整界面和真实电脑接收仍待用户验收，不能把音频文件推理通过描述为整条实录到电脑落字通过。

保持两个输入来源和一份草稿：本地识别使用应用内 AudioRecord；输入法来源仍由用户的输入法负责打字或语音。两者共享编辑、确认、发送和 Outbox，不为模型另建发送通道。

## 会话与交互

```text
本地识别 → 开始说话 → 按需申请麦克风权限
  → 后台加载模型（可以取消，尚未录音）
  → 录音 / 流式更新草稿
  → 用户点“结束说话” → 停止麦克风 / 消费剩余 PCM / 补右上下文 / 结束输入
  → 最终稿 → 原有手动确认或智能发送规则

离开页面 / 退后台 / 切来源 / 点编辑 / 草稿被外部清除
  → 取消识别 → 停止麦克风并释放资源 → 保留已显示的草稿 → 不进入自动发送
```

- 模型加载和推理使用后台线程，录音使用独立 IO 协程。JNI recognizer 和 stream 由识别工作协程独占，关闭 stream 后关闭 recognizer。
- 采集为 16 kHz、单声道、PCM16，每次最多 100ms；归一化为 float 后交给 OnlineRecognizer。CPU provider、2 个推理线程是初始配置，没有宣称已经完成性能调优。
- 录音与推理之间使用最多 20 个块的有界队列；跟不上时以明确错误停止，不无限缓存或静默丢音频。
- recognizer 配置使用 `zipformer2` Transducer、80 维特征、greedy search 和原生 endpoint。endpoint 只提交当前片段并重置流，继续录音，**不触发发送**。
- 当前片段的 partial 覆盖上一版假设，已提交片段只追加一次；结束时补 0.8 秒零样本右上下文、调用 `inputFinished` 并解码至无可消费输入后取最终稿。
- 开始录音时冻结草稿及选区。转写只替换该选区，不逐次追加 partial，不覆盖选区之外的文字；录音中草稿只读，也不能发送未定稿内容。点“编辑”会先取消识别，再进入输入法。
- 只有用户明确结束且成功定稿，才进入既有完成输入规则。静音没有新稿时保留原文，不为旧稿启动倒计时；失败或中断也不启动倒计时。电脑接收范围或发送方式在录音中改变后，本次结果仅手动发送，恢复原范围也不重新授权自动发送。
- 权限拒绝、加载失败、麦克风不可用、推理跟不上和识别异常均有中英资源文案。权限设置入口只打开本应用设置，不自行授权。
- 此阶段按会话加载和释放模型，没有模型常驻缓存、独立 VAD、后台录音或自动恢复录音。没有保存音频文件，也没有新增音频上传路径。

## 文件与构建

`android/asr-artifacts.json` 固定 AAR URL / SHA-256、模型压缩包与四个部署文件摘要、测试音频来源和摘要。

```powershell
python scripts/prepare_x_asr.py
.\scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease
python scripts/check_i18n.py
```

准备脚本先验证现有模型压缩包，再只提取 encoder / decoder / joiner / tokens，下载并验证官方 AAR；不执行压缩包里的 Python 代码。产物放在 Git 忽略的 `android/local-asr/`。`preBuild` 运行 `--verify-only` 再核对实际文件 SHA-256，构建期间不隐式下载模型或依赖。

模型通过 Android assets 打包，ONNX 使用 `noCompress`；不是把整个压缩包或其他四个模型打入应用。四个文件共 614,981,400 字节，当前 Debug APK 约 663 MB。这是先验证现有 FP32 文件的实现，尚未换用 INT8，也不以文件体积推导峰值内存。

官方 AAR 的 consumer ProGuard 文件为空，应用为 sherpa 的 JNI 配置 / 结果类保留名称与字段，避免 Release 缩减破坏 JNI 查找。Release/R8 构建及 mapping 名称核对通过；实际设备加载证据对应 Debug APK，不代表已在设备运行 Release。

## 行为对比

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 本地识别入口 | 禁用，说明未接入 | 点击后按需申请权限并启动真实识别 |
| 模型推理 | 不存在 | 官方 AAR + X-ASR assets，原生 OnlineRecognizer |
| 实时稿 | 不存在 | partial 替换当前片段，在冻结选区内更新同一份草稿 |
| 停顿 | 输入法负责 | ASR endpoint 只分段，持续听取，不发送 |
| 完成输入 | 输入法完成事件 | 两种来源进入相同最终稿与发送规则 |
| 静音 / 中断 / 失败 | 无 ASR 会话 | 不自动发送，保留已显示草稿并给出必要说明 |
| 离线编辑 | 输入法可用 | 本地识别也不依赖电脑在线；离线保留草稿 |
| 既有发送 | 受就绪状态与范围校验约束 | 沿用同一 Repository / Outbox / 回执；未定稿时禁用发送 |

## 验证记录

修改前 `:app:testDebugUnitTest :app:assembleDebug` 基线通过。修改后：

- 25 项 JVM 测试通过，零失败 / 跳过，包含原有 19 项及 6 项新测试：partial 修订与 endpoint、英语片段间隔、草稿选区插入、尾字完成、取消停止录音且不定稿、采集失败不能返回成功稿。
- Debug、Release/R8、AndroidTest APK 编译通过；Android i18n 门禁通过，Lint 0 errors / 33 warnings（未屏蔽检查）。
- Debug APK 的 `zipalign -c -P 16 4` 检查通过；Debug / Release APK 中的 ARM64 原生库 PT_LOAD 对齐均至少 16KB，ONNX 文件保持未压缩。该静态检查不等于已经在 16KB 页大小设备运行。
- Windows 上用独立 `pip --target` 目录中的 sherpa-onnx 2.0.1 运行四个官方示例 WAV，全部产生非空流式结果，3 秒静音结果为空。主机验证脚本为 `scripts/verify_x_asr_host.py`，日志和完整假设序列在 `docs/research/31-x-asr-host-inference.json`。主机耗时不是手机延迟。
- Redmi K40（ADB 序列 `dc39c31d`）运行 **1 项非 UI 原生仪器化测试**：真实加载应用 assets 和 Android JNI；中英样本包含 `Monday`、末尾 `星期三` 及中文逗号，至少三个不同的非空 partial；显式释放后重新加载，再验证 3 秒静音为空。结果 `OK (1 test)`，整个测试约 8.139 秒，不是模型首字或首启指标。
- 主应用和测试 APK 普通 `adb install -r` / `-r -t` 均返回 Success，不清除用户数据。没有录音、读取配对凭据、操作 Outbox 或运行界面自动化。

设备测试命令：

```powershell
adb -s dc39c31d shell am instrument -w -r -e class seeyuer.sayin.asr.XAsrInstrumentedTest seeyuer.sayin.test/androidx.test.runner.AndroidJUnitRunner
```

测试源位于 `android/app/src/androidTest/kotlin/seeyuer/sayin/asr/XAsrInstrumentedTest.kt`。PCM fixture 来自固定模型包 `test_wavs/0.wav`，去掉 WAV 头后为 321,700 字节，保留来源摘要，不进入主 APK。

完整设备套件现在预期 19 项，通信验证脚本计数已更新；**本轮只执行上面的 1 项 ASR 测试，没有重跑完整 UI / 通信套件**。用户已明确真机 UI 由其检查，不能将测试编译描述为 19 项设备测试通过。

## 用户真机验收

打开 SayIn → 本地识别 → 开始说话 → 允许麦克风 → 观察实时文字 → 结束说话 → 检查并发送。先用默认手动发送验证，再检查智能发送的短中文、英文转手动和取消倒计时。

仍需实际覆盖：首次 / 永久拒绝麦克风权限、实录与尾字、继续补充及替换选区、静音与噪声、录音中切页 / 退后台 / 切来源、输入法回归、长期录音与发热、目标日常手机上的内存和延迟，以及真实电脑接收和回执。当前已验证的设备是 Redmi K40，不能推广成 REDMI K90 Pro Max 的实测结果；也不能声称 CER/WER、p95、功耗或端到端落字目标已经达标。
