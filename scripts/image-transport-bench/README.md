# 图片局域网传输实验

这是独立 Rust CLI，不接入 SayIn 生产协议、UI、凭据和输入执行器。不安装或更新手机 App。只将测试文件发到指定电脑的精确 IPv4，接收端边读边计算 SHA-256 后丢弃。不要用它声称图片已经在目标应用粘贴成功。

## 复跑

工具链 Rust 1.99.1；`Cargo.lock` 固定实验依赖。Windows/Android 都使用 ring + rustls TLS 1.3，禁用 0-RTT。无 Java/Gradle 依赖，Android CLI 使用 NDK API 26 交叉链接；这是 shell 进程实验，不代替 target37 应用网络授权测试。

```powershell
cd scripts/image-transport-bench
cargo +1.99.1 test --locked
cargo +1.99.1 clippy --all-targets --locked -- -D warnings
cargo +1.99.1 fmt --package sayin-image-transport-bench -- --check
cargo +1.99.1 build --release --locked
$ndk = "$env:LOCALAPPDATA/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$ndk/aarch64-linux-android26-clang.cmd"
$env:CC_aarch64_linux_android = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$env:AR_aarch64_linux_android = "$ndk/llvm-ar.exe"
cargo +1.99.1 build --release --target aarch64-linux-android --locked
```

以上环境变量仅在专用构建终端设置；不要修改系统工具链或生产 Android 构建设置。

电脑启动（将示例 IPv4 换成当前真实接口；不能填写 0.0.0.0）：

```powershell
./target/release/sayin-image-transport-bench.exe server 192.168.71.6 D:/100_Projects/110_Daily/VoiceInput/refer/image-test ./run-lan
```

服务仅绑定给定 IPv4，TCP/UDP 端口由系统动态分配，最多运行 30 分钟。在 `run-lan/stop` 创建文件可提前结束。每次复跑使用全新目录（旧 stop 文件会使服务立即退出）。不要修改防火墙或 Windows 网络类别来伪装可达。

经 ADB 将 Android 二进制、8 张原图和 `run-lan/config.json` 复制至手机 `/data/local/tmp/sayin-image-bench-<本次唯一标记>/`，其中图片放在 `images/`。`config.json` 含本次临时固定证书、随机认证 token、端口及样本清单，权限设为 600，不能提交 Git 或公开。服务器私钥只在电脑内存中。令牌仅在验证临时证书及完整 DER 后发送。准备阶段的 ADB 复制不属于测速；不配置 ADB reverse/forward。

```sh
chmod 700 bench
chmod 700 . images
chmod 600 config.json
./bench client config.json images 192.168.71.72 3 results.jsonl
```

可选最后一个参数选择 `wss`、`wss-split`、`tls-split`、`quic-1`、`quic-2` 或 `quic-4`，支持逗号分隔选择多种；未指定则运行全部六种。手机显式绑定自己的 Wi-Fi IPv4。每轮在所选配置内轮换顺序，偶数轮倒序传图片。输出开始至收到所有分区校验完成确认的耗时；会话建立耗时另记。每个配置前/后各采集 1.5/1 秒空闲控制 echo，负载期约 20ms 一次，记录同一手机单调时钟的 RTT。

双 WSS 同期对照使用：

```sh
./bench client config.json images 192.168.71.72 6 results.jsonl wss,wss-split,tls-split
```

对应的严格完整性检查：

```powershell
python summarize.py ./run-lan/results.jsonl --expect-rounds 6 --expect-modes wss,wss-split,tls-split --expect-files 8 --output ./run-lan/summary.json
```

结束后将 `results.jsonl` 拉回电脑，运行：

```powershell
python summarize.py ./run-lan/results.jsonl --expect-rounds 3 --expect-modes wss,wss-split,tls-split,quic-1,quic-2,quic-4 --expect-files 8 --output ./run-lan/summary.json
```

保留脱敏的结果、样本清单和环境摘要；关闭本次服务并确认退出，删除手机本次测试目录及本机临时认证配置。不要删除用户图片、生产配对、草稿、账本或其他测试会话。

## 可比较的范围

- WSS：一条连接，共享控制与图片，图片为 48 KiB 二进制消息，不沿用文字提交限流。
- WSS split：两条独立 WSS 连接，一条控制、一条图片；两条都固定证书并使用同一临时 session/token 认证。图片为二进制 WebSocket 消息，不用 Base64；控制连接接收最终摘要确认。
- TLS split：两条连接，一条控制、一条图片，均 TLS 1.3。
- QUIC：一条连接，一条控制双向流 + 1/2/4 条图片双向流；控制发送优先级 10，图片为 0。
- 所有方案流式读取，图片应用发送队列每流两块；控制队列 32 项且优先于尚未发出的图片块。raw framing 长度上限 48 KiB + 32 字节，读数据前检查。
- QUIC 每流接收窗口 2 MiB、连接窗口/发送窗口 8 MiB，使用 Quinn 默认拥塞控制；TCP 使用系统默认缓冲/拥塞控制，TCP_NODELAY。**不声称内核 TCP 缓冲与 QUIC 窗口完全相同**。并行方案应用队列总量随流数增加。
- 分区为连续不重叠区间，1/2/4 分区均提前计算摘要；服务端按可信清单累计长度和摘要，全部匹配才确认。照片全量 SHA-256 用于样本识别，多流接收校验的是完整覆盖原图的各分区摘要。
- 单条控制消息的 RTT 包含队列、TLS/QUIC、无线传输及程序调度，不是单向网络延迟。小图样本较少；用大图期间的 p95/p99 判断控制阻塞，不能将小样本 p99 视为稳定承诺。
- 不含真实选图、编码压缩、加密资源落盘/fsync、解码、缩略图、剪贴板、实际输入、App/JNI 生命周期或长期功耗。接收完整性通过不等于完整图片功能实现。

初始结果与约束见 [41 实测记录](../../docs/41-image-transport-wifi5-benchmark.md)。

双 WSS 同期六轮补测见 [42 补测记录](../../docs/42-double-wss-wifi5-benchmark.md)。复跑初次五模式实验时，客户端最后参数显式指定 `wss,tls-split,quic-1,quic-2,quic-4`，汇总的 `--expect-modes` 使用同一列表；初始记录和补测均归档了当次精确 Rust 源码及 Cargo.lock，可在独立目录恢复。原始数据复算仍支持 `.jsonl` 和 `.jsonl.gz`。

## Kotlin / OkHttp 补测入口

当前工具另提供 `server-okhttp BIND_IPV4 IMAGE_DIR RUN_DIR`，在精确 IPv4 的单个随机 TCP 端口提供 TLS1.3 下的控制 WSS、图片 WSS 和 HTTP1.1 流式 PUT；这是实验入口，不是生产 HTTP 服务器。配套手机客户端已使用 Kotlin / Android ART / OkHttp，见 [客户端构建及复跑](../okhttp-image-bench/README.md)及 [43 实测记录](../../docs/43-kotlin-okhttp-image-upload-benchmark.md)。上文“无 Java/Gradle 依赖”仅适用于原 Rust 客户端，不能用于描述该 Kotlin 补测。

`server-all BIND_IPV4 IMAGE_DIR RUN_DIR` 在相同临时身份/清单下增加独立 raw TLS TCP 监听和 QUIC UDP endpoint，支持 Kotlin 全矩阵及取消/查询；三个端口均精确绑定给定 IPv4，最长一小时，stop 文件可退出。QUIC ALPN 为 sayin-bench。当前九项测试含取消迟到分块回归；完整同期真机结果见 [44 全传输对照](../../docs/44-kotlin-all-transports-wifi5-benchmark.md)。首次/二次实验和 43 的精确源码在各归档目录内，复跑历史数据不要假设当前工具默认矩阵仍相同。
