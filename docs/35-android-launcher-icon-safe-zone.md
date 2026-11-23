> 2026-11-23 后续：Launcher 图标已改用 F-yan（暖白底 #F5F4F0 深色言字标，自适应背景 #F5F4F0）。按本文同一安全区规则，F-yan 的前景/单色层使用 0.5714 缩放（图形高约 48dp，中心对齐 54,54），修改源位于 `assets/sayin-icons/F-yan/android/res`。首次直接使用资源包自带 0.75 缩放时图形过大，真机桌面已复核并改为当前比例；B-field 的调整过程保留为历史记录。
# 35 · Android 启动器图标安全区与留白

日期：2026-11-14。

## 问题与目标

用户提供的 Redmi K40 桌面截图中，SayIn 的 B-field 图形相对底板偏大，与相邻应用相比内部留白不足。资源虽然已经使用 Adaptive Icons 分层，但“存在分层”不能证明图形比例合适。此次仅调整 Android Launcher 内部图形的等比尺寸，保留图案、颜色、宽高比和背景底板。

## 官方依据与实现

- [Adaptive icons](https://developer.android.com/guide/practices/ui_guidelines/icon_design_adaptive)
- [Compose 文档中的 Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- [AdaptiveIconDrawable](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable)
- [Image Asset Studio](https://developer.android.com/studio/write/image-asset-studio)
- [Google Play 图标规范](https://developer.android.com/distribute/google-play/resources/icon-design-specifications)：独立于 Launcher，此次不改商店资产。

前景和背景均使用 108×108dp 图层；内层 72×72dp 是可见窗口的参考范围，外侧各 18dp 留给系统效果。关键图形应在中心**直径 66dp 的安全圆**内，不能只检查 66×66dp 方框。系统负责最终遮罩，Adaptive 图层不预制外圆角或外轮廓阴影，背景必须不透明。

48dp 是此次为 B-field 选择的图形宽度，不是要求把横向品牌图案拉成 48×48dp。保留原始宽高比后，实际图形为 48×36.57dp。前景、单色层均以 (54,54) 为中心；前景原有颜色和各路径透明度保持不变，单色层由系统主题着色。

| 项目 | 修改前 | 修改后 |
| --- | --- | --- |
| 前景/单色层画布 | 108×108dp | 108×108dp |
| 图形等比缩放系数 | 0.6657 | 4/7，约 0.571429 |
| 图形宽×高 | 55.92×42.60dp | 48×36.57dp |
| 图形范围 | 约 x26.04–81.96、y32.70–75.30 | x30–78、y35.71–72.29 |
| 背景 | 不透明 #252525 | 不透明 #252525 |
| Legacy 回退 PNG | 原内部图形大小 | 图形按相同比例缩小，尺寸与底板 alpha 保持不变 |

新图形外接矩形最远角距中心约 30.17dp，小于安全圆半径 33dp；因此整个图形都在安全圆内。实际边界的微小浮点误差不影响这一结论。图形相对原尺寸约为 85.84%。

修改源位于 `assets/sayin-icons/B-field/android/res`：两个矢量层和 mdpi–xxxhdpi 共十张回退 PNG。通过现有 `python scripts/sync_brand_assets.py` 同步应用资源并更新来源 SHA-256 清单；应用端不再另加 inset 或缩放，避免重复留白。Manifest 的 icon/roundIcon 和 adaptive 定义沿用既有接入。

SVG 品牌母版、Windows ICO、应用内品牌 PNG 与 Google Play 商店资产不在修改范围。Legacy PNG 是已栅格化的旧系统回退图，保留原底板不等于给 Adaptive 图层增加遮罩。

## 验证与实际结果

1. 修改前保存用户桌面截图局部与原始资源，修改后核对矢量边界、安全圆和 PNG 底板 alpha。
2. 执行 `./scripts/gradle.ps1 :app:assembleDebug :app:lintDebug`，构建和 Lint 通过。此次仅改图标资源，不将此前业务回归描述为本轮重新执行。
3. 验证全部 24 个同步目标的 SHA-256 与平台源资源一致，并核对 SVG 母版、Windows ICO、应用内品牌 PNG 未变。
4. 在已授权 Root 的 Redmi K40 上以 `pm install -r -t` 保留数据更新 `seeyuer.sayin`，安装成功；已安装 APK 与本地构建 SHA-256 一致：`386afc517c29a2d1bc171dd34be23888ee97fd27bcd953e3ba4a16d70907c3c7`。
5. 在 MIUI 桌面实际找到 SayIn 并截图确认：底板尺寸保持一致，图形留白增加，与相邻图标更协调。未清除 Launcher 数据或改变桌面布局。

- [真机前后对比](research/35-launcher-comparison.png)
- [更新后完整桌面截图](research/35-launcher-after-device.png)
- [资源及部署验证记录](research/35-icon-padding-verification.json)
- [构建日志](research/35-android-build.log)

这些本地验证证据位于被 Git 忽略的 `docs/research`。安全圆检查证明资源几何符合约束；真机视觉检查只覆盖当前 Redmi K40/MIUI Launcher，未声称其他 OEM 遮罩或 Android 13+ 主题化图标均已实机验收。
