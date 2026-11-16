# 常用语拖拽排序动画重写

2026-11-15。说话页「剪贴板 / 常用语」面板采用用户指定的 `refer/Reorderable-main` 重写拖拽，完整页与快捷卡共用。

## 根因与目标实现

旧实现有三套互相干扰的位移来源：手工累计手势位移并减去行高、LazyColumn 的重新布局与让位动画、自动滚动后的手工补偿。行高来自经过变换的 `boundsInRoot`，布局和动画中的位置不能作为稳定的排序槽位。旧自动滚动忽略 `scrollBy` 实际消费量，到达边界仍把请求滚动量加到拖动位移。松手使用独立的 140ms tween 和全局残余偏移状态，不能协调连续手势。

旧 Row 的 `shadow / clip / background` 还在 `graphicsLayer(translationY)` 外侧，卡片外观与内部内容没有一起变换。

新实现直接使用参考项目的 `ReorderableItem`、`rememberReorderableLazyListState` 和 `draggableHandle`：

- 对整项（包括卡片、阴影、正文与菜单）进行图层变换；手指位移直接驱动拖动项。
- 通过稳定的 phrase UUID 和 LazyList 实际布局协调换位，邻项由 `animateItem` 平滑让位。
- 松手保持残余位移，以参考项目的 `Spring.StiffnessMediumLow` 弹簧回落；抬升阴影同样平滑过渡。
- 参考项目负责持续边缘滚动及滚动中的槽位调整，删除旧行高估算、root 坐标表、手工补偿循环和 settling 状态。
- 只有手柄可拖动，正文仍是插入入口；保留键盘上下排序、无障碍动作与 48dp 手柄。

## 参考源码与本地修正

保留 Reorderable 3.1.0 的六个必要源码文件，位于 `android/app/src/main/java/sh/calvin/reorderable/`，没有引入参考工程的构建工具链或其它布局模块。原始及本地 SHA-256 见 [来源清单](../android/reorderable-sources.json)，Apache-2.0 许可证和版权说明随 APK 放在 `assets/licenses/reorderable/`。

真机持续滚动测试暴露参考实现的调度错误：`Scroller.start()` 先启动协程、再投递指令。在可立即执行的调度器下，协程读到空 channel，立即退出；已经完成的 Job 非 null，后续 start 也不会重启。修正为先入队，再在 Job 不活跃时启动。另为两个 orientation `derivedStateOf` 加 `remember`，解决当前 Compose Lint 的 `UnrememberedMutableState`；移除未使用的废弃 column/row 转发接口。

两个确定性 JVM 测试分别覆盖立即启动及边界完成后的反向重启。实际将 Scroller 换回原始文件运行，两项均失败；恢复修正后两项通过，见 [复现日志](ui-verification/58-phrase-reorder/upstream-scroller-reproduction.log)。

## 排序提交与取消

拖动过程中只更新本地预览，松手捕获当次 ID 顺序和开始拖动时的库 revision，一次提交到原有串行 PhraseLibrary。存储期间禁止新排序，保存失败恢复持久列表；没有改变顺序的手势不写盘。

Reorderable 的 stopped 回调同时涵盖松手、取消和移除。用其公开的手势检测接口区分 pointer-up 与 cancel，取消/返回只恢复预览，不持久化。另一入口修改库 revision 时，撤销当前手势并采用新库，不能把旧顺序套到新内容。排序不修改草稿，也不触发发送。

## 实际验证

修改前 Debug 构建、JVM 基线与真机既有的指针/键盘排序持久化测试通过。修改后结果和 SHA-256 见 [验证 JSON](ui-verification/58-phrase-reorder/verification.json)。

| 验证 | 结果 |
| --- | --- |
| JVM | app 87 项 + translation 1 项，共 88 项，零失败/跳过；含新增 2 项 Scroller 测试 |
| Redmi K40 / API 33 隔离包 | 99 项常规文字/UI + 独立电脑身份的 4 项图片，共 103 项通过 |
| 面板测试 | 31 项包含在常规套件；新增跟手/让位、取消/返回、边缘滚动、失败回滚、外部修改、首尾边界 6 项 |
| 最终动画补验 | 两项通过；跟手跨槽位无跳变、邻项经过中间位置、松手经过弹簧回落中间位置、静止手指持续滚动到末尾且无漂移 |
| 通信 fixture | 文字恰好 2 次 fake 输入、图片 1 次，查询/恢复无重放 |
| Android | Debug、imageTest 及测试 APK 构建、`:app:lintDebug`、`check_i18n.py` 通过 |
| Windows 回归 | 138 项 Rust 测试、Clippy `-D warnings`、包限定格式检查、工作区构建、依赖摘要和双语测试通过 |

完整套件通过后，对 orientation 的 remember 和废弃接口清理做了最终构建/Lint/JVM 与两个动画用例补验。没有使用新实现的截图冒充旧实现截图。

执行命令（仓库根；Rust 命令在 desktop 下）：

```powershell
./scripts/gradle.ps1 :app:testDebugUnitTest :translation:testDebugUnitTest :app:assembleDebug :app:lintDebug
./scripts/gradle.ps1 '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest
./scripts/verify_android_transport.ps1 -DeviceSerial dc39c31d -FullSuite -SkipBuild -SkipInstall
# 最终补验使用相同设备互斥：
adb -s dc39c31d shell am instrument -w -r -e class 'seeyuer.sayin.ui.AssistPanelInstrumentedTest#stationaryEdgeDragScrollsAndEndOfListDoesNotDrift,seeyuer.sayin.ui.AssistPanelInstrumentedTest#draggedPhraseTracksFingerAcrossAnimatedSlotChanges' seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
cargo build --workspace --locked
```

`-SkipInstall` 仅复用本轮已安装的隔离 imageTest APK；最终补验重新安装最终构建的隔离包。没有覆盖日常 `seeyuer.sayin` 包。

## 关键行为对比与边界

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 拖动 | 行高累计补偿，图层只包含内部内容 | 整项跟手，跨槽位保持屏幕位置连续 |
| 让位/落位 | 让位与手工位移分离，落位独立 tween | 稳定 key 的让位动画和残余位移弹簧落位 |
| 边缘滚动 | 不使用真实消费量，边界仍补偿位移 | 实际布局协调滚动；20 条列表静止边缘拖动到末尾不漂移 |
| 保存/取消 | 原有排序与键盘持久化基线通过 | 继续通过；新增每次拖动一次写入、取消零写入、失败恢复与 revision 冲突验证 |
| 草稿与发送 | 原有插入/发送语义 | 完整回归通过，排序本身不编辑草稿或发送 |

截图：[跨槽位](ui-verification/58-phrase-reorder/58-phrase-crossing.png)、[静止边缘拖到末尾](ui-verification/58-phrase-reorder/58-phrase-edge-scroll.png)、[落位](ui-verification/58-phrase-reorder/58-phrase-settled.png)。

本轮验证真实设备上的 Compose 界面与隔离 fake 通信；未做帧耗时/丢帧率基准、长时间人工手感验收、完整 TalkBack 走查、真实麦克风或真实目标应用输入验收，不用测试耗时声称动画性能指标达标。

2026-11-16 主线整合补充：保存提示会改变列表在窗口中的位置；松手动画的回归现使用列表内坐标，避免把提示引起的整体布局位移误算成动画方向错误。仍保留中间位置、向新槽位靠近及一次持久化的原断言。失败定位与组合回归见 [66](66-main-branch-consolidation.md)。
