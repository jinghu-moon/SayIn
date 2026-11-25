# SayIn v3 重构执行交接文档

| 项目 | 内容 |
| --- | --- |
| 更新 | 2026-11-26 |
| 状态 | 待执行（不是已完成记录） |
| 执行对象 | 外部编码 Agent |
| 仓库 | `D:\100_Projects\110_Daily\SayIn`，Windows，Git Bash |
| 分支 | 从 `main` 创建并常驻 `feat/sayin-v3`，不直接推 `main` |
| 测试设备 | Redmi K40 / `alioth` / `M2012K11AC`，adb 序列号 `dc39c31d` |
| 依据 | [86 规范](86-multi-host-multi-device-design.md)、[89 验收](89-multi-device-verification.md)、[90 增量](90-multi-device-increment-design.md)、[91 TDD 计划](91-multi-device-tdd-implementation-plan.md) |

本文是交给执行 Agent 的完整工作说明。它是任务书，不是设计文档；规范冲突以 86 为准。

## 0. 一页摘要

把现有的 sayin.v2 单机协议整体重构为 sayin.v3 多机/多手机协议，按 91 的 T01–T36 逐任务 TDD 推进，分 P0–P9 九个阶段。每个阶段门禁全绿后提交并推送到 `feat/sayin-v3`，然后进入下一阶段，直到全部完成。

项目处于开发阶段、尚未发布：不考虑向后兼容，鼓励破坏性改动，目标是**根因正确 + 验证通过**，不是最小 diff。质量优先于速度，但不要用重复工作、全量重跑或反复整份读大文件来浪费速度。

## 1. 开工前必读（全部读完再动第一行代码）

1. `AGENTS.md` —— 工程规则，优先级高于你的默认习惯。
2. `docs/86-multi-host-multi-device-design.md` —— v3 规范的**唯一权威**。
3. `docs/89-multi-device-verification.md` —— 验收计划与 M1–M28 用例。
4. `docs/90-multi-device-increment-design.md` —— 三个独立增量。
5. `docs/91-multi-device-tdd-implementation-plan.md` —— 你真正要执行的任务卡、阶段划分、里程碑与默认值。

代码入口（先读再改，禁止盲改核心路径）：

- `desktop/crates/receiver/src/{ledger,worker,gate,protocol,windows_input,windows_image_delivery,history,application_icon,application_name}.rs`
- `desktop/crates/transport/src/{server,storage,discovery,identity,interfaces}.rs`
- `desktop/crates/pairing/src/{lib,android}.rs`
- `android/app/src/main/java/seeyuer/sayin/{data,pairing,ui,quick,asr,polish}`

## 2. 执行模型：TDD，逐任务推进

按 91 的顺序执行 T01 → T36。每个任务固定五步：

1. **红**：先写会失败的测试，用真实命令运行，记录真实失败输出。测试名描述行为，不写 `test1`。
2. **绿**：写最小实现让测试通过。不顺手加未被要求的功能，不留“以后再说”的分支。
3. **任务回归**：跑该 crate/模块的测试与该阶段已有测试，确认无退化。
4. **阶段回归**：该阶段最后一个任务完成后，跑第 3 节的完整门禁。
5. **提交推送**：门禁全绿后按第 4 节提交并推送。

阶段与任务对应：

| 阶段 | 任务 | 主要产出 |
| --- | --- | --- |
| P0 | T01–T04 | v2 基线冻结、v3 线类型、黄金向量、错误码穷举 |
| P1 | T05–T08 | 账本 schema 7、状态转移表、`request_rev`/`accept_order`、配额 |
| P2 | T09–T12 | 可执行队首、30 秒执行期限、`held` 10 分钟保留期、重启恢复 |
| P3 | T13–T16 | `uncertain` 范围分类、目标窗口阻断、多 `uncertain` 并存、闸门代次 |
| P4 | T17–T22 | `/sayin/v3` 传输、`hello/welcome/fencing`、`query/queried`、`control`、出站预算、连接槽 |
| P5 | T23–T26 | Android host/pair 凭据目录、Outbox 双范围、回执映射、目标选择与退避 |
| P6 | T27–T29 | 图片绑定链、资源与公平、手机图片草稿 |
| P7 | T30–T31 | Windows / Android 数据迁移 |
| P8 | T32–T33 | 冻结性能门槛、执行 M1–M28 |
| P9 | T34–T36 | 紧凑二维码、WebP 图标、多图原型 |

里程碑 M1–M4 的可运行定义见 91 §3。里程碑之间不插入临时兼容层。

## 3. 阶段门禁（必须真实执行，不许声称）

每个阶段结束，在仓库根目录执行：

```bash
cargo test --workspace --all-targets --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo fmt -p voicelink-desktop -p voicelink-receiver -p voicelink-pairing -p voicelink-transport -- --check
python scripts/check_i18n.py
python scripts/check_desktop_dependencies.py
scripts/gradle.ps1 :app:testDebugUnitTest
scripts/gradle.ps1 :app:lintDebug
```

里程碑 M2/M3/M4（P5、P6、P7 末尾与 P8）追加设备回归：

```bash
scripts/gradle.ps1 :app:assembleDebug
adb -s dc39c31d install -r android/app/build/outputs/apk/debug/app-debug.apk
scripts/verify_android_transport.ps1 -FullSuite
```

规则：

- 任一项失败 = 该阶段未通过，**不提交为该阶段完成**。先修根因，不许降低标准。
- `cargo test` 输出里的失败用例必须逐条处理；不允许“整组复跑通过”掩盖单测失败。
- 门禁失败若属于 T01 已登记的既有基线问题，照实说明，不据此宣称新阶段通过。
- 设备测试只安装隔离包 `seeyuer.sayin.imagetest`，由脚本负责；日常包 `seeyuer.sayin` 只允许 `adb -s dc39c31d install -r` 覆盖安装，禁止清数据。
- `dc39c31d` 不可用时，设备相关项必须登记为“未执行”，不得用模拟器或 fake 结果冒充。
- 无法完成全量的项（真机、真实 Wi-Fi、长期功耗等）必须在提交信息与报告里写明“未执行”，不得省略。

## 4. Git 循环（每阶段一次）

1. `git status` 确认没有不该提交的产物：构建输出、APK、模型权重、日志、临时目录、`assets/translation-model/`、`assets/images-send-tests/` 之外的临时样本。
2. `git add -A`，再自查 `git diff --cached --stat`。
3. 提交信息用 `<type>(<scope>): <英文摘要>`，破坏性改动加 `!`：

```text
feat(receiver)!: add v3 ledger schema and state machine

Tasks: T05-T08
Tests: 142 passed (receiver), 118 (transport), 0 failed
Notes: held retention 10min; accepted migrates to expired/restart_confirmation
```

4. `git push origin feat/sayin-v3`（首次带 `-u`）。推送失败不要静默继续，先解决。
5. 同一次提交里更新 `docs/91-multi-device-tdd-implementation-plan.md` 的阶段状态表（任务编号 → 通过 / 提交 SHA），让进度可核对。

分支规则：从 `main` 创建并常驻 `feat/sayin-v3`，**不直接推 `main`**，不 force push，不 squash 掉阶段提交。若执行环境禁止建分支，改在 `main` 上按阶段提交，并在每次报告里显著说明。

## 5. 质量红线（违反即视为任务失败）

- 不跳过、注释、删除测试；不弱化断言；不修改正确预期来让错误实现通过。测试变更必须能说明“预期行为发生了正确变化”。
- 不写临时兼容分支、Adapter、双协议、旧路径兜底。v3 整体替换 v2 是既定决定。
- 不留 TODO 后声称完成；不留只把调用方改好、根因没动的补丁。
- 不用 `sleep` 或真实时间验证期限、退避、保留期；一律注入时钟。
- 不把编译通过、构建成功、loopback/fake 通过描述成真机落字通过。
- 不把凭据、token、DPAPI/Keystore 明文、正文载荷写进日志或提交。
- 关键行为变化必须给出“修改前 / 修改后 / 预期”的对比记录。

## 6. 规范未定或矛盾时

- 局部数值用 91 §1.2 的默认值（执行期限 30 秒、`held` 保留 10 分钟、查询批次 64、`foreground_ttl_ms` 5000 等），并把实际选用值写进该阶段提交信息。
- 规范有冲突以 86 为准；86 未覆盖时选**最简单且可测**的方案，先单独提交 86 的修订，再写实现。
- 不为“让流程继续”发明第二套语义；任何新概念先回答“为什么不能用现有状态或字段表达”。

## 7. 卡住时的处置

- 同一问题连续两次尝试无效：停止堆改动，做最小复现，写清“现象 / 已排除 / 剩余假设”，该任务提交为 WIP 不推送，转做不依赖它的后续任务。
- 阶段被阻塞：不伪造通过，也不跳过门禁进入下一阶段；按依赖调整顺序并在报告中列出阻塞项。
- 三次尝试仍无法定位：停止推进，产出阻塞报告（复现步骤、日志、影响范围、需要用户决策的点）。

## 8. 环境与性能约束

- Rust 工具链固定 `1.99.1`；JDK 固定 `C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot`；Gradle 一律经 `scripts/gradle.ps1` 调用。
- 禁止 `cargo fmt --all`，它会遍历并改动相邻 Yue path 工作区。
- 不修改 `D:/100_Projects/110_Daily/Yue-GPUI-Component`。
- 不修改防火墙、Windows 网络类别、Android 后台/悬浮窗权限来“让测试过”。
- 加速手段：单 crate 用 `-p` 定向测试；`cargo test` 与 Gradle 任务互不依赖时可并行；先 grep 定位再读文件片段，不整份重读。
- `desktop/Cargo.toml` 保留 Kit `=0.8.0` 精确约束，不单独升级框架版本。

## 9. 完成的定义

全部阶段完成后产出最终报告，至少包含：

1. 91 任务卡逐项状态（完成 / 部分 / 未做，附提交 SHA）。
2. 每阶段测试统计与前后对比（用例数、失败数、耗时）。
3. 89 §7 退出条件逐条结论，明确哪些真实通过、哪些未验证。
4. 关键行为前后对比表（状态转移、期限、`uncertain` 范围、回执字段）。
5. 未覆盖风险清单（真机、真实 Wi-Fi、长期功耗、性能样本、图片增量等）。
6. 迁移证据：旧数据升级前后的实际输出。

只有“根因解决 + 新功能正确 + 相关已有功能正常 + 门禁真实通过”同时成立，才允许把某阶段标为完成并推送。
