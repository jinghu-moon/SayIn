# 48 · 图片历史渲染资源生命周期修复

日期：2026-11-15。基线 `9d186a0`。这是已交付图片历史的独立回归修复，不代表图片阶段 6 的应用/平台验收全部完成。

## 根因与修改

满 30 条独立 DPAPI 图片历史反复打开原图时，关闭对话框会清除 `ImagePreviewImage` 的 CPU 引用，但直接传给 GPUI `img` 的资源不会因此自动移出窗口 sprite atlas。新增弱引用断言原先通过，新增真实 atlas key 断言在修改前失败：`closed original must leave the renderer atlas as well as Rust state`。只观察工作集或 `Arc` 都不足以发现这一问题。

`HistoryView` 是资源所有者。现在在原图关闭/替换时使用 GPUI 既有 `App::drop_image` 并传入当前窗口；缩略图过滤、翻页或删除淘汰时也显式释放。视图销毁时将释放延后至当前窗口恢复到 App 后执行，避免窗口更新期间漏掉当前窗口。不修改 Yue、渲染器或相邻工作区，不增加第二套缓存，也不改变收据、配对或输入权限。

## 验证

- 新增控件回归构建 30 条独立资源，检查每张缩略图像素 ≤1MiB、合计 ≤30MiB；原图真正进入 atlas 后关闭，CPU 弱引用及 atlas key 都必须消失。过滤清空历史后缩略图 atlas key 也必须消失。
- 原生 D3D 完整生产 HistoryView：30 张（20 张 1080×2400/2400×1080，10 张 64×64），通过本例程自身窗口的实际菜单/关闭事件打开关闭原图 60 次，约 3 分 49 秒；随后生成深浅色与多窗口宽度截图。没有监听、粘贴或访问日常身份。
- 每 200ms 采样工作集和 Private Bytes。修改前/后采用相同合成数据、Debug 构建和轮数；原始脱敏数字见 [before](benchmarks/2026-11-15-production-images/history-soak-before.json)、[after](benchmarks/2026-11-15-production-images/history-soak-after.json)。

| 指标 | 修改前 | 修改后 |
| --- | ---: | ---: |
| Windows PeakWorkingSet | 203.75 MiB | 195.60 MiB |
| 采样最大 Private Bytes | 990.98 MiB | 389.88 MiB |
| 关闭态前 10 轮中位 Private Bytes | 300.47 MiB | 262.48 MiB |
| 关闭态后 10 轮中位 Private Bytes | 803.22 MiB | 254.51 MiB |

修改前私有内存持续增长而工作集近似稳定；修改后这次有限负载未重现持续增长。脚本检查预热后关闭态中位私有内存增长不超过 64MiB，捕捉此次数百 MiB 的回归；该门槛仅适用于固定合成负载，不是进程/VRAM硬上限。直接 atlas key 断言提供资源移除证据，进程 Private Bytes 仍不能等同于独立 VRAM 测量，更不能证明无限时长无泄漏。

复现：`scripts/verify_image_history.ps1`。只创建可见的独立测试窗口，所有事件均在程序自己的 GPUI 窗口内分发，不能替代第三方应用人工矩阵。

在仅包含本修复及测试工具的干净验证 worktree，完整 `cargo test --workspace --all-targets --locked` **137 项通过，0 失败/忽略**；Clippy `-D warnings`、四包限定 fmt、工作区构建、i18n 和依赖来源检查通过。未修改 Android 产品代码，现有 JVM/Debug/Lint 基线仍通过；图片阶段 6 的隔离设备回归另记 46。
