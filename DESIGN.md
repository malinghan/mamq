# MAMQ 架构设计文档

## 1. 项目介绍

MAMQ 是一个轻量级的消息队列系统，基于 HTTP 协议实现 Producer-Consumer 模型，支持消息持久化、手动 ACK 确认、订阅管理和消费进度追踪。

项目定位是一个**教学/原型级别**的 MQ 实现，麻雀虽小五脏俱全，覆盖了消息队列的核心设计要素：持久化存储、偏移量管理、订阅关系、消费确认。

### 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.1 |
| 通信协议 | HTTP REST |
| 序列化 | FastJSON |
| 持久化 | MappedByteBuffer（内存映射文件） |
| 工具库 | Lombok、cn.malinghan:utils |
| 构建工具 | Maven |

---

## 2. 项目架构

### 整体结构

```
mamq/
├── server/
│   ├── MQServer.java        # HTTP 接入层（REST Controller）
│   └── MessageQueue.java    # 队列核心逻辑（Topic 管理、订阅、收发）
├── store/
│   ├── Store.java           # 持久化层（MappedByteBuffer 读写）
│   └── Indexer.java         # 内存索引（offset → entry 映射）
├── client/
│   ├── KKBroker.java        # 客户端代理（封装 HTTP 调用 + 轮询调度）
│   ├── KKProducer.java      # 生产者
│   ├── KKConsumer.java      # 消费者（支持 pull/push 两种模式）
│   └── KKListener.java      # 消息监听器接口（push 模式回调）
└── model/
    ├── Message.java         # 消息体（id + body + headers）
    ├── Subscription.java    # 订阅关系（topic + consumerId + offset）
    ├── Stat.java            # 消费统计（total + position）
    └── Result.java          # HTTP 响应包装
```

### 核心概念

#### Topic（主题）
消息的逻辑分类，类似**广播频道**。生产者向 Topic 发消息，消费者订阅 Topic 收消息。每个 Topic 对应一个独立的 `MessageQueue` 实例和一个 `.dat` 持久化文件。

#### Offset（偏移量）
消息在存储文件中的**字节位置**，是定位消息的唯一坐标。类比书签——记录你读到哪一页了。每条消息写入后，其起始字节位置就是它的 offset。

#### Subscription（订阅）
消费者与 Topic 的绑定关系，同时记录该消费者当前已消费到的 offset。类比**快递签收记录**——知道你上次签收到哪一单了。

#### ACK（确认）
消费者处理完消息后，主动告知服务端更新 offset。没有 ACK，下次 recv 还会拿到同一条消息，实现**至少一次投递**语义。

#### Store + Indexer（存储 + 索引）
Store 负责将消息序列化后写入内存映射文件；Indexer 在内存中维护 `offset → (offset, length)` 的映射，加速随机读取。类比**仓库 + 货架目录**：仓库存货物，目录记录每件货物在哪个货架哪个位置。

### 架构图

```
┌─────────────────────────────────────────────────────────┐
│                      Client Side                        │
│                                                         │
│  KKProducer ──┐                                         │
│               ├──► KKBroker ──► HTTP ──► MQServer       │
│  KKConsumer ──┘   (轮询调度)              (REST API)    │
│    └── KKListener (push回调)                            │
└─────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────┐
│                      Server Side                        │
│                                                         │
│  MQServer (Controller)                                  │
│    /send  /recv  /ack  /sub  /unsub  /stat  /batch      │
│         │                                               │
│         ▼                                               │
│  MessageQueue (per topic)                               │
│    ├── subscriptions: Map<consumerId, Subscription>     │
│    └── Store                                            │
│          ├── MappedByteBuffer (.dat file)               │
│          └── Indexer (内存索引)                          │
│               └── Map<offset, Entry(offset, length)>    │
└─────────────────────────────────────────────────────────┘
```

### 消息存储格式

每条消息在 `.dat` 文件中的布局：

```
┌──────────────┬──────────────────────────────┐
│  10字节长度头  │  N字节 JSON 消息体            │
│  (左补零数字)  │  (Message 序列化后的 JSON)    │
└──────────────┴──────────────────────────────┘
  e.g. "0000000123" + "{\"id\":1,\"body\":...}"
```

### 消息收发流程

```
Producer                MQServer              Store / Indexer
   │                       │                       │
   │── POST /send?t=topic ─►│                       │
   │                       │── store.write(msg) ──►│
   │                       │                       │── 写入 MappedByteBuffer
   │                       │                       │── Indexer.addEntry(offset, len)
   │◄── {code:1, data:offset}                      │
   │                       │                       │

Consumer               MQServer              Store / Indexer
   │                       │                       │
   │── GET /recv?t=&cid= ──►│                       │
   │                       │── 查 subscription.offset
   │                       │── Indexer.getEntry(offset)
   │                       │── store.read(offset) ─►│
   │◄── {code:1, data:msg} │                       │
   │                       │                       │
   │── GET /ack?offset=N ──►│                       │
   │                       │── subscription.setOffset(N)
   │◄── {code:1}           │                       │
```

---

## 3. Quick Start

### 启动服务端

```bash
mvn spring-boot:run
# 服务监听 http://localhost:8765
```

### HTTP API 直接调用

```bash
# 订阅 topic
curl "http://localhost:8765/mamq/sub?t=cn.malinghan.test&cid=consumer-1"

# 发送消息
curl -X POST "http://localhost:8765/mamq/send?t=cn.malinghan.test" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"body":"hello","headers":{}}'

# 消费消息
curl "http://localhost:8765/mamq/recv?t=cn.malinghan.test&cid=consumer-1"

# ACK 确认（offset 从 recv 响应的 headers.X-offset 获取）
curl "http://localhost:8765/mamq/ack?t=cn.malinghan.test&cid=consumer-1&offset=0"

# 查看消费统计
curl "http://localhost:8765/mamq/stat?t=cn.malinghan.test&cid=consumer-1"
```

### Java 客户端使用

```java
String topic = "cn.malinghan.test";
KKBroker broker = KKBroker.getDefault();

// 生产者
KKProducer producer = broker.createProducer();
producer.send(topic, new Message<>(1L, "hello mamq", null));

// 消费者 - Pull 模式
KKConsumer<?> consumer = broker.createConsumer(topic);
Message<?> msg = consumer.recv(topic);
consumer.ack(topic, msg);

// 消费者 - Push 模式（自动轮询）
KKConsumer<?> consumer2 = broker.createConsumer(topic);
consumer2.listen(topic, message -> {
    System.out.println("received: " + message);
    // ACK 由 KKBroker 自动调用
});
```

### 运行 Demo

```bash
# 确保服务端已启动，然后运行
mvn exec:java -Dexec.mainClass="cn.malinghan.mamq.demo.KKMqDemo"

# 交互命令：
# p - 生产一条消息
# c - 消费一条消息
# s - 查看统计
# b - 批量生产 10 条
# q/e - 退出
```

---

## 4. 优点与不足

### 优点

- **结构清晰**：Server / Store / Client 三层分离，核心概念（Topic、Offset、Subscription、ACK）完整，是学习 MQ 设计的良好范本。
- **持久化可靠**：使用 `MappedByteBuffer` 内存映射文件，兼顾读写性能与数据持久化，重启后可从文件恢复索引。
- **Push/Pull 双模式**：客户端同时支持主动拉取（pull）和监听器回调（push，由 KKBroker 定时轮询驱动）。
- **手动 ACK**：消费者需显式确认，避免消息丢失，实现至少一次投递语义。
- **零外部依赖**：无需 Kafka、RabbitMQ 等中间件，纯 Spring Boot 即可运行。

### 不足

- **单文件存储上限**：`Store.LEN = 1024 * 10`（10KB），文件写满后无法扩展，代码中有 `todo` 标注但未实现多文件管理。
- **Topic 硬编码**：`MessageQueue` 中 Topic 在静态块里手动注册，不支持动态创建 Topic。
- **无消费者组**：多个消费者订阅同一 Topic 时，每人独立消费全量消息，不支持分组负载均衡（类似 Kafka ConsumerGroup）。
- **内存索引不持久化**：`Indexer` 的索引数据仅在内存中，重启时通过扫描文件重建，但 `batch` 方法中存在逻辑 bug（循环内 offset 递增方式有误，且 `return` 语句位置错误导致永远抛异常）。
- **无并发保护**：`MessageQueue.subscriptions`、`Store` 的写操作均无线程安全保障，高并发下存在数据竞争风险。
- **客户端轮询固定间隔**：`KKBroker` 以 100ms 固定间隔轮询，无背压机制，空轮询浪费资源。
- **无消息过期/TTL**：消息写入后永久保留，无清理机制。
- **传输无鉴权**：HTTP 接口完全开放，无任何认证授权。

---

## 5. 功能清单

### 已实现

| 功能 | 说明 | 状态 |
|------|------|------|
| Topic 订阅 / 取消订阅 | `sub` / `unsub` 接口，维护消费者与 Topic 的绑定关系 | ✅ |
| 消息发送 | `send` 接口，Producer 向指定 Topic 写入消息 | ✅ |
| 消息消费（Pull） | `recv` 接口，Consumer 主动拉取下一条未消费消息 | ✅ |
| 手动 ACK | `ack` 接口，消费者确认后推进 offset | ✅ |
| 消息持久化 | 基于 `MappedByteBuffer` 的内存映射文件存储 | ✅ |
| 启动恢复 | 服务重启时扫描 `.dat` 文件重建内存索引 | ✅ |
| 消费统计 | `stat` 接口，返回 total 消息数和当前消费 position | ✅ |
| Push 模式（监听器） | `KKConsumer.listen()` + `KKBroker` 定时轮询驱动回调 | ✅ |
| 批量消费 | `batch` 接口，一次拉取多条消息（含 bug，待修复） | ⚠️ |

### 未实现 / 待完善

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 动态 Topic 创建 | 发送消息时自动创建不存在的 Topic | 高 |
| 存储文件分段 | 单文件超过阈值后滚动到新文件，支持无限写入 | 高 |
| 消费者组 | 同一 Group 内多个消费者分摊消费，互不重复 | 高 |
| 并发安全 | 对 subscriptions、Store 写操作加锁或使用并发容器 | 高 |
| batch 接口修复 | 修复循环逻辑 bug 和 return 位置错误 | 高 |
| 索引持久化 | 将 Indexer 数据写入独立索引文件，加速重启恢复 | 中 |
| 消息 TTL / 过期清理 | 支持消息设置过期时间，定期清理过期数据 | 中 |
| 消费位点持久化 | Subscription offset 持久化，重启后恢复消费进度 | 中 |
| 死信队列（DLQ） | 消费失败超过重试次数后转入死信 Topic | 中 |
| 消息重试 | 消费失败自动重试，支持配置重试次数和间隔 | 中 |
| 背压 / 自适应轮询 | 无消息时退避，有消息时加速，减少空轮询 | 低 |
| 接口鉴权 | 基于 Token 或 Basic Auth 保护 HTTP 接口 | 低 |
| 消息过滤 | 消费者按 header 属性过滤消息 | 低 |
| 延迟消息 | 支持消息在指定延迟后才可被消费 | 低 |
| 管理控制台 | Web UI 查看 Topic 列表、消费进度、消息详情 | 低 |

---

## 6. 迭代计划

### Iteration 1 — 稳定核心（修 bug + 补安全）

**目标**：让现有功能可靠运行，消除已知 bug 和数据竞争风险。

```
1. 修复 MessageQueue.batch() 的循环逻辑 bug 和 return 位置错误
2. 对 MessageQueue.subscriptions 使用 ConcurrentHashMap
3. 对 Store.write() 加 synchronized，保证写入原子性
4. 动态 Topic 创建：send/sub 时若 Topic 不存在则自动初始化
5. 补充单元测试：send → recv → ack 完整链路测试
```

---

### Iteration 2 — 存储增强（突破 10KB 限制）

**目标**：支持生产级别的消息量，存储不再是瓶颈。

```
1. Store 分段存储
   - 单个 .dat 文件达到阈值（如 1GB）时，自动创建下一个分段文件
   - Indexer 记录 (segmentId, offset, length) 三元组
   - Store 管理 segment 列表，read/write 透明路由到正确分段

2. 索引文件持久化
   - 启动时优先从 .idx 文件加载索引，无需全量扫描 .dat
   - 每次 write 同步追加写入 .idx

3. 消费位点持久化
   - Subscription offset 定期刷写到 .sub 文件
   - 重启后从文件恢复各消费者的消费进度
```

---

### Iteration 3 — 消费者组（水平扩展消费能力）

**目标**：支持多消费者协作消费同一 Topic，实现负载均衡。

```
1. 引入 ConsumerGroup 概念
   - Subscription 增加 groupId 字段
   - 同一 Group 内，一条消息只投递给其中一个消费者

2. 分区分配策略
   - 简单轮询（Round-Robin）：消费者轮流取消息
   - 或基于 offset 范围划分：每个消费者负责一段 offset 区间

3. Group 级别的 offset 管理
   - ACK 推进的是 Group offset，而非个人 offset
   - stat 接口支持按 Group 查询消费进度
```

---

### Iteration 4 — 可靠投递（重试 + 死信）

**目标**：消息不因消费失败而丢失，提供完整的错误处理链路。

```
1. 消费重试
   - KKBroker 捕获 listener 异常后，按退避策略重试（1s/2s/4s）
   - 重试次数可配置，默认 3 次

2. 死信队列（DLQ）
   - 超过最大重试次数的消息转入 ${topic}.DLQ
   - DLQ 本身也是普通 Topic，可单独订阅处理

3. 消息 TTL
   - Message.headers 支持 X-ttl 字段（毫秒）
   - recv 时检查消息是否过期，过期则跳过并推进 offset
```

---

### Iteration 5 — 生产就绪（性能 + 运维）

**目标**：具备基本的生产环境运行能力。

```
1. 自适应轮询
   - 无消息时指数退避（最长 5s），有消息时立即拉取
   - 减少空轮询对服务端的压力

2. 延迟消息
   - send 时携带 X-delay-ms header
   - 服务端维护延迟队列，到期后才对消费者可见

3. 接口鉴权
   - 基于 HTTP Header 的 Token 认证
   - 支持 Topic 级别的读写权限控制

4. 指标暴露
   - 集成 Spring Boot Actuator
   - 暴露每个 Topic 的 send_total、recv_total、lag（积压量）指标

5. 管理控制台（可选）
   - 简单的 Web 页面展示 Topic 列表、消费者列表、消费进度
```

---

### 迭代路线总览

```
v0.1 (当前)   v0.2           v0.3           v0.4           v0.5
    │              │              │              │              │
    ▼              ▼              ▼              ▼              ▼
核心可用  ──►  存储无上限  ──►  消费者组  ──►  可靠投递  ──►  生产就绪
(修bug)      (分段+索引)    (负载均衡)    (重试+DLQ)    (监控+鉴权)
```