# 64 · 剪贴板与常用语面板固定底部操作区

日期：2026-11-16。基线：`ac66af2`。用户确认采集和列表功能正常，但模块剪贴板页的“清除全部”底部被系统导航区遮住，要求常用语页采用相同处理。

## 根因与实现

面板使用边到边的 OverlayBottomSheet，没有为内容统一预留导航栏 inset。原常用语/普通剪贴板页靠固定 32dp 底部留白兜底；模块页另写按钮，只留 12dp，且采用组件默认内部留白。新增模块状态行不是根因：列表本来已有 weight，底部操作的安全区和样式却由不同实现分别负责，导航区侵入时无法保证完整可见。

- `AssistPanel` 在自身受限高度内统一应用底部 navigationBars inset，适用于普通剪贴板、模块记录、常用语，以及编辑/全文预览。沿用 OverlayBottomSheet 的 IME 处理，不在按钮重复叠加导航 inset。
- 新增共用 `AssistClearAction`，统一 48dp 最小触控高度、零组件内部留白、12dp 上下间距及颜色。清除按钮位于非滚动区域，列表只使用剩余空间。
- 常用语删除撤销行独立于列表；模块暂停说明/加载进度、全局操作提示使用弹性内容区，提示只压缩列表，不能向下挤出底部按钮。模块自身通知位于清除按钮上方。
- 保留“模块剪贴板记录/刷新”入口、清空确认、菜单复制/粘贴及点条目插入行为；不改变模块协议、历史数据、草稿票据、发送或桌面端。

## 修改前后验证

真机：Redmi K40 / API 33，独立 `seeyuer.sayin.imagetest`。新增 `AssistPanelLayoutInstrumentedTest` 挂载生产 Scaffold 和 AssistPanel，使用生成的 50 条模块/常用语记录、内存库及假识别器；无 Root 历史访问、网络、录音、发送或截图。以语义坐标和滚动检查完整按钮是否位于系统导航区之上，至少保留 12dp 间距。

| 检查 | 修复前 | 最终修复版 |
| --- | --- | --- |
| 模块页普通字号、50 条记录滚动 | 导航安全区断言失败 | 完整显示，滚动前后按钮底边/高度不变 |
| 模块暂停提示、两倍字号，与常用语切页 | 模块页导航安全区断言失败 | 两页按钮高度、底边一致；常用语滚动不移动按钮 |
| 无模块的普通剪贴板与常用语、两倍字号 | 未另记前测 | 均完整位于安全区；底边一致 |
| 320dp 等效窄屏、1.5 倍字号、模块暂停提示 | 未另记前测 | 两页按钮完整可见，底边一致 |

320dp 使用该 K40 的 360dp 宽度及测试 CompositionLocal 内 1.125 倍 density 构造，不更改用户系统显示密度/字体设置。布局证据不代表其它 OEM、三键导航、真实键盘横屏或所有字体组合均已验收。

初次新增测试只挂载 Box 而没有生产 Scaffold，OverlayBottomSheet 未出现，测试超时；补齐实际宿主后，旧版明确触发安全区断言失败，新版相同检查通过。失败没有被当成通过或弱化断言。

构建源为独立干净目录的基线加本次改动；共享工作区存在其它任务的 UI、常用语重排及桌面修改，全部保留，不整文件提交它们，不将其混入这次日常 APK。

## 执行命令

仓库的 `scripts/gradle.ps1` 固定使用项目 JDK；以下 Android 命令在独立源码目录执行：

```powershell
pwsh -NoProfile -File scripts/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
pwsh -NoProfile -File scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest
python scripts/check_i18n.py
```

结果：87 项 JVM、Debug 构建、Lint、隔离产品/测试 APK 构建和 i18n 通过，未屏蔽既有 warning。

新增四项真机测试逐项运行，例如：

```text
adb -s dc39c31d shell am instrument -w -e class seeyuer.sayin.ui.AssistPanelLayoutInstrumentedTest#moduleFooterStaysAboveNavigationWhileListScrolls seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner
```

其余方法为 `moduleAndPhraseFootersSharePositionAtLargeFont`、`ordinaryAndPhraseFootersStayInsideSafeArea`、`narrowPanelKeepsBothFootersFullyVisible`。它们按 RootHelperDeviceTest 分组显式运行，不把“测试包编译成功”当作真机通过。隔离启动时一次性恢复匹配 ActivityScenario 的前台 Activity，不修改 MIUI 权限或截屏。

原有六项真机回归也通过：`clipboardClearConfirmCancelAndConfirmStaysInPanel`、`copyKeepsPanelAndDoesNotTouchDraft`、`pasteKeepsPanelAndEachPasteIsOneUndoStep`、`phraseAddDuplicateEditDeleteUndoAndClearAll`、`narrowAndLargeFontLayoutKeepsControlsReachable`、`quickCardUsesTheSamePanel`。它们已有自己的前台规则；初次外部启动脚本又拉起 Activity，导致生命周期冲突并失败，不计为通过。仅去掉重复的外部启动、原测试与全部断言不变后，六项全过；不运行原有截图用例。

这六项涉及生成剪贴板内容，执行前临时 `stop` 日常采集，保留 enabled 偏好和历史；完成后通过正式 service 恢复 `running=true / enabled=true / capture_ready=true`。未读取日常模块记录，未触碰日常配对或发送。

交付：独立源码最终 debug APK 已通过 `pm install -r` 覆盖安装到 K40，无卸载或清数据；手机安装 APK 的 SHA-256 为 `063ef93243082fa5256c97de5412adfcd136477285b5704304ee43a4e1a3f136`，与构建产物一致。隔离 App 已停止，日常 SayIn 已重新打开。自动断言成绩见 [evidence.json](ui-verification/64-assist-footer/evidence.json)；实际界面观感由用户检查，本轮没有截图或替用户作视觉验收。

## 后续简化：移除重复刷新入口

用户确认面板打开及从常用语切换到剪贴板时已有自动加载，因此移除常驻的「模块剪贴板记录」说明行与「刷新」按钮，正常情况下直接展示列表。首次加载、切页重新加载、回前台加载及复制后的更新继续沿用原逻辑，不新增定时轮询。错误说明旁才显示「重试」，清空成功提示不显示重试；底部固定操作区保持不变。删除已无引用的三项双语文案。

用户明确要求这次不用测试，因此本次不运行 JVM、仪器化测试、Lint 或 i18n 检查；上文的测试成绩属于前一次布局修复，不能作为本次改动的验证成绩。

本次仅执行独立源码目录的 `scripts/gradle.ps1 :app:assembleDebug`，构建成功；已通过 `pm install -r` 覆盖安装到 Redmi K40，保留应用数据，无需重启。未截图，实际 UI 由用户查看。

## 后续修正：条目操作不显示列表加载

用户发现更多菜单的各项操作都会显示加载动画。根因是读取条目全文、清空、列表读取共用 `busy` 互斥标记，而已加载列表上方的进度条也使用了该标记；读取全文并不等于重新加载列表。移除已加载列表上方的进度条，仅在尚无列表的初次读取阶段显示原有加载占位。保留 `busy` 的防重复操作语义，以及复制后的后台列表更新，不引入新状态；菜单操作和后台更新不再插入进度条挤动列表。

延续用户本轮 UI 微调不运行测试的要求，不运行测试或截图。独立源码目录的 `scripts/gradle.ps1 :app:assembleDebug` 构建成功；通过 `pm install -r` 覆盖安装到 Redmi K40，保留应用数据，无需重启。实际菜单交互由用户查看。
