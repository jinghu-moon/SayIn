# SayIn 手机端设置重构实施记录

日期：2026-11-16。状态：阶段 1—3 已实现并完成构建/静态检查；阶段 4 的完整设备回归尚未执行。

设计依据：[69 手机端设置设计与实施计划](69-android-settings-design-and-plan.md)。

## 1. 已交付

新增 `android/app/src/main/java/seeyuer/sayin/ui/settings/Settings.kt`，把设置从 `VoiceLinkScreens.kt` 拆出，使用 Miuix `Card`、`ArrowPreference`、`SwitchPreference`、`WindowDropdownPreference`、`SmallTopAppBar`。

一级页固定 5 组 13 项：

| 分组 | 项目 | 状态 |
| --- | --- | --- |
| 语音与输入 | 语音识别 | 可用，二级页 |
|  | 说完自动发送 | 可用，复用 `SpeakSession.changeSendMode` |
|  | 翻译 | 可用，二级页 |
| 快捷入口 | 悬浮球 | 可用，保留悬浮窗/通知授权流程 |
|  | 控制中心快捷入口 | 可用，API 33+ 请求系统磁贴；旧系统行内说明 |
|  | 后台运行帮助 | 可用，二级静态说明与快捷卡入口 |
| 使用体验 | 外观 | 置灰“跟随系统 · 外观选择开发中” |
|  | 应用语言 | 可用，AppCompat `AppLanguages` 唯一来源 |
|  | 使用时保持亮屏 | 可用，沿用 presentation 偏好 |
| 数据与隐私 | 存储管理 | 置灰“开发中” |
|  | 剪贴板增强 | 可用，二级说明与显式有界检查 |
|  | 隐私说明 | 可用，二级三张说明卡 |
| 关于 | 关于 SayIn | 可用，二级页 |

二级/三级页：语音识别、豆包语音配置、热词编辑、翻译与模型、后台运行帮助、剪贴板增强、隐私说明、关于 SayIn、使用帮助。二/三级页隐藏底部导航，返回按父子关系回到原页；根列表和子页滚动位置在本次进程内保留。

## 2. 关键行为

- 语音识别页同时展示离线模型和云端配置；切换引擎调用 `SpeakSession.selectSource`，录音/收尾/翻译/冻结请求期间禁用，不开麦、不发送、不清草稿。
- 豆包配置复用 `CloudSettings` 与 `CloudSettingsCard`，凭据只保存在内存编辑缓冲；新增显示/隐藏切换、必填校验和清除确认；热词从配置卡拆到独立编辑页，保存仍走 `CloudSettings.save` 的既有校验。
- 翻译页复用 `SpeakSession.prepareTranslationModel`、`translationModelReady`、`modelPreparation`；未准备时提供下载，已准备时不再重复下载。
- 悬浮球只展开入口；关闭时停止服务并写回偏好；返回页面时重新核对系统悬浮窗权限。
- 剪贴板页只在用户点击“检查模块状态”时调用一次有界 `RootClipboardClient().list()`，只使用 `captureReady` 与可用性，不展示或保存剪贴板正文，不申请 Root。
- 隐私、后台帮助、使用帮助为静态说明；项目主页通过系统浏览器打开；关于页版本取自 `BuildConfig.VERSION_NAME`。
- 硬件返回键、顶栏返回和底部导航共用同一路由；语言切换仍由系统重建 Activity。

## 3. 明确预留项

以下入口保持 Miuix 禁用态，不弹窗、不导航、不执行任务：

- 外观选择；
- 存储管理；
- 开源许可（许可清单整理中）；
- 诊断信息；
- 剪贴板模块采集控制（无受鉴权启停协议）。

## 4. 验证

已执行：

```powershell
scripts/gradle.ps1 :app:compileDebugKotlin --offline
scripts/gradle.ps1 :app:compileDebugAndroidTestKotlin --offline
scripts/gradle.ps1 :app:assembleDebug --offline
scripts/gradle.ps1 :app:lintDebug --offline
python scripts/check_i18n.py
```

结果：编译、Debug 构建、Lint（0 error）、i18n 检查通过。Debug APK 已通过 Root 覆盖安装到 Redmi K40（`dc39c31d`），手动检查了设置根页、语音识别、豆包配置、翻译、剪贴板页面及二级页返回/底部导航显隐。

未执行：设备仪器化测试与 JVM 测试。本轮沿用用户“未经允许不跑测试”的约束；阶段 4 的完整状态矩阵、320dp/大字号/横屏/深浅色设备截图和旧测试更新仍需在获得测试授权后完成。

## 5. 已知差异与后续

- 豆包配置与热词目前使用页面内保存按钮，尚未迁移为顶栏保存动作；未保存离开确认也未实现。
- 关于页“开源许可”和“诊断信息”保持禁用，符合设计的前置条件。
- 旧仪器化测试仍按迁移前的设置 UI（`input-source-*` 等）编写，需要随新页面更新后才能作为本轮验收证据。
- 设置页尚未迁移到独立 Navigation 组件；进程内路由与滚动已满足当前使用，跨进程恢复和复杂深链不在本轮范围。
