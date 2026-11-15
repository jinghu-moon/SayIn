# 59 · SayIn Root 模块工程骨架

日期：2026-11-15。阶段：**0.1 Kotlin 骨架的历史记录，当时采集、保活和 IPC 未实现**。

后续进展：用户指定 C++ 后，0.2 已替换 DEX 工程，完成限定 Android 13 arm64 的独立采集、加密存储及 Root 控制，见 [60 实施记录](60-root-helper-native-clipboard-collection.md)。下文保留 0.1 当时的安装与验证证据，不再作为当前构建说明；App 读取和保活仍未接入。

## 1. 基线与范围

依据用户要求参考 `D:/100_Projects/110_Daily/PathGuard-Next`。核对其模块描述、安装/启动/操作/卸载入口、`skip_mount` 及白名单打包方式；按 [56](56-root-module-clipboard-and-keepalive-design.md) 的 SayIn 职责独立编写必要工程文件。用户已明确 PathGuard-Next 是自己的项目，可以参考使用；本次未复制其路径隔离、Provider、内核或 Zygisk 实现。

开始实施时主分支 HEAD 为 `063d455`。工作区另有 Android 面板、测试、资源和文档改动，本次保留，没有 reset、stash 或启动并行 agent。新工程位于独立目录，不修改 App Gradle settings、版本目录或现有业务代码。

修改前不存在 Root 模块工程、可构建 DEX 或模块 ZIP。修改后可以独立执行 Kotlin JVM 测试、生成真实 DEX、验证模块模板并打包；**仍不能采集剪贴板或保活 App**。

## 2. 工程组织与取舍

入口说明见 [root-helper/README](../root-helper/README.md)。

| 文件/目录 | 职责 |
| --- | --- |
| `root-helper/settings.gradle.kts`、`build.gradle.kts` | 独立 Gradle 构建，复用共享版本目录 |
| `root-helper/daemon/` | Kotlin 一次性诊断入口、JVM 测试和 D8 任务 |
| `root-helper/module/` | `module.prop`、安装/启动/操作/卸载入口、`skip_mount` |
| `module/config/defaults.json` | 采集、保活两个开关默认均为 false |
| `module/app-policy.json` | 固定包名、空签名列表及未开放的 IPC 版本 |
| [build_root_helper.ps1](../scripts/build_root_helper.ps1) | 使用项目 JDK/Wrapper 完成构建、测试、打包 |
| [package_root_helper.py](../scripts/package_root_helper.py) | 白名单打包、制品校验和原子替换 |
| `root-helper/tests/test_engineering.py` | 主机侧包/安装脚本/生命周期测试 |

复用 Temurin 25.0.5+8、现有 Gradle 9.7.0 Wrapper、共享目录中的 Kotlin 2.4.21，字节码目标 17。SDK/build-tools 为 37.0.0，DEX 最低 API 29。编译器与运行依赖由 Gradle lockfile 锁定；运行时仅 Kotlin 标准库及其 JetBrains annotations 传递依赖，不引入 Compose、Miuix、网络库、Hook 或数据库。

不新建 Wrapper 副本；不把模块工程 include 到 App，不触发 ASR、翻译/native 或桌面编译。原 `scripts/gradle.ps1` 固定 `-p android`，不能再叠加第二个 `-p`；新增构建入口复用相同 JDK 校验和 Wrapper，以独立工程路径调用，环境变量在结束时恢复。

当前没有需要早期挂载或系统属性注入的能力，故不增加 `post-fs-data.sh`、`boot-completed.sh`、`system/`、SELinux 规则、Zygisk 占位库或更新下载器。Magisk 官方说明 META-INF 仅为 Recovery 安装所需；本阶段只面向管理器安装，不额外复制 Recovery 安装器。KernelSU 不因本模块而额外要求挂载 metamodule。

## 3. 真实行为与包边界

- `HelperMain --status` 是一次性诊断，返回 `running=false`，采集、保活、IPC 均为 `not_implemented`，随后退出。`--run` 返回 69；未知参数返回 64，不回显参数正文。
- `service.sh` 不启动进程，不加载模型、不读取剪贴板、不连接电脑。遇 `disable/remove` 直接返回；`action.sh` 只报告未实现；卸载入口不对其它进程或已有文件执行删除。
- 仅为 arm64、Android 10+、Root 管理器正常系统内安装准备；安装检查采用 `BOOTMODE/API/ARCH`，缺失制品或校验不一致拒绝继续。两个能力不自动开启。上述是代码目标和主机测试结果，不是已验证的 ROM 支持矩阵。
- 空签名列表是未来鉴权的拒绝默认值，当前没有 IPC 监听器，不用空列表暗示“全部信任”。也不预填手机的真实签名、UID 或配对凭据。
- 诊断 DEX 来自真实 Kotlin → D8；包内为单个原始 `daemon.dex`。若后续产生 multidex，打包明确拒绝，不能只取首个文件丢掉其它类。
- 模板文件必须 UTF-8 无 BOM、LF。ZIP 文件使用固定时间、排序及 UNIX 权限；逐文件 SHA-256、构建清单、外部 ZIP 摘要共同用于检查完整性，**不是发行签名或信任根**。
- 打包采用明确文件清单，排除额外环境文件和运行数据；校验全部完成才原子替换 ZIP。产物位于被忽略的 `root-helper/build/`。仅 `--scaffold` 允许打包，不能把它发布为功能完成版。

原子实例锁、生命周期监督、签名鉴权、私有加密历史、用户停止识别和发送安全语义仍是后续实现门槛。本阶段用“不开启运行”满足边界，没有用轮询空进程冒充保活。

## 4. 实际执行的验证

```powershell
pwsh -File scripts/build_root_helper.ps1
```

| 检查 | 实际结果 |
| --- | --- |
| 首次 Kotlin 编译、JVM 测试、D8 | `check :daemon:dex --write-locks` 通过，生成依赖锁和真实 DEX |
| 正常构建入口与严格依赖锁 | 构建入口通过，后续构建不使用 `--write-locks` |
| JVM 测试 | **4/4** 通过，0 跳过，覆盖状态、未实现运行拒绝、非法参数和帮助 |
| 主机工程测试 | **13/13** 通过，0 跳过 |
| Shell 语法 | 4 个脚本用 Git Bash `-n` 解析通过，包含于上述工程测试 |
| 安装入口 | mock 管理器函数下支持条件通过；Recovery、旧/未知 API、非 arm64 和篡改包拒绝 |
| 包边界 | 白名单、逐文件摘要、权限、确定性、坏重建保留旧包、缺失锁/DEX、损坏和 multidex 拒绝通过 |
| 生命周期入口 | 主机下不修改已有样例文件，停用/删除标记被遵守 |
| 主机 CLI | 实际 `:daemon:run --args=--status` 成功，输出明确未运行状态 |
| 运行依赖核对 | 只有 `kotlin-stdlib:2.4.21`、`annotations:13.0`，均有严格版本约束 |

一次主机测试初跑误选 Windows System32 的 WSL `bash.exe`，支持条件用例失败。已改为优先 Git Bash，并排除 WSL 启动器；重新执行后 13 项全部通过。没有忽略失败、弱化断言或把“不支持条件”误当成脚本已正确运行。

本次没有修改 Android/桌面产品代码、UI 文案或通信行为，因此没有执行产品 JVM/Lint/i18n/桌面完整回归，也不借用其它 agent 的结果作为模块证据。上述构建阶段没有安装、授予权限或启动 Root 常驻进程；Bash 主机解析及 mock 安装不代替真机验证。用户后续安装及 Android ART 诊断的实际结果补充在下一节。

## 5. 交付到手机

用户随后要求传到手机手动安装。已通过 ADB 将工程骨架包传至当前连接的 Redmi K40（`M2012K11AC`）：

```text
/sdcard/Download/sayin-helper-0.1.0-dev-scaffold.zip
```

文件大小 675268 字节；电脑及手机 `sha256sum` 一致：

```text
05bbf305fe06f37fde54326fc6e1ab1fb2ac213f907cc6d26c506eef1d21c6c9
```

交付时仅传文件，没有代用户安装。后续重新生成包应重新记录摘要，不能复用此摘要描述不同产物。

### 5.1 用户安装及重启后真机核对

用户通过 Magisk 安装并提供以 `- Done` 结束的日志。安装后首次核对发现 `/data/adb/modules_update/sayin_helper` 存在，正式目录存在 `update` 标记，说明已完成安装、等待重启切换，并非安装失败。此时从待更新目录实际执行 `app_process ... --status` 已成功。

用户随后重启，2026-11-15 对同一台 Redmi K40 再次核对：

| 项目 | 实际观察 |
| --- | --- |
| 系统 / 框架 | API 33、arm64-v8a、Magisk `30.6:MAGISK:R`，`sys.boot_completed=1` |
| 安装目录 | 正式 `/data/adb/modules/sayin_helper` 存在，待更新目录已消失 |
| 管理器标记 | 无 `disable`、`remove`、`update` |
| 安装文件完整性 | 排除安装后被管理器移除的 `customize.sh`，其余清单中 13 个文件 SHA-256 全部匹配 |
| 脚本权限 | `service.sh`、`action.sh`、`uninstall.sh` 均为 0755；DEX 为 0644 |
| ART 状态诊断 | 正式目录的原始 `daemon.dex` 通过 `app_process` 执行 `--status`，返回 0；状态为未运行，三个能力均未实现 |
| 常驻启动拒绝 | `--run` 明确返回 69，并提示未实现；没有启动后台循环 |
| 默认策略 | 采集/保活均 false；签名列表为空，IPC 版本为 null |
| 诊断结束后进程 | `ps` 查询未发现 `seeyuer.sayin.helper.HelperMain` 残留进程 |

完整 `SHA256SUMS` 是安装包清单。Magisk 安装后移除 `customize.sh`，因此不能用它原样进行已安装运行目录的全量校验，更不能将这个正常差异解释为安装损坏。核对时仅排除这个已知的安装专用文件；其余文件均校验，没有宽泛忽略错误。

这证明当前设备的**管理器安装、重启后文件切换和一次性 ART 诊断**通过；不证明开机脚本已实际执行、独立采集、IPC、保活、停用/升级/卸载或功耗通过。未增加模块运行日志，因此没有声称通过日志确认了开机脚本的调用。未读取系统剪贴板或用户历史，未启动 SayIn Activity、改变 App 权限或重放请求。

## 6. 后续顺序

先按 56 的 A 阶段在目标 Root/ROM 证明最小采集入口及可靠停止判断，再实现固定操作的鉴权 IPC、单实例守护、私有加密历史和待命恢复。安装脚本/声明/测试必须随真实能力一起更新；不能只开启模板开关而保留当前空生命周期入口。

官方工程依据：

- [Magisk Developer Guides](https://topjohnwu.github.io/Magisk/guides.html)：模块结构、管理器安装、`customize.sh` 的 source 语义、`skip_mount` 和生命周期入口。
- [KernelSU Module guide](https://github.com/tiann/KernelSU/blob/main/website/docs/guide/module.md)：模块安装及脚本机制；挂载 metamodule 不是纯脚本模块的必需依赖。
