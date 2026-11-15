# 43 · Kotlin / OkHttp 图片通道真机对照

日期：2026-11-14。状态：独立实验完成，生产通信协议及图片业务未修改。

后续：[44 全传输方案同期补测](44-kotlin-all-transports-wifi5-benchmark.md)已加入双 TLS 与 QUIC 1/2/4 流，384 次上传及 48 次取消通过。本文件保留当次事实；下文限速降低 p95 的观察未在后续 WSS/HTTPS 矩阵稳定复现，不能推广为固定60Mbps 的通用收益。

## 1. 结论

**使用项目相同版本的 Kotlin / OkHttp 后，没有测到 WSS＋HTTPS 相对双 WSS 的稳定性能优势。当前继续优先考虑双 WSS；对满载控制延迟，发送节奏比这两种承载的差异更值得继续验证。**

手机端为真正的 **Kotlin 2.4.21 源码、OkHttp 5.5.1、Android ART**，不再使用此前 Rust 手机客户端，也未使用 Java 源码。初始编写的 Java 实验客户端在正式采集前已删除；清理构建后 `compileJava NO-SOURCE`，正式数据均来自 Kotlin DEX。Kotlin 使用 JVM 标准库和 Android/OkHttp API，与编写 Java 源码是两回事。

四轮交错测试：八张原始 JPEG × 四种配置 × 四轮，共 **128 次上传、1,612,284,016 字节完整图片、11,978 个控制 RTT 样本**。128 次长度及 SHA-256 校验全部通过；每会话两条 socket 均复用。另有 16 次取消探针全部通过。

| 同期指标 | 双 WSS | WSS＋HTTPS | 双 WSS，60Mbps | WSS＋HTTPS，60Mbps |
| --- | ---: | ---: | ---: | ---: |
| 10.08MiB 中位耗时 | 1.070s | 0.999s | 1.415s | 1.416s |
| 25.17MiB 中位耗时 | 2.681s | 2.561s | 3.522s | 3.529s |
| 55.94MiB 中位耗时 | 5.426s | 5.837s | 7.832s | 7.832s |
| 大图控制 RTT p50 | 44.8ms | 45.4ms | 4.8ms | 4.9ms |
| 大图控制 RTT p95 | 89.7ms | 88.6ms | 39.8ms | 41.6ms |
| 大图控制 RTT p99 | 179.3ms | 181.8ms | 146.8ms | 201.7ms |
| 本地取消停止，中位数 | 0.45ms | 1.37ms | 0.33ms | 0.75ms |
| 服务端取消 ACK，中位数 | 43.84ms | 43.73ms | 3.74ms | 5.47ms |

不限速时两者控制 p95 只差约 1.1ms；HTTPS 在前两张大图略快，在最大图却慢约 7.6%。这不支持“HTTPS 一定更快”或“换 HTTP 就能解决满载排队”。四次重复不足以证明统计等价，也不能把差异都归因于协议。

双 WSS 增加 60Mbps 应用发送节奏后，控制 p95 从 89.7ms 降至 39.8ms，约降低 56%；最大图耗时增加约 44%。这证明本网络中存在可测量的吞吐/响应取舍，**不意味着产品应该写死 60Mbps**。限速组仍出现百毫秒级 p99/最大值，不能承诺稳定即时响应。

当前建议保持一个控制 WSS＋一个按需建立、有界发送的图片 WSS，不增加 raw TLS、QUIC 或单图并行。是否引入自动发送节奏，应在明确控制延迟目标后用不同链路验证；无需增加让用户选择协议或填写带宽的设置。本实验没有替换产品承载。

## 2. 实验边界与环境

| 项目 | 实际条件 |
| --- | --- |
| 基线与隔离 | `f9b52bec3513515d14be3ebd75fac1946120e7b3`；原 `codex/image-network-bench` 独立 worktree |
| 手机 | Redmi K40 / M2012K11AC，Android 13 / API33 |
| 客户端 | Kotlin 2.4.21、OkHttp 5.5.1、Okio 3.18.1；ART `app_process` / shell UID2000 |
| 构建 | Temurin 25.0.5+8、Gradle 9.7.0、JVM17、Android37 编译 API、D8 |
| Wi-Fi | Wi-Fi5、5825MHz、Tx/Rx 协商 192Mbps，开始/结束 RSSI 均约 -53dBm |
| 电脑 | Windows 千兆以太网，精确绑定 `192.168.71.6:59787` |
| 手机绑定 | 精确绑定 Wi-Fi IPv4 `192.168.71.72`，不经过代理或 ADB 隧道 |
| 安全与 HTTP | TLS1.3；完整 DER 固定、有效期及默认主机名校验；HTTP1.1；禁自动重试及跳转 |
| 服务端 | 独立 Rust 1.99.1 release，rustls/ring；边读边 SHA-256，正文丢弃 |
| Windows 范围 | 原网络为 Public；三类防火墙测试前后原本关闭，未改网络类别/防火墙 |
| 电源 | USB 供电；最后一轮中电池温度 29.7°C；不作为功耗测试 |

首次、二次实验见 [41](41-image-transport-wifi5-benchmark.md)、[42](42-double-wss-wifi5-benchmark.md)。它们的手机客户端为 Rust CLI，不能拿其数字与本次 Kotlin/OkHttp 数字直接计算协议提升。本次所有比较只取本次同期四轮数据。

实验客户端不安装 APK、不接触生产应用配置、配对、草稿、历史或 Outbox。两条连接通过本次临时 token/session 关联，完整 TLS 校验完成后才发送认证头；每次实验使用独立随机证书及端口。它不代替生产 host/device 认证、撤销、网络范围与生命周期测试。

## 3. 两种承载与采集方法

- 双 WSS：控制承载 START/READY、echo、摘要 DONE 与取消；图片承载 48KiB 二进制正文及结束标记。调用 `send` 前检查 `queueSize`，待发预算约两块，即 98,316 字节。send 成功或 queueSize 归零不算接收成功。
- WSS＋HTTPS：控制完全相同；图片用固定 Content-Length、`isOneShot=true` 的 `RequestBody.writeTo` 流式 PUT。先通过 `/health` 预建独立 keepalive 图片连接，逐图复用，不将每图握手混入传输耗时。
- 两者都不使用 Base64、不全量读图、不并行传图片，不使用 HTTP2 多路复用；每会话严格检查只创建两个 socket。计数位于自定义 SocketFactory，避免 EventListener 未覆盖 WebSocket 导致漏计。
- 两种限速组均按已写字节与单调时钟控制发送节奏；初始分块突发、内核缓冲与已有在途数据仍存在。这是应用节奏控制，未对网络链路做整形，也不是自动估算带宽。
- 耗时从发送 START 之前到收到服务端摘要 DONE 且本地发送任务/HTTP 响应完成；准备及握手另记。服务端只确认完整长度和 SHA-256，不含资源加密落盘、fsync、解码、剪贴板及真实目标粘贴。
- 控制 echo 约每 20ms 一次，同一手机单调时钟测 RTT。负载大图指三张 ≥10MiB 图片；表中大图百分位不包含取消探针。每轮样本相关，不能把数千次 echo 当成独立网络环境。

模式按轮次轮换，每种配置在四个位置各出现一次；奇数轮从小图到大图，偶数轮倒序。每个模式先等待 1.5 秒、传八图、等待 1 秒、运行一次取消探针；会话间休息 250ms。一次独立进程先完成四模式 smoke，再由新进程执行正式四轮。未人为清缓存、锁频或控制邻居网络流量。

## 4. 全样本与轮间波动

四次中位耗时，单位 ms；偶数样本取中间两次平均。

| 原图实际大小 | 双 WSS | WSS＋HTTPS | 双 WSS，60Mbps | WSS＋HTTPS，60Mbps |
| --- | ---: | ---: | ---: | ---: |
| 0.36MiB | 46.8 | 54.3 | 56.8 | 62.9 |
| 0.50MiB | 56.4 | 60.2 | 75.6 | 77.3 |
| 0.80MiB | 85.6 | 94.8 | 117.1 | 120.5 |
| 1.21MiB | 121.3 | 130.4 | 173.3 | 176.3 |
| 2.04MiB | 202.1 | 223.5 | 290.1 | 291.8 |
| 10.08MiB | 1069.9 | 998.8 | 1415.1 | 1415.9 |
| 25.17MiB | 2680.9 | 2560.9 | 3522.3 | 3529.0 |
| 55.94MiB | 5426.4 | 5836.6 | 7832.0 | 7831.6 |

各轮大图控制 RTT p95，单位 ms：

| 轮次 | 双 WSS | WSS＋HTTPS | 双 WSS，60Mbps | WSS＋HTTPS，60Mbps |
| --- | ---: | ---: | ---: | ---: |
| 1 | 92.5 | 95.5 | 35.2 | 34.1 |
| 2 | 93.4 | 87.1 | 27.8 | 35.5 |
| 3 | 92.0 | 80.5 | 40.6 | 51.1 |
| 4 | 78.9 | 93.2 | 57.1 | 46.9 |

两种承载的控制 p95 没有稳定胜负；两种承载各自四轮的限速组 p95 均低于对应不限速组。但没有队列/无线/CPU 的分层 profiling，不能证明具体在哪一层排队，也不能直接归咎于 WebSocket 掩码。

## 5. 内存与取消

每 100ms 采样本进程 RSS、ART 已用堆与 native heap，每图开始重置采样峰值；表为所有该模式上传的最大采样值，单位 MiB：

| 配置 | RSS | ART 已用堆 | native heap | 图片 WSS 队列峰值 |
| --- | ---: | ---: | ---: | ---: |
| 双 WSS | 130.01 | 30.26 | 4.91 | 98,316 字节 |
| WSS＋HTTPS | 137.26 | 24.58 | 3.96 | 不适用 |
| 双 WSS，60Mbps | 140.90 | 28.16 | 3.61 | 98,316 字节 |
| WSS＋HTTPS，60Mbps | 124.04 | 18.91 | 3.99 | 不适用 |

绝对 RSS 包含 ART、依赖、线程与先前会话；GC 及采样间隔影响结果。HTTP 没有图片 WebSocket 队列不代表没有 Okio/TLS/内核缓冲。这组数据只提供运行观察，**不是严格瞬时峰值、协议独占内存或正式 App 内存结论**。不能依据表格宣称 HTTPS 内存一定更低。两者均未全量缓存原图。

取消探针：最大图开始发送 200ms 后，停止本地 producer/Call 或图片 WebSocket，再通过保留的控制 WSS 发送 CANCEL。服务端移除任务并冻结已接受计数，返回 ACK；300ms 后 STATUS 验证计数不增长，无 DONE。它证明服务器取消后不继续接受这张图，不证明网络中没有残留字节，也未实现生产资源/配额回收。

正式 16 次取消均满足上述断言；本地停止全部 <4ms；取消 ACK 最大值为双 WSS 70.97ms、WSS＋HTTPS 49.80ms、两者限速版分别 5.10/10.50ms。每组只有四次，p95 就是最大值，不作为稳定尾延迟承诺。未覆盖取消恰逢最后字节、落盘提交或生产输入执行的竞态。

## 6. 修改与验证

修改前独立 Rust 工具六项测试；本次新增有界 HTTP1.1 实验入口、取消/查询命令与 Kotlin 客户端，不改生产代码。初始 Java 客户端没有本轮有效性能结果，已由 Kotlin 完整替换。

实际执行：

```powershell
# scripts/image-transport-bench
cargo +1.99.1 test --locked
cargo +1.99.1 clippy --all-targets --locked -- -D warnings
cargo +1.99.1 fmt --package sayin-image-transport-bench -- --check
cargo +1.99.1 build --release --locked

# scripts/okhttp-image-bench，JAVA_HOME 使用项目指定 Temurin25
& ../../android/gradlew.bat -p . clean dex --console=plain
```

八项 Rust 测试全部通过，含旧分区/完整性/有界消息/三种 TCP 承载集成回归，以及新增重复关键 HTTP 头拒绝、取消冻结接受计数；Clippy、包限定格式及 release 构建通过。Kotlin 编译启用 warnings as errors，clean DEX 构建通过；Java 源码不存在。Python 两个分析脚本编译检查、完整矩阵校验及取消断言通过，归档 gzip 复算与原结果一致。

真机预检额外通过四次首图上传及四次取消，不混入 128 次正式矩阵。初次预检发现 EventListener 计数漏掉 WebSocket，修正计数后重新预检，再开始正式采集；没有以放宽连接数断言让错误数据通过。

未改生产 Android/desktop 源码，未运行无关正式 App/桌面构建来代替图片验收。服务端是独立有界实验 HTTP 解析器，不能直接移入产品作为生产 HTTP 服务器。

## 7. 证据与复算

- [Kotlin 客户端与复跑说明](../scripts/okhttp-image-bench/README.md)
- [完整原始 JSONL，gzip](benchmarks/2026-11-14-okhttp-kotlin/results.jsonl.gz)、[smoke 数据](benchmarks/2026-11-14-okhttp-kotlin/smoke.jsonl.gz)
- [完整汇总](benchmarks/2026-11-14-okhttp-kotlin/summary.json)、[取消/内存/逐轮统计](benchmarks/2026-11-14-okhttp-kotlin/comparison.json)
- [环境](benchmarks/2026-11-14-okhttp-kotlin/environment.json)、[原图长度及摘要](benchmarks/2026-11-14-okhttp-kotlin/samples.json)、[归档 SHA-256](benchmarks/2026-11-14-okhttp-kotlin/sha256.json)
- [当次 Kotlin 源码](benchmarks/2026-11-14-okhttp-kotlin/source/scripts/okhttp-image-bench/src/main/kotlin/seeyuer/sayin/bench/Bench.kt)及 source 下的 Rust、Gradle、Cargo 锁文件与分析器快照

```powershell
python scripts/image-transport-bench/summarize.py docs/benchmarks/2026-11-14-okhttp-kotlin/results.jsonl.gz --expect-rounds 4 --expect-modes wss-split,https-stream,wss-split-60,https-stream-60 --expect-files 8 --output summary-recomputed.json
python scripts/okhttp-image-bench/analyze.py docs/benchmarks/2026-11-14-okhttp-kotlin/results.jsonl.gz --rounds 4 --output comparison-recomputed.json
```

采集前后 ADB forward/reverse 均为空；未发出增删隧道命令。服务已正常结束，手机本次独立目录及本机临时 token 配置已清理，原图和日常配对保留。公开归档不含 config.json、token、证书私钥、图片正文或 Wi-Fi 名称/MAC。

尚未验证：target37 App 本地网络运行时授权、界面/前后台生命周期、后台断线恢复、开启防火墙、切网/多网卡、丢包/弱信号、长期功耗、生产身份撤销、受保护图片落盘、解码、剪贴板、真实应用粘贴。**本次网络摘要确认耗时仍不等于图片上屏耗时。**
