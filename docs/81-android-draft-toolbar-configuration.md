# Android 草稿工具栏配置与短行对齐

日期：2026-11-21。范围：首页草稿卡工具栏的显示/隐藏、顺序调整，以及显示 3 个及以下按钮时的左右位置选择。

## 1. 入口与界面

设置根页“使用体验”分组新增“草稿工具栏”，位于“外观”之前；摘要为“完整页 N 个 · 快捷卡 M 个”，随两份配置实时变化。

子页全部复用 Miuix 组件：

- 页头：`SmallTopAppBar` + 返回，标题“草稿工具栏”。
- 顶部“完整页 / 快捷卡”分段：两份配置互相独立，切换后编辑对应的顺序、可见性与对齐。
- 说明文案：拖动排序、关闭开关只隐藏按钮、两份配置分开。
- 工具列表：独立圆角行（`shadow` + `clip` + `surface`），左侧 48dp 拖动把手（Tabler `grip-vertical`），中间工具图标与名称，右侧 Miuix `Switch`。
- 位置选择：可见按钮 ≤3 个时出现一张圆角卡片，使用 `TabRowWithContour` 选择“靠左 / 靠右”；≥4 个时该卡片不存在（此时工具行自动铺满卡片）。
- 底部“恢复默认工具栏”：同时恢复默认顺序、全部显示与靠左。

拖动沿用短语排序的交互约定：按下把手立即开始拖动（不要求长按），松手才落盘，取消不写盘；拖动行有抬升阴影；键盘上下键与无障碍自定义操作（上移/下移）可移动，移动后焦点回到同一把手，并把“第 N 位，共 M 位”写入 `stateDescription`。

## 2. 数据与语义

```kotlin
internal enum class DraftTool(val id: String, @StringRes val labelRes: Int) {
    Assist, Image, Translate, Polish, Copy, Clear
}
internal data class DraftToolEntry(val tool: DraftTool, val visible: Boolean)
internal enum class DraftToolSide(val id: String) { Start, End }
```

持久化仍在现有 `presentation` SharedPreferences，仅两个键：

- `draft_tools`：完整有序列表，格式 `id=1,id=0,...`，包含隐藏项，因此重新显示时回到原位置。
- `draft_tool_side`：`start` / `end`，缺省 `start`。

规范化规则（纯函数，见 `DraftTools.kt`）：

- 未知 id 丢弃；重复 id 保留第一次出现的槽位；非法 `flag` 丢弃该 token。
- 存储值中缺失的工具按注册表顺序追加并默认可见：新增按钮不会被旧值静默当成“用户已隐藏”。
- 全部 token 非法或缺失时回到默认（全部可见、注册表顺序）；隐藏项保留顺序槽位，便于恢复。
- 这是纯展示偏好，坏值直接回默认，不需要草稿/密钥那套“坏文件不静默覆盖”。

快捷卡规则：拥有独立的顺序与显示开关。首次加载时从完整页派生——保留完整页前 5 个可见工具，其余隐藏，顺序不变；因此升级后的快捷卡外观与旧版一致。之后新增的工具在快捷卡默认隐藏，避免升级把浮动卡撑大；对齐也独立保存。

主工具栏行规则：完整页显示所有开启的按钮，超出一行时按可用宽度自动换成均衡的多行（例如 10 个显示为 5+5、7 个显示为 4+3），不再有“更多”中间层。每行都铺满卡片宽度均匀分布；不足一行的末行（如 4+3 的 3 个）居中均匀分布，不靠边堆叠。快捷卡使用自己的配置，同样按宽度换行。

短行规则：可见数 ≤3 时使用用户选择的 `Start`/`End`（LTR 即左/右，镜像布局随界面方向）；≥4 时继续 `SpaceBetween` 铺满；0 个时整行消失。`TabRowWithContour` 只在可选项生效时出现，避免无效设置。

## 3. 渲染改造（根因）

旧实现把 6 个按钮硬编码在 `FlowRow`，成员判断分散在主行、窄屏兜底、内联兜底和 `if (!quick)` 四处，工具栏宽度固定为 `48.dp * 5/6`。本次改为单一列表驱动：

- `SpeakPage` 收集 `DraftTools.entries` 与 `DraftTools.side`，由 `visibleDraftTools()` 得出全部可见工具；`visibleTools.forEach { DraftIcon(...) }` 直接铺满工具栏，动作、启用条件、动态文案集中为 `toolLabel/toolEnabled/toolProgress/toolSelected/runTool`。
- 删除四处分散判断：窄屏/内联兜底的 Assist 入口只在 Assist 可见时出现；quick 不再单独判断复制。
- 卡片外边距固定 20dp、文本内缩 16/12dp；每行图标数由实际可用宽度推导（360dp 宽约 6 个），超出后换成均衡多行。
- 隐藏与暂时不可用分离：用户隐藏=不渲染；配对、存储、草稿为空等运行条件只影响启用态，仍显示禁用按钮以解释“为什么现在点不了”。
- `toolsShown` 语义收窄为“主工具栏渲染了 Assist”，隐藏 Assist 后不再向不存在的入口请求焦点。
- `DraftTools` 与 `Appearance` 同构：`MutableStateFlow` + 进程级单例，`SayInApplication.onCreate` 初始化，设置页写入后主页面与快捷卡立即重组。
- 三个新 sheet 放在 `SpeakPage` 顶层（与 `PolishSheet` 一致），依赖 miuix 的根 `Scaffold` 宿主渲染；直接渲染 `SpeakPage` 的测试必须包一层 `Scaffold`，否则 `OverlayBottomSheet` 的内容不会进入语义树（本轮踩到，已写入测试约定）。

## 4. 边界

- 全部隐藏：工具栏整行消失，卡片按无工具栏边距铺开；剪贴板/常用语入口也随之消失，只能回设置恢复。这是用户显式选择，不做“至少保留一个”的额外约束。
- 快捷卡默认只显示完整页的前 5 个可见工具；用户可在“快捷卡”分段自行增删与排序。完整页与快捷卡都不再有“更多”。
- 设置页不受录音/发送锁定影响：纯展示偏好，不触碰草稿、Outbox、配对或接收闸门。
- RTL 使用逻辑 start/end；中文/英文下“靠左/靠右”与视觉一致。
- 未增加 Adapter、迁移层或按页面分别配置；新增工具只需改注册表与动作分支，设置页自动出现。

### 关键行为对比

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 工具栏成员 | 固定 6 个，快捷卡固定去掉复制 | 完整页/快捷卡两份独立配置；10 个工具可开关与排序，按宽度换行 |
| 短行位置 | 始终靠左 4dp 间距 | ≤3 个可选靠左/靠右 |
| 卡片边距 | 由固定 5/6 个图标宽度推导 | 由实际主行槽位推导 |
| 全部隐藏 | 不可能 | 整行消失，设置中恢复 |
| 设置入口 | 无 | 设置 → 使用体验 → 草稿工具栏 |
| 发送模式 | 只能进设置 | 工具栏发送模式按钮打开面板 |
| 识别引擎 | 只能进设置 | 工具栏识别引擎按钮打开面板 |
| 存为常用语 | 只能先复制再进面板 | 一键入库并提示重复/已满 |
| 分享 | 无 | 系统分享面板，不触发电脑发送 |

## 5. 扩展动作

四个按钮共用同一注册表与启用判定，默认可见（旧安装按“新工具追加在末尾”的规则出现在末位）：

- 发送模式：打开 `SendModeSheet`，复用 `changeSendMode/changeAutoSendSeconds`；与发送策略页相同的 `canChange` 条件下可改，录音、冻结、翻译中禁用。若当前应用有单独规则，面板明确提示规则优先，避免“改了不生效”。
- 识别引擎：打开 `RecognitionEngineSheet`，复用 `selectSource`；云端未配置时给出说明与“配置云端”按钮。选择引擎同时切换当前输入来源，与识别设置页一致。
- 存为常用语：直接 `phrases.add(draft)`，复用短语校验与去重；结果以“已存为常用语 / 已存在 / 已满 / 过长 / 不可用”气泡提示，不打开编辑器、不改草稿。
- 分享：系统 `ACTION_SEND` + chooser，分享当前显示的草稿文本（译文显示时分享译文）；不触碰 Outbox、接收闸门或电脑端。

边界：分享无接收应用时只提示；分享、存为常用语在翻译中禁用；发送模式、引擎在录音/待核对/翻译中禁用；四个动作都不改动草稿内容本身。

## 6. 验证

```powershell
python scripts/check_i18n.py
scripts/gradle.ps1 :app:testDebugUnitTest :app:lintDebug
scripts/gradle.ps1 :app:assembleDebug
adb -s dc39c31d install -r android/app/build/outputs/apk/debug/app-debug.apk
```

结果：

- `check_i18n.py`：通过（en、zh-Hans 键完整、占位符一致）。
- JVM：192 项通过，0 失败；其中 `DraftToolsTest` 15 项覆盖规范化、未知/重复/新增 id、全隐藏、移动、快捷卡派生/升级、side 解析。
- `:app:lintDebug`：0 errors（229 warnings + 7 hints，均为既有或与本改动无关的 `UseKtx`/`ModifierParameter` 风格提示）。
- 隔离包设备回归（`seeyuer.sayin.imagetest`，Root 安装绕过 MIUI 的 adb 安装限制）：
  - “均衡换行”版本的完整套件为 155 项文字/UI + 4 项图片且全部通过（证据见文末）；本轮“每行均匀分布 + 快捷卡独立配置”改动后，按用户要求未重跑整套。
  - 中间一次整套运行出现 1 个与 UI 无关的 `TransportInstrumentedTest` uncertain 状态等待偶发失败，非布局改动引起；后续未复现。
  - 本轮定向运行 2 项设备用例通过：工具栏设置页（含完整页/快捷卡分段与快捷卡重置）、`SpeakInformationInstrumentedTest` 的完整页/快捷卡尺寸矩阵（快捷卡渲染派生的前 5 个）。
  - `verify_android_transport.ps1` 的常规套件期望数量保持 155；本轮未新增测试用例，仅扩展既有设置用例。
- 日常包 `seeyuer.sayin` 已覆盖安装并保留配对、草稿与历史；真机确认：完整页按容器宽度换行显示全部已启用工具，设置页“完整页 / 快捷卡”分段可切换并编辑各自开关，发送模式/识别引擎面板、存为常用语提示、系统分享面板均正常；验证后清空了测试草稿并删除测试常用语，保留用户原有工具栏配置。
- 截图核对：设置页（含分段、位置控件与开关）、短行靠左/靠右、拖动后顺序、日常包换行工具栏与三个动作面板。

证据目录：`desktop/target/image-android-fixture-4e17fd7a-961a-49a9-9240-350a8114957f`（155 项）与 `desktop/target/image-android-fixture-c9bac717-845f-4f07-9c1f-67078879ab4a`（4 项）；截图位于隔离包的 `files/ui-verification/draft-toolbar-*.png`。

## 7. 未覆盖与后续

- 拖动主要依赖 Compose 测试的触摸注入与真机人工核对；换行已在 10 个工具的尺寸矩阵中覆盖，但超大字号叠加多行的组合未单独压测。
- 快捷卡首次派生前 5 个是默认值，不是硬上限；用户可在“快捷卡”分段增加工具。
- 未做工具栏配置的跨设备同步。
- 快捷卡独立配置后的整套 155 项回归未重跑（仅 JVM、lint 与 2 项定向设备用例）；下次完整回归时需补齐。
- RTL 仅按逻辑 start/end 实现，未做阿拉伯语真机回归。
