# VoiceLink：Android工程基础实施记录

日期：2026-11-12。

后续进展：[05国际化设计与实施](05-voicelink-internationalization-design.md)已接入双语资源和应用语言设置。本篇及其APK校验和是首次工程基础的历史快照；最新构建和资源核查以05记录为准。

本次开始首阶段实现，先完成工具链与最小原生输入页面的工程基础。**首阶段的WSS、持久Outbox、安全配对和电脑上屏链路尚未完成**，不将输入页面或APK构建当成端到端功能实现。

## 1. 已落地的工程基础

- 工程目录`android/`，单`:app`模块；`namespace`、`applicationId`及Kotlin根包均为`seeyuer.voice.link`。
- Gradle9.7.0 Wrapper与官方发行包SHA-256，Version Catalog锁定AGP9.3.1、Kotlin/Compose编译器2.4.21。
- 保留AGP内置Kotlin，用显式KGP类路径对齐2.4.21；没有应用`org.jetbrains.kotlin.android`或关闭新DSL。
- 项目构建入口`scripts/gradle.ps1`使用Temurin25.0.5+8；daemon条件限制为Temurin25，未修改全局Java配置。
- 已通过SDK Manager安装`platforms;android-37.0`和`build-tools;37.0.0`。平台包名确为`android-37.0`，不是`android-37`。
- Compose + Miuix0.9.4 UI/Preference发布包；页面具备可由现有输入法编辑的输入区、未连接状态及禁用的发送按钮。
- 编辑值使用可保存状态和`TextFieldValue`，保留输入法组合信息；这不是Room持久草稿或Outbox。
- Yue neutral原色阶与全部Miuix主题颜色角色的明暗映射；主题色固定`#1f75db`，不启用Monet。
- 6个Tabler3.51.0图标的语义入口、原始SVG、MIT许可、来源commit、SHA-256与可重复生成脚本。
- minSdk26、compileSdk37.0、targetSdk37；Java/Kotlin字节码目标17；关闭备份及明文流量，提供自适应启动图标。

应用显示名暂采用前文推荐的“声达”；这是可修改的展示名称，已确认的包名保持为`seeyuer.voice.link`。

## 2. 已执行的验证

从仓库根目录执行：

```powershell
.\scripts\gradle.ps1 --version
.\scripts\gradle.ps1 :app:assembleDebug --console plain
python scripts/generate_tabler_icons.py
```

首个Debug构建成功：`BUILD SUCCESSFUL in 3m 39s`。实际执行了Kotlin编译、AAR元数据检查、重复类检查、资源处理、Dex和APK打包。APK的applicationId为`seeyuer.voice.link`。Wrapper输出Launcher JVM为Eclipse Adoptium25.0.5+8-LTS，daemon也使用对应安装目录。

Tabler生成器再次执行后产物SHA-256一致；它会校验SVG源哈希和支持的路径样式，遇到未支持的元素会失败。主题色上的白字对比度约4.54:1；整体界面与明暗/大字体的真机视觉验收仍待执行。

完成配置修正后的干净构建及依赖核查：

```powershell
.\scripts\gradle.ps1 :app:clean :app:assembleDebug :app:lintDebug :app:assembleRelease buildEnvironment :app:dependencyInsight --dependency foundation --configuration debugRuntimeClasspath --console plain
.\scripts\gradle.ps1 :app:assembleDebug :app:lintDebug --console plain
```

干净构建成功（25s），随后增量构建与Lint成功（8s）。Release执行了R8和资源压缩，输出`app-release-unsigned.apk`；尚未配置发布签名。Lint为**0错误、1警告**：`AndroidGradlePluginVersion`提示存在更新AGP。这是用户指定Kotlin完整支持范围后有意保留的9.3.1，没有屏蔽检查或添加baseline。

`buildEnvironment`确认AGP的KGP2.2.10依赖实际提升为**2.4.21**；Foundation依赖核查显示AndroidX **1.12.1**和Miuix声明的JetBrains **1.12.0**坐标。AAR检查、重复类检查、编译及R8已通过，尚不据此宣称所有组件的设备行为兼容。Miuix的alpha窗口尺寸传递依赖仍按03文档记录。

初次Lint发现系统栏属性超出minSdk范围及本机SDK路径未转义，均已修正；启动图标目录调整后资源增量链接曾失败，完整清理构建及后续增量构建均已通过。未跳过错误或修改验收条件。

已保存[机器可读核查结果和APK校验和](research/04-android-foundation-verification-2026-11-12.json)。原始构建输出位于本机忽略目录`android/build/initial-verification.log`与`android/build/incremental-verification.log`，Lint报告位于`android/app/build/reports/`。

## 3. 真机验证限制

检测到已连接的Android13/API33设备。执行Debug APK安装时，设备返回 **`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`**。安装失败，因此未启动应用，也没有宣称页面、输入法编辑、旋转恢复或真机性能通过。未修改手机的USB安装、安全或权限设置。

本机Debug APK可在`android/app/build/outputs/apk/debug/app-debug.apk`找到。手机允许此次安装后，再进行启动、输入法、横竖屏/重建、明暗模式与大字体检查。

## 4. 下一步实现范围

基础构建通过后，推进普通输入链路：v2消息类型与校验、应用级连接状态、Room持久Outbox、WSS证书信任、ACK/查询/不确定状态恢复，以及对应测试。没有电脑核心时使用受控协议服务验证，不把服务替身称为真实电脑上屏。

扫码配对、发现、通知/磁贴/悬浮入口按[03选型文档](03-android-technology-selection.md)的顺序推进；离线ASR和Root方案继续留在后续阶段。
