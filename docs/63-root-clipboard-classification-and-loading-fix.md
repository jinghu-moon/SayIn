# 63 · 剪贴板普通文本误拒绝与面板闪现修复

日期：2026-11-16。基线：`4c56548`。范围：C++ Root 模块 0.4.1 与 Android 剪贴板面板；不修改桌面、配对或发送协议。

## 现象与根因

K40 正式模块 0.4.0 已启用、采集器已就绪，但复制文字后记录仍为空。打开面板时，当前系统剪贴板短暂显示，随后被模块空列表替换。用户怀疑代码问题，核对证实有两个独立缺陷，不能靠重复点 Action 解决。

1. 原生解析把 `TextUtils` kind 0 一律视为富文本。Android 的无实际 span 的 `SpannedString` 也使用 kind 0，因此普通文字会被拒绝。
2. 系统文本分类会为普通文字附加 `TextLinks`；旧解析把这个最后字段与 Intent、URI 等可执行/引用对象一起拒绝。先修复第一项后，实际剪贴板仍被第二项拒绝。
3. App 用同一个 `snapshot=null` 表示“尚未完成模块查询”和“模块不可用”，先渲染普通单条，异步 LIST 成功后再换成模块列表；空列表文案又错误暗示用户尚未开启采集。

旧测试主要用新建的普通 String 写入，未覆盖空 span 包装和分类后序列化的实际格式。这是测试覆盖缺口，不是 Android 后台权限限制。

## 修改

- 仅接受有精确零 span 终止符的 kind 0；有实际 ParcelableSpan 的富文本仍拒绝。
- 按已核对的 API 33/37 布局有界解析 TextLinks 的字符串、typed list、EntityConfidence、范围和 Bundle 边界；不执行链接、不反序列化任意 Parcelable、不保存分类信息。截断、未知标记、超限及非法范围拒绝。
- 新增 Root-only `diagnose-clipboard`，只在解锁门禁通过后检查当前格式，输出固定成功/错误码；不导出正文、标签、extras 或原始 Parcel，不打开历史存储。敏感标记在解析正文前拒绝。
- 面板明确区分“加载中”与“模块不可达”；首次查询完成前显示进度，不闪现普通剪贴板及其底部操作。模块不可达后才回到普通模式。空列表只说明暂无记录，不推断开关状态。
- 用小型数据源接口为异步 UI 提供可控的延迟响应，覆盖加载、回退和关闭后的迟到响应；不新增产品后台服务、轮询或持久缓存。

## 修改前后

| 行为 | 0.4.0 / 原 App | 0.4.1 / 修复 App |
| --- | --- | --- |
| 无实际样式的 SpannedString | `styled_text_unsupported` | 接受合法纯文本 |
| 普通文本附带 TextLinks | `complex_item_unsupported` | 校验已知格式，正文正常入库 |
| 首次打开模块面板 | 系统单条闪现后切换 | 先加载，再显示已确定的数据源 |
| 模块可用但记录为空 | 暗示尚未开启 | 只说明还没有记录 |
| 模块不可用 | 普通单条 | 查询完成后普通单条 |
| 敏感/HTML/真实样式/URI/未知对象 | 跳过 | 继续跳过 |
| 草稿插入、复制、清空、发送授权 | 原路径 | 保持原路径；不新增发送权 |

## 验证

全部设备写入使用生成的 `SAYIN_TEST_` 内容、随机私有数据目录和隔离 `seeyuer.sayin.imagetest`。未读取或导出日常历史。测试期间暂时停止日常采集器，保留原启用偏好和历史；结束恢复原状态。未改 SELinux、AppOps、后台启动或 Root 授权。

在实际当前剪贴板上进行只输出格式结果的诊断：旧解析为 `styled_text_unsupported`，修复空 span 后为最后字段不支持，完整修复后为 `current_text_supported=true`。不将真实剪贴板内容写入测试证据。

| 命令/检查 | 实际结果 |
| --- | --- |
| `pwsh -NoProfile -File scripts/build_root_helper.ps1` | 原生主机/ARM64 构建、2,511 项 CTest 断言、9 项工程测试、白名单打包通过 |
| `python root-helper/tests/build_device_fixture.py` | 测试 APK 构建通过 |
| `python root-helper/tests/verify_device.py --serial dc39c31d --allow-synthetic-clipboard-writes` | K40/API 33、SELinux Enforcing，15 组隔离采集回归通过；新增无 span 包装和 TextLinks 实际 Binder 写入/采集 |
| 独立源码 `python root-helper/tests/verify_app_bridge.py --serial dc39c31d --allow-synthetic-clipboard-writes --ordinary-regression` | 9 组桥接/UI 检查及 21 项普通剪贴板/草稿回归通过。新增组包含 3 项异步 UI 测试；保持完整页/快捷卡粘贴不发送、清空确认、停采仍可读、旧修订拒绝、未知包及同包错误签名拒绝 |
| 独立源码 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | 87 项 JVM、Debug、Lint 通过；未屏蔽原有 warning |
| 独立源码 `-Psayin.instrumentationBuildType=imageTest :app:assembleImageTest :app:assembleImageTestAndroidTest` | 隔离产品及测试 APK 构建通过 |
| `python scripts/check_i18n.py` | 英文、简体中文键与参数检查通过 |
| `apksigner verify --verbose --print-certs` | 单一 v2 signer，证书摘要与模块固定准入一致，无 v3/v3.1/v3.2 signer |

Android 以基线加本次文件导出到独立源码目录构建，不把其它任务未提交的 UI、前台信息或桌面修改混入交付。工作区原改动全部保留。

## 安装与边界

模块包：`root-helper/build/distributions/sayin-helper-0.4.1-dev-arm64.zip`。

- ELF SHA-256：`545ce40fcbda72b0dea33d7c06b633fad317eca1f4396515cdb53c4cfbc04c49`。
- ZIP SHA-256：`a56e6d85c8879e6b1477052c6844c63fcc4aa757a5406a3236c20d39d9263bb4`。

必须更新模块才修复采集格式；只装 App 能消除闪现，不能让 0.4.0 接受新格式。用户通过 Magisk/KernelSU 安装并重启，工程规则禁止自动安装 Root 模块。更新保留已启用偏好与历史，不要重复点击 Action 把采集关掉。用户重启前，正式目录仍是 0.4.0，不能声称正式模块已修复。

交付状态：K40 日常 `seeyuer.sayin` 已用独立源码构建的 debug APK 执行 `pm install -r` 更新，无卸载或清数据；安装前后 APK 摘要一致，为 `ade3ed7c70054d2c6606712f3c02b002049c00d1895f6e46a71207e1d1fdc972`。模块 ZIP 已传到 `/sdcard/Download/sayin-helper-0.4.1-dev-arm64.zip` 并核对手机端摘要。隔离 App 已停止，日常 App 已打开；原正式 0.4.0 采集器已恢复 `running=true / enabled=true / capture_ready=true`。尚待用户安装 0.4.1 并重启，再复验正式路径；此状态不等于采集格式已经在正式模块修复。

本轮真机仅 Redmi K40/API 33；API 37 新布局仅有主机解析覆盖，未执行新版 API 37 真机回归。实际带格式文本仍不采集。锁屏/解锁门禁未修改，已有回归继续通过；本轮没有新做真实锁屏人工验收。保留快速复制合并、同毫秒和时钟回拨漏采，以及原生 Binder 非稳定 ABI 的既有边界。

格式依据：AOSP `android-13.0.0_r1` 的 `ClipData.writeToParcel`、`TextUtils.writeToParcel`、`TextLinks` 和 `EntityConfidence`。不根据观察猜测未知 padding，不宣称通用 ROM 支持。

## 0.4.1 正式安装复验与启动时序调整（2026-11-16）

用户安装并重启后，K40 的正式目录为 `0.4.1-dev / versionCode=6`；13 个文件摘要匹配交付 ZIP（排除管理器正常删除的 `customize.sh`），日常 App 摘要也与上述交付一致。

但本次真实开机发现 `running=false / enabled=true`，固定诊断状态为 `last_exit=capture_fault`，accepted/dropped 均为零。系统启动完成后，相同正式 ELF 的三秒限时运行正常退出；其 15 组隔离真机采集回归全部通过，包括空 span 包装及 TextLinks。因此不把“安装版本正确”当成开机采集已成功。

旧 `service.sh` 在 Magisk late_start 阶段立即执行一次 native。native 使用非等待的 `AServiceManager_checkService` 查找 clipboard/trust/activity/window/power，启动依赖缺少 Android 框架就绪门禁。此次状态未保留具体 Binder 子错误，不能证明究竟哪项服务在失败时未就绪；已确认的是早期开机失败、完整启动后成功及启动脚本缺失阶段约束。

0.4.2 只修改开机包装：等待 `sys.boot_completed=1`，最多 120 次一秒等待；等待前/中/后检查模块 disable/remove。随后 `exec` 一次 native，native 退出不再拉起。不等待用户解锁，锁屏仍由原生门禁暂停；不调用 `enable`，不修改偏好、历史、权限或 App 保活。首次关闭仍启动只读实例。ELF 与 0.4.1 完全相同。

构建和包检查通过：2,511 项 CTest 断言、11 项工程测试。新增工程测试包含已就绪、延迟就绪、超时、等待中停用/移除、启动前停用/移除七种情形，并验证 native 故障退出只启动一次。这七种脚本行为也在 K40 的 Android shell 执行通过；使用模拟的启动属性/时间及假 native，**不等于真实开机通过**。初次主机测试因 Windows 外部 cat 调用开销超过测试预算而失败，改用 shell 内建读取计数并隔离每个用例目录后全数通过，未降低断言或生产等待上限。

0.4.2 模块 ZIP SHA-256：`4ff415201a6a6bafd8e117235a4b80e3def9b035582615176b2cb2f54092e354`。实际重新开机、锁屏待机至解锁及 API 37 启动验证尚待完成，不能以模拟启动属性的脚本成绩替代。

正式来源 App 桥接回归发生 UI 启动超时，未完整通过。隔离进程的 LifecycleMonitor 显示脚本预先启动的 ComponentActivity 在测试规则启动时被销毁，前台回到 MIUI 桌面；尝试恢复裸 component 又被 ActivityScenario 以 Intent 不匹配拒绝。临时尝试匹配 MAIN/LAUNCHER/flags 的有界恢复后，仍遇到测试退出时 ActivityScenario 等待 DESTROYED 失败。已撤回这些前台恢复尝试，验证脚本保持原提交内容；不修改权限、不删除用例或弱化断言。前次失败证据单独保留。此前修复交付的 9 组桥接/UI 与 21 项普通回归仍是此前成绩，本次重启复验不能重复声称通过这些检查。

本轮结束时已停止隔离 App，通过正式 `service.sh` 恢复原日常采集器，确认 `running=true / enabled=true / capture_ready=true`，重新打开日常 SayIn；不改变 enabled 偏好，不向日常端点发送 LIST/GET/CLEAR。0.4.2 已传到手机 `Download/sayin-helper-0.4.2-dev-arm64.zip`，手机摘要匹配上文；仅修改开机等待脚本/版本，当前正式模块仍为 0.4.1，需用户管理器安装后再次重启。Android 产品源码和 APK 本轮未修改，桌面及其它任务未提交内容保持原样。
