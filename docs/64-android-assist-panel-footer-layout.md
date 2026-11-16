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

## 根因复核：内部页面切换销毁了列表状态

用户反馈上次修改后仍有加载动画。上一次只移除了 `busy` 对应的进度条，未验证完整菜单路径，不能算完成根因修复。本次在生产 `AssistPanel` 宿主上使用生成记录及计数的假模块做设备复现，不截图、不连接日常模块 socket。

基线为 `278e3a2`。粘贴后读取次数维持 1 次；但「查看全文 → 返回」「保存常用语 → 放弃编辑 → 返回」均由 1 次变成 2 次，原断言明确失败。原因是 `AssistMode.List` 分支移除 `RootClipboardTab` 后，原本由它 `remember` 的快照、解析完成标记和默认列表滚动状态一起丢失。返回重新组成组件，必然走首次读取，原先去掉忙碌进度条无法解决它。

将这三项状态收归外层面板的 `RootClipboardState`，内部全文/编辑页面切换复用同一个实例。列表操作仍由可见列表组件发起、互斥并按代次取消；读取全文依然按 ID 请求，不增加正文缓存、常驻 socket 或后台采集。返回已有解析结果时不重复 LIST，滚动位置保留；复制后的原有后台更新不清空当前列表。切换标签、关闭面板和 Activity 暂停显式清理；即使后台发生在全文/编辑页，父级生命周期也会清理快照，恢复后重新读取。

本次为解决复现失败补充针对性设备验证，覆盖菜单与缓存生命周期，不将前两次免测构建当成本次验收证据。新增 `RootClipboardPanelStateInstrumentedTest`（`RootHelperDeviceTest`，独立 imageTest 包）检查粘贴、复制期间保留列表、全文返回与滚动位置、编辑返回、标签切换、面板关闭重开、全文页进后台恢复。复制用例只写生成文字并恢复原剪贴板；期间临时停止日常采集，保留 enabled 偏好，结束恢复，不读取或清空日常模块历史。设备操作串行并持有原有命名互斥锁。实际完成成绩与最终安装信息见后续交付记录。

最终结果：上述 7 项新增设备用例和既有 `RootClipboardUiInstrumentedTest` 的 3 项加载/无模块回退/迟到响应回归全部通过。最终源码执行 `scripts/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`（87 项 JVM，无失败/跳过）、独立 imageTest 产品/测试 APK 构建及 `python scripts/check_i18n.py` 均通过，保留既有弃用警告。设备测试逐方法使用 `am instrument -w -e class seeyuer.sayin.ui.RootClipboardPanelStateInstrumentedTest#方法名 seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner`；基线失败与最终逐项结果见 [menu-state-evidence.json](ui-verification/64-assist-footer/menu-state-evidence.json)。

最后补充了父级销毁时显式清理，并对最终构建重新执行上述 10 项设备检查。日常模块恢复为 `running=true / enabled=true / capture_ready=true`；隔离 App 已停止。最终 debug APK 已覆盖安装到 K40并重新打开，安装包摘要与构建文件一致，配对和数据保留，无需重启。实际界面观感由用户查看；此证据针对生产面板与生成模块数据，不代表其它 ROM、真实模块内容或所有输入法组合均已验收。
