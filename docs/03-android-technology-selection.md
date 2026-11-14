# 语音直达：Android 手机端技术选型与依赖

日期：2026-11-12  
阶段：技术选型已确认，Android工程基础已开始实现；实际构建与实现边界见[04工程基础实施记录](04-android-foundation-implementation.md)。本文件中的发布核查与工程验证分别记录。  
依据：[通信协议 v2](01-voicelink-protocol-v2.md)、[Root 辅助模块后续计划](02-voicelink-root-assistant-plan.md)。

## 1. 选型结论与版本原则

采用 **Kotlin + Jetpack Compose + Miuix + Tabler Icons + Navigation 3 + Coroutines/Flow + OkHttp + kotlinx.serialization + Room + DataStore**。扫码配对采用 CameraX + 捆绑模型版 ML Kit；离线识别采用 sherpa-onnx Android AAR。2026-11-13 按用户选择，首个实施模型调整为 X-ASR 480ms 中英带标点 FP32，替代此前 SenseVoice int8 + VAD 的首期设想，见 [31 实施与实际验证](31-x-asr-local-recognition.md)。Root 增强继续作为后续独立计划。

按用户最新要求，**Kotlin取最新稳定版，AGP和Gradle取该Kotlin版本官方完整支持范围内的最新稳定版**。因此工具链确定为 **Kotlin 2.4.21 + AGP 9.3.1 + Gradle 9.7.0**。构建JDK选最新维护版的LTS分支，其他直接依赖以查阅日最新稳定版为候选。所有版本显式锁定，不使用 `+`、`latest.release` 或 SNAPSHOT 自动漂移。本文件的「最新」是2026-11-12的查阅快照，初始化工程及新增阶段依赖时再核对一次。

Miuix采用用户指定的组件体系，Tabler采用最新上游图标源。直接选择稳定发布不等于整个传递依赖图没有预览版：Miuix 0.9.4包含一个alpha传递依赖，详见3.5节。

区分以下三件事：

1. **发布存在与最新状态**：查阅官方发布说明、Google Maven/Maven Central元数据及POM。
2. **兼容性范围**：取Kotlin、AGP、Gradle、JDK官方范围的交集，记录第三方组件限制与已知问题。
3. **实际可构建**：创建工程后执行依赖解析、编译、测试和设备安装。本次只完成前两项的选型研究，尚未执行第三项。

传递依赖遵循所选组件的依赖图与BOM约束，不为了「每个jar都最新」强行替换JNI内部运行库或所有传递组件。若发现冲突，修正配置或记录具体阻断，不静默降级为旧版并继续声称全部最新。

## 2. 工程与工具链

| 项目 | 当前候选版本/设置 | 说明 |
| --- | --- | --- |
| Android Gradle Plugin | **9.3.1** | Kotlin2.4.21完整支持范围的上限；采用AGP9新DSL和内置Kotlin |
| Gradle Wrapper | **9.7.0** | Kotlin2.4.21完整支持范围的上限；满足AGP9.3所需的最低9.5.0 |
| Kotlin/KGP | **2.4.21** | Kotlin官方文档及Maven Central确认；需显式对齐AGP内置KGP实际解析版本 |
| Compose Compiler插件 | **2.4.21** | `org.jetbrains.kotlin.plugin.compose`；与Kotlin版本一致，不使用旧Compose编译器1.5.x |
| Serialization插件 | **2.4.21** | `org.jetbrains.kotlin.plugin.serialization`；注意它与序列化运行库是不同版本体系 |
| KSP | **2.3.13** | `com.google.devtools.ksp`；Room代码生成使用KSP2，存在已知并发回归，见第3节 |
| JDK发行版 | **Temurin 25.0.5+8（25 LTS）** | 项目构建JDK选择；在Gradle9.7支持的17–26范围内，不采用JDK27 |
| compileSdk | **37.0（API 37 / minor 0）** | AGP9.3明确支持API37；不将37.2的最新仓库状态等同于这套工具链已经验证支持 |
| targetSdk | **37** | 面向Android17行为，必须处理对应局域网权限与后台规则 |
| minSdk | **26，初始产品选择** | 表示支持范围，不是要使用旧依赖；实际用户机型确认后可调整 |
| SDK Build-Tools | **37.0.0** | SDK稳定channel确认；需要显式设置以免AGP继续采用其默认36.0.0 |
| Platform-Tools | **37.0.1** | 稳定channel；37.0.3位于预览channel，不作为本次稳定版选择 |
| NDK | 首阶段不引入 | 使用预编译AAR无需本机编译ASR；若以后自行编译，当前稳定候选为30.0.16248370，届时重新核对 |
| Java/Kotlin字节码目标 | 初始均为17 | 与构建JDK版本分开配置；显式对齐Java targetCompatibility与Kotlin jvmTarget |
| 版本管理 | Gradle Version Catalog + Wrapper | 在`libs.versions.toml`和Wrapper中锁定，提交校验和；不生成依赖旧全局环境的构建 |

用户已安装并确认本项目使用 **Temurin25.0.5+8 LTS（x64）**，本机目录为`C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot`。已直接运行该目录的`java -version`与`javac -version`，分别确认25.0.5+8-LTS与25.0.5。

系统默认PATH仍指向Temurin21.0.10+7，系统/用户`JAVA_HOME`未作全局修改。工程已通过`scripts/gradle.ps1`显式选择Temurin25.0.5+8，Wrapper固定9.7.0，并设置Temurin25 daemon条件；已核对启动JVM和实际构建环境。Android Studio打开`android/`后应选择同一JDK。构建结果见04文档。

## 3. 必须在初始化阶段验证的组合问题

### 3.1 在官方完整支持范围内取最新

Kotlin官方表对KGP **2.4.20–2.4.21**列出的完整支持范围为：

- Gradle：**7.6.3–9.7.0**。
- AGP：**8.5.2–9.3.1**。

AGP9.3最低要求Gradle9.5.0、JDK17。因此选 **2.4.21 / 9.3.1 / 9.7.0**，同时满足Kotlin范围和AGP最低要求。即使AGP9.3.2、9.3.3或更高版本已发布，也不把表中的9.3.1上限自行解释为整个9.3.x系列。

撤回此前AGP9.5.1、Gradle9.9.1、JDK27的候选组合。Kotlin表允许的“完整支持”描述的是KGP与构建工具的范围，不代表Miuix、KSP、Room和整个应用已经通过组合测试。Gradle内嵌的Kotlin2.4.0用于Gradle自身/Kotlin DSL，与应用KGP2.4.21分别管理。

compileSdk先采用官方明确列出的37.0。已读取Miuix UI/Preference、Core、Activity、Compose UI/Foundation、Lifecycle及Navigation3 UI的AAR元数据，这些抽查项的最低compileSdk不超过37.0、最低AGP不超过9.1.0；不要求为了它们突破9.3.1。该抽查不替代完整依赖图的`checkDebugAarMetadata`。37.2虽然是仓库中更新的SDK，不在本次未经证明地启用。

### 3.2 AGP内置Kotlin需要实际对齐

本次读取AGP9.3.1的POM，其运行时KGP依赖仍为2.2.10。仅在Version Catalog写`kotlin = "2.4.21"`，不代表实际编译器已经升级。

按AGP官方说明使用受支持的高版本KGP类路径配置，并检查实际解析结果。不要同时给Android模块应用`org.jetbrains.kotlin.android`，也不通过关闭built-in Kotlin或启用legacy DSL来沿用旧模板。Compose和Serialization编译器插件需与最终实际Kotlin版本一致。

### 3.3 最新KSP存在官方已知回归

KSP2.3.13官方发布页明确列出并发执行KSP的回归问题（issue3286）。仍按用户最新要求记录该版本，不悄悄切换2.3.12。首期单`:app`模块可减少处理任务数量，但**不能据此证明回归不会发生**。

初始化后验证干净构建、增量构建、重复构建及需要的并行构建。若实际触发，记录为存储集成的明确阻断，依据官方修复或经验证的配置处理；不预设未经验证的禁用缓存/并行开关为保证有效的方案。

### 3.4 Compose BOM已经检查实际映射

最新稳定Compose BOM为**2026.09.00**。本次读取其POM，确认对应UI、Runtime、Foundation为**1.12.1**，与分别读取的最新稳定元数据一致。BOM也包含Material3 1.4.0约束，但本应用不再直接选用Material3作为主组件库。

使用BOM管理`androidx.compose.*`组件；它不管理Kotlin编译器、Activity、Lifecycle、Navigation、Room和CameraX，**也不自动管理Miuix依赖的`org.jetbrains.compose.*`坐标**。初始化时检查Gradle Module Metadata、变体选择、最终解析版本与重复类，不强行把JetBrains Compose版本号全部替换为AndroidX版本号。

### 3.5 Miuix版本、使用范围与传递依赖

采用Maven Central发布的 **Miuix 0.9.4**。官方纯Android接入方式是`top.yukonga.miuix.kmp:miuix-ui-android`，设置页追加同版本`miuix-preference-android`。本地`refer/miuix-main`用于查阅API与示例，不作为整个KMP源码工程导入，也不继承其AGP9.5.1配置。

发布包POM声明JetBrains Compose Foundation1.12.0、Kotlin stdlib2.4.20，以及 **`org.jetbrains.compose.material3:material3-window-size-class-android:1.12.0-alpha03`** 等传递依赖。必须保留该事实，不能声称依赖图完全由稳定版本组成；也不未经验证就排除窗口尺寸组件或强制换成另一个坐标。发布包与main分支的依赖版本不完全相同，以发布元数据和实际解析结果为准。

Miuix UI和Preference的AAR已实际下载并读取元数据，二者要求minCompileSdk37/minor0；这不是应用构建通过的证明。库仍声明API处于实验阶段，应用在designsystem集中封装主题与常用组件。使用Miuix的页面、按钮、输入框、对话框、列表及设置组件，保留Navigation3作为唯一页面导航栈；不为示例效果添加第二套导航、模糊或窗口运行库。应用业务图标使用Tabler，不额外引入Miuix图标全集。

### 3.6 Java SDK是否需要升级

这里的Java SDK指**构建用JDK**，与Android SDK、设备上的ART和应用字节码目标不同。

- **不强制升级大版本**：当前JDK21满足AGP9.3最低17及Gradle9.7运行范围17–26，接入Miuix不构成必须换JDK的理由。
- **建议更新维护版本**：本机21.0.10+7可更新到当前Temurin **21.0.13+9**；如果继续使用21，应采用这一维护版。
- **本项目已确认使用JDK25 LTS**：用户已安装 **Temurin25.0.5+8** 并确认采用该版本。Gradle从9.1起支持用Java25运行；初始化时仍需验证AGP、Lint和KSP任务，不能将Gradle支持等同于所有插件已测试。
- **不采用JDK27**：Gradle9.7明确尚不支持用Java27运行。Java26虽处于其支持范围，但不因非LTS主版本更高就替代本方案的LTS选择。

Miuix源码仓库使用JDK21构建，这是库作者的构建环境，不能推导成消费者必须设为21或输出21字节码。应用Java/Kotlin目标初始显式设为17；构建JDK更新不会让旧Android自动获得Java25平台API。

## 4. 首阶段直接依赖

这些依赖用于「手机原生页面 + 现成输入法编辑 + v2网络客户端 + 持久Outbox」，先不加入ASR和Root。运行库与插件分开记录。

| 用途 | Maven坐标 | 当前稳定版本 | 选择理由 |
| --- | --- | --- | --- |
| Android基础扩展 | `androidx.core:core-ktx` | **1.19.1** | 通知、系统API辅助及基础Android扩展 |
| Compose Activity | `androidx.activity:activity-compose` | **1.13.0** | 单Activity、Compose入口和Activity Result权限流程 |
| 应用语言宿主 | `androidx.appcompat:appcompat` | **1.8.0** | 最新稳定版；Compose宿主采用AppCompatActivity，统一API26–32与API33+应用语言设置，见05方案 |
| 生命周期订阅 | `androidx.lifecycle:lifecycle-runtime-compose` | **2.11.0** | 按生命周期收集StateFlow |
| ViewModel | `androidx.lifecycle:lifecycle-viewmodel-compose` | **2.11.0** | 界面状态与业务状态生命周期分离 |
| Compose版本平台 | `androidx.compose:compose-bom` | **2026.09.00** | 对齐Compose系列 |
| UI基础 | `androidx.compose.ui:ui` | BOM → **1.12.1** | 原生Compose界面 |
| 布局/编辑/交互 | `androidx.compose.foundation:foundation` | BOM → **1.12.1** | 文本编辑、列表、手势等 |
| Miuix组件 | `top.yukonga.miuix.kmp:miuix-ui-android` | **0.9.4** | 手机端主组件库，显式接入Yue配色 |
| Miuix设置项 | `top.yukonga.miuix.kmp:miuix-preference-android` | **0.9.4** | 与主组件同版本，设置页使用 |
| 预览元数据 | `androidx.compose.ui:ui-tooling-preview` | BOM → **1.12.1** | Android Studio预览 |
| 调试工具 | `androidx.compose.ui:ui-tooling` | BOM → **1.12.1** | 仅debugImplementation |
| 导航状态 | `androidx.navigation3:navigation3-runtime` | **1.2.1** | App拥有类型化back stack，适合纯Compose新工程 |
| 导航展示 | `androidx.navigation3:navigation3-ui` | **1.2.1** | 主界面、设置、历史与配对页面导航 |
| 导航ViewModel作用域 | `androidx.lifecycle:lifecycle-viewmodel-navigation3` | **2.11.0** | 页面级ViewModel管理 |
| 异步与状态流 | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.11.0** | Coroutines/Flow；不引入RxJava |
| JSON运行库 | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.13.0** | v2消息序列化，类型清晰；配合2.4.21编译器插件 |
| 网络版本平台 | `com.squareup.okhttp3:okhttp-bom` | **5.5.1** | 对齐网络与测试组件 |
| WSS客户端 | `com.squareup.okhttp3:okhttp` | BOM → **5.5.1** | WebSocket、TLS、连接控制；无需Retrofit或另引Ktor |
| 事务数据库 | `androidx.room:room-runtime` | **2.8.5** | Outbox、历史、常用语与事务更新 |
| 数据库编译器 | `androidx.room:room-compiler` | **2.8.5** | 使用`ksp(...)`，不作为运行依赖 |
| 轻量设置 | `androidx.datastore:datastore-preferences` | **1.3.0** | 模式、倒计时、阈值、外观等偏好 |

图标采用 **Tabler Icons v3.51.0** 的项目内矢量源，不新增未经发布验证的Maven运行依赖，具体方案见9.2节。

首期使用构造函数注入与小型AppContainer，不引入Hilt/Koin。该选择用于控制初始化依赖与对象所有权，不代表它们性能差；以后按实际模块复杂度重新评估。

Room2.8系列的协程能力已并入runtime，首期不为了旧示例重复添加`room-ktx`。不引入Rx、Paging、WorkManager或数据库驱动替换，除非具体功能确有需求。Room schema输出可先通过KSP参数配置；需要时再引入同版本`androidx.room` Gradle插件。

## 5. 扫码配对阶段新增依赖

| 用途 | Maven坐标 | 最新稳定版本 |
| --- | --- | --- |
| 相机实现 | `androidx.camera:camera-camera2` | **1.6.2** |
| 生命周期绑定 | `androidx.camera:camera-lifecycle` | **1.6.2** |
| 相机预览 | `androidx.camera:camera-view` | **1.6.2** |
| 捆绑扫码模型 | `com.google.mlkit:barcode-scanning` | **17.3.0** |

相机预览可通过AndroidView承载PreviewView，首期不额外引入CameraX Compose适配包。仅启用QR格式，扫描结果进入结构化配对校验，不能把任意二维码当成可执行URL。

选择捆绑模型版ML Kit，是为了扫码无需首次联网下载识别模块。官方文档区分捆绑与依赖Google Play Services动态下载的实现；本项目不把后者当成离线首用保证。不开第二套ZXing解码栈，也不使用需要首次下载模块的Google Code Scanner作为唯一入口。

QR中包含完整证书和秘密，密度可能较高；真实显示尺寸、相机距离和编码长度必须验证，不能仅验证小样例二维码。CAMERA权限仅在扫码时申请，二维码和配对材料不写日志。

## 6. 离线语音识别阶段新增依赖

| 项目 | 当前选择 | 引入方式 |
| --- | --- | --- |
| 推理框架 | **sherpa-onnx 2.0.1** | 官方Release的Android AAR |
| 流式识别 | X-ASR 480ms 中英带标点 FP32 | 用户下载的固定 ONNX 模型，首期已接入 |
| 分段 | OnlineRecognizer 原生 endpoint | 只提交片段，不结束录音或发送；首期无独立 VAD |
| 录音 | Android `AudioRecord` | 系统API，无额外录音封装依赖 |

官方发布页存在`sherpa-onnx-2.0.1.aar`。GitHub的`releases/latest`可能返回`fa-models`等模型资源发布，不能把这个标签误认为推理框架版本；本次同时核对语义版本标签和对应Release，当前运行库选择v2.0.1。

AAR官方下载地址：

<https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar>

GitHub Release API给出的SHA-256为：

```text
a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7
```

该值在选型阶段来自发布元数据；2026-11-13 已实际下载 AAR、计算并核对 SHA-256，Android 真机 JNI 推理通过，见 31。不能把 JVM 平台 JAR/JitPack 坐标当成 Android AAR 直接替代。

首期真机以arm64-v8a为主，模拟器需要时保留x86_64。必须检查JNI库及其依赖完整性、16KB页大小兼容、最低系统版本、Release/R8构建与设备实际加载。先用官方AAR自带的运行库组合，不另强行添加一份最新版ONNX Runtime造成重复so或ABI冲突。

模型有独立版本、许可证和校验和，不能用`sherpa-onnx=2.0.1`代替模型清单。实际 X-ASR 文件摘要固定于 `android/asr-artifacts.json`；SenseVoice / 独立 VAD 不属于本轮实施，不能写成已接入能力。

## 7. 直接使用系统API的能力

| 能力 | 选择 | 说明 |
| --- | --- | --- |
| mDNS发现 | `NsdManager` | 无需JmDNS等另一套发现库 |
| 网络变化 | `ConnectivityManager` | 网络切换触发连接检查；不能仅凭Wi-Fi图标判定局域网可达 |
| 私密材料保护 | Android Keystore + `Cipher` AES-GCM | token以密文保存；不把DataStore本身当加密方案 |
| 通知/快捷设置 | Notification与`TileService` | Core兼容辅助；不额外引入通知SDK |
| 悬浮入口 | `WindowManager`与系统悬浮权限 | 先做入口，录音面板用可见Activity；不为保活引入无障碍服务 |
| 权限 | Activity Result APIs | 无需Accompanist Permissions或通用权限弹窗库 |
| 唤醒/低延迟锁 | `PowerManager`、`WifiManager` | 限时持有、按条件使用；不会因依赖存在就自动生效 |
| 日志 | 脱敏封装 + `android.util.Log` | 默认无全文、token和音频；不加HTTP正文日志拦截器 |

token、待处理文字、历史与设置分开存储：Room负责事务结构，DataStore只负责非敏感偏好；敏感字段采用系统密钥保护。配对授权与Outbox的备份/恢复需要明确排除或重新验证，不能把恢复出来的旧会话直接启用。

Root、su、libsu、Shizuku、Zygisk均不是首阶段依赖，按02文档后续评估。前台服务不是一个需下载的保活库，也不使用WorkManager维持实时WSS连接。

## 8. 首阶段测试依赖

| 用途 | 坐标 | 当前版本/管理方式 |
| --- | --- | --- |
| JVM测试API | `org.jetbrains.kotlin:kotlin-test-junit` | **2.4.21**；JUnit4体系与Android/Compose测试一致 |
| JUnit运行器 | `junit:junit` | **4.13.2**；JUnit4系列最新，不为更高大版本号同时引入另一套测试引擎 |
| 协程测试 | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | **1.11.0** |
| WSS/HTTP受控服务 | `com.squareup.okhttp3:mockwebserver3` | OkHttp BOM → **5.5.1**；可覆盖连接与ACK丢失场景 |
| Room测试 | `androidx.room:room-testing` | **2.8.5**；按实际使用API配置在设备/JVM测试侧 |
| Android测试运行器 | `androidx.test:runner` | **1.7.0** |
| AndroidJUnit | `androidx.test.ext:junit` | **1.3.0** |
| Compose UI测试 | `androidx.compose.ui:ui-test-junit4` | Compose BOM → **1.12.1**；androidTestImplementation |
| Compose测试宿主 | `androidx.compose.ui:ui-test-manifest` | Compose BOM → **1.12.1**；debugImplementation |

Android测试配置需单独导入Compose/OkHttp BOM，不能假设某一配置的BOM自动覆盖所有测试配置。优先采用Fake、受控服务和虚拟时间测试，不为了每个接口加Mock框架。

有价值的首批测试是：Outbox先保存再发送、原ID重复/重连查询、过期不补打、paused/held与控制不互相阻塞、WSS证书不符拒绝、数据库事务恢复，以及编辑取消自动发送。先不写大量只镜像UI布局或内部实现的测试。

## 9. 工程结构与界面技术边界

用户已确认Android包名为 **`seeyuer.voice.link`**。工程初始化时配置如下，Kotlin源码根包名也采用该值：

```kotlin
android {
    namespace = "seeyuer.voice.link"
    defaultConfig {
        applicationId = "seeyuer.voice.link"
    }
}
```

初期一个Android应用模块即可，按职责分包，不提前拆十几个Gradle模块。建议目录为`android/`，其中`:app`包含：

```text
app/
  ui/              主界面、输入面板、历史、连接、设置、扫码
  designsystem/    Yue颜色、排版、间距、图标与组件包装
  protocol/        v2消息类型、校验与状态映射
  connection/      WSS、发现、配对、会话与重连
  data/            Room、Outbox、历史、DataStore
  security/        主机信任、Keystore、敏感材料存取
  input/           编辑、智能发送、后续录音与识别
  service/         通知、磁贴、悬浮入口和生命周期协调
```

UI采用ViewModel + StateFlow + 单向事件/状态，不另引入MVI框架。连接与Outbox所有权归应用核心，不能被某个页面ViewModel持有后随导航消失。v2业务操作与UI消息分开，不用Compose重组回调直接发送文字。

国际化从工程基础开始接入，首批为用户确认的简体中文与English。Android采用原生XML资源、AppCompat应用语言API、完整翻译及格式门禁；UI语言与ASR/用户原文分离。具体数据边界与生命周期规则见[05国际化设计](05-voicelink-internationalization-design.md)。

### 9.1 Miuix与Yue主题

Yue tokens位于`D:/100_Projects/110_Daily/Yue-Design-System/packages/tokens`，是CSS自定义属性包，不能作为原生Compose依赖直接安装。将已确认的颜色、间距等显式映射到Kotlin token，并记录来源文件；不引入WebView、Node或npm运行时。

使用`MiuixTheme(colors = ...)`和自定义明暗`lightColorScheme`/`darkColorScheme`。主题色固定 **`#1f75db`**，全部非主题色使用 **neutral**；关闭Monet/动态取色。不能只修改primary后保留库默认的其他彩色角色：secondary、tertiary、error及其container/on颜色、背景、表面、描边和遮罩都要显式映射。错误/成功/离线等状态通过文字、Tabler图标及形态区分，并检查可读性和对比度，不擅自加红绿黄。

### 9.2 Tabler图标与YingLi-Player参考方案

参考YingLi-Player的Version Catalog、`app/build.gradle.kts`与`core/designsystem/icon/YingLiIcon.kt`。它使用：

```toml
tabler-icons = "0.1.0-local.1"
compose-icons-tabler = { module = "io.github.jinghu-moon.composeicons:icons-tabler", version.ref = "tabler-icons" }
```

该包来自本机`mavenLocal()`，POM注明由svg2compose流程生成，源码地址为`jinghu-moon/compose-icons`；YingLi文档记录其Tabler源为3.46.0。**0.1.0-local.1是本地包装版本，不是Tabler官方版本，也不是已验证存在于Maven Central的公共依赖**（对应Central元数据本次返回404）。不能照抄后让新工程依赖这台机器的`~/.m2`缓存。

本项目沿用其统一语义图标入口与Compose `ImageVector`方案，将最新官方 **Tabler v3.51.0** 中实际需要的图标生成到项目`designsystem/icon`源码。固定上游tag和commit **`58f60538fe2fab2e1459a09198f9bd3b033bf90e`**，保留MIT许可。工程基础已生成6个图标，原始SVG、MIT许可、SHA-256和生成器版本记录在`android/design/tabler/`，生成脚本位于`scripts/generate_tabler_icons.py`；无需本地Maven缓存或手机运行时图标依赖。

应用页面只引用`VoiceLinkIcon`等语义入口，统一映射为`ImageVector`；普通图标使用24×24、2px描边的Outline，选中态确有需要时使用Filled。按需生成麦克风、键盘、电脑、连接、二维码、历史、设置等，补充`contentDescription`与独立触控区域。缺失语义先查Tabler或设计本地矢量，不自动回退Material图标。图标生成工具只参与开发构建，不成为手机运行时依赖。

## 10. 依赖引入与实现顺序

1. **工具链与UI验证**：固定范围内的最新工具链、JDK25、Wrapper和Version Catalog，建立最小Compose + Miuix页面，接入Yue明暗主题和Tabler语义图标，确认编译与安装；检查KGP实际解析结果、Compose依赖图与KSP已知问题。
2. **普通输入主链路**：原生页面、编辑框与输入法、v2协议类型、WSS、Room Outbox、状态查询、DataStore设置；电脑尚无核心时用受控协议服务验证，不将其称为真实端到端完成。
3. **安全配对与发现**：CameraX/ML Kit、二维码校验、限定主机信任、NsdManager、设备撤销和权限处理；不采用trust-all或明文降级。
4. **便捷入口**：通知、磁贴和可选悬浮球；按Android17/实际ROM验证前台服务和局域网权限。
5. **离线ASR**：官方 AAR + AudioRecord + X-ASR 流式识别已接入并验证模型文件推理；实录、冷启动、准确率和温升仍需实测，见 31。
6. **Root增强**：在普通模式基线形成后，依照02文档启动单独原型，当前不引入。

首阶段完成选型后，初始化工程至少验证以下命令及真实结果，不能以依赖POM存在代替构建成功：

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:checkDebugAarMetadata
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
.\gradlew.bat :app:dependencyInsight --dependency foundation --configuration debugRuntimeClasspath
.\gradlew.bat :app:dependencyInsight --dependency material3-window-size-class --configuration debugRuntimeClasspath
```

设备就绪后执行安装和必要的connected测试，核对实际解析版本、权限行为与网络/存储恢复。Room默认配置不等于v2要求的持久保证；需要检查事务、WAL、实际writer连接的synchronous配置以及崩溃/重启行为，不能只看到Room名称就声称Outbox已经可靠。

## 11. 官方资料与版本核查记录

本次通过官方资料与仓库元数据核查；Context7提供用法定位，具体版本以发布仓库为准。文档示例即使来自官方也可能使用旧版本，例如Compose示例中的Kotlin2.4.10不作为本次最新值。

已保存[Maven组件与工具链核查记录](research/03-android-dependencies-2026-11-12.json)，包含所选版本、当日最新稳定版、元数据和POM地址，以及Miuix与Tabler来源。AGP9.3.1是用户指定范围下的有意选择，`matches_latest=false`，不伪装成仓库最新9.5.1；Material3从直接组件选择中移除，新增Miuix UI/Preference。记录还包含AAR元数据抽查，仍不代表执行过Gradle依赖解析或应用构建。

- [AGP9.3发布与兼容要求](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[API级别的最低工具版本](https://developer.android.com/build/releases/about-agp)、[内置Kotlin说明](https://developer.android.com/build/migrate-to-built-in-kotlin)、[AGP9.0高版本KGP配置](https://developer.android.com/build/releases/agp-9-0-0-release-notes)。
- [Kotlin插件兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)、[Compose编译器配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)。
- [Gradle9.7/JDK兼容表（固定版本）](https://docs.gradle.org/9.7.0/userguide/compatibility.html)、[Temurin当前GA更新公告](https://adoptium.net/news/2026/10/eclipse-temurin-17021-21013-2505-2701-available)。
- [Miuix官方仓库](https://github.com/compose-miuix-ui/miuix)、[Android接入](https://github.com/compose-miuix-ui/miuix/blob/main/docs/guide/getting-started.md)、[主题配置](https://github.com/compose-miuix-ui/miuix/blob/main/docs/guide/theme.md)、[本地发布配置](../refer/miuix-main/build-logic/src/main/kotlin/BuildConfig.kt)、[本地颜色角色定义](../refer/miuix-main/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/theme/Colors.kt)。
- [Tabler官方v3.51.0发布](https://github.com/tabler/tabler-icons/releases/tag/v3.51.0)、[固定上游源码与许可](https://github.com/tabler/tabler-icons/tree/58f60538fe2fab2e1459a09198f9bd3b033bf90e)、[社区Compose生成项目](https://github.com/jinghu-moon/compose-icons)。
- 本地用法参考：[YingLi版本目录](D:/100_Projects/110_Daily/YingLi-Player/gradle/libs.versions.toml)、[YingLi语义图标入口](D:/100_Projects/110_Daily/YingLi-Player/app/src/main/java/seeyuer/yingli/player/core/designsystem/icon/YingLiIcon.kt)。
- [Google SDK包元数据](https://dl.google.com/android/repository/repository2-3.xml)、[Platform-Tools发布说明](https://developer.android.com/tools/releases/platform-tools)。稳定channel与preview revision需要同时检查，不能只按显示版本号排序。
- [Compose BOM实际POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom)、[Compose版本页面](https://developer.android.com/jetpack/androidx/releases/compose)。
- [Navigation3发布](https://developer.android.com/jetpack/androidx/releases/navigation3)、[Room发布与依赖](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore发布与依赖](https://developer.android.com/jetpack/androidx/releases/datastore)。
- [OkHttp官方项目](https://github.com/lysine-dev/okhttp)、[OkHttp Maven元数据](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml)。当前官方组织已迁移到lysine-dev，Maven group仍为com.squareup.okhttp3。
- [KSP2.3.13发布与已知回归](https://github.com/google/ksp/releases/tag/2.3.13)。
- [ML Kit离线扫码配置](https://developers.google.com/ml-kit/vision/barcode-scanning/android)、[CameraX版本页面](https://developer.android.com/jetpack/androidx/releases/camera)。
- [sherpa-onnx v2.0.1发布](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)、[官方Android AAR说明](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxAar/README.md)、[SenseVoice模型与Android说明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html)。

各Maven组件的最新稳定版另以仓库`<group路径>/<artifact>/maven-metadata.xml`核对；不是依据搜索结果摘要猜测版本。初始化时还需验证整个依赖图、插件与实际设备，不把本文当作已经执行过的工程构建报告。
