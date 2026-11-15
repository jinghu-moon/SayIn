# Kotlin 图片通道对照

独立 Kotlin 2.4.21 客户端，实际运行在手机 Android ART 上。WSS/HTTPS 使用项目相同的 OkHttp 5.5.1（实验解析到 Okio 3.18.1），双 raw TLS 使用 Android SSLSocket，原始 QUIC 使用 Kwik 0.12.2 / Agent15 4.0。所有实验编排及发送代码都是 Kotlin；没有 Java 源码客户端。不安装 APK，不读取正式应用、配对、草稿、账本或云服务配置。不是生产图片功能。

## 构建

在本目录使用项目 Gradle 9.7.0 Wrapper 与 Temurin 25.0.5+8：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot'
& ../../android/gradlew.bat -p . clean dex --console=plain
```

`sdkRoot` 默认当前电脑的 Android SDK 路径，可通过 `-PsdkRoot=...` 指定。编译使用 Android 37 API、JVM 17 字节码和 D8；实验实跑 API33。依赖由 `gradle.lockfile` 固定，Kotlin 插件精确指定 2.4.21。仅在明确更新依赖时增加 `--write-locks`。

D8 不会自动打包依赖资源；dex 任务合并 `META-INF/services`，保留 Agent15 密钥交换 SPI 描述。不能通过跳过 TLS 或证书校验绕过缺失实现。Kwik/Agent15 属 LGPL-3.0 依赖，仅用于本隔离实验，未加入正式 App；[Kwik 官方仓库及许可证](https://github.com/ptrd/kwik/tree/v0.12.2)。

## 运行

先构建并启动相邻 Rust 实验服务：

```powershell
../image-transport-bench/target/release/sayin-image-transport-bench.exe server-all 192.168.71.6 ../../refer/image-test ./run-unique
```

端口随机，监听精确 IPv4；server-all 提供 HTTP/WSS TCP、raw TLS TCP、QUIC UDP 三个测试端口。所有端口使用同一临时证书/token/可信图片清单。全新 run 目录生成的 config.json 含临时认证 token，不得提交或公开。经 ADB 将 config.json、build/bench-dex.zip 和原始八图复制到手机独立目录，其中图片放在 images/。目录权限 700，config.json 权限 600。不能配置 ADB 隧道代替实际 Wi-Fi，也不修改防火墙或网络类别。

在手机本次目录内执行：

```sh
CLASSPATH=bench-dex.zip app_process /system/bin seeyuer.sayin.bench.Bench config.json images 192.168.71.72 1 smoke.jsonl smoke
CLASSPATH=bench-dex.zip app_process /system/bin seeyuer.sayin.bench.Bench config.json images 192.168.71.72 4 results.jsonl
```

先 smoke，12 种模式各传首图并取消最大图；正式测试四轮×12 种模式×八图。基础模式为 `wss-split,https-stream,tls-split,quic-1,quic-2,quic-4`，以及各自的 `-60` 限速版。默认全部运行；每轮顺序移动三位，偶数轮倒序传图。三种 TCP 模式每会话只创建两个 socket，QUIC 只创建一个 UDP socket / 一个连接＋一个控制流＋1/2/4 个图片双向流；逐图强制检查复用。SocketFactory 计数，不能用未覆盖 WebSocket 的 EventListener 统计。

全部固定完整 DER、校验有效期与正常 TLS 主机名，使用 TLS1.3；HTTP 使用 1.1；禁重试/跳转/公网代理，socket 绑定手机给定 Wi-Fi IPv4。验证 TLS 后才发送认证数据。Kwik 的 `proxy(ip)` API 仅用于覆盖直接 UDP 目标地址，源码确认它仍保留原 host 作为 TLS/SNI/证书名称，不经过中转，也没有关闭证书/主机名校验。QUIC ALPN 为 sayin-bench，无 0-RTT/会话票据。

WSS 正文 48KiB，待发队列约两块；HTTPS 固定 Content-Length 的一次性 RequestBody 流式写入，复用 keepalive；raw TLS/QUIC 用四字节有界长度帧＋相同二进制正文。QUIC 2/4 流发送连续、不重叠、完整覆盖原图的分区，服务端分别校验所有分区长度与 SHA-256 后才确认。没有 Base64 或整图内存。Kwik 发送缓冲默认 50KiB/流、默认 NewReno，流数量也改变缓冲总量；应用未另加无界队列，未宣称与 TCP/OkHttp 内部缓冲或调度相等。Kwik 没有在此使用可配置的控制流优先级，使用库默认发送调度。

全部模式使用同一测量/发送编排：耗时从 START 前到摘要 DONE 且全部本地 producer/HTTP 响应完成；不含建连，建连单独记录。每 20ms 控制 echo，每 100ms 采样 RSS/managed/native heap，另记录 WSS 队列峰值。每会话结束时启动最大图 200ms 后取消，确认全部本地 producer 停止、服务器 CANCEL ACK、300ms 后接受计数不增长且无完成回执。取消仅取消本次图片，不关闭控制通道；QUIC reset 图片流、保留连接与控制流。

限速指累计字节/单调时钟约束应用发送节奏，不是整机 QoS；并行流共用总计 60Mbps 预算，不能变成每流 60Mbps。初始突发、内核缓冲、在途字节及网络竞争仍存在。采样内存包含 ART 与客户端运行时，同一进程轮换并受 GC 影响，不能据绝对 RSS 宣称协议内存优劣，也不是完整 App 峰值。本次比较具体库实现，不能把差异全部归为协议固有开销。

拉回 JSONL，复算：

```powershell
$benchModes = 'wss-split,https-stream,tls-split,quic-1,quic-2,quic-4,wss-split-60,https-stream-60,tls-split-60,quic-1-60,quic-2-60,quic-4-60'
python ../image-transport-bench/summarize.py ./run-unique/results.jsonl --expect-rounds 4 --expect-modes $benchModes --expect-files 8 --output ./run-unique/summary.json
python analyze.py ./run-unique/results.jsonl --rounds 4 --modes $benchModes --output ./run-unique/comparison.json
```

仅复跑四种历史模式时，使用 server-okhttp，在客户端参数末尾附加 `full wss-split,https-stream,wss-split-60,https-stream-60`；smoke 时 full 换成 smoke，分析器默认仍为历史四模式。历史精确源码见 [43 记录](../../docs/43-kotlin-okhttp-image-upload-benchmark.md) source 快照。

归档脱敏结果和源码后，在服务 run 目录创建 stop 并确认退出；删除仅本次手机目录、本地临时 token 配置，不删除原图。ART/shell 没有正式 App 身份和 target37 生命周期，不能据此验收运行时本地网络权限、后台切换、真实图片落盘/粘贴或完整产品性能。
