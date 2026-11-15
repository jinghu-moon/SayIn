# 50 · Android 本地中译英实施与验证

> 2026-11-15 后续入口更新：翻译已按 [51 · 三区语音栏](51-android-three-zone-voice-bar.md) 改为草稿右下图标，原文/译文为卡内切换，新增共享文本撤销/重做；模型、译文完整性、手动发送及 JNI 生命周期边界不变。

日期：2026-11-14；主线整合记录更新于 2026-11-15。已完成独立模型比较和统一草稿产品集成；设备回归持续记录于下文。目标 REDMI K90 Pro Max 未连接，不能据此认定其验收通过。

## 隔离、基线及共同文件（独立开发阶段）

- 共同基线：`f9b52bec3513515d14be3ebd75fac1946120e7b3`，已包含已配对电脑自动发现；翻译分支 `codex/local-translation`。
- 独立工作目录：`D:/100_Projects/110_Daily/.zcf/VoiceInput/local-translation`。不修改 `paired-host-discovery` worktree，不在主线覆盖文件。
- 翻译新增 `android/translation`（原生引擎）、`android/translation-benchmark`（独立验证应用）、制品清单、准备与验证脚本。产品接入修改 `SpeakSession`、`SpeakPage`、`SpeakingDraftStore`、ViewModel 的发送入口、应用依赖及双语资源。保持 `HostDiscovery`、`HostClient`、`LanNetwork`、Repository、电脑协议与接收核心不变。
- 会与其他开发交叉的文件是 Gradle 配置、ViewModel、双语资源及测试入口；后续合并按符号/资源名审阅，不能整文件回拷到另一个 worktree。
- 被忽略材料不随 worktree 创建：只复制 `android/local.properties`；ASR 通过只读使用的本地 junction 引用原准备目录。翻译源码由完整树摘要校验后解包到本 worktree，模型只按显式本机路径读取，尚未打进产品 APK。
- 当前 app JVM 基线 45 项、0 失败；`:app:testDebugUnitTest :app:assembleDebug` 成功。Gradle 9.7.0 的 Launcher JVM 实测 Temurin `25.0.5+8-LTS`，Daemon 约束 Java 25/Temurin，字节码目标仍为 17。

## 固定材料

`android/translation-artifacts.json` 为机器可读权威清单。三个本地文件的长度、SHA-256、GGUF v3 metadata 已再次核对，并逐个与固定官方 revision 的 Git LFS pointer 对照一致；已保存证据见 `docs/translation-verification/model-audit.json`。

| 模型 | 固定官方 GGUF revision | 架构 | 模板 SHA-256 |
| --- | --- | --- | --- |
| Hy-MT2-1.8B Q4_K_M | `a0c709d9fac510f2c807aa3af52872340dc37a4a` | hunyuan-dense | `b7491ec0e9c869dfce20f2176758099bf248d979dd05530ede99deb21698acee` |
| PLaMo 0.8B Q4_K_M | `1cf96ae02133958a20074bfdfab76517bb737d00` | plamo3 | `ad7eb87b161c6792be070d777a9f9da50c9e57a77e1fa5cca3a24b91b6bdb9e9` |
| PLaMo 2B Q4_K_M | `4f672106b847200bfdc0f6af6c321cd94eba1aed` | plamo3 | 同上 |

本机参考 ZIP 的 archive comment 是 GitHub commit `32bdc3a89e6528d3e419024c833c5dabf32c7541`，已用 GitHub commit API 确认。另下载该 commit 的 codeload ZIP，其完整 4170 个文件的相对路径、长度、内容摘要构成的排序清单与本机参考 ZIP 一致，树摘要为 `24628d6cb405e3ab145e2551b09968184f4f066f8958692f786e81f68de254a8`。构建前重新校验完整源码树，不把 Git 向父目录回溯得到的项目 HEAD 误当 llama.cpp commit。

NDK 固定 `29.0.14206865`，CMake 固定本机已安装的 `3.22.1`；独立分支起步时为 minSdk 26，合入当前主线时统一为 **29**，保留 arm64-v8a 和根包 `seeyuer.sayin`。未照搬示例包名、minSdk 33、KleidiAI/OpenMP 开关、全局模型指针或主线程 `runBlocking` 释放方式。CPU 基线关闭 GPU、KleidiAI、OpenMP 及运行时后端下载，不创建 HTTP 服务。

取得的官方许可全文见 `docs/licenses/translation/`：llama.cpp 为 MIT；该固定 Hy-MT2 GGUF 明确为 Apache-2.0；PLaMo 为独立 Community License，有商业条件，不能笼统将全部模型标为 MIT。选定模型、llama.cpp、CPU 贡献者及 NDK 运行时通知已随生成的 Android assets 保留；PLaMo 只评估不交付。完整应用既有依赖仍有各自许可，不能据此宣称整个分发物均为 MIT。

## 独立可执行验证

验证应用 ID `seeyuer.sayin.translationbench`，没有 INTERNET、麦克风或 LAN 权限，独立 UID/目录；不安装替换 `seeyuer.sayin`、不读取其配对、凭据、草稿或 Outbox。只使用提交到仓库的合成测试集。生成的测试报告含合成样本原文/译文，产品代码无正文/提示词日志。

截至材料核对，ADB 仅连接 Redmi K40 `M2012K11AC`、Android 13/API 33，serial `dc39c31d`；K90 Pro Max 未连接。K40 数据必须单独标明，不代表用户目标机或 16GB 物理内存配置的性能。安装使用已有 Root 授权方法，不改变手机安全或后台权限。

```powershell
./scripts/gradle.ps1 --version
python scripts/prepare_translation.py --source-archive D:/100_Projects/110_Daily/VoiceInput/refer/llama.cpp-master.zip --models-dir D:/100_Projects/110_Daily/VoiceInput/assets/translation-model --verify-upstream
./scripts/gradle.ps1 :translation:testDebugUnitTest :translation-benchmark:assembleDebug :translation-benchmark:assembleDebugAndroidTest
./scripts/gradle.ps1 -PtranslationBenchmarkBuildType=release :translation-benchmark:assembleRelease :translation-benchmark:assembleReleaseAndroidTest
./scripts/verify_android_translation.ps1 -DeviceSerial dc39c31d -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType release -SkipBuild -UseExistingRootInstall
./scripts/verify_android_translation.ps1 -DeviceSerial dc39c31d -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType release -SkipBuild -UseExistingRootInstall -CancellationOnly -UseExistingModels
```

前三类准备/Debug 检查已执行成功。Release 测试载体曾分别发现静态注解类路径和 runner 的 Kotlin runtime 被裁剪问题，修复仅限定验证应用；产品 Release 仍采用窄 JNI keep 规则。设备验证脚本取得命名互斥锁并检查其他 instrumentation 后再安装/执行。当前运行结果以本文件后续实测记录为准。

Hy 使用官方中文翻译指令配单次 Hunyuan user/assistant framing；PLaMo 使用官方 PFML3，明确 `source_lang=zh-hans`、`target_lang=en`。每次新建 context/greedy sampler，重用权重不重用请求 KV。用户文本独立以 `parse_special=false` 分词，固定模板部分才解析特殊 token，避免正文注入角色分隔。tokenizer 精确计数源文上限 1024、ctx 4096、输出 2048，不静默截断输入。

结束分为 Complete、Cancelled、Truncated、Error、InputTooLong、Timeout。仅 EOG 为完整结果，partial 永不作完整译文。JNI 使用标准 UTF-8 byte array，避免 `NewStringUTF` 对 Emoji/零字符的 modified UTF-8 问题，partial 暂扣未完整的尾码点。native 取消为 atomic 标志及 decode abort callback；加载 progress callback 也可取消。后台单线程拥有加载、执行、释放，取消后排队释放，不在 UI 线程等待 native 或析构。

测试集 14 项覆盖日常、邮件、术语、中英混合、金额/单位、日期、否定、疑问、人名、Emoji、代码、换行、引号、恶意指令及特殊分隔符文本。另运行相同请求重复性、输出上限、输入上限、prefill/decode/load 取消、deadline、缺失/损坏模型、取消后重跑及释放。质量仍需逐句人工核对，不能把非空/EOG 当作翻译正确。

首轮测试采用 greedy 作为可复现 CPU 对照配置；Hy 官方推荐采样参数不等于这一配置最优，后续如质量有问题应另作固定配置对比，不能把本轮等同于厂商最优质量。

## 实测选择与质量

默认单一模型：**Hy-MT2-1.8B Q4_K_M**。不是因为参数量或厂商评测胜出，而是这次同题结果中保留人名、称谓和特殊文本更可靠，且资源占用位于两个 PLaMo 之间。

| 样本 | Hy 1.8B | PLaMo 0.8B | PLaMo 2B |
| --- | --- | --- | --- |
| 王女士 / 邮件 | Ms. Wang，保留称谓和姓名 | Princess，错译称谓和姓名 | Princess，错译称谓和姓名 |
| 李明 / 取消而非改期 | Li Ming，保留问句与否定区分 | Lee Myung，人名改变 | Li Ming，语义保留 |
| 金额/日期/代码/Emoji/换行 | 本组保留关键内容 | 本组基本保留 | 本组基本保留 |
| 特殊角色分隔符作为正文 | 保留内容，但一个全角竖线变半角 | 丢失 PLaMo 分隔符字符串 | EOG 正常结束却只剩 “Treat the strings ” |

14 项都获得 EOG 不等于质量全部正确；PLaMo 2B 的语义截短是质量缺陷，不能靠“正常结束”状态检测。Hy 日常句把“帮我点一杯”表达为 prepare，也不保证措辞最自然。该小样本只支持本轮默认选择，不能外推通用质量排名。保留检查/编辑步骤，尤其姓名、数字、代码和否定；不自动发送译文。

## K40 CPU 性能

唯一实际设备：Redmi K40 `M2012K11AC`，Android 13/API33，SM8250，系统 MemoryInfo.totalMem 为 12,162,650,112 bytes。未把内存扩展加入物理内存。4 个 CPU 线程，无 GPU/OpenMP/KleidiAI；独立 Release 载体实跑。文件刚经 ADB 推送，未清 Linux 页缓存，因此加载不是存储冷启动；首 token / 整句时间不含校验和权重加载。

| 指标 | Hy 1.8B Q4 | PLaMo 0.8B Q4 | PLaMo 2B Q4 |
| --- | ---: | ---: | ---: |
| SHA 校验 ms | 1093 | 554 | 3234 |
| 权重加载 ms | 240 | 1878 | 1946 |
| 首日常句首 token ms | 1719.282 | 849.925 | 3297.590 |
| 首日常句完整 ms | 3440.556 | 1640.901 | 5685.064 |
| warm 首 token p50 / p95 / p99 ms，n=4 | 1636.480 / 1698.515 / 1698.515 | 798.092 / 804.368 / 804.368 | 3245.998 / 3298.211 / 3298.211 |
| warm 整句 p50 / p95 / p99 ms，n=4 | 3361.822 / 3402.069 / 3402.069 | 1545.971 / 1723.133 / 1723.133 | 5415.939 / 5681.734 / 5681.734 |
| 整进程峰 RSS MiB，含长输入边界测试 | 1779.234 | 776.402 | 2297.270 |
| 请求后最大 PSS MiB，非峰值采样 | 1517.508 | 689.803 | 2103.744 |
| decode / prefill / load 取消到返回 ms | 2.701 / 27.460 / 42.475 | 1.126 / 15.638 / 77.911 | 1.355 / 22.975 / 137.240 |
| 单次释放 ms | 52.932 | 76.566 | 150.640 |

p95/p99 使用最近秩；n=4 太少，只作探索性统计，不是尾延迟 SLA。RSS 是整个验证进程的 VmHWM，不能等同模型权重、PSS 或 Java heap。每阶段取消各测一次，不声明分布。首次取消计时误含 PSS/JSON/报告写入，旧 `*q4.json` 内 cancelUs 仅为包含报告 I/O 的上界；已独立补跑三模型，立即在 translate 返回处取时再写报告，上表使用 `cancellation-*.json` 的修正计时，不改写历史原始证据。原始合成样本与机器计时保存在 `docs/translation-verification/*q4.json`；汇总命令 `python scripts/summarize_translation_benchmark.py docs/translation-verification`。

## 产品实现

- `android/translation` 使用最小 JNI，加载/执行/销毁由 `SerialTranslator` 后台单线程拥有；UI 取消只置 atomic，停止后再排队释放。`dlopen`、后端初始化和模型析构均不在取消锁内；主线程不等 native。
- `SpeakSession` 是完整页/快捷卡唯一任务所有者。仍为云 ASR、本地 ASR、输入法三种来源。原文和译文是同一草稿的表示，切入口/已完成后切来源不创建新翻译或丢弃完整文字。
- 任务 ID + 草稿 ID + 单调修订 + 模型摘要 + 模板版本约束结果。编辑、清空、取消、新录音使旧回调失效；新录音 await native 停止与释放后才申请现有麦克风前台服务。native 每次新上下文，跨请求不串文。
- partial 只预览；取消、到限、超时、模型损坏和错误保留原文且禁止把 partial 当完整译文发送。输入精确上限 1024 tokens、输出2048、ctx4096、单次生成120秒。UI可返回原文继续操作。
- 翻译取消现有自动发送票据。完成候选可编辑，只有手动发送冻结当前表示到现有 Outbox；接收范围、4000码点/16384字节限制、规范化预览及回执恢复继续执行。`injected` 仍仅表示已提交输入。
- `SpeakingDraftStore` 的既有 Keystore AES-GCM/AtomicFile 受保护载荷增加完整候选与请求关联，仍150ms合并保存，不持久化 partial 或自动发送授权。译文 injected 后保留原文和译文、轮换已消耗的草稿 UUID，防止进程重建复用旧发送身份。
- 低内存、语言变化及后台安全释放；Activity/快捷卡交接有500ms宽限，其他入口共享同一任务，后台不重启推理或发送。用户文字与 prompt 不进入日志。上游 llama 日志回调关闭。
- 普通页面/快捷卡提供翻译和原文/英文切换。横屏键盘造成高度不足时移入“更多”，保障编辑区和发送按钮。回归曾发现新增48dp操作行挤掉横屏编辑区，修复布局而未弱化原34dp完整行断言。

## 准备与分发

开发验证先用用户提供的本机文件，三个权重逐一测试完成后才选型/集成。现有 APK 已携带 X-ASR 大权重，最终 Debug 约670 MB、Release约655 MB；再内嵌1.13GB会明显增加安装、复制和更新成本。因此当前产品实现用户明确点击后的单模型下载准备，APK不打包任何GGUF，普通用户不选模型/量化/线程。

固定 Hy revision HTTPS下载仅接收公开权重，不接受草稿或prompt；不自动重试、回退云端、探测资源或切模型。私有 `.pending` 写入，真实字节进度和 SHA-256 校验、fsync后同目录原子rename；错误/取消保留原稿及旧可用模型，临时文件清理。准备需要至少模型大小+16MiB额外可用空间，页面说明约1.15GB。Debug另提供SAF导入同摘要本地文件，Release不提供开发文件入口。每次重新加载完整SHA校验，常驻同一权重复用但请求上下文独立。

这确定了可执行交付路径，不代表应用商店/AAB大资源渠道已验收。国内网络可达性、真实整包下载结果和分发许可审阅边界须以实际测试项为准；完整第三方通知在生成的 `assets/licenses/translation` 内，来源清单固定。

## 回归与命令（独立分支历史）

以下为整合图片输入主线前实际执行的历史命令；其中旧的 `IsolatedTranslation` / `sayinVerificationBuildType` 参数已被移除，当前入口见末尾的主线整合节。修改前：应用45项JVM及Debug成功。修改后：应用48项、翻译UTF8测试1项（49总计），0失败/错误/跳过。首次42项设备回归有1项横屏键盘失败，日志保留在忽略的 `android/local-translation/results/product-first-regression-42.txt`；未删除或弱化测试。

```powershell
./scripts/gradle.ps1 -PsayinVerification=true :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :translation:testDebugUnitTest
./scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -UseExistingRootInstall -SkipBuild -IsolatedTranslation
./scripts/gradle.ps1 -PsayinVerification=true -PsayinVerificationBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest
./scripts/verify_android_translation_product.ps1 -DeviceSerial dc39c31d -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType release -UseExistingRootInstall -UseExistingModel
./scripts/verify_android_translation_product.ps1 -DeviceSerial dc39c31d -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType debug -UseExistingRootInstall -UseExistingModel -IncludeSessionRegression
./scripts/gradle.ps1 -PsayinVerificationBuildType=release :app:assembleReleaseAndroidTest
python scripts/check_i18n.py
python scripts/check_translation_package.py android/app/build/outputs/apk/debug/app-debug.apk
python scripts/check_translation_package.py android/app/build/outputs/apk/release/app-release-unsigned.apk
```

设备脚本共用 `Local\SayIn-Android-$DeviceSerial` 互斥，检查在途instrumentation；从APK实际包名核验为 `seeyuer.sayin.translationverification` 后才安装，不替换/清理日常 `seeyuer.sayin`，不更改用户配对与Outbox。Root参数仅使用既有授权，不改安全设置。Release验证包独立suffix/debug签名，只在显式验证属性下启用；普通Release仍原包名、无验证签名配置、窄JNI keep，不宽保留整个应用。产品Release仪器化首次在runner启动时发现共享 `androidx.tracing.Trace` 被裁剪，尚未到达推理；随后又发现runner引用被优化移除的共享Kotlin facade；没有扩大产品keep。最终使用现有独立验证应用中的 Kotlin `TranslationReleaseSmoke.kt` 从外部通过 Android accessibility 驱动实际Release说话页；测试与产品分进程，不再引用产品被裁剪的运行时或反射产品内部 API。产品没有 Java 实现文件，生产JNI规则保持原样。仅独立benchmark保留其公开测试入口、Kotlin运行时和runner所需Trace。Release开发fixture使用既有root授权在隔离包私有目录写入并核验权重、恢复SELinux标签，产品正常准备与翻译不需要root。

Debug/AndroidTest、Release/R8、Lint和i18n已成功；APK检查覆盖全部native库arm64 ABI、DT_NEEDED闭包、ELF LOAD及APK ZIP16KB对齐、自有JNI六个导出、静态llama符号隐藏、NDK29 libc++ Build ID来源和许可assets、无GGUF。完整常规设备套件43项已在K40通过，耗时1122.836秒，fake输入恰好2次，无重连/进程恢复重放。新增4项session、1项原子模型准备、1项完整页/快捷卡UI，均通过；原37项包括ASR文件推理、配置与凭据、草稿加密恢复、三输入来源和通信fixture。完整页/400dp快捷卡以及横屏键盘截图已经人工查看：文字、原文/英文切换和发送入口可见；横屏草稿区满足原有完整行断言。此轮UI用fake引擎，不冒充实录/真实翻译。日志摘要、SHA与忽略目录内原始证据路径见 `docs/translation-verification/product-regression.json`。

实际产品Release页面已在K40经外部Kotlin驱动通过，真实本地推理、原文/英文切换保留且真实ASR权重同驻；未开麦、未点击发送。单次UI点击到完成4,741ms，包含文件校验/加载/页面调度，不与独立基准仅推理耗时混算，证据 `product-release-native.json`。

Debug首轮真实JNI和5项session/UI测试6/6通过（203.387秒），但发现短句推理77,858ms，首token51,960ms，远慢于Release。编译命令无优化参数、采样堆栈位于 `ggml_vec_dot_q4_K_q8_K`/矩阵乘法，据此为Debug原生C/C++配置 `-O2 -g`，保留调试符号；不是放宽超时或减少输入。原始Debug报告保留为 `product-debug-unoptimized-native.json`，优化后相同6项再次6/6通过（18.268秒），短句首token1,489ms、完整2,358ms，原生加载前的私有文件导入准备4,149ms，包含真实ASR的进程峰RSS 2,333,312KiB（约2.23GiB）。来源 `product-debug-native.json`；与原始Debug报告是同一合成句/代码路径，不作为K90性能承诺。

最终无隔离属性的普通 `seeyuer.sayin` Debug/DebugAndroidTest/Release（unsigned）构建、49项JVM和Lint已再次通过，Kotlin外部测试入口定稿后的ReleaseAndroidTest/R8构建也单独通过；未安装普通包。Debug/Release产物均完成JNI/依赖/16KB对齐/许可/无GGUF检查。`python scripts/check_desktop_dependencies.py` 的实际依赖图正确，但外部Yue源码摘要与共同基线记录不一致而退出1；外部Yue当前HEAD `cb0fa6e7594558c3e88c8ed387a457b94f855092`。本分支没有改Yue、桌面代码或重录其摘要；此项不能列为通过，也没有为Android任务改写他人的桌面验收记录。

## 关键行为对比

| 场景 | 修改前 | 修改后预期与验证路径 |
| --- | --- | --- |
| 云/本地ASR、输入法 | 共用原文草稿和发送 | 保持三来源；原套件验证录音状态、编辑、取消、发送 |
| 本地中译英 | 无 | 本地原生推理，原文/译文分开；14样本三模型实际执行 |
| 自动发送 | final后可显式启用倒计时 | 翻译撤销倒计时，候选仅手动发；session测试 |
| 迟到结果 | 无翻译任务 | 改稿/清空/录音后旧结果不能覆盖；任务版本回归 |
| 恢复及去重 | 原文+请求加密保存 | 完整译文同样加密保存，injected后不复用旧UUID |
| 取消/到限/错误 | ASR保留原稿 | 翻译独立类型化状态，partial不可发送；native与session测试 |
| 横屏键盘 | 编辑区至少完整一行 | 曾回归失败；操作折叠后仍按原断言重测 |
| 现有发送通信 | 只有用户选定正文进入Outbox | 冻结实际选定表示，不上传额外原文/模型信息，不改电脑协议 |

## 下载验收交接

实际固定revision HTTPS测试已收到真实数据，但网络很慢；用户明确表示完整下载由其测试，因此停止仅本轮隔离 instrumentation（脚本如实退出失败，没有记为下载通过）。中止时收到 28,660,657 bytes / 1,133,080,448 bytes；删除本次 `.pending`，重新SHA-256确认旧模型仍为固定摘要。未停止/清理日常 `seeyuer.sayin`。证据 `download-handoff.json`；本地文件准备、完整性/原子性及真实推理已通过，整包HTTPS下载与完整校验仍待用户执行。

用户可在尚未准备模型的安装中，先输入草稿并点“翻译成英文”，在准备面板选择下载。核对真实进度、取消/重试保留原稿、准备完成后断网翻译，以及自行检查译文后手动发送。Debug开发导入可先使用本机同摘要GGUF；Release正常准备不需要root、Python或另装服务。自动化完整下载命令为：

```powershell
./scripts/verify_android_translation_product.ps1 -DeviceSerial <设备序列号> -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType debug -DownloadModel
```

该脚本使用独立安装；仅当前已有root授权的K40可显式追加 `-UseExistingRootInstall`，普通手机不要追加。运行前手动解锁，其他设备测试须串行。

## 剩余验收边界

K90 Pro Max未连接，无法提供其16GB物理内存、首字/整句、峰值内存、温升、功耗或长期稳定性数据。K40数据不外推K90。没有本轮真实麦克风→翻译→真实Wi-Fi→普通目标应用落字证据；常规通信fixture是ADB reverse/loopback、fake输入，且必须恰好两次尝试（injected与uncertain），不能当真实落字。付费云ASR仍需明确opt-in，未调用。未启用硬件加速，未做连续功耗/热降频或多设备测量。

## 2026-11-15 主线整合与重新验收

独立翻译提交为 `620a5d2`；在同一翻译 worktree 中整合 `main@7c90899d5a743c0115a2b7830ea4415a6d6371b3`，该主线已经包含图片输入、图片历史与 Android 平台验收。整合前在主工作目录实跑 51 项 JVM 与 Debug 构建成功，主线保持干净后才进行合并。没有整文件覆盖其他 worktree；电脑接收/协议产品代码与主线一致。

冲突按功能合并：保留主线 minSdk 29、OkHttp 5.5.1、图片资源与 imageTest；翻译库及独立验证应用也统一 minSdk 29。测试构建只保留 `sayin.instrumentationBuildType`，不再并存旧选择项；Debug/JVM 与 imageTest 分两次构建。真实 Debug JNI 复用隔离 `seeyuer.sayin.imagetest`，显式 Release 验证仍为 `seeyuer.sayin.translationverification`。翻译文档编号由 42 改为 50，保留主线已有文档 42。产品和新增测试均为 Kotlin，JNI 为 C++；`src/main/java` 是已有源码目录名，不表示使用 Java 实现。

实际执行的当前命令如下。`-SkipBuild` 只在对应新 APK/fixture 已成功构建后使用；设备测试串行，安装脚本先核验隔离包。普通 Debug/Release APK 只构建和检查，没有安装到日常包。

```powershell
./scripts/gradle.ps1 :app:testDebugUnitTest :translation:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease
./scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest
./scripts/gradle.ps1 '-Psayin.instrumentationBuildType=release' :app:assembleReleaseAndroidTest
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
cargo build --workspace --locked
cargo build -p voicelink-transport --example android_fixture --locked
./scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -UseExistingRootInstall -FullSuite -SkipBuild
./scripts/verify_android_translation_product.ps1 -DeviceSerial dc39c31d -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType debug -UseExistingRootInstall -IncludeSessionRegression -SkipBuild
./scripts/gradle.ps1 '-PsayinVerification=true' '-Psayin.instrumentationBuildType=release' :app:assembleRelease :translation-benchmark:assembleDebug :translation-benchmark:assembleDebugAndroidTest
./scripts/verify_android_translation_product.ps1 -DeviceSerial dc39c31d -ModelsDirectory D:/100_Projects/110_Daily/VoiceInput/assets/translation-model -BuildType release -UseExistingRootInstall -SkipBuild
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
python scripts/check_translation_package.py android/app/build/outputs/apk/debug/app-debug.apk
python scripts/check_translation_package.py android/app/build/outputs/apk/release/app-release-unsigned.apk
```

Cargo 命令在 `desktop/` 执行，使用该目录固定的 Rust 1.99.1。Wrapper 再次确认 Launcher Temurin 25.0.5+8-LTS / Gradle 9.7.0，项目 Kotlin 2.4.21、字节码 17；Wrapper 输出的 Kotlin 2.4.0 是 Gradle 自带 Kotlin，并非项目编译插件版本。

| 本轮检查 | 实际结果 |
| --- | --- |
| JVM | 应用 54 + 翻译 1 = **55**，0 失败/错误/跳过 |
| Debug、DebugAndroidTest、imageTest/AndroidTest、Release/R8、ReleaseAndroidTest、Lint | 构建成功 |
| 常规设备 | **50/50**；共同套件 46 项 / 111.383 秒、独立图片 4 项 / 1.518 秒；分别恰好 2/1 次 fake 输入，无重放 |
| Debug 真 JNI + 会话/UI | **6/6**，18.177 秒；两次独立翻译、真实 ASR 权重同驻，无开麦/发送 |
| 优化后 Release 实际页面 | **1/1**；外部 Kotlin 驱动，真实本地翻译、原文/译文切换与 ASR 就绪断言通过 |
| Rust 全工作区/全目标 | **138** 项通过，0 失败/忽略；Clippy、四包限定格式、工作区/fixture 构建通过 |
| i18n、Yue 依赖/来源 | 通过；使用主线已更新的来源记录，没有重录外部 Yue 指纹 |
| Debug/Release 原生打包 | arm64、全部 DT_NEEDED 闭包、六个 JNI 导出、隐藏 llama 符号、16KB ELF/ZIP 对齐、NDK29 libc++ 来源与许可通过；无 GGUF |

新增 UI 联动检查在实际“更多→选图”回调后等待后台释放，确认完整译文和中文仍在、推理任务仍只有一次、未触发文字发送。完整常规套件仍验证三种输入、草稿恢复、取消、手动发送、接收范围/收据恢复、图片数据与界面；常规套件不包含付费云、发现、平台权限和性能专用类，也排除显式 opt-in 的真 JNI 类。

整合过程中发现并修正以下问题，原失败日志保留，未降低断言：

| 问题 | 修正前实际行为 | 修正后实际行为 |
| --- | --- | --- |
| 小高度/大字布局 | 150% 字号编辑区仅 80px，小于原断言 140.25px；横屏高 IME 下仅 50px，小于一行 93.5px | 按实际高度/字号将翻译移入“更多”，极小高度收起提示并减少页脚留白；横屏实际 115px，大字与横屏原断言及截图复核通过 |
| 图片历史测试造数 | 快速造 30 条历史耗尽四令牌突发，触发正常 RATE_LIMITED | 测试夹具按生产 20 次/秒间隔造数，原缓存/atlas 释放断言全部通过；未改生产限流 |
| 首次 Release 验证目录 | root mkdir 同时创建 no_backup 父目录，父目录仍属 root，产品按设计报告存储不可用 | 只给隔离包修正父目录及模型目录 UID/GID/SELinux，应用可正常持久化；没有清除日常数据或放宽产品存储保护 |
| 首次 Release 验证标签 | 新安装默认 Cloud 已选中，Compose 无障碍不提供重复点击，外部驱动等到超时 | 驱动识别已选标签，再切到本地并等待真实 ASR Ready；翻译/原文保留断言继续实际执行 |

首个整合 Gradle 命令同时选择 imageTest 与 Debug JVM，因该选择下没有 `testDebugUnitTest` 而失败；改为上面的两次规范调用。曾在 fixture 运行期间重复构建同一个 Windows exe，出现 LNK1104 文件占用；待本轮 fixture 正常退出后串行重建成功。两者均保留失败证据，不算产品通过项。

设备仍仅 Redmi K40 / M2012K11AC / API33。Debug 同一合成句首 token **1496ms**、完整 **2392ms**，私有模型导入准备 1467ms；含真实 ASR 的全进程峰 RSS 为 **2,324,984KiB**，不是单独模型内存。Release 页面点击到完成 **4130ms**，含校验/加载/调度，不与纯推理时间混算。新的原始报告为 `main-integration-debug-native.json`、`main-integration-release-native.json`，完整命令、日志 SHA-256、失败与修正证据索引见同目录 `main-integration.json`。

本轮没有重新运行 API29/API37 或 x86_64 模拟器；文档 49 的主线平台记录不能充当新增翻译 JNI 在这些设备上的实跑证据。K90、完整 HTTPS 下载、真实麦克风→翻译→Wi-Fi 落字、热降频/功耗和硬件加速边界仍按前文如实保留；没有恢复用户已接手的 1.13GB 网络下载。
