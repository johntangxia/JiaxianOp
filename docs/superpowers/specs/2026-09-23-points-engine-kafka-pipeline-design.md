# 积分引擎 + Kafka 实时流水线 · 设计方案

- 日期：2026-09-23
- 状态：已实现（实施计划见 [Kafka 实时流水线](../plans/2026-10-04-kafka-realtime-pipeline.md)）
- 前置依赖：[基础设施与数据模型](2026-09-23-community-analytics-design.md) 已完成（见 `docs/superpowers/plans/2026-09-23-infra-and-data-model.md`），本设计建立在其已有的数据模型、JPA 实体/仓储、积分引擎（`BehaviorEventGenerator`）和历史批量回填模拟器之上。

## 1. 背景与目标

Plan 1（基础设施与数据模型）已经交付了完整的"积分引擎"——`BehaviorEventGenerator` 实现了设计文档确认的双边积分记账规则（主动/被动积分、团队账号多作者均分、评论二级回复、评论点赞），并通过历史批量回填模拟器（`HistoricalBackfillService`）直接写库验证过。

本阶段要实现母设计文档第 4 节里的"实时模拟模式"：一个可启停的后台事件生产者，模拟"社区正在运营中"的持续活动，通过 Kafka 把"决定发生了什么事"和"把这件事落库"解耦成两个独立阶段，复用（而不是重新实现）已有的积分引擎逻辑；同时引入 Redis，维护一组近实时的运营计数器，作为后续排行榜/看板阶段的基础设施验证。

核心目标：
1. 验证"批量回填走直接写库、实时模式走 Kafka 事件流"这套批流混合架构在真实基础设施上能跑通。
2. Kafka 消费端完全复用 Plan 1 已经测试过的 `BehaviorEventGenerator`，不引入第二套积分记账逻辑，避免两套逻辑漂移不一致。
3. 用 Redis 维护基础的"今日 PV / 今日互动数"计数器，验证事件驱动更新缓存这条链路可行。

## 2. 范围

**包含**：
- 一个可通过管理 API 启停、按可配置速率持续运行的实时事件生产者（`RealtimeEventProducer`）
- 对应的 Kafka 消费者（`RealtimeEventConsumer`），复用 `BehaviorEventGenerator` 落库
- 基础 Redis 计数器（频道维度的"今日 PV"、"今日互动数"）
- 管理 API：启动/停止/查看运行状态

**明确不包含**（留给后续计划）：
- 新内容（"发帖"）的实时生成——实时模式只对 Plan 1 已经批量生成的员工/账号/内容产生互动事件，不创建新的 `Content` 行
- 排行榜、PV 多维分析聚合、预测——这些是后续独立计划的范围，Redis 里这次只做最基础的计数器，不做排行榜 Sorted Set
- KOL/RFM 分层
- 前端展示这些实时数据的页面

## 3. 总体架构

```
┌──────────────────┐     ┌───────────────┐     ┌──────────────────────┐
│ RealtimeEvent      │     │  Kafka         │     │ RealtimeEvent          │
│ Producer            │────▶│  community-    │────▶│ Consumer               │
│ (后台线程，可启停)    │     │  events topic  │     │ (@KafkaListener)       │
├──────────────────┤     │ (按contentId   │     ├──────────────────────┤
│ 从DB随机选已有员工/  │     │  分区，保证同一   │     │ 反序列化→按eventType   │
│ 内容，按概率决定事件  │     │  内容事件有序)   │     │ 分发给BehaviorEvent    │
│ 类型，序列化成JSON   │     │                │     │ Generator对应方法      │
└──────────────────┘     └───────────────┘     │ （复用Plan 1代码）      │
                                                 │ + 更新Redis今日计数器    │
                                                 └──────────────────────┘
```

数据流：
1. 管理员调用 `POST /api/admin/simulator/realtime/start` 启动实时模拟。
2. `RealtimeEventProducer` 内部的后台任务按配置的速率循环：从数据库随机取一批已有内容（可复用 Plan 1 `PopularityDistribution`/热度加权的选取思路，热度越高的内容被选中概率越大），按跟 `HistoricalBackfillService` 类似的概率组合（浏览/点赞/分享/收藏/评论/评论回复/评论点赞/登录）决定这一轮要发生的事件，把事件序列化成 JSON，发到 `community-events` topic。
3. `RealtimeEventConsumer` 订阅该 topic，反序列化消息，根据 `eventType` 字段分发调用 `BehaviorEventGenerator` 对应的方法（`generateView`/`generateLike`/`generateShare`/`generateFavorite`/`generateComment`/`generateCommentLike`/`generateLogin`）完成落库和积分记账。
4. 消费成功后，Consumer 用 `RedisTemplate` 对相应的"今日计数器" key 做 `INCR`，并设置到次日零点过期的 TTL。
5. 管理员调用 `POST /api/admin/simulator/realtime/stop` 停止生产者；`GET /api/admin/simulator/realtime/status` 随时查看运行状态和已生成事件数。

## 4. 消息体设计

单一 topic（`community-events`）+ `eventType` 判别字段，避免每新增一种事件类型就要新建一个 topic。

```java
public class RealtimeEvent {
    private String eventType;       // VIEW / LIKE / SHARE / FAVORITE / COMMENT / COMMENT_LIKE / LOGIN
    private Long contentId;         // 除LOGIN外都需要
    private Long parentCommentId;   // 仅 COMMENT 事件、且是"回复"而不是"新建顶层评论"时才非空
    private Long targetCommentId;   // 仅 COMMENT_LIKE 事件需要，表示被点赞的评论ID
    private Long actorEmployeeId;   // 触发这个事件的员工
    private LocalDateTime occurTime;
    private String batchId;
}
```

字段拆成 `parentCommentId`（回复评论场景）和 `targetCommentId`（评论点赞场景）两个独立字段，而不是共用一个含义随事件类型变化的 `commentId`——避免实现时把两种语义搞混。`COMMENT` 事件对应 `BehaviorEventGenerator.generateComment(contentId, parentCommentId, ...)`（`parentCommentId` 为空即新建顶层评论）；`COMMENT_LIKE` 事件对应 `generateCommentLike(targetCommentId, ...)`。

- 序列化：Spring Kafka 默认的 `JsonSerializer`/`JsonDeserializer`（Jackson，项目里已经在用，跟 REST 层保持一致的技术选型，不引入 Avro/Protobuf 这类额外的 schema registry 依赖）。
- 分区 key：`contentId`（`LOGIN` 事件例外，因为它不关联具体内容，用 `actorEmployeeId` 做 key）——保证同一篇内容/同一个人的事件在同一分区内保持时间顺序，不会乱序消费。
- Topic 提前用 3 个分区创建（`docker-compose`/应用启动时用 `NewTopic` Bean 声明），单 broker 但多分区，用来演示消费者组内可以并行消费不同分区，即使这次只跑一个消费者实例。

## 5. Producer 设计

`RealtimeEventProducer`：
- 通过 `start(RealtimeSimulationConfig config)` / `stop()` 控制启停，内部用 `ScheduledExecutorService` 起一个周期任务（不是简单的 `while(true)` 死循环，方便优雅停止）。
- `RealtimeSimulationConfig` 包含：`batchId`、事件生成间隔（如"每 2 秒生成一批事件"）、每批事件数量。
- 每次执行：从 `EmployeeRepository`/`ContentRepository` 里随机取样员工和内容（数量不大，直接查询即可，不需要为此建缓存层），按概率决定本轮事件类型和参与者，通过 `KafkaTemplate<String, RealtimeEvent>` 发送到 `community-events`。当随机决定生成 `COMMENT`（回复场景）或 `COMMENT_LIKE` 时，额外从 `CommentRepository` 按选中的 `contentId` 查一批已有评论作为候选目标（`findByParentCommentId` 为空的作为可回复的顶层评论候选，任意已有评论作为可点赞的候选）；如果该内容目前还没有任何评论，则该轮退化为生成新的顶层评论（`COMMENT` 事件，`parentCommentId` 为空）而不是回复。
- 内部维护一个已发送事件计数（供 `/status` 接口返回），用 `AtomicLong` 保证线程安全。

## 6. Consumer 设计

`RealtimeEventConsumer`：
- `@KafkaListener(topics = "community-events", groupId = "jiaxian-analytics-realtime")`，方法签名接收反序列化后的 `RealtimeEvent`。
- 内部持有 `BehaviorEventGenerator` 实例（复用 Plan 1 的构造方式，注入相应的 Repository），按 `eventType` 做一次 `switch`/`if-else` 分发到对应的 `generateXxx` 方法。
- 分发完成后，调用一个内部的 `RealtimeCounterUpdater`（见第 7 节）更新 Redis。
- 消费异常处理：如果反序列化失败或者 `eventType` 未知，记录日志并跳过（不重试、不进死信队列——这是一个参考/演示项目，简单的"跳过并记录"足够，暂不引入更复杂的重试/DLQ 机制）。

## 7. Redis 计数器设计

`RealtimeCounterUpdater`（Consumer 内部依赖，用 `StringRedisTemplate`）：
- `rt:pv:channel:{channelId}:{yyyy-MM-dd}`：频道当日 PV，`VIEW` 事件触发时 `INCR`
- `rt:engagement:channel:{channelId}:{yyyy-MM-dd}`：频道当日互动数（`LIKE`/`SHARE`/`FAVORITE`/`COMMENT`/`COMMENT_LIKE` 汇总），对应事件触发时 `INCR`
- 更新时用 `content.getChannelId()` 查出所属频道；两个 key 都设置到"当天结束"的 TTL（`INCR` 后如果是新 key 则顺带 `EXPIRE`），避免 key 无限堆积。
- `LOGIN` 事件不更新这两个计数器（跟频道无关）。

## 8. 管理 API

新增 `RealtimeSimulatorController`（复用 `SimulatorController` 已经确立的 `/api/admin/simulator` 前缀）：
- `POST /api/admin/simulator/realtime/start`：body 含 `batchId`、生成间隔、每批数量；如果已在运行则返回冲突提示，不允许重复启动。
- `POST /api/admin/simulator/realtime/stop`：停止后台任务；如果本来就没在运行，返回明确提示而不是报错。
- `GET /api/admin/simulator/realtime/status`：返回 `running`（布尔）、`batchId`、已发送事件数、启动时间。

## 9. 测试策略

- **Producer/Consumer 端到端**：对接 docker-compose 里真实运行的 Kafka（延续这个项目"不用假基础设施掩盖真实差异"的原则——历史上已经因为这个原则在 openGauss 上挖出好几个真实兼容性问题）。集成测试启动生产者、等待若干事件被消费、断言数据库和 Redis 里的计数变化。
- **Consumer 事件分发逻辑**：用 Mockito 单元测试覆盖"给定某个 `eventType` 应该调用 `BehaviorEventGenerator` 的哪个方法"，不需要真 Kafka。
- **管理 API**：集成测试覆盖启动/停止/状态查询的基本流程，包括"重复启动/重复停止"的边界情况。

## 10. 明确不做的范围（Out of Scope）

- 不做消息重试、死信队列——演示/参考项目，消费失败直接记录日志跳过。
- 不引入 schema registry（Avro/Protobuf）——用 Spring Kafka 默认 JSON 序列化，跟项目现有技术选型一致。
- 不做多消费者实例的水平扩展验证——topic 建 3 个分区是为了展示"设计上支持并行消费"，但本阶段只跑单实例。
- 不做排行榜相关的 Redis Sorted Set——留给后续"积分排行榜"计划。
- 不做实时生成新内容（发帖）——只对已有内容产生互动事件。
