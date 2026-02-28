# MAMQ 版本迭代计划

> 从 0 到 1 的完整演进路线，每个版本聚焦一个核心问题，改动最小化，可独立测试验证。

---

## 版本总览

```
v1.0      v2.0      v3.0      v4.0          v5.0          v6.0          v7.0
 │         │         │         │             │             │             │
 ▼         ▼         ▼         ▼             ▼             ▼             ▼
内存队列 ► 持久化 ► 客户端  ──►  能存更多  ──►  能分组  ──►  能容错  ──►  能上线
(跑通链路) (重启恢复) (push/pull) (存储分段)  (消费者组)   (重试+DLQ)   (监控+鉴权)

```

---

## v1.0 — 内存队列，跑通链路

**目标**：用最少的代码跑通 send → sub → recv → ack 完整链路，所有数据存内存，重启即丢失。理解消息系统最核心的 4 个角色和 4 个动作。

### 本版本实现

| # | 内容 | 涉及文件 |
|---|------|---------|
| 1 | 定义 `Message`、`Subscription`、`Stat`、`Result` 模型 | `model/` |
| 2 | `MessageQueue`：用 `ArrayList` 存消息，`ConcurrentHashMap` 管订阅，实现 send/recv/ack/stat | `MessageQueue.java` |
| 3 | `MQServer`：HTTP REST 接口，暴露 `/send` `/sub` `/unsub` `/recv` `/ack` `/stat` | `MQServer.java` |

**刻意不做**：持久化、客户端封装、batch 接口。只让链路跑通，看清结构。

### 功能流程图

```
Producer          MQServer            MessageQueue (内存)
   │                  │                      │
   │  POST /send      │                      │
   │ ───────────────► │  queue.messages      │
   │                  │  .add(msg)  ────────►│  [msg0, msg1, msg2 ...]
   │  {code:1,        │                      │
   │   data: 0}       │                      │  offset = 消息在列表中的下标
   │ ◄─────────────── │                      │
   │                  │                      │
Consumer          MQServer            MessageQueue (内存)
   │                  │                      │
   │  GET /sub        │                      │
   │ ───────────────► │  subscriptions       │
   │  {code:1,"OK"}   │  .put(cid, sub) ────►│  {cid → Subscription{offset=0}}
   │ ◄─────────────── │                      │
   │                  │                      │
   │  GET /recv       │                      │
   │ ───────────────► │  sub.offset=0        │
   │                  │  messages.get(0) ───►│
   │  {code:1, msg0}  │                      │
   │ ◄─────────────── │                      │
   │                  │                      │
   │  GET /ack?offset=0                      │
   │ ───────────────► │  sub.offset = 1 ────►│  书签往后移一格
   │  {code:1}        │                      │
   │ ◄─────────────── │                      │
```

### 测试流程

```bash
# 1. 发 3 条消息
curl -X POST "http://localhost:8765/mamq/send?t=test.t1" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"body":"hello","headers":{}}'
# 期望: {"code":1,"data":0}

curl -X POST "http://localhost:8765/mamq/send?t=test.t1" \
  -H "Content-Type: application/json" \
  -d '{"id":2,"body":"world","headers":{}}'
# 期望: {"code":1,"data":1}

# 2. 订阅
curl "http://localhost:8765/mamq/sub?t=test.t1&cid=c1"
# 期望: {"code":1,"data":"OK"}

# 3. 消费第 1 条
curl "http://localhost:8765/mamq/recv?t=test.t1&cid=c1"
# 期望: {"code":1,"data":{"id":1,"body":"hello",...}}

# 4. ACK，推进 offset
curl "http://localhost:8765/mamq/ack?t=test.t1&cid=c1&offset=0"
# 期望: {"code":1,"data":0}

# 5. 再 recv，拿到第 2 条（验证 offset 已推进）
curl "http://localhost:8765/mamq/recv?t=test.t1&cid=c1"
# 期望: {"code":1,"data":{"id":2,"body":"world",...}}

# 6. 查看统计
curl "http://localhost:8765/mamq/stat?t=test.t1&cid=c1"
# 期望: {"total":2,"position":1}
```

---

## v2.0 — 持久化存储，重启不丢

**目标**：把内存 `ArrayList` 替换为基于 `MappedByteBuffer` 的文件存储，加 `Indexer` 内存索引加速读取。重启服务后消息和消费进度都能恢复。理解消息系统如何做持久化。

### 本版本改动

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | 新增 `Store`：消息序列化后写入 `.dat` 文件（MappedByteBuffer），支持按 offset 读取 | `Store.java` |
| 2 | 新增 `Indexer`：内存中维护 `offset → (position, length)` 映射，启动时扫描 `.dat` 重建 | `Indexer.java` |
| 3 | `MessageQueue` 的 `messages` 列表替换为 `Store`，send/recv 改走文件读写 | `MessageQueue.java` |
| 4 | 新增 `/batch` 接口，一次拉取多条消息 | `MQServer.java`, `MessageQueue.java` |

**不变**：HTTP 接口、模型类、订阅逻辑保持 v1.0 不变，只换底层存储。

### 消息存储格式

```
.dat 文件布局（每条消息）：

┌──────────────┬──────────────────────────────┐
│  10字节长度头  │  N字节 JSON 消息体            │
│  (左补零数字)  │  (Message 序列化后的 JSON)    │
└──────────────┴──────────────────────────────┘
  e.g. "0000000042" + {"id":1,"body":"hello",...}

Indexer 内存结构：
  offset(消息序号) → Entry{ position(文件字节位置), length }
  0 → {0,    52}
  1 → {52,   48}
  2 → {100,  55}
  ...
```

### 功能流程图

```
写入流程

store.write(msg)
      │
      ▼
  JSON 序列化 → "0000000042{...}"
      │
      ▼
  MappedByteBuffer.put(bytes)
      │
      ▼
  Indexer.addEntry(offset, position, length)
      │  内存：offset → {position, length}

读取流程

store.read(offset)
      │
      ▼
  Indexer.getEntry(offset)
  → {position, length}
      │
      ▼
  MappedByteBuffer.get(position, length)
      │
      ▼
  JSON 反序列化 → Message

启动恢复流程

Store.init()
      │
      ▼
  扫描 .dat 文件，逐条读取
      │
      ▼
  重建 Indexer（offset → position/length）
```

### 测试流程

```bash
# 1. 发 5 条消息
for i in 1 2 3 4 5; do
  curl -s -X POST "http://localhost:8765/mamq/send?t=test.t2" \
    -H "Content-Type: application/json" \
    -d "{\"id\":$i,\"body\":\"msg-$i\",\"headers\":{}}"
done

# 2. 订阅，消费前 3 条并 ACK
curl "http://localhost:8765/mamq/sub?t=test.t2&cid=c1"
for offset in 0 1 2; do
  curl -s "http://localhost:8765/mamq/recv?t=test.t2&cid=c1"
  curl -s "http://localhost:8765/mamq/ack?t=test.t2&cid=c1&offset=$offset"
done

# 3. 重启服务（Ctrl+C 后重新启动）

# 4. 重启后验证消息仍在（持久化生效）
curl "http://localhost:8765/mamq/stat?t=test.t2&cid=c1"
# 期望: total=5（消息未丢失）

# 5. 批量拉取剩余消息
curl "http://localhost:8765/mamq/batch?t=test.t2&cid=c1&size=5"
# 期望: 返回 msg-4、msg-5（从上次 ACK 位置继续）
```

---

## v3.0 — 客户端封装，支持 push 模式

**目标**：在 HTTP 接口之上封装 Java 客户端，让调用方不用手写 curl，直接用对象操作。新增 push 模式（MABroker 后台轮询，消息到达时自动回调 listener）。理解 MQ 客户端的设计。

### 本版本改动

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | `MAProducer`：封装 `send` HTTP 调用 | `client/MAProducer.java` |
| 2 | `MAConsumer`：封装 `sub`/`recv`/`ack`/`stat` HTTP 调用，支持 pull 模式 | `client/MAConsumer.java` |
| 3 | `MAListener`：消息监听器接口，push 模式的回调契约 | `client/MAListener.java` |
| 4 | `MABroker`：管理 Producer/Consumer 生命周期，后台线程轮询驱动 push 回调，固定 100ms 间隔 | `client/MABroker.java` |
| 5 | `MAMqDemo`：演示 pull 和 push 两种用法的可交互 Demo | `demo/MAMqDemo.java` |

**不变**：服务端代码完全不动，只新增客户端层。

### 功能流程图

```
Pull 模式（用户主动调用）

MAProducer              MAConsumer
    │                       │
producer.send(topic, msg)   consumer.recv(topic)
    │                       │
    ▼                       ▼
HTTP POST /send         HTTP GET /recv
    │                       │
    ▼                       ▼
返回 offset             返回 Message
                            │
                        consumer.ack(topic, msg)
                            │
                            ▼
                        HTTP GET /ack

Push 模式（MABroker 后台驱动）

MABroker
    │
    ▼
后台线程每 100ms 轮询一次
    │
    ▼
consumer.recv(topic)
    │
    ▼
msg != null ?
┌───┴───┐
│ Yes   │ No → 继续等待
▼
listener.onMessage(msg)   ← 用户注册的回调
    │
    ▼
consumer.ack(topic, msg)  ← MABroker 自动 ACK
```

### Java 客户端用法

```java
MABroker broker = MABroker.getDefault(); // 连接 localhost:8765

// Pull 模式
MAProducer producer = broker.createProducer();
producer.send("test.t3", new Message<>(1L, "hello mamq", null));

MAConsumer<?> consumer = broker.createConsumer("test.t3");
Message<?> msg = consumer.recv("test.t3");
consumer.ack("test.t3", msg);

// Push 模式（自动轮询 + 回调）
MAConsumer<?> consumer2 = broker.createConsumer("test.t3");
consumer2.listen("test.t3", message -> {
    System.out.println("received: " + message.getBody());
    // ACK 由 MABroker 自动调用，无需手动处理
});
```

### 测试流程

```bash
# 启动服务端
mvn spring-boot:run

# 另开终端，运行 Demo
mvn exec:java -Dexec.mainClass="cn.malinghan.mamq.demo.MAMqDemo"

# Demo 交互命令：
# p - 用 MAProducer 生产一条消息（pull 模式验证）
# c - 用 MAConsumer.recv() 消费一条消息
# s - 查看统计
# b - 批量生产 10 条
# l - 启动 push 监听（注册 listener，MABroker 自动轮询消费）
# q - 退出
```

---

## v4.0 — 存储分段

**目标**：突破单文件存储上限，支持任意消息量写入，并持久化索引和消费位点，重启不丢进度。

### 核心概念类比

> 原来的存储像一本**只有10页的笔记本**，写满就没地方写了。
> v2.0 改成**活页夹**：每本写满后自动换一本新的，封面编号连续，查找时先看目录（索引文件）找到是第几本第几页，直接翻过去。

- **Segment（分段）** = 活页夹里的一本笔记本，固定大小（如 1GB）
- **索引文件 `.idx`** = 活页夹的目录，记录每条消息在哪本哪页
- **位点文件 `.sub`** = 每个消费者的书签，记录读到哪里了

### 本版本改动

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | `Store` 支持多 segment，写满自动滚动新文件 | `Store.java` |
| 2 | `Indexer` 增加 `segmentId` 字段，持久化到 `.idx` 文件 | `Indexer.java` |
| 3 | 启动时优先从 `.idx` 加载索引，跳过全量扫描 | `Store.java` |
| 4 | `Subscription.offset` 定期写入 `.sub` 文件，重启恢复 | `MessageQueue.java` |

### 功能流程图

```
写入流程（Store 分段）

store.write(message)
       │
       ▼
  当前 segment 还有空间？
       │
  ┌────┴────┐
  │ Yes     │ No
  ▼         ▼
写入当前   创建新 segment 文件
segment    (topic.1.dat, topic.2.dat ...)
  │         │
  └────┬────┘
       ▼
  Indexer.addEntry(segmentId, offset, len)
       │
       ▼
  追加写入 topic.idx 文件

读取流程

store.read(globalOffset)
       │
       ▼
  Indexer.getEntry(globalOffset)
  → {segmentId, localOffset, length}
       │
       ▼
  segments.get(segmentId).read(localOffset, length)
       │
       ▼
  返回 Message

重启恢复流程

Store.init()
       │
       ▼
  topic.idx 存在？
  ┌────┴────┐
  │ Yes     │ No
  ▼         ▼
从 .idx    扫描所有 .dat
加载索引   重建索引并写 .idx
  │         │
  └────┬────┘
       ▼
  加载 topic.sub → 恢复各消费者 offset
```

### 测试流程

```bash
# 1. 发送大量消息（每条约 200 字节，发 100 条）
for i in $(seq 1 100); do
  curl -s -X POST "http://localhost:8765/mamq/send?t=test.v2" \
    -H "Content-Type: application/json" \
    -d "{\"id\":$i,\"body\":\"$(python3 -c 'print("x"*150)')\",\"headers\":{}}" > /dev/null
done
echo "发送完成"

# 2. 订阅并消费前 50 条，ACK
curl "http://localhost:8765/mamq/sub?t=test.v2&cid=c1"
for i in $(seq 1 50); do
  MSG=$(curl -s "http://localhost:8765/mamq/recv?t=test.v2&cid=c1")
  OFFSET=$(echo $MSG | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['headers']['X-offset'])")
  curl -s "http://localhost:8765/mamq/ack?t=test.v2&cid=c1&offset=$OFFSET" > /dev/null
done
echo "消费 50 条完成"

# 3. 重启服务（Ctrl+C 后重新 mvn spring-boot:run）

# 4. 重启后验证消费进度恢复
curl "http://localhost:8765/mamq/stat?t=test.v2&cid=c1"
# 期望: position 仍在第 50 条的 offset，total=100

# 5. 继续消费剩余 50 条，验证跨 segment 读取正常
curl "http://localhost:8765/mamq/batch?t=test.v2&cid=c1&size=50"
# 期望: 返回第 51~100 条消息
```

---

## v5.0 — 消费者组

**目标**：同一个 Group 内多个消费者协作消费，每条消息只被其中一个消费者处理，实现水平扩展。

### 核心概念类比

> 没有消费者组时，每个消费者都是**独立的读者**，每人从头读一遍同一份报纸。
> 有了消费者组，同一组的消费者是**流水线上的工人**：报纸被撕成几份，每人负责读其中一份，合起来读完整份报纸，效率翻倍。

- **ConsumerGroup** = 一个工人班组，有统一的进度记录
- **Group offset** = 班组共用的书签，某工人读完一页就往后翻，其他人不会重复读这页
- **Round-Robin 分配** = 班长按顺序把页面分给工人，轮流来

### 本版本改动

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | `sub` 接口增加可选 `gid` 参数（消费者组 ID） | `MQServer.java` |
| 2 | `Subscription` 增加 `groupId` 字段 | `Subscription.java` |
| 3 | `MessageQueue` 增加 Group 级别 offset 管理，`recv`/`ack` 按 Group 路由 | `MessageQueue.java` |
| 4 | `stat` 接口支持按 Group 查询 | `MQServer.java`, `MessageQueue.java` |

### 功能流程图

```
消费者组 recv 流程

Consumer-A (gid=g1)         MQServer              MessageQueue
      │                         │                       │
      │  GET /recv?t=T&cid=A&gid=g1                     │
      │ ──────────────────────► │                       │
      │                         │  groupSubscriptions.get("g1")
      │                         │ ──────────────────────►│
      │                         │  group.offset = 10     │
      │                         │  next_offset = 11      │
      │                         │  store.read(11)        │
      │  {msg at offset=11}     │                       │
      │ ◄─────────────────────  │                       │

Consumer-B (gid=g1)         MQServer              MessageQueue
      │                         │                       │
      │  GET /recv?t=T&cid=B&gid=g1  (同时发起)         │
      │ ──────────────────────► │                       │
      │                         │  group.offset 已被 A 推进到 11
      │                         │  next_offset = 12      │
      │                         │  store.read(12)        │
      │  {msg at offset=12}     │                       │
      │ ◄─────────────────────  │                       │

ACK 流程（推进 Group offset）

Consumer-A                  MQServer              MessageQueue
      │                         │                       │
      │  GET /ack?t=T&cid=A&gid=g1&offset=11            │
      │ ──────────────────────► │                       │
      │                         │  groupSubscriptions.get("g1")
      │                         │  .setOffset(11)        │
      │  {code:1}               │                       │
      │ ◄─────────────────────  │                       │

无 gid 时（兼容 v1/v2 行为）：每个 cid 独立维护 offset，互不影响
```

### 测试流程

```bash
# 1. 两个消费者加入同一个 Group g1
curl "http://localhost:8765/mamq/sub?t=test.v3&cid=c1&gid=g1"
curl "http://localhost:8765/mamq/sub?t=test.v3&cid=c2&gid=g1"

# 2. 发 10 条消息
for i in $(seq 1 10); do
  curl -s -X POST "http://localhost:8765/mamq/send?t=test.v3" \
    -H "Content-Type: application/json" \
    -d "{\"id\":$i,\"body\":\"msg-$i\",\"headers\":{}}" > /dev/null
done

# 3. c1 和 c2 交替消费，验证不重复
echo "=== c1 消费 ==="
curl "http://localhost:8765/mamq/recv?t=test.v3&cid=c1&gid=g1"
echo "=== c2 消费 ==="
curl "http://localhost:8765/mamq/recv?t=test.v3&cid=c2&gid=g1"
echo "=== c1 再消费 ==="
curl "http://localhost:8765/mamq/recv?t=test.v3&cid=c1&gid=g1"
# 期望: 三次消费到的是不同消息（offset 递增，不重复）

# 4. 验证独立消费者（无 gid）不受 Group 影响
curl "http://localhost:8765/mamq/sub?t=test.v3&cid=solo"
curl "http://localhost:8765/mamq/recv?t=test.v3&cid=solo"
# 期望: solo 从头开始消费，拿到第 1 条消息

# 5. 查看 Group 统计
curl "http://localhost:8765/mamq/stat?t=test.v3&cid=c1&gid=g1"
# 期望: position 反映 g1 整体消费进度
```

---

## v6.0 — 可靠投递

**目标**：消费失败不丢消息，支持自动重试和死信队列，消息支持 TTL 过期。

### 核心概念类比

> 快递员送包裹（消息），收件人不在家（消费失败）：
> - **重试** = 快递员隔一段时间再来送，最多送 3 次
> - **死信队列（DLQ）** = 3 次都没人签收，包裹退回到"问题件仓库"，等待人工处理
> - **TTL** = 包裹上贴了"3天内有效"，过期就不送了，直接丢弃

### 本版本改动

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | `MABroker` 捕获 listener 异常，按退避策略重试（最多 3 次） | `MABroker.java` |
| 2 | 超过重试次数后，将消息转发到 `${topic}.DLQ` Topic | `MABroker.java`, `MessageQueue.java` |
| 3 | `recv` 时检查 `X-ttl` header，过期消息自动跳过 | `MessageQueue.java` |
| 4 | 新增 `/dlq` 接口，查看指定 Topic 的死信消息 | `MQServer.java` |

### 功能流程图

```
Push 模式重试流程（MABroker 轮询线程）

MABroker (定时任务)
      │
      ▼
  consumer.recv(topic)
      │
      ▼
  msg != null ?
  ┌───┴───┐
  │ Yes   │ No → 等待下次轮询
  ▼
  listener.onMessage(msg)
      │
      ▼
  抛出异常？
  ┌───┴───┐
  │ Yes   │ No → consumer.ack(msg) → 完成
  ▼
  retryCount < 3 ?
  ┌───┴───┐
  │ Yes   │ No
  ▼       ▼
退避重试  转发到 topic.DLQ
(1s/2s/4s) consumer.ack(原消息)

TTL 过期检查流程（MessageQueue.recv）

recv(topic, consumerId)
      │
      ▼
  store.read(next_offset) → msg
      │
      ▼
  msg.headers["X-ttl"] 存在？
  ┌───┴───┐
  │ Yes   │ No → 正常返回 msg
  ▼
  当前时间 > 发送时间 + ttl ?
  ┌───┴───┐
  │ Yes   │ No → 正常返回 msg
  ▼
  自动 ack 跳过，读取下一条
  （循环直到找到未过期消息或无消息）
```

### 测试流程

```bash
# === 测试重试 + DLQ ===

# 1. 订阅 Topic，用 Java 客户端注册一个必定失败的 listener
# （在 MAMqDemo 中临时改 listener 为 throw new RuntimeException）
# 启动 Demo，发一条消息
curl -X POST "http://localhost:8765/mamq/send?t=test.v4" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"body":"will-fail","headers":{}}'

# 2. 等待约 10 秒（3次重试：1s+2s+4s）
sleep 10

# 3. 查看死信队列
curl "http://localhost:8765/mamq/recv?t=test.v4.DLQ&cid=dlq-handler"
# 期望: 拿到 body="will-fail" 的消息

# === 测试 TTL ===

# 4. 发一条 TTL=2000ms 的消息
curl -X POST "http://localhost:8765/mamq/send?t=test.v4" \
  -H "Content-Type: application/json" \
  -d '{"id":2,"body":"will-expire","headers":{"X-ttl":"2000","X-send-time":"'$(date +%s%3N)'"}'

# 5. 等待 3 秒后消费
sleep 3
curl "http://localhost:8765/mamq/sub?t=test.v4&cid=c1"
curl "http://localhost:8765/mamq/recv?t=test.v4&cid=c1"
# 期望: 该消息被跳过，返回 null 或下一条未过期消息
```

---

## v7.0 — 生产就绪

**目标**：具备基本的生产运行能力，包括自适应轮询、接口鉴权、可观测性指标。

### 核心概念类比

> v7.0 是给工厂装上**监控摄像头、门禁系统和智能调度**：
> - **自适应轮询** = 智能调度：没活干时工人休息，有活时立刻叫醒，不再每隔 100ms 无效打卡
> - **接口鉴权** = 门禁系统：没有工牌（Token）不能进入工厂
> - **指标暴露** = 监控摄像头：实时看到每条传送带的吞吐量和积压情况

### 本版本改动

| # | 改动 | 涉及文件 |
|---|------|---------|
| 1 | `MABroker` 轮询改为指数退避（无消息时最长退避 5s） | `MABroker.java` |
| 2 | Spring Security 或 Filter 实现 Token 认证 | 新增 `AuthFilter.java` |
| 3 | 集成 Micrometer，暴露 `mamq_send_total`、`mamq_recv_total`、`mamq_lag` 指标 | 新增 `MQMetrics.java` |
| 4 | 支持延迟消息（`X-delay-ms` header，服务端延迟队列） | `MessageQueue.java` |

### 功能流程图

```
自适应轮询流程

MABroker 轮询线程
      │
      ▼
  consumer.recv(topic)
      │
      ▼
  msg != null ?
  ┌───┴───┐
  │ Yes   │ No
  ▼       ▼
立即处理  waitInterval = min(waitInterval * 2, 5000ms)
consumer  Thread.sleep(waitInterval)
.ack()    │
  │       ▼
  ▼     下次轮询
waitInterval = 100ms（重置）

接口鉴权流程

HTTP Request
      │
      ▼
  AuthFilter
      │
      ▼
  Header "X-Token" 存在且合法？
  ┌───┴───┐
  │ Yes   │ No
  ▼       ▼
放行到   返回 401 Unauthorized
Controller

延迟消息流程

send(msg with X-delay-ms=5000)
      │
      ▼
  MessageQueue 不写入主存储
  而是放入 delayQueue（优先级队列，按到期时间排序）
      │
      ▼
  后台线程每秒扫描 delayQueue
      │
      ▼
  到期消息 → 写入主 Store → 对消费者可见
```

### 测试流程

```bash
# === 测试鉴权 ===

# 1. 无 Token 访问，期望 401
curl -v "http://localhost:8765/mamq/sub?t=test.v5&cid=c1"
# 期望: HTTP 401

# 2. 携带正确 Token
curl -H "X-Token: mamq-secret" \
  "http://localhost:8765/mamq/sub?t=test.v5&cid=c1"
# 期望: {"code":1,"data":"OK"}

# === 测试延迟消息 ===

# 3. 发一条延迟 5 秒的消息
curl -H "X-Token: mamq-secret" \
  -X POST "http://localhost:8765/mamq/send?t=test.v5" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"body":"delayed","headers":{"X-delay-ms":"5000"}}'

# 4. 立即消费，期望拿不到
curl -H "X-Token: mamq-secret" \
  "http://localhost:8765/mamq/recv?t=test.v5&cid=c1"
# 期望: data=null

# 5. 等待 6 秒后消费
sleep 6
curl -H "X-Token: mamq-secret" \
  "http://localhost:8765/mamq/recv?t=test.v5&cid=c1"
# 期望: {"code":1,"data":{"body":"delayed",...}}

# === 查看指标 ===

# 6. 访问 Actuator 指标端点
curl "http://localhost:8765/actuator/metrics/mamq.lag?tag=topic:test.v5"
# 期望: 返回当前积压消息数
```

---

## 未来规划（v8.0+）

以下功能超出当前阶段范围，作为长期演进方向，简要说明实现思路。

### v8.0 — 多节点集群

**功能**：多个 MAMQ 实例组成集群，Topic 数据分布在不同节点，单节点故障不影响整体服务。

**实现思路**：
- 引入 Raft 或 ZooKeeper 做 Leader 选举和元数据管理
- Topic 按 partition 分片，每个 partition 分配到一个主节点
- 客户端 `MABroker` 增加路由层，根据 Topic 找到对应节点的地址
- 主节点写入后同步到副本节点，副本数可配置（默认 2）

### v9.0 — 消息过滤与路由

**功能**：消费者可以按消息属性（header）过滤，只消费感兴趣的消息；支持基于规则的消息路由到不同 Topic。

**实现思路**：
- `sub` 接口增加 `filter` 参数，支持简单表达式（如 `type=order AND amount>100`）
- `recv` 时在服务端过滤，跳过不匹配的消息
- 路由规则配置在服务端，`send` 时根据消息 header 自动转发到目标 Topic

### v10.0 — 管理控制台

**功能**：Web UI 实时查看所有 Topic 的消息量、消费者列表、消费进度、积压情况，支持手动重发死信消息。

**实现思路**：
- 后端新增 `/admin` 系列 REST 接口，聚合各 Topic 的 stat 数据
- 前端用轻量框架（Vue 或纯 HTML + fetch）渲染 Topic 列表和消费进度条
- 死信消息支持在 UI 上一键重新投递到原 Topic

---

## 版本依赖关系

```
v1.0 (内存队列)
  └─► v2.0 (持久化存储)
        └─► v3.0 (客户端封装)
              └─► v4.0 (存储分段) ──────────────────────────────┐
                    └─► v5.0 (消费者组)                         │
                          └─► v6.0 (可靠投递)                   │
                                └─► v7.0 (生产就绪)             │
                                      └─► v8.0 (集群) ◄─────────┘
                                            └─► v9.0 (过滤路由)
                                                  └─► v10.0 (控制台)
```

每个版本都在上一版本基础上叠加，v1.0~v7.0 为核心演进主线，v8.0+ 为扩展能力。
