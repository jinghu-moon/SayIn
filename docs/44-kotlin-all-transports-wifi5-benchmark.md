# 44 · Kotlin 全传输方案 Wi-Fi 5 真机对照

日期：2026-11-14。状态：独立真机网络实验完成，生产 App、配对协议与图片输入功能未修改。

## 1. 结论

**双 raw TLS 和原始 QUIC 1/2/4 图片流均已实际补测，未发现一个方案在吞吐、控制尾延迟和取消上全面占优。单图增加 QUIC 并行流没有线性提速。**

手机编排、发送与测量全部使用 Kotlin 2.4.21；WSS/HTTPS 用 OkHttp 5.5.1，双 TLS 用 Android SSLSocket，QUIC 用 Kwik 0.12.2 / Agent15 4.0。QUIC 是实际 UDP 上的 QUIC v1＋TLS1.3，不是用 TCP、HTTP2 或网关模拟。

六种方案各有不限速及总计 60Mbps 两档，八张原始 JPEG、四轮交错测试，共 **384 次完整上传、4,836,852,048 字节图片、39,347 个控制 RTT 样本**。全部长度及 SHA-256 校验通过；另有 **48 次取消探针全部通过**。没有正式上传错误、超时、连接数断言失败或正式服务端错误日志。

以下均为本次同期不限速结果，耗时为四次中位数：

| 方案 | 10.08MiB | 25.17MiB | 55.94MiB | 大图控制 RTT p95 | 大图控制 RTT p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 双 WSS | 1.356s | 3.663s | 7.428s | 81.9ms | 178.9ms |
| WSS＋HTTPS | 1.271s | 2.952s | 6.079s | 100.0ms | 352.0ms |
| 双 raw TLS | 1.067s | 2.698s | 6.787s | 135.3ms | 236.9ms |
| QUIC，1 图片流 | 1.178s | 3.355s | 7.424s | 90.8ms | 231.0ms |
| QUIC，2 图片流 | 1.147s | 3.165s | 6.940s | 86.7ms | 232.0ms |
| QUIC，4 图片流 | 1.256s | 3.344s | 6.969s | 91.0ms | 219.8ms |

本轮最大图是 WSS＋HTTPS 最快，前两张大图是双 TLS 最快，而不限速控制 p95 最低的是双 WSS。双 TLS 不能被描述为控制延迟必然更低，QUIC 也没有稳定的吞吐优势。QUIC 两流的最大图比单流快约 6.5%，四流没有继续改善；并行的缓冲和线程预算同时增加，不能仅归因于协议。

当前仍可将双 WSS 作为减少实现复杂度的首版候选，但理由应是复用及维护成本，**不能称为所有指标最优**。这组测试没有证明为了吞吐或响应必须引入 QUIC/raw TLS，也没有证明所有 QUIC 实现都不值得采用。生产承载没有在本次实验中定案或替换。

## 2. 环境与比较边界

| 项目 | 实际条件 |
| --- | --- |
| 基线 | `f9b52bec3513515d14be3ebd75fac1946120e7b3`，继续原 `codex/image-network-bench` 独立 worktree |
| 手机 | Redmi K40 / M2012K11AC，Android13 / API33，ART `app_process` / shell UID2000 |
| Wi-Fi | Wi-Fi5，5825MHz，Tx/Rx 协商 192Mbps；开始/结束约 -54dBm |
| 手机绑定 | 精确 Wi-Fi IPv4 `192.168.71.72` |
| 电脑 | Windows 千兆以太网，精确 IPv4 `192.168.71.6` |
| 独立端口 | HTTP/WSS TCP 49579，raw TLS TCP 49580，QUIC UDP 53091 |
| 构建 | Temurin25.0.5+8、Gradle9.7.0、Kotlin2.4.21、JVM17、Android37 编译 API、D8 |
| 库 | OkHttp5.5.1 / Okio3.18.1；Kwik0.12.2 / Agent15 4.0；服务器 Rust1.99.1 release、Quinn0.11.12 / rustls |
| Windows 范围 | 原网络 Public；三类防火墙测试前后原本关闭，未改网络类别或防火墙 |
| 电源 | USB 供电；初期/结束电池约 24.5/25.7°C，不属于功耗测试 |

原图未修改，合计 100,767,751 字节；最大图 58,658,641 字节，约 55.94MiB。服务端边读边计算摘要后丢弃，没有图片解码、受保护落盘、fsync、剪贴板及真实粘贴。

仅比较本次同期数据。不能与 [41](41-image-transport-wifi5-benchmark.md)、[42](42-double-wss-wifi5-benchmark.md) 的 Rust 客户端或 [43](43-kotlin-okhttp-image-upload-benchmark.md) 的早先四模式数字混算提升。尤其 43 中限速改善控制 p95 的观察，本轮 WSS/HTTPS 没有重复得到；保留真实差异，不择优引用。

本实验比较可在 Kotlin 手机端调用的**具体库实现**，不是只替换协议头的实验。Kwik 默认 NewReno、50KiB/流发送缓冲；图片流数增加也增加总缓冲和 producer 线程。此处未配置专用控制流优先级，使用库默认调度；原 Rust Quinn 客户端的调度/缓冲不同。Android TLS、OkHttp、Kwik 的加密、拥塞控制、调度及内部缓冲不等价，不能将差异全部归为语言、WebSocket 掩码或 QUIC 协议本身。

## 3. 安全、承载与测量

全部使用同一临时证书、随机 token/session、可信图片清单，验证完整 DER、有效期及 `bench.sayin.local` 主机名后才发送认证。TLS1.3，HTTP1.1；禁自动重试、跳转、0-RTT 和票据恢复。每次启动独立身份与数据目录，不读取日常配对、草稿、Outbox、历史或云凭据。

Kwik 的 `proxy(ip)` API 在这里是直接 UDP 目的地址覆盖；源码确认它保留原 host 用于 SNI/主机名校验，只将 socket 目的地换为可信测试 IP。没有代理服务器、隧道或公网中转，也没有调用跳过证书/主机名校验。QUIC ALPN 为 `sayin-bench`。

共用一个 Kotlin `ExperimentSession` 统一控制 echo、时间窗口、读取、分区、完成判定、采样和取消，仅承载写入及封装不同：

- 双 WSS：两条连接，图片正文 48KiB/二进制消息，待发预算约两块（98,316 字节）；不使用 Base64，`send` 成功或 `queueSize=0` 不算接收完成。
- WSS＋HTTPS：控制 WSS 不变；图片用固定 Content-Length、一次性 `RequestBody.writeTo` 流式 PUT，预建独立 keepalive 连接，跨图片复用。
- 双 TLS：两条 TLS/TCP，四字节有界长度头＋相同二进制消息，流式写入，不全量缓存。
- QUIC：一条 UDP 连接、一条控制双向流及 1/2/4 条图片双向流；采用相同长度帧。并行分区为连续、不重叠、完整覆盖原图的区间，所有分区长度与 SHA-256 匹配才确认 DONE。

三种 TCP 模式逐图断言 socket 数为 2，QUIC 为 1，跨图片复用。耗时从 START 前到摘要 DONE 且全部 producer/HTTP 响应结束，握手和准备另记。控制约每 20ms 一个 echo，用手机同一单调时钟；每 100ms 采样内存。

模式列表为六种不限速后接六种限速，四轮分别从第 1/4/7/10 项开始；奇数轮小图到大图、偶数轮相反。四轮没有覆盖所有 12 个起始位置，时间漂移和位置偏差未完全消除。每会话先空闲 1.5 秒、传八图、空闲 1 秒、取消探针、300ms 观察，间隔 250ms。未控制邻居设备、背景流量或人工锁频，不做统计等价性声明。

## 4. 限速与控制延迟

60Mbps 是应用按累计字节/单调时钟控制的**总上限**，QUIC 并行流共用预算，不能变成每流 60Mbps。它不是整机 QoS、固定可用带宽或自适应算法；初始分块突发、停顿后的累计预算、在途字节与内核缓冲仍存在，链路也可能达不到上限。

| 方案，均总计 60Mbps | 55.94MiB 中位耗时 | 控制 p50 | 控制 p95 | 控制 p99 |
| --- | ---: | ---: | ---: | ---: |
| 双 WSS | 8.003s | 8.8ms | 99.3ms | 163.3ms |
| WSS＋HTTPS | 7.860s | 13.2ms | 119.0ms | 242.2ms |
| 双 raw TLS | 7.838s | 8.9ms | 56.4ms | 116.2ms |
| QUIC，1 流 | 7.960s | 20.5ms | 79.3ms | 188.6ms |
| QUIC，2 流 | 7.940s | 14.1ms | 68.0ms | 199.7ms |
| QUIC，4 流 | 7.862s | 14.5ms | 71.2ms | 211.7ms |

本轮双 TLS 的限速组控制尾延迟改善明显，QUIC 各组 p95 也下降；双 WSS/HTTPS 虽然控制中位数下降，p95 却上升。**不能把固定限速当作可靠解决全部网络尾延迟的方案，也不能依据 43 的一轮环境将 60Mbps 写死到产品。** 尚无用户确认的控制延迟 SLO，不替用户定义哪一档合格。

下列百分位只取三张 ≥10MiB 完整上传期间，不包含取消探针；nearest-rank。每轮内多次 echo 相关，不能当成数千个独立网络环境。逐轮大图 p95，单位 ms：

| 模式 | 第1轮 | 第2轮 | 第3轮 | 第4轮 |
| --- | ---: | ---: | ---: | ---: |
| 双 WSS | 59.4 | 88.0 | 81.1 | 94.2 |
| WSS＋HTTPS | 135.8 | 97.0 | 82.8 | 94.8 |
| 双 TLS | 161.9 | 49.6 | 52.7 | 120.4 |
| QUIC 1 | 106.4 | 50.7 | 80.4 | 112.9 |
| QUIC 2 | 101.5 | 78.5 | 69.8 | 77.9 |
| QUIC 4 | 94.8 | 86.9 | 101.3 | 78.0 |
| 双 WSS，60 | 125.2 | 58.9 | 44.2 | 83.0 |
| WSS＋HTTPS，60 | 107.4 | 134.3 | 92.1 | 66.8 |
| 双 TLS，60 | 67.5 | 59.3 | 31.3 | 31.4 |
| QUIC 1，60 | 101.2 | 69.6 | 68.1 | 50.4 |
| QUIC 2，60 | 96.3 | 35.2 | 43.4 | 59.9 |
| QUIC 4，60 | 106.0 | 38.4 | 83.2 | 51.2 |

轮间差异较大。例如双 TLS 第一/四轮尾延迟比第二/三轮高，不能挑后两轮宣布 raw TLS 控制一定更快。完整逐图中位/min/max、空闲 RTT、连接准备及采样计数均在 summary.json。

## 5. 取消与内存

每会话八图结束后重传最大图，所有 producer 进入后等待 200ms，再取消图片发送；保留控制连接/流发送 CANCEL。QUIC 取消全部图片流，保留同一连接的控制流。服务器在统一锁顺序内移除任务、冻结接受字节；300ms 后 STATUS 必须计数不增长且无 DONE。迟到 DATA/END 不得重建或完成任务，QUIC 并行流统计为全部流的总量。

以下为取消 ACK 中位/最大值，单位 ms；每格只有四次，最大值不是可靠尾延迟承诺。

| 方案 | 不限速，中位/最大 | 总60Mbps，中位/最大 |
| --- | ---: | ---: |
| 双 WSS | 68.36 / 101.59 | 4.58 / 38.95 |
| WSS＋HTTPS | 80.80 / 91.11 | 16.19 / 78.91 |
| 双 TLS | 38.73 / 85.96 | 7.60 / 70.68 |
| QUIC 1 | 14.58 / 24.24 | 10.90 / 23.94 |
| QUIC 2 | 28.11 / 149.63 | 10.92 / 20.08 |
| QUIC 4 | 17.20 / 24.52 | 12.67 / 28.32 |

48 次正式探针全部通过；全部 producer 本地退出最大约 2.03ms，精确值见 comparison.json。本地停止不等于空中没有残留字节；测试未覆盖取消恰逢最后字节、持久化或输入执行的所有竞态。

每图重置采样峰值；下表为所有对应模式上传观察到的最大采样值（MiB）：

| 方案 | 不限速 RSS / ART堆 / native堆 | 总60Mbps RSS / ART堆 / native堆 |
| --- | ---: | ---: |
| 双 WSS | 151.02 / 28.14 / 4.96 | 143.95 / 28.74 / 4.00 |
| WSS＋HTTPS | 146.77 / 27.17 / 4.00 | 142.31 / 26.59 / 3.92 |
| 双 TLS | 143.80 / 30.49 / 3.90 | 143.80 / 26.84 / 3.78 |
| QUIC 1 | 133.15 / 30.54 / 3.76 | 138.79 / 30.15 / 3.84 |
| QUIC 2 | 139.22 / 31.03 / 3.83 | 144.59 / 31.46 / 3.80 |
| QUIC 4 | 141.22 / 31.31 / 3.88 | 137.09 / 30.75 / 3.89 |

采样是同一进程轮换的绝对值，包含 ART、依赖、线程及前序会话，受 GC 和 100ms 间隔影响；不是严格瞬时峰值、协议独占内存或完整 App 内存。WSS 图片待发队列峰值不超过 98,316 字节，但不含库/内核缓冲；其他承载该字段为零也不代表无缓冲。不能依据绝对 RSS 宣称某协议内存一定更省。

## 6. 实施与验证

修改前独立 Rust 工具八项测试、Kotlin 四模式原始证据均保留。本次抽出公共 Kotlin 测量会话，新增双 TLS 和 QUIC；服务器 `server-all` 复用临时身份与任务语义，精确绑定两个 TCP 端口及一个 UDP endpoint。原实验入口保留用于复跑，不修改生产通信协议、依赖或输入语义。

QUIC 首次预检发现 D8 不包含 `META-INF/services`，Agent15 无法加载密钥交换实现。修复构建资源合并后，真实 UDP 握手、完整证书固定、认证及 echo 通过；未关闭验证或替换协议。失败预检不计入性能矩阵。12 次首图上传＋12 次取消预检通过，再启动新进程、新服务身份/端口跑正式四轮；预检服务是在补充迟到分块原子保护前，正式服务包含此保护，回归及正式取消均通过。

实际执行并通过：

```powershell
# scripts/image-transport-bench
cargo +1.99.1 test --locked
cargo +1.99.1 clippy --all-targets --locked -- -D warnings
cargo +1.99.1 fmt --package sayin-image-transport-bench -- --check
cargo +1.99.1 build --release --locked
# scripts/okhttp-image-bench，JAVA_HOME 使用项目 Temurin25
& ../../android/gradlew.bat -p . clean dex --write-locks --console=plain
```

九项 Rust 测试通过，包含原有分区覆盖、所有分区摘要、消息边界及三种 TCP 集成回归，新增迟到 DATA/END 在取消后不计数/不完成/不重建。Clippy `-D warnings`、包限定格式及 release 构建通过。Kotlin warnings as errors、clean DEX 构建通过，`compileJava NO-SOURCE`。Python 严格检查 12 模式×8文件×4轮、连接复用、流数和取消断言；归档 gzip 复算及摘要校验通过；缺上传、错流数、额外连接、取消后字节增长的负例均拒绝。前三轮实验归档仍能精确复算。

Kwik/Agent15 为 LGPL-3.0 依赖，仅加入独立实验，未加入正式 App。编写的客户端源码全部 Kotlin，没有复制其 Java 实现。[Kwik0.12.2](https://github.com/ptrd/kwik/tree/v0.12.2)、[客户端接口](https://github.com/ptrd/kwik/blob/v0.12.2/core/src/main/java/tech/kwik/core/QuicClientConnection.java)、[默认发送缓冲](https://github.com/ptrd/kwik/blob/v0.12.2/core/src/main/java/tech/kwik/core/stream/SendBuffer.java)、[默认拥塞控制](https://github.com/ptrd/kwik/blob/v0.12.2/core/src/main/java/tech/kwik/core/send/SenderImpl.java)为 API/默认配置依据。

## 7. 证据、复跑与限制

- [构建和复跑说明](../scripts/okhttp-image-bench/README.md)
- [正式原始 JSONL，gzip](benchmarks/2026-11-14-kotlin-all-transports/results.jsonl.gz)、[12模式预检](benchmarks/2026-11-14-kotlin-all-transports/smoke.jsonl.gz)
- [完整汇总](benchmarks/2026-11-14-kotlin-all-transports/summary.json)、[逐轮/取消/内存](benchmarks/2026-11-14-kotlin-all-transports/comparison.json)
- [环境及二进制摘要](benchmarks/2026-11-14-kotlin-all-transports/environment.json)、[样本摘要](benchmarks/2026-11-14-kotlin-all-transports/samples.json)、[归档 SHA-256](benchmarks/2026-11-14-kotlin-all-transports/sha256.json)
- source 下保留本次精确 Kotlin/Rust 源码、构建配置、Cargo/Gradle 锁文件及分析器。

```powershell
$benchModes = 'wss-split,https-stream,tls-split,quic-1,quic-2,quic-4,wss-split-60,https-stream-60,tls-split-60,quic-1-60,quic-2-60,quic-4-60'
python scripts/image-transport-bench/summarize.py docs/benchmarks/2026-11-14-kotlin-all-transports/results.jsonl.gz --expect-rounds 4 --expect-modes $benchModes --expect-files 8 --output summary-recomputed.json
python scripts/okhttp-image-bench/analyze.py docs/benchmarks/2026-11-14-kotlin-all-transports/results.jsonl.gz --rounds 4 --modes $benchModes --output comparison-recomputed.json
```

采集前后 ADB forward/reverse 为空；未发出增删隧道命令。正式服务正常退出；手机本次目录、本地临时认证配置已清理，原图与日常数据保留。归档不含 config.json、token、图片正文、证书私钥、Wi-Fi 名称或 MAC。

限制：未控制其他无线流量，未测试人工丢包、弱信号、切网/多网卡、开启防火墙、QUIC 移动性、长期功耗或其他 QUIC 实现。ART/shell API33 不能验证 targetSdk37 正式 App 的权限与前后台生命周期；生产身份撤销、受保护落盘/fsync、解码、剪贴板及目标应用粘贴仍未验证。**网络摘要完成仍不等于图片上屏。**
