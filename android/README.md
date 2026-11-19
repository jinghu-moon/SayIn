# SayIn Android

包名、namespace 与 applicationId：`seeyuer.sayin`。已接入安全配对、TLS/WSS 与持久 Outbox。UI 使用 Miuix，业务图标使用 Tabler；主题色 `#1f75db`，其他颜色来自 Yue neutral。

包名已在开发期直接调整，源码、测试、Room schema 与 JNI 使用新名称。新包独立安装并重新配对，不提供旧包数据迁移。

## 工具链

| 工具 | 固定版本 |
| --- | --- |
| 构建JDK | Eclipse Temurin 25.0.5+8 LTS |
| Kotlin / Compose编译器插件 | 2.4.21 |
| AGP | 9.3.1（保留内置Kotlin） |
| Gradle Wrapper | 9.7.0 |
| SDK平台 / Build Tools | android-37.0 / 37.0.0 |
| minSdk / targetSdk | 29 / 37 |
| Java / Kotlin字节码目标 | 17 / 17 |

## 本机构建

在仓库根目录执行，脚本使用已确认的JDK，并在结束后恢复进程原有的`JAVA_HOME`：

```powershell
.\scripts\gradle.ps1 --version
.\scripts\gradle.ps1 :app:assembleDebug
.\scripts\gradle.ps1 :app:lintDebug
.\scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebugAndroidTest
python scripts/check_i18n.py
```

默认JDK目录为`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`。安装位置不同时，通过当前进程的`VOICELINK_JDK_HOME`指定同版本JDK。Gradle daemon另通过`gradle/gradle-daemon-jvm.properties`限制为Temurin25，不依赖系统默认JDK21。

Android SDK位置由未提交的`local.properties`（`sdk.dir=...`）或环境变量提供。Android Studio打开本目录，Gradle JDK选择同一个Temurin25安装目录，使用项目Wrapper；不要自动升级AGP或Gradle到Kotlin完整支持范围之外。

APK输出：`app/build/outputs/apk/debug/app-debug.apk`。

手工配对使用共享 Rust JNI `voicelink-pairing`，构建前需安装 Rust `1.99.1` 的 `aarch64-linux-android` target 和 Android NDK `29.0.14206865`。Gradle 通过 Variant API 自动运行 `scripts/build_pairing_android.ps1`，当前仅打包 `arm64-v8a`。这与构建 JDK 及 JVM 字节码目标独立。

测试专用 x86_64 构建额外需要同工具链的 `x86_64-linux-android` target，只可对隔离 imageTest 显式启用：

```powershell
./scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' '-Psayin.emulatorTests=true' :app:assembleImageTest :app:assembleImageTestAndroidTest
./scripts/verify_android_platform.ps1 -DeviceSerial emulator-5582
```

模拟器参数会禁用日常 Debug/Release variants。常规 JVM/Debug/Lint 使用默认参数单独执行；随后不带 emulatorTests 构建 imageTest，即恢复默认 arm64。平台和通信脚本只操作 `seeyuer.sayin.imagetest`，不覆盖日常包。环境与实测范围见 [49](../docs/49-android-platform-support-and-validation.md)。

当前连接的小米手机在无SIM情况下拦截普通USB安装，但已验证可使用设备现有Root授权，通过`su -c 'pm install -r ...'`安装本项目APK。具体命令和实际结果见[06设备安装记录](../docs/06-android-device-installation.md)。

## 设计资源

`designsystem/YuePalette.kt`映射Yue原始neutral色阶，`VoiceLinkTheme.kt`显式定义全部Miuix颜色角色，不使用Monet。业务页面只引用`VoiceLinkIcon`语义入口。

Tabler v3.51.0 的原始 SVG、MIT 许可及 SHA-256 清单位于 `design/tabler/`。可重复生成，无需网络或额外 Python 依赖：

```powershell
python scripts/generate_tabler_icons.py
```

上述命令从仓库根目录执行。生成结果随源码保存，Android构建不要求安装Python。

## 国际化

首批支持简体中文与English，提供跟随系统选项。`values/strings.xml`是完整英文兜底，`values-b+zh+Hans/strings.xml`是简体翻译。Android系统/AppCompat统一保存应用语言，Compose宿主为`AppCompatActivity`；不要在DataStore或全局Locale中再维护另一份覆盖。

新增用户可见文案时同时补齐两种语言，并执行`python scripts/check_i18n.py`及Lint；格式参数使用编号，数量使用复数资源。语言切换不能修改或发送用户原文。Debug生成`en-XA`/`ar-XB`测试资源，Release不生成伪翻译。完整约束与尚待真机验收的场景见[05国际化设计](../docs/05-voicelink-internationalization-design.md)。

## 当前边界

当前支持扫码/8 位配对码二选一、本机 KeyStore AES-256-GCM 凭据、TLS 1.3 固定叶证书与主机名验证、Room WAL/FULL 持久 Outbox、最终文字预览、相同请求 ID 查询、显式 unknown/held/uncertain 状态和前台重连。电脑自动选择网络并批准配对后待命，手机发送的文字直接输入电脑当前光标；显式暂停需要在电脑恢复。发送成功以实际回执为准，不以 socket send 成功冒充目标程序显示成功。

应用在没有可见入口且未录音时断开 WSS；用户主动录音期间由 microphone 前台服务维持会话。已接入有界系统 NSD/mDNS 找回已配对电脑；尚无 IPv6 或 Root 辅助模块。最低 Android 10 与系统 TLS 1.3 要求保持一致，不再允许 Android 8/9 安装后才在连接阶段失败，也不降级 TLS 或新增安全提供程序。平台决策和验证见 [49](../docs/49-android-platform-support-and-validation.md)，通信实现见 [10 实施记录](../docs/10-secure-pairing-and-android-transport.md)。

选型与协议依据见[03选型文档](../docs/03-android-technology-selection.md)和[01协议v2](../docs/01-voicelink-protocol-v2.md)。

## 语音识别

本地中译英的原生依赖准备和回归命令见本文末节。

说话页提供本地识别、云端、输入法三个来源，共用草稿、完成输入和 LAN Outbox。本地使用 X-ASR 480ms 中英带标点模型；准备依赖与验收边界见 [31 本地识别](../docs/31-x-asr-local-recognition.md)。

云端首期接入豆包流式语音识别 2.0 小时版，手机通过 WSS 直接上传音频与可选热词，电脑只接收最终选择发送的文本。进入设置填写 APP Key（旧控制台可用 APP ID + Access Token），保存后验证服务；回到说话页选择「云端」并开始说话。凭据加密存于手机、不进入源码或电脑。使用方法、官方协议依据和真实服务验收步骤见 [32 云端接入](../docs/32-doubao-cloud-streaming-asr.md)。


## 说话页与连接入口（2026-11-14）

原生界面已按手机 Demo 重构：顶栏连接状态面板、草稿主区、底部来源与居中录音入口、清空/发送。首次扫码或代码连接等待电脑批准；已配对前台自动重连，旧地址失败后有界发现，完整固定证书与令牌认证成功后才保存新路由；连接帮助仍可维护 IPv4:端口，固定证书身份及历史不变。电脑主动暂停与待核对必须在电脑处理，手机不能解除；重连不补发旧文本。

本地识别选中后真实加载并预热，准备期间不录音。同页复用模型权重、每段更换流；云端继续使用原配置，不上传未明确开始录音的音频。新增状态文案和紧凑布局支持中英、明暗、大字号及键盘场景。

34 为上一阶段实现记录；当前共享会话、快捷入口及双端联动见下节。


## 统一会话与快捷入口

完整页与原生快捷卡共享 SpeakSession、草稿、选区、录音、云配置及当前请求；换入口不重复录音。设置可打开快捷卡、添加系统磁贴，或按需授权可选悬浮球，默认不启用悬浮球。收起保留已开始录音，录音通知提供结束和返回入口。AppCompat 安装浮动窗口后才设置卡片宽高，避免退化成全屏对话框。

说完自动发送默认关闭，仅显式结束且拿到 final 后在可见界面显示三秒倒计时；更多、连接、编辑、收起或输入范围变化取消且不恢复。后台结束不会自动发；输入法完成键只收起键盘。injected 展示为「已提交输入」，不代表已验证目标框显示文字。已接收/无回执只查原 ID；uncertain 在电脑本地核对，不能从手机解除。

草稿使用独立 Keystore AES-GCM + AtomicFile 合并保存；Room schema 2 为发送记录增加独立加密正文，终态释放待执行载荷而保留最近 100 条展示记录。升级安装保留本包配对、密钥及账本，不卸载清数据。

42 项 JVM、37 项常规设备与双端 fixture 测试、实际窗口检查、已知边界和人工验收步骤见 [37 手机统一说话界面与桌面联动](../docs/37-android-unified-speak-and-desktop-coordination.md)。


已配对发现的资源与安全规则、真实 LAN/回环验证及旧 Android API 限制见 [40 实施记录](../docs/40-paired-host-discovery-and-reconnect.md)。`scripts/verify_android_discovery.ps1 -Lan` 使用真实 NSD、独立身份和随机端口，不使用 ADB reverse；不带 `-Lan` 只验证隔离连接/认证/回执，不代表真实发现。`-Revoked` 验证撤销身份无法迁移路由。此独立测试类不进入常规 37 项套件；不得运行付费云测试冒充连接验证。

当前完整隔离回归：`scripts/verify_android_transport.ps1 -FullSuite`。仅构建/安装 `seeyuer.sayin.imagetest`，文字/图片分别使用独立电脑身份；不再通过日常包运行仪器化测试。`-TransportOnly` 仅文字数据回归，单独 `-Lan` 为真实局域网图片数据测试；专用发现、平台授权与付费云端测试不混入常规套件。
## 本地中译英

三种输入来源完成草稿后可选择“翻译成英文”。翻译由手机 llama.cpp CPU JNI 执行；原文和可编辑译文分别保留，译文只能手动发送，复用既有 Outbox 和接收范围检查。翻译失败不会回退云服务。完整页和快捷卡共享任务；横屏键盘展开等高度不足场景的翻译入口位于“更多”。

开发环境先准备固定源码，保留 JDK 25.0.5+8 / 字节码 17，另安装 NDK 29.0.14206865、CMake 3.22.1：

```powershell
python scripts/prepare_x_asr.py
python scripts/prepare_translation.py
./scripts/gradle.ps1 :app:testDebugUnitTest :translation:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
python scripts/check_i18n.py
python scripts/check_translation_package.py android/app/build/outputs/apk/debug/app-debug.apk
```

这些准备命令在开发电脑执行，安装后的 Android 产品不需要 Python、Termux 或本地 HTTP 服务。Hy-MT2-1.8B Q4_K_M（1,133,080,448 bytes）与 PLaMo 0.8B Q4_K_M（573,126,848 bytes）以未压缩 asset 内嵌 APK：构建任务读取被忽略的 `assets/translation-model/`，逐个核验固定长度/SHA-256 后复制，运行时不复制第二份，直接通过 `AssetFileDescriptor` 读取。Debug/Release APK 约 2.4GB。PLaMo 2B 仍由用户明确下载；Debug 另可导入同一摘要的本机模型，Release 不暴露文件选择器。下载准备期间需要约 1.15 GB 额外可用存储；网络失败保留原稿和旧可用模型。

回归脚本 `verify_android_transport.ps1 -FullSuite` 与 `verify_android_translation_product.ps1 -BuildType debug` 共用独立 `seeyuer.sayin.imagetest` 包（imageTest）；真实Release验证使用显式隔离的 `seeyuer.sayin.translationverification` 包，不覆盖日常数据；共享设备必须串行运行。前者验证完整常规套件和恰好两次 fake 输入，后者显式验证真实 JNI 与 ASR 权重共存；两者都不证明真实麦克风到 Wi-Fi 目标应用落字。模型比较和 K40 实测、K90 待验收边界见 [50 本地翻译实施](../docs/50-android-local-translation-implementation.md)。
