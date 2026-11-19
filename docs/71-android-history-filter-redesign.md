# Android 记录页筛选区重设计

日期：2026-11-18。范围：`MobileHistoryPage` 与记录页顶部栏；不改记录卡片的图片区域，不改历史/回执/Outbox 语义。

设计 demo：[sayin-history-filter-redesign-demo.html](../refer/sayin-history-filter-redesign-demo.html)（`refer/` 为本地参考目录，不纳入 Git）。

## 1. 目标

把搜索框下方的三组 chip 和一行文字操作，收为：

- 搜索框内筛选图标；
- 一行全宽状态分段控件；
- 顶部栏右侧 dots-vertical 下拉菜单；
- 仅在筛选生效时出现的可移除条件 chip；
- 批量模式的上下文工具栏。

图片卡片（缩略图、尺寸信息、来源应用、复制按钮）保持原样。

## 2. 实现

### 顶部栏与下拉菜单

- 记录页顶部栏从 `VoiceLinkScreens` 移入 `MobileHistoryPage`，使用 Miuix `SmallTopAppBar`。
- 右侧显示当前结果数，以及 Miuix `WindowIconDropdownMenu`；触发图标为 `VoiceLinkIcon.More`（Tabler `dots-vertical`）。
- 菜单项：批量管理、导出当前结果、清理历史。菜单项使用 `DropdownItem` 的 icon，不再使用带说明文字的长菜单项，避免弹窗高度被摘要撑满后裁切。

### 搜索框与筛选

- 使用 Miuix `TextField`：`leadingIcon` 为搜索图标，`trailingIcon` 包含清空搜索按钮和漏斗筛选图标。
- 筛选按钮在条件生效时使用品牌色，并在右上角显示日期 + 应用条件数量。
- 筛选使用 Miuix `OverlayBottomSheet`：
  - 日期：`TabRowWithContour`；
  - 应用：Miuix `Card` + 行内 `RadioButton`，显示历史快照中的应用图标；
  - 底部：重置 `TextButton` + “查看 N 条结果”主按钮。
- 生效条件以可移除 chip 展示；清除按钮一次复位日期和应用。状态不在筛选面板中重复。

### 状态与批量

- 状态使用全宽 Miuix `TabRowWithContour`，选中项为品牌蓝，匹配设计稿中的分段控件。
- 批量模式用蓝色上下文条替换状态控件：已选数量、全选/取消全选、删除、完成。
- 卡片使用 Miuix `Checkbox`，选中为品牌蓝；未选中为低对比中性圆。整张卡片可点击切换选择，避免 26dp 复选框成为唯一命中区域。
- 图片区域不调整。

### 图标资源

为复用现有设计系统，新增 Tabler v3.51.0 的 `search` 与 `filter` SVG 源文件及 SHA-256：

```powershell
python scripts/generate_tabler_icons.py
```

生成 `TablerVectors.Search` / `TablerVectors.Filter`，并在 `VoiceLinkIcon` 中增加同名语义项。来源与摘要仍由 `android/design/tabler/provenance.json` 固定。

## 3. 最终交互微调

- 搜索框内只保留右侧筛选/清空图标，移除左侧搜索图标；`TextField` 左侧不再占用图标位。
- 删除记录与删除选中记录使用并排按钮：左侧“取消”中性色，右侧“确认”红色实心；确认文字统一为“确认”。
- 更多菜单的导出项使用 Tabler `download` 图标；清理项图标和文字使用错误色。
- 清理旧历史改为“清理历史”：
  - 标题“清理历史”；
  - 7 天以前 / 30 天以前 / 90 天以前 / 全部历史显示记录使用单选列表，默认 30 天；
  - 底部取消 + 红色“清理”并排按钮。
- 更多菜单继续使用 Miuix `WindowIconDropdownMenu`；导出项使用 Tabler `download` 图标。清理项通过 `DropdownItem(selected = true)` 配合 `DropdownColors` 的 `selectedContentColor`/`selectedContainerColor` 使用错误色，并将 `selectedIndicatorColor` 设为透明，避免多出一个选中勾；记录卡片的单条更多菜单同样继续使用该组件。

## 4. 验证

本轮没有新增测试文件和多余用例；只更新已有记录页 UI 测试以匹配新的交互位置：

```powershell
scripts/gradle.ps1 :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --offline
scripts/gradle.ps1 :app:testDebugUnitTest --offline
python scripts/check_i18n.py
scripts/gradle.ps1 :app:lintDebug :app:assembleRelease --offline
```

设备回归使用隔离包 `seeyuer.sayin.imagetest`：

```powershell
adb -s dc39c31d shell am instrument -w -r -e class seeyuer.sayin.ui.MobileShellInstrumentedTest seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`MobileShellInstrumentedTest` 24 项通过；历史搜索/复制/本地隐藏、状态语义、日期与应用筛选、批量确认、导出面板和设置入口均保持。`:app:lintDebug` 0 errors（172 warnings，未屏蔽）；`:app:assembleRelease` 通过；JVM 单元测试通过。Debug 包已覆盖安装到日常包并保留数据。

当前记录页真机预览：

- `refer/history-redesign-device.png`
- `refer/history-redesign-filter.png`
- `refer/history-redesign-batch-selected.png`
- `refer/history-redesign-active-device.png`
- `refer/history-redesign-menu-device.png`
- `refer/history-redesign-delete-device.png`
- `refer/history-redesign-cleanup-device.png`

## 5. 明确边界

- 仅修改记录页展示与筛选交互；历史隐藏、清理、导出和 Outbox/回执逻辑未改。
- 记录卡片图片区域未改。
- 未新增测试类型；原有批量/导出/语音等测试继续使用。
- 筛选图标、顶部菜单和筛选面板均使用现有 Miuix 组件；未引入新的 UI 框架。
