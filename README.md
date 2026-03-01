# MAMQ

一个轻量级的消息队列系统，基于 HTTP 协议实现 Producer-Consumer 模型，支持消息持久化、手动 ACK、订阅管理和消费进度追踪。

定位是**教学/原型级别**的 MQ 实现，覆盖消息队列的核心设计要素：持久化存储、偏移量管理、订阅关系、消费确认。

## 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 4.0.3 |
| 通信协议 | HTTP REST |
| 序列化 | FastJSON 2 |
| 持久化 | MappedByteBuffer（内存映射文件） |
| 构建工具 | Maven |

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                      Client Side                        │
│                                                         │
│  MAProducer ──┐                                         │
│               ├──► MABroker ──► HTTP ──► MQServer       │
│  MAConsumer ──┘   (轮询调度)              (REST API)    │
│    └── MAListener (push回调)                            │
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
└─────────────────────────────────────────────────────────┘
```

### 核心概念

- **Topic**：消息的逻辑分类，每个 Topic 对应独立的 `MessageQueue` 实例和 `.dat` 持久化文件
- **Offset**：消息在存储文件中的序号，是定位消息的唯一坐标
- **Subscription**：消费者与 Topic 的绑定关系，记录当前消费进度（offset）
- **ACK**：消费者处理完消息后主动确认，推进 offset，实现**至少一次投递**语义
- **Store + Indexer**：Store 将消息写入内存映射文件；Indexer 在内存中维护 `offset → (position, length)` 映射加速随机读取

### 消息存储格式

```
┌──────────────┬──────────────────────────────┐
│  10字节长度头  │  N字节 JSON 消息体            │
│  (左补零数字)  │  (Message 序列化后的 JSON)    │
└──────────────┴──────────────────────────────┘
  e.g. "0000000123" + "{\"id\":1,\"body\":...}"
```

## 快速开始

### 启动服务端

```bash
mvn spring-boot:run
# 服务监听 http://localhost:8765
```

### HTTP API

```bash
# 订阅 topic
curl "http://localhost:8765/mamq/sub?t=demo.topic&cid=consumer-1"

# 发送消息
curl -X POST "http://localhost:8765/mamq/send?t=demo.topic" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"body":"hello","headers":{}}'

# 消费消息
curl "http://localhost:8765/mamq/recv?t=demo.topic&cid=consumer-1"

# ACK 确认
curl "http://localhost:8765/mamq/ack?t=demo.topic&cid=consumer-1&offset=0"

# 查看消费统计
curl "http://localhost:8765/mamq/stat?t=demo.topic&cid=consumer-1"

# 批量消费
curl "http://localhost:8765/mamq/batch?t=demo.topic&cid=consumer-1&size=10"
```

### Java 客户端

```java
MABroker broker = MABroker.getDefault(); // 默认连接 localhost:8765

// 生产者
MAProducer producer = broker.createProducer();
producer.send("demo.topic", new Message<>(1L, "hello mamq", null, null));

// 消费者 - Pull 模式
MAConsumer<String> consumer = broker.createConsumer("demo.topic", "consumer-1");
consumer.sub();
Message<String> msg = consumer.recv();
consumer.ack(msg.getOffset());

// 消费者 - Push 模式（自动轮询回调）
consumer.listen(m -> System.out.println("received: " + m.getBody()));
broker.startPolling(consumer);
```

### 运行 Demo

```bash
# 确保服务端已启动，然后运行
mvn exec:java -Dexec.mainClass="com.malinghan.mamq.demo.MAMqDemo"
```

Demo 支持的交互命令：

| 命令 | 说明 |
|------|------|
| `p` | 生产一条消息 |
| `c` | 消费一条消息（Pull） |
| `s` | 查看消费统计 |
| `b` | 批量生产 10 条消息 |
| `l` | 启动 Push 监听模式 |
| `q` | 退出 |

## 项目结构

```
src/main/java/com/malinghan/mamq/
├── server/
│   ├── MQServer.java        # HTTP 接入层（REST Controller）
│   └── MessageQueue.java    # 队列核心逻辑（Topic 管理、订阅、收发）
├── store/
│   ├── Store.java           # 持久化层（MappedByteBuffer 读写）
│   └── Indexer.java         # 内存索引（offset → entry 映射）
├── client/
│   ├── MABroker.java        # 客户端代理（封装 HTTP 调用 + 轮询调度）
│   ├── MAProducer.java      # 生产者
│   ├── MAConsumer.java      # 消费者（支持 pull/push 两种模式）
│   ├── MAListener.java      # 消息监听器接口（push 模式回调）
│   └── HttpUtil.java        # HTTP 工具类
├── model/
│   ├── Message.java         # 消息体（id + body + headers + offset）
│   ├── Subscription.java    # 订阅关系（topic + consumerId + offset）
│   ├── Stat.java            # 消费统计（total + position）
│   └── Result.java          # HTTP 响应包装
└── demo/
    └── MAMqDemo.java        # 交互式 Demo
```

## 已实现功能

| 功能 | 说明 |
|------|------|
| Topic 订阅 / 取消订阅 | `sub` / `unsub` 接口 |
| 消息发送 | `send` 接口，Producer 向指定 Topic 写入消息 |
| 消息消费（Pull） | `recv` 接口，Consumer 主动拉取下一条未消费消息 |
| 手动 ACK | `ack` 接口，消费者确认后推进 offset |
| 消息持久化 | 基于 `MappedByteBuffer` 的内存映射文件存储（10MB/topic） |
| 启动恢复 | 服务重启时扫描 `.dat` 文件重建内存索引 |
| 消费统计 | `stat` 接口，返回 total 消息数和当前消费 position |
| Push 模式 | `MAConsumer.listen()` + `MABroker` 定时轮询驱动回调 |
| 批量消费 | `batch` 接口，一次拉取多条消息 |
| 动态 Topic | send/sub 时若 Topic 不存在则自动创建 |