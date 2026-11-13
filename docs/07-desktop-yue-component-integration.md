# VoiceLink：电脑端Yue组件库接入记录

日期：2026-11-12。本记录保留最初接入核查；电脑端基础工程现已创建、构建，实施结果与边界见[08 基础工程记录](08-desktop-foundation-implementation.md)。后文“尚未”均描述本记录最初核查时点。

## 1. 已确认的选择

电脑端使用本地 **`D:/100_Projects/110_Daily/Yue-GPUI-Component`**，组件入口为 **`yue-component`**，底层是GPUI Kit/Base。协议运行时、持久接收、串行Win32输入及窗口关闭后的核心生命周期继续依据[协议v2](01-voicelink-protocol-v2.md)。

实际已读取该库的根Cargo配置、组件crate配置、README、`lib.rs`导出和最小消费者示例。当前本地组件版本为0.1.0，使用GPUI Kit0.8.0；这是本地源码的组合事实，不代表已经为VoiceLink构建通过，也不声称其版本是上游最新。

## 2. 开发期路径与补丁

若电脑端Cargo工作区位于本项目`desktop/`，其根`Cargo.toml`使用以下相对路径：

```toml
[dependencies]
yue-component = { path = "../../Yue-GPUI-Component/crates/yue-component", default-features = false }

[patch.crates-io]
gpui-base = { path = "../../Yue-GPUI-Component/vendor/gpui-base" }
gpui-pre = { path = "../../Yue-GPUI-Component/vendor/gpui-pre" }
gpui-pre-windows = { path = "../../Yue-GPUI-Component/vendor/gpui-pre-windows" }
```

这些是待初始化工程的接入约定，本次未写入一个空壳Cargo工程。若工作区目录改变，必须重新计算路径。Cargo补丁只从消费方工作区根生效，不能以为path依赖自带的补丁会自动被继承。

优先使用`yue_component::gpui_kit`导出的GPUI类型；若确需显式依赖Kit，关闭默认features，并保证整个依赖图与Yue使用同一套Kit/Base/backend。应用无需再引入独立`gpui`，也不额外启用Kit的默认styled component或Lucide assets层。

组件库在本地持续开发，`Cargo.lock`不能锁住外部path源码内容。初始化时应记录外部库修订及补丁状态；正式分发采用可追溯源码快照或固定修订。组件库、Kit和三个补丁一起验证，不能为了单项版本号更高而独立升级。

## 3. 初始化与界面边界

参考库的[最小消费者](../../Yue-GPUI-Component/examples/minimal/src/main.rs)：宿主拥有Application、Window和状态，在首个窗口创建前调用`yue_component::init`。组件库初始化承担Kit/Base的集成，不另建平行Application或每页一个Root，不在render里重复安装主题。

Yue组件和内置Tabler图标优先通过公开API使用。选用具体控件时再阅读对应组件文档、实现及必要的测试，不从旧GPUI示例猜测API；库的控件展示文案、默认提示也要纳入VoiceLink的中英文检查，不把Showcase中文示例直接当产品文案。

主题要求继续为 **`#1f75db`** 和 **neutral**。已确认库提供`Accent::Azure`、`NeutralTone::Neutral`及`with_neutral`，但其明暗颜色和其他状态角色仍必须逐项核查。默认Azure/Neutral不等于所有默认状态颜色均符合本项目约束；不承诺尚未验证的自定义主题接口。若公开能力不足，在正式组件库能力边界处理，并验证所有控件与Base主题一致。

## 4. 首次接入验证

初始化时至少完成：

1. Cargo依赖与重复包检查，确认三个patch确实命中，Kit/GPUI类型没有平行来源。
2. 编译最小Yue窗口，再验证实际启动、输入焦点、弹层和关闭窗口的核心生命周期。
3. 主题明暗状态、Tabler图标、中英文长文案及Windows显示缩放检查。
4. 接入接收核心后验证GUI订阅状态；UI重建或关窗不改变持久接收、ACK及输入结果语义。

本次只完成源码接入核查和记录，未修改外部组件库，未运行其Showcase或宣称电脑端已实现。

## 5. 本地依据

- [组件库README](../../Yue-GPUI-Component/README.md)：开发期依赖、三个必需补丁、初始化与Base-only约定。
- [根Cargo配置](../../Yue-GPUI-Component/Cargo.toml)、[组件crate配置](../../Yue-GPUI-Component/crates/yue-component/Cargo.toml)。
- [最小消费者配置](../../Yue-GPUI-Component/examples/minimal/Cargo.toml)、[源码导出](../../Yue-GPUI-Component/crates/yue-component/src/lib.rs)。
