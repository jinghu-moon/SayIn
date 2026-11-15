# 62 · SayIn 读取 Root 模块剪贴板记录

日期：2026-11-16。范围：模块 → Android 组合面板；不实现 App 保活，不修改桌面或跨设备协议。

## 基线与根因

共同基线 `1a44a3f`。工作区存在其它任务未提交改动；不覆盖、不重置、不 stash，不合并提交这些内容。修改前 Android JVM 基线通过。

用户的 Redmi K40（API 33、arm64、Magisk）已安装 0.3.1。核对结果：采集默认关闭；0.3.1 只有 UID 0 的文件系统 socket 控制，`list` 要求实例停止，没有 App 访问接口。不能靠给 App Root 权限、放宽数据文件权限或读取私有历史文件解决。

## 已实现的边界

- 0.4.0 C++ 模块通过 abstract Unix stream socket `seeyuer.sayin.clipboard.v1` 提供单次请求，不新增 Java/DEX 模块运行时。
- App 复用完整页/快捷卡的剪贴板与常用语面板，不另存模块历史副本。模块可用时显示最多 50 条预览；完整正文按 ID 读取。点行粘贴并关闭；菜单复制、粘贴保持面板；查看全文、保存常用语走原实现。全部经过原有草稿插入票据，不发送、重发或操作电脑。
- 清空全部使用既有独立确认窗口，绑定列表修订；只删除模块历史，不清系统剪贴板或常用语。过期修订拒绝；失败或超时不自动重试变更，刷新核对结果。
- 无模块、旧模块或模块断开时回到原当前单条剪贴板。首次不可达保持原普通界面；明确的鉴权拒绝、曾经可用后断开或主动刷新失败才显示说明与刷新入口。不能在无 Root 的 App 中可靠区分“没装”“旧版”“没运行”，不伪造精确检测结果。
- App 只在可见面板中建立一次性连接；请求总预算 4 秒，取消关闭 socket。离页/暂停/切页回收任务、清缓存；迟到响应不能修改新面板或草稿。查看全文缓存也在暂停时清除。
- 采集开关仍由模块管理器 Action 明确控制，不因 App 打开而开启。首次关闭、不建历史密钥、不读取系统剪贴板；启动轻量只读控制实例。Action 先等待旧实例停止，再切换开关并启动唯一新实例。停止采集后仍能读取加密保存的历史；直接 CLI `disable` 是停止管理命令，需要 service/Action 再启动只读实例。
- 已有配对、草稿、Outbox、历史、录音、自动发送及桌面接收流程未修改。

## 修改前后

| 项目 | 0.3.1 / 原 App | 0.4.0 / 配套 App |
| --- | --- | --- |
| 模块记录读取 | 仅停止后 Root CLI | 可见面板鉴权读预览与单条正文 |
| 普通模式 | 当前单条剪贴板 | 保持原单条分类、敏感遮罩、插入路径 |
| 独立采集 | Action 明确启停 | 保留；App 打开不改变采集偏好 |
| 关闭采集 | 进程退出，App 没有读取能力 | Action 接续只读实例，旧记录仍可查看 |
| 清空历史 | Root 命令与时间戳屏障 | 增加 App 确认和修订校验，仍由唯一写入者提交 |
| App 身份 | 没有 App 通道 | UID + 主用户唯一包 + 固定签名；每次重验 |
| 粘贴与发送 | 单条插入经过原草稿票据 | 多条同路径插入；不授予发送权 |

## 身份与信任

1. App 校验本地服务的内核对端 UID 为 0。
2. 模块使用 `SO_PEERCRED` 的真实 UID，不接收客户端自报包名、证书、路径或 UID。只接收主用户普通 App UID，拒绝共享 UID/多包、工作资料及不在允许清单中的安装包。
3. 固定 argv 调用 `/system/bin/cmd package` 查询该 UID 的唯一实际安装包和 PackageManager 返回的 `/data/app/.../base.apk`。不用 shell，不执行客户端命令。子进程输出有界，超时杀死并回收，不缓存安装映射。
4. 检查 PackageManager 所有的安装 APK 为不可由普通用户写入的常规文件，从严格有界 APK v2 签名块取唯一当前签名证书，使用系统 SHA-256 核对固定摘要。**明确拒绝 v3/v3.1/未知签名块，只允许 v2 和已知 padding**，确保 Android 验证的是这个 v2 签名，避免“v3 由攻击者签名、v2 放伪造可信证书”的混用。当前开发 APK 确认为 v2-only。未来改签名方案需要重新实现并测试准入，不静默放行。
5. 当前 AOSP apksig V2SchemeSigner 会在 signedData 追加一个空的 length-prefixed 字段；解析器仅接受这个精确空字段，拒绝其它尾随内容。不是猜测 padding。
6. `module/app-policy.json` 是 CMake 生成准入常量的来源；打包核对允许包名、摘要确实在 ELF 中，避免只改清单未重建。当前为明确的本机开发 debug 公钥摘要，准入 `seeyuer.sayin`、独立 `seeyuer.sayin.imagetest`；不分发私钥，不是首次使用信任，也不是通用商店签名策略。
7. 每个请求及返回正文前重新查询身份，并再次检查锁屏/主用户/交互状态、模块停用标记与实例有效状态。没有长连接授权或 UID 缓存。不能抵御拥有 Root 的攻击者。

数据仍只由 Root 父进程拥有，采集子进程在读取密钥前 fork，降为 shell；没有动态启用时把密钥继承给新采集子进程。关闭/开启采集通过完整实例接续，不在带密钥的父进程中 fork 新采集器。

## IPC v1 权威定义

本节定义本机模块 IPC，不改变 `docs/01` 的双端协议。

每个连接一个请求/响应：4 字节 little-endian 无符号长度 + UTF-8 正文。请求最多 128 字节（ASCII 固定动词），响应最多 128KiB。监听 backlog 4，单写者一次处理一个客户端，读写各阶段有总预算 500ms；每条固定 PackageManager 查询预算 800ms。未知客户端不能占用无限线程或提交采集正文。

| 请求 | 结果 |
| --- | --- |
| `LIST` | `protocol:1`、`revision`、`capture_ready`、最多 50 个 `id/timestamp/preview` |
| `GET <revision> <id>` | 原修订及完整 UTF-8 `text`，最多 16KiB |
| `CLEAR <revision>` | 持久化并确认 `cleared:true`；不自动重试 |

修订为 `<generation>:<next_id>`，与唯一写入者的已有持久状态一致。新增/去重移动改变 next_id，清空改变 generation；不新增重复 revision 状态。预览最多 160 UTF-8 字节，按完整字符截断，正文不批量返回。响应错误仅有固定错误码，不含正文、路径、凭据或命令输出。

清空与 Root 控制优先于捕获队列，更新持久时间戳屏障并通知子进程；迟到采集不能复活已清记录。持久失败恢复内存原状态。模块停用和取消最多等待当前有界处理结束，不宣称实时抢占 Binder 系统调用。

本次不增加单条删除、App 内启停开关、后台保活、分页、多用户或跨 ROM 自适应。列表最多 50 条，没有分页必要。完整设计 56 中尚未实现的操作不是本版接口。

## 验证记录

真机均使用随机 `SAYIN_TEST_` 前缀、独立 `/data/local/tmp` 私有目录和隔离 App，不读取日常历史；生成样例会替换当前系统剪贴板。K40 本轮 `getenforce` 返回 `Enforcing`；不修改 Root 授权、SELinux、AppOps 或后台权限。

| 验证 | 实际结果 |
| --- | --- |
| `pwsh -File scripts/build_root_helper.ps1` | 原生构建、CTest、工程与白名单打包通过；CTest 2,318 项断言，Python 9 项工程测试 |
| `python root-helper/tests/verify_device.py --serial dc39c31d --allow-synthetic-clipboard-writes` | 最终 ELF 在 Redmi K40/API 33 的 14 组原有采集回归通过 |
| `python root-helper/tests/verify_app_bridge.py --serial dc39c31d --allow-synthetic-clipboard-writes --ordinary-regression` | 独立源码 App + 最终 ELF 真机通过 8 组桥接验证与 21 项普通回归；未知包、同包错误签名拒绝，完整页/快捷卡粘贴不发送，清空取消/确认、关闭后读取、旧修订拒绝、只读实例无采集子进程均通过 |
| 独立源码 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | 87 项 JVM 测试、Debug 构建、Lint 通过；Lint 原有 warning 未屏蔽 |
| 独立源码 `-Psayin.instrumentationBuildType=imageTest :app:assembleImageTest :app:assembleImageTestAndroidTest` | 隔离产品包及仪器化测试包构建通过 |
| `python scripts/check_i18n.py` | 英文、简体中文完整键和格式参数检查通过 |
| `apksigner verify --verbose --print-certs` | 配套 Debug APK 唯一 v2 signer，摘要与模块固定准入一致，未含 v3/v3.1/v3.2 signer |

工作区其它任务的修改仍保留。为验证本次提交本身，使用暂存内容导出的独立源码目录重新构建和运行上述 Android 回归；不将其它任务的未提交前台信息、重排序或桌面改动装入本次日常 APK。没有新增 Android 依赖，也未修改桌面接收和双端协议，因此本轮不将桌面测试或旧 LAN 成绩冒充本机 IPC 验证。

真机过程遇到 MIUI 启动确认窗口，早期合并运行的普通回归因此超时，不计为通过。最终脚本在仪器化新进程启动后显式将隔离测试 Activity 带到前台；若确认窗口挡住，只关闭该窗口后再次显式启动，不批准或修改后台启动策略。每条界面回归独立启动，以免上一条 ActivityScenario 退出后失去前台条件。

最终模块 ELF SHA-256：`7d5161732633939215a50daf66fb87577057ade800368b38c486e66fa0dbb353`。ZIP SHA-256：`537be1b5b4b007cf13172a476f1b16926fdea3666918fbb054f83a0bb27f9f6d`。机器证据位于被忽略的 `docs/research/native-clipboard/evidence/`；不能因文件名包含 API/机型便推断其它设备已验证。

## 安装与剩余边界

本次 0.4.0 新增 App IPC，0.3.1 无此能力；不能只更新 App。已提供 `sayin-helper-0.4.0-dev-arm64.zip` 并传到 K40 的 `Download/`，手机文件摘要与上述 ZIP 一致。模块由用户通过管理器安装并重启，不能自动安装 Root 模块。正式安装后的 Action/开机只读实例需要另行核对，临时 ELF/隔离 App 测试不等于完成正式路径验收。

配套日常 debug APK 使用上述独立源码构建，与模块固定签名匹配；已通过 `pm install -r` 在 K40 更新成功，没有卸载 `seeyuer.sayin` 或清除其数据。正式模块仍为 0.3.1 时，App 继续单条模式；模块升级后重新打开组合面板才能使用模块记录。采集维持原偏好，不自动开启；本轮没有真实锁屏期间 App IPC 的专项验证。

API 37 本次未连接设备，不能引用此前 0.3.1 的 API 37 成绩作为 0.4.0 App IPC 真机通过。其它 ROM、签名轮换/正式签名、长期资源和功耗仍未验收。保留 0.3.1 已记录的原生非稳定 Binder ABI、快速复制合并、同毫秒/时钟回拨漏采边界。

官方源码核对：AOSP `platform/tools/apksig` 的 `V2SchemeSigner.java`（2026-11-16），以及 Android `LocalSocket` 的 `getPeerCredentials`、`setSoTimeout`、关闭流即关闭 socket 文档。平台 API 可用不等于 ROM 自动验收。
