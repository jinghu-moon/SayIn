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
| minSdk / targetSdk | 26 / 37 |
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

当前支持扫码/8 位配对码二选一、本机 KeyStore AES-256-GCM 凭据、TLS 1.3 固定叶证书与主机名验证、Room WAL/FULL 持久 Outbox、最终文字预览、相同请求 ID 查询、显式 unknown/held/uncertain 状态和前台重连。电脑选择网络并批准配对后自动待命，手机发送的文字直接输入电脑当前光标；显式暂停需要在电脑恢复。发送成功以实际回执为准，不以 socket send 成功冒充目标程序显示成功。

应用在后台断开 WSS。没有 mDNS/IPv6、ASR 或 Root 辅助模块。TLS 1.3 不可用时拒绝降级，旧 Android 默认安全提供程序可能不支持。完整实现/测试边界见 [10 实施记录](../docs/10-secure-pairing-and-android-transport.md)。

选型与协议依据见[03选型文档](../docs/03-android-technology-selection.md)和[01协议v2](../docs/01-voicelink-protocol-v2.md)。
