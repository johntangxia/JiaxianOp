# 积分引擎 + Kafka 实时流水线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出一个可通过管理 API 启停的"实时事件生产者"，经单一 Kafka topic 把事件交给消费者，消费者复用 Plan 1 已有的积分引擎（`BehaviorEventGenerator`）落库，并把"今日 PV / 今日互动数"写进 Redis 计数器。

**Architecture:** Producer 只负责"决定发生什么事"（纯逻辑放在 `RealtimeEventPlanner`，Kafka 发送和调度放在 `RealtimeEventProducer`），事件序列化成 JSON 发到 `community-events` topic（3 分区，按 contentId / LOGIN 按员工 id 分区）；`RealtimeEventConsumer`（`@KafkaListener`）反序列化后交给事务内的 `RealtimeEventHandler` 分发给 `BehaviorEventGenerator` 对应方法，事务提交后再由 `RealtimeCounterUpdater` 更新 Redis。集成测试全部对接 docker-compose 里真实运行的 Kafka/Redis/OpenGauss。

**Tech Stack:** Spring Boot 3.2.5 / Spring Kafka（Boot 管理版本）/ Spring Data Redis（Lettuce）/ Lombok / JUnit 5 + Mockito + Awaitility（均来自 `spring-boot-starter-test`）。

---

## 参考文档

- 设计文档：[`docs/superpowers/specs/2026-09-23-points-engine-kafka-pipeline-design.md`](../specs/2026-09-23-points-engine-kafka-pipeline-design.md)
- 前置计划（已全部完成）：[`docs/superpowers/plans/2026-09-23-infra-and-data-model.md`](2026-09-23-infra-and-data-model.md)，尤其是它顶部的"执行过程中发现的修正记录"——下面"必读的项目约定"是从那份记录里提炼出的、对本计划有直接影响的部分。

## 必读的项目约定（Plan 1 踩过的坑）

1. **基础设施必须先起来**：`docker compose up -d` 之后 `jiaxian-opengauss` / `jiaxian-redis` / `jiaxian-kafka` 三个容器都是 `Up`。所有 `*IT` 测试都连真实基础设施，不用嵌入式/模拟替代品。
2. **OpenGauss 不支持 Spring Data 派生的"取第一条/前 N 条"查询**（`findFirstBy...`、`findTopBy...`、`Pageable` 分页都会生成带绑定参数的 `FETCH FIRST ? ROWS ONLY`，OpenGauss 解析器报语法错误）。本计划里**不要**写这类查询；需要随机抽样时先 `findAll()` 再在 Java 里抽。普通的 `findByXxx` / `countByXxx` 派生查询没问题。
3. **测试夹具不能撞上种子数据**：`data.sql` 每次启动都会往库里灌 15 个真实频道名和 15 条积分权重配置（`UNIQUE` 约束）。测试里新建 `Channel`/`PointsWeightConfig` 必须用明显是测试专用的名字（如 `测试频道-xxx`、`TEST_LIKE`）。本计划的新测试基本不需要新建这两类数据。
4. **每个任务做完都要跑一次不带 `-Dtest` 的全量 `mvn test`**——Plan 1 里有两个 bug 只有全量跑才暴露。
5. **本机 8080 端口被无关服务占用**：手工起后端时用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093`；跑完要确认进程真的杀掉了（`lsof -i :8093` 为空）。`@SpringBootTest` 用 `RANDOM_PORT`/默认 MOCK 环境，不受影响。
6. **首次添加依赖后的第一次 Maven 命令必须联网**（不要加 `-o`），让 Maven 把 `spring-kafka`、`spring-boot-starter-data-redis` 下载下来；之后可以用 `-o`。
7. 提交信息末尾带上当前会话约定的 `Co-Authored-By` 署名行。

## 文件结构

```
backend/
├── pom.xml                                         (修改：加 spring-kafka、spring-boot-starter-data-redis)
└── src/
    ├── main/
    │   ├── java/com/jiaxian/analytics/
    │   │   ├── realtime/
    │   │   │   ├── RealtimeConfig.java             (topic 声明 + BehaviorEventGenerator Bean)
    │   │   │   ├── RealtimeEventType.java          (事件类型枚举)
    │   │   │   ├── RealtimeEvent.java              (Kafka 消息体 + 分区 key 规则)
    │   │   │   ├── RealtimeEventPlanner.java       (纯逻辑：随机决定一批事件)
    │   │   │   ├── RealtimeEventHandler.java       (事务内：按类型分发给 BehaviorEventGenerator)
    │   │   │   ├── RealtimeCounterUpdater.java     (Redis 今日 PV / 互动数计数器)
    │   │   │   ├── RealtimeSimulationConfig.java   (启动参数)
    │   │   │   ├── RealtimeStatus.java             (运行状态快照)
    │   │   │   ├── RealtimeEventProducer.java      (后台调度 + Kafka 发送)
    │   │   │   ├── RealtimeEventConsumer.java      (@KafkaListener)
    │   │   │   └── RealtimeSimulatorController.java(启停/状态 API)
    │   │   └── repository/
    │   │       ├── CommentRepository.java          (修改：加 findByContentId)
    │   │       ├── PvLogRepository.java            (修改：加 countBySimulationBatchId)
    │   │       └── PointsLedgerRepository.java     (修改：加 findBySimulationBatchId)
    │   └── resources/application.yml               (修改：加 kafka/redis/realtime 配置)
    └── test/
        ├── resources/application.properties        (新增：测试默认关闭 Kafka 消费者)
        └── java/com/jiaxian/analytics/
            ├── realtime/
            │   ├── RealtimeInfrastructureIT.java
            │   ├── RealtimeEventTest.java
            │   ├── RealtimeEventPlannerTest.java
            │   ├── RealtimeEventHandlerTest.java
            │   ├── RealtimeCounterUpdaterTest.java
            │   ├── RealtimeEventProducerTest.java
            │   ├── RealtimeEventConsumerTest.java
            │   ├── RealtimeSimulatorControllerIT.java
            │   └── RealtimePipelineIT.java
            └── repository/DimensionRepositoryIT.java (修改：加一条断言)
```

**关于 Kafka 消费者在测试里的开关**：`@KafkaListener` 的 `autoStartup` 由属性 `realtime.consumer.auto-startup` 控制，默认 `true`；`src/test/resources/application.properties` 把它设成 `false`，这样 Plan 1 留下的那些 `@SpringBootTest`（以及被 Spring 缓存的旧上下文）不会各自起一个同组消费者去抢分区、重复消费。只有 Task 10 的端到端测试用 `@SpringBootTest(properties = "realtime.consumer.auto-startup=true")` 打开它，并配 `@DirtiesContext` 在类结束后关闭上下文。

---

### Task 1: 依赖、配置、Topic 声明与基础设施冒烟测试

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeInfrastructureIT.java`
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeConfig.java`
- Create: `backend/src/test/resources/application.properties`

- [ ] **Step 1: 编写失败的基础设施冒烟测试**

```java
package com.jiaxian.analytics.realtime;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RealtimeInfrastructureIT {

    @Autowired private KafkaAdmin kafkaAdmin;
    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    void communityEventsTopicExistsWithThreePartitions() throws Exception {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = adminClient
                .describeTopics(List.of(RealtimeConfig.COMMUNITY_EVENTS_TOPIC))
                .allTopicNames().get(30, TimeUnit.SECONDS)
                .get(RealtimeConfig.COMMUNITY_EVENTS_TOPIC);

            assertThat(description.partitions()).hasSize(3);
        }
    }

    @Test
    void redisRoundTripWorks() {
        String key = "rt:test:infrastructure-it";
        redisTemplate.opsForValue().set(key, "ok");
        try {
            assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ok");
        } finally {
            redisTemplate.delete(key);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeInfrastructureIT`
Expected: FAIL，编译错误（`org.apache.kafka.clients.admin`、`org.springframework.kafka.core`、`StringRedisTemplate`、`RealtimeConfig` 都找不到）。

- [ ] **Step 3: 在 `backend/pom.xml` 加依赖**

在 `<dependencies>` 里 `opengauss-jdbc` 那个 `<dependency>` 块的后面，追加：

```xml
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
```

（版本由 Spring Boot parent 管理，不写 `<version>`。）

- [ ] **Step 4: 把 `backend/src/main/resources/application.yml` 整个替换成下面的内容**

```yaml
spring:
  application:
    name: jiaxian-analytics
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/postgres}
    username: ${DB_USERNAME:jiaxian_app}
    password: ${DB_PASSWORD:Jiaxian@2026}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    defer-datasource-initialization: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  sql:
    init:
      mode: always
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      repositories:
        enabled: false
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        spring.json.add.type.headers: false
    consumer:
      group-id: jiaxian-analytics-realtime
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.value.default.type: com.jiaxian.analytics.realtime.RealtimeEvent
        spring.json.use.type.headers: false
        spring.json.trusted.packages: com.jiaxian.analytics.realtime

realtime:
  consumer:
    auto-startup: true

server:
  port: 8080
```

**说明**：
- 消费端用 `ErrorHandlingDeserializer` 包一层 `JsonDeserializer`：消息体反序列化失败（比如 `eventType` 是未知值）时，容器的默认错误处理器会记录日志并跳过这条消息，而不是无限重试同一条坏消息——对应设计文档"反序列化失败/未知类型 → 记录并跳过"。
- `auto-offset-reset: earliest`：新消费组第一次启动时从头消费，避免"消费者还没分配到分区、Producer 已经发出去的消息被跳过"。之后消费组有了已提交位点就从位点继续。
- Redis 的 Spring Data Repository 扫描没用到，关掉避免多余的启动日志。

- [ ] **Step 5: 创建 `RealtimeConfig`（先只声明 topic，Task 5 会再往里加 Bean）**

```java
package com.jiaxian.analytics.realtime;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class RealtimeConfig {

    public static final String COMMUNITY_EVENTS_TOPIC = "community-events";

    @Bean
    public NewTopic communityEventsTopic() {
        return TopicBuilder.name(COMMUNITY_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
```

- [ ] **Step 6: 创建测试用配置 `backend/src/test/resources/application.properties`**

```properties
realtime.consumer.auto-startup=false
```

（`src/test/resources` 下的 `application.properties` 会和 `src/main/resources/application.yml` 一起被 Spring Boot 加载，`.properties` 里的同名属性优先；它不会替换掉主配置文件。）

- [ ] **Step 7: 运行测试确认通过**

前提：`docker compose ps` 里 opengauss / redis / kafka 都是 `Up`。

Run: `cd backend && mvn test -Dtest=RealtimeInfrastructureIT`（首次联网下载新依赖，不要加 `-o`）
Expected: PASS（2 个测试）。

- [ ] **Step 8: 跑全量测试确认没有回归**

Run: `cd backend && mvn test`
Expected: `Tests run: 18, Failures: 0, Errors: 0`，`BUILD SUCCESS`（原有 16 个 + 本任务 2 个）。

- [ ] **Step 9: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
        backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeConfig.java \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeInfrastructureIT.java
git commit -m "feat: add Kafka/Redis dependencies, config and community-events topic"
```

---

### Task 2: 事件类型与 Kafka 消息体

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventTest.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventType.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEvent.java`

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeEventTest {

    // 和 Spring Kafka 的 JsonSerializer/JsonDeserializer 默认使用的是同一个 ObjectMapper 工厂
    private final ObjectMapper objectMapper = JacksonUtils.enhancedObjectMapper();

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        RealtimeEvent original = RealtimeEvent.builder()
            .eventType(RealtimeEventType.COMMENT)
            .contentId(10L)
            .parentCommentId(20L)
            .targetCommentId(30L)
            .actorEmployeeId(40L)
            .occurTime(LocalDateTime.of(2026, 10, 4, 12, 30, 15, 123_000_000))
            .batchId("batch-x")
            .build();

        String json = objectMapper.writeValueAsString(original);
        RealtimeEvent restored = objectMapper.readValue(json, RealtimeEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void partitionKeyIsNotSerializedIntoTheMessage() throws Exception {
        RealtimeEvent event = RealtimeEvent.builder()
            .eventType(RealtimeEventType.VIEW).contentId(1L).actorEmployeeId(2L).build();

        assertThat(objectMapper.writeValueAsString(event)).doesNotContain("partitionKey");
    }

    @Test
    void contentEventsArePartitionedByContentId() {
        RealtimeEvent event = RealtimeEvent.builder()
            .eventType(RealtimeEventType.LIKE).contentId(42L).actorEmployeeId(7L).build();

        assertThat(event.partitionKey()).isEqualTo("42");
    }

    @Test
    void loginEventsArePartitionedByActorEmployeeId() {
        RealtimeEvent event = RealtimeEvent.builder()
            .eventType(RealtimeEventType.LOGIN).actorEmployeeId(7L).build();

        assertThat(event.partitionKey()).isEqualTo("7");
    }

    @Test
    void unknownEventTypeFailsDeserialization() {
        String json = "{\"eventType\":\"SOMETHING_ELSE\",\"actorEmployeeId\":1}";

        assertThatThrownBy(() -> objectMapper.readValue(json, RealtimeEvent.class))
            .isInstanceOf(JsonProcessingException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeEventTest`
Expected: FAIL，编译错误（`RealtimeEvent`、`RealtimeEventType` 尚不存在）。

- [ ] **Step 3: 实现事件类型枚举**

```java
package com.jiaxian.analytics.realtime;

public enum RealtimeEventType {
    VIEW, LIKE, SHARE, FAVORITE, COMMENT, COMMENT_LIKE, LOGIN
}
```

- [ ] **Step 4: 实现消息体**

```java
package com.jiaxian.analytics.realtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeEvent {

    private RealtimeEventType eventType;
    /** 除 LOGIN 外都需要 */
    private Long contentId;
    /** 仅 COMMENT 事件、且是"回复"而不是"新建顶层评论"时才非空 */
    private Long parentCommentId;
    /** 仅 COMMENT_LIKE 事件需要，表示被点赞的评论 id */
    private Long targetCommentId;
    private Long actorEmployeeId;
    private LocalDateTime occurTime;
    private String batchId;

    /** Kafka 消息 key：LOGIN 不关联内容，用员工 id；其余用内容 id，保证同一篇内容的事件落在同一分区、有序。 */
    public String partitionKey() {
        if (eventType == RealtimeEventType.LOGIN) {
            return String.valueOf(actorEmployeeId);
        }
        return String.valueOf(contentId);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeEventTest`
Expected: PASS（5 个测试）。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventType.java \
        backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEvent.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventTest.java
git commit -m "feat: add realtime event type and Kafka message body"
```

---

### Task 3: `CommentRepository.findByContentId`

**Files:**
- Modify: `backend/src/test/java/com/jiaxian/analytics/repository/DimensionRepositoryIT.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/repository/CommentRepository.java`

- [ ] **Step 1: 在已有集成测试末尾追加一条失败的断言**

在 `DimensionRepositoryIT.savesAndLoadsFullDimensionGraph()` 里，紧跟在现有三条断言之后（`assertThat(commentRepository.findByParentCommentId(rootComment.getId())).containsExactly(reply);` 这一行之后）追加：

```java
        assertThat(commentRepository.findByContentId(content.getId()))
            .extracting(Comment::getId)
            .containsExactlyInAnyOrder(rootComment.getId(), reply.getId());
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=DimensionRepositoryIT`
Expected: FAIL，编译错误（`CommentRepository.findByContentId` 尚不存在）。

- [ ] **Step 3: 给 `CommentRepository` 加派生查询**

在 `List<Comment> findByParentCommentId(Long parentCommentId);` 这一行的下面追加一行（其余内容，包括 `deleteReplies()` 和它上面那段说明注释，保持不动）：

```java
    List<Comment> findByContentId(Long contentId);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=DimensionRepositoryIT`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/repository/CommentRepository.java \
        backend/src/test/java/com/jiaxian/analytics/repository/DimensionRepositoryIT.java
git commit -m "feat: add CommentRepository.findByContentId"
```

---

### Task 4: 事件规划器（纯逻辑：随机决定一批事件）

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventPlannerTest.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventPlanner.java`

**概率设计**（单个事件的一次掷骰）：VIEW 60% / LIKE 12% / SHARE 4% / FAVORITE 3% / COMMENT 10% / COMMENT_LIKE 6% / LOGIN 5%。内容按 `popularityWeight` 加权随机选取（热度高的更常被选中）。COMMENT 有 30% 的概率是"回复"（目标只会是顶层评论，保证最多两级）；内容还没有任何评论时 COMMENT_LIKE 退化为新建顶层评论。

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeEventPlannerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 12, 0);
    private static final String BATCH = "planner-test";

    private static List<Employee> employees(int count) {
        List<Employee> employees = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            employees.add(Employee.builder().id(id).build());
        }
        return employees;
    }

    private static Content content(long id, double popularityWeight) {
        return Content.builder().id(id).popularityWeight(popularityWeight).build();
    }

    private static Comment comment(long id, Long parentCommentId) {
        return Comment.builder().id(id).parentCommentId(parentCommentId).build();
    }

    private static final Function<Long, List<Comment>> NO_COMMENTS = contentId -> List.of();

    private List<RealtimeEvent> plan(RealtimeEventPlanner planner, int count, List<Content> contents,
                                     Function<Long, List<Comment>> comments) {
        return planner.planBatch(count, BATCH, NOW, employees(5), contents, comments);
    }

    @Test
    void producesRequestedNumberOfEventsStampedWithBatchAndTime() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 25, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).hasSize(25);
        assertThat(events).allMatch(e -> BATCH.equals(e.getBatchId()) && NOW.equals(e.getOccurTime()));
        assertThat(events).allMatch(e -> e.getActorEmployeeId() >= 1 && e.getActorEmployeeId() <= 5);
    }

    @Test
    void loginEventsCarryNoContentAndAllOtherEventsDo() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).filteredOn(e -> e.getEventType() == RealtimeEventType.LOGIN)
            .isNotEmpty()
            .allMatch(e -> e.getContentId() == null);
        assertThat(events).filteredOn(e -> e.getEventType() != RealtimeEventType.LOGIN)
            .allMatch(e -> e.getContentId() != null);
    }

    @Test
    void popularContentIsPickedFarMoreOftenThanUnpopularContent() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 2000,
            List.of(content(1, 0.9), content(2, 0.1)), NO_COMMENTS);

        long hot = events.stream().filter(e -> Long.valueOf(1).equals(e.getContentId())).count();
        long cold = events.stream().filter(e -> Long.valueOf(2).equals(e.getContentId())).count();
        assertThat(hot).isGreaterThan(cold * 3);
    }

    @Test
    void withoutExistingCommentsThereAreNoRepliesAndNoCommentLikes() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), NO_COMMENTS);

        assertThat(events).noneMatch(e -> e.getEventType() == RealtimeEventType.COMMENT_LIKE);
        assertThat(events).filteredOn(e -> e.getEventType() == RealtimeEventType.COMMENT)
            .isNotEmpty()
            .allMatch(e -> e.getParentCommentId() == null);
    }

    @Test
    void repliesTargetOnlyTopLevelCommentsAndCommentLikesTargetExistingComments() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Comment> existing = List.of(comment(1, null), comment(2, null), comment(3, 1L));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), contentId -> existing);

        assertThat(events)
            .filteredOn(e -> e.getEventType() == RealtimeEventType.COMMENT && e.getParentCommentId() != null)
            .isNotEmpty()
            .extracting(RealtimeEvent::getParentCommentId)
            .containsOnly(1L, 2L);
        assertThat(events).filteredOn(e -> e.getEventType() == RealtimeEventType.COMMENT_LIKE)
            .isNotEmpty()
            .allMatch(e -> e.getContentId() != null)
            .extracting(RealtimeEvent::getTargetCommentId)
            .containsOnly(1L, 2L, 3L);
    }

    @Test
    void allSevenEventTypesAppearWhenCommentsExist() {
        RealtimeEventPlanner planner = new RealtimeEventPlanner(new Random(42));
        List<Comment> existing = List.of(comment(1, null));

        List<RealtimeEvent> events = plan(planner, 2000, List.of(content(1, 0.5)), contentId -> existing);

        assertThat(events).extracting(RealtimeEvent::getEventType)
            .contains(RealtimeEventType.values());
    }

    @Test
    void sameSeedProducesSameEvents() {
        List<Content> contents = List.of(content(1, 0.6), content(2, 0.4));

        List<RealtimeEvent> first = plan(new RealtimeEventPlanner(new Random(5)), 50, contents, NO_COMMENTS);
        List<RealtimeEvent> second = plan(new RealtimeEventPlanner(new Random(5)), 50, contents, NO_COMMENTS);

        assertThat(first).isEqualTo(second);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeEventPlannerTest`
Expected: FAIL，编译错误（`RealtimeEventPlanner` 尚不存在）。

- [ ] **Step 3: 实现规划器**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Comment;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

public class RealtimeEventPlanner {

    private static final double VIEW_THRESHOLD = 0.60;
    private static final double LIKE_THRESHOLD = 0.72;
    private static final double SHARE_THRESHOLD = 0.76;
    private static final double FAVORITE_THRESHOLD = 0.79;
    private static final double COMMENT_THRESHOLD = 0.89;
    private static final double COMMENT_LIKE_THRESHOLD = 0.95;
    private static final double REPLY_PROBABILITY = 0.3;

    private final Random random;

    public RealtimeEventPlanner(Random random) {
        this.random = random;
    }

    public List<RealtimeEvent> planBatch(int eventCount, String batchId, LocalDateTime occurTime,
                                         List<Employee> employees, List<Content> contents,
                                         Function<Long, List<Comment>> commentsByContentId) {
        List<RealtimeEvent> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(planOne(batchId, occurTime, employees, contents, commentsByContentId));
        }
        return events;
    }

    private RealtimeEvent planOne(String batchId, LocalDateTime occurTime, List<Employee> employees,
                                  List<Content> contents, Function<Long, List<Comment>> commentsByContentId) {
        Employee actor = employees.get(random.nextInt(employees.size()));
        double roll = random.nextDouble();

        if (roll >= COMMENT_LIKE_THRESHOLD) {
            return RealtimeEvent.builder()
                .eventType(RealtimeEventType.LOGIN)
                .actorEmployeeId(actor.getId())
                .occurTime(occurTime)
                .batchId(batchId)
                .build();
        }

        Content content = pickContentByPopularity(contents);
        if (roll < VIEW_THRESHOLD) {
            return contentEvent(RealtimeEventType.VIEW, content, actor, batchId, occurTime);
        }
        if (roll < LIKE_THRESHOLD) {
            return contentEvent(RealtimeEventType.LIKE, content, actor, batchId, occurTime);
        }
        if (roll < SHARE_THRESHOLD) {
            return contentEvent(RealtimeEventType.SHARE, content, actor, batchId, occurTime);
        }
        if (roll < FAVORITE_THRESHOLD) {
            return contentEvent(RealtimeEventType.FAVORITE, content, actor, batchId, occurTime);
        }

        List<Comment> comments = commentsByContentId.apply(content.getId());
        if (roll < COMMENT_THRESHOLD) {
            return planComment(content, actor, comments, batchId, occurTime);
        }
        return planCommentLike(content, actor, comments, batchId, occurTime);
    }

    private RealtimeEvent contentEvent(RealtimeEventType type, Content content, Employee actor,
                                       String batchId, LocalDateTime occurTime) {
        return RealtimeEvent.builder()
            .eventType(type)
            .contentId(content.getId())
            .actorEmployeeId(actor.getId())
            .occurTime(occurTime)
            .batchId(batchId)
            .build();
    }

    private RealtimeEvent planComment(Content content, Employee actor, List<Comment> comments,
                                      String batchId, LocalDateTime occurTime) {
        List<Comment> topLevel = comments.stream().filter(c -> c.getParentCommentId() == null).toList();
        Long parentCommentId = null;
        if (!topLevel.isEmpty() && random.nextDouble() < REPLY_PROBABILITY) {
            parentCommentId = topLevel.get(random.nextInt(topLevel.size())).getId();
        }
        return RealtimeEvent.builder()
            .eventType(RealtimeEventType.COMMENT)
            .contentId(content.getId())
            .parentCommentId(parentCommentId)
            .actorEmployeeId(actor.getId())
            .occurTime(occurTime)
            .batchId(batchId)
            .build();
    }

    private RealtimeEvent planCommentLike(Content content, Employee actor, List<Comment> comments,
                                          String batchId, LocalDateTime occurTime) {
        if (comments.isEmpty()) {
            return planComment(content, actor, comments, batchId, occurTime);
        }
        Comment target = comments.get(random.nextInt(comments.size()));
        return RealtimeEvent.builder()
            .eventType(RealtimeEventType.COMMENT_LIKE)
            .contentId(content.getId())
            .targetCommentId(target.getId())
            .actorEmployeeId(actor.getId())
            .occurTime(occurTime)
            .batchId(batchId)
            .build();
    }

    private Content pickContentByPopularity(List<Content> contents) {
        double total = contents.stream().mapToDouble(Content::getPopularityWeight).sum();
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (Content content : contents) {
            cumulative += content.getPopularityWeight();
            if (target < cumulative) {
                return content;
            }
        }
        return contents.get(contents.size() - 1);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeEventPlannerTest`
Expected: PASS（7 个测试）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventPlanner.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventPlannerTest.java
git commit -m "feat: add realtime event planner"
```

---

### Task 5: 事件处理器（复用积分引擎落库）

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventHandlerTest.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventHandler.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeConfig.java`

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.simulator.BehaviorEventGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RealtimeEventHandlerTest {

    private static final Long ACTOR = 7L;
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 4, 12, 0);
    private static final String BATCH = "handler-test";

    @Mock private BehaviorEventGenerator eventGenerator;

    private RealtimeEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RealtimeEventHandler(eventGenerator);
    }

    private RealtimeEvent.RealtimeEventBuilder event(RealtimeEventType type) {
        return RealtimeEvent.builder().eventType(type).actorEmployeeId(ACTOR).occurTime(TIME).batchId(BATCH);
    }

    @Test
    void viewDispatchesToGenerateView() {
        handler.handle(event(RealtimeEventType.VIEW).contentId(10L).build());

        verify(eventGenerator).generateView(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void likeDispatchesToGenerateLike() {
        handler.handle(event(RealtimeEventType.LIKE).contentId(10L).build());

        verify(eventGenerator).generateLike(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void shareDispatchesToGenerateShare() {
        handler.handle(event(RealtimeEventType.SHARE).contentId(10L).build());

        verify(eventGenerator).generateShare(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void favoriteDispatchesToGenerateFavorite() {
        handler.handle(event(RealtimeEventType.FAVORITE).contentId(10L).build());

        verify(eventGenerator).generateFavorite(10L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void topLevelCommentDispatchesWithNullParent() {
        handler.handle(event(RealtimeEventType.COMMENT).contentId(10L).build());

        verify(eventGenerator).generateComment(10L, null, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void replyCommentDispatchesWithParentCommentId() {
        handler.handle(event(RealtimeEventType.COMMENT).contentId(10L).parentCommentId(55L).build());

        verify(eventGenerator).generateComment(10L, 55L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void commentLikeDispatchesToGenerateCommentLike() {
        handler.handle(event(RealtimeEventType.COMMENT_LIKE).contentId(10L).targetCommentId(55L).build());

        verify(eventGenerator).generateCommentLike(55L, ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void loginDispatchesToGenerateLogin() {
        handler.handle(event(RealtimeEventType.LOGIN).build());

        verify(eventGenerator).generateLogin(ACTOR, TIME, BATCH);
        verifyNoMoreInteractions(eventGenerator);
    }

    @Test
    void missingEventTypeIsRejected() {
        assertThatThrownBy(() -> handler.handle(event(null).contentId(10L).build()))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void missingActorIsRejected() {
        RealtimeEvent event = event(RealtimeEventType.VIEW).contentId(10L).actorEmployeeId(null).build();

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void contentEventWithoutContentIdIsRejected() {
        assertThatThrownBy(() -> handler.handle(event(RealtimeEventType.VIEW).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contentId");
        verifyNoInteractions(eventGenerator);
    }

    @Test
    void commentLikeWithoutTargetCommentIdIsRejected() {
        assertThatThrownBy(() -> handler.handle(event(RealtimeEventType.COMMENT_LIKE).contentId(10L).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetCommentId");
        verifyNoInteractions(eventGenerator);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeEventHandlerTest`
Expected: FAIL，编译错误（`RealtimeEventHandler` 尚不存在）。

- [ ] **Step 3: 实现处理器**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.simulator.BehaviorEventGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个数据库事务里把一条实时事件交给 Plan 1 已有的积分引擎落库：
 * 一个事件产生的明细记录和两边的积分流水要么全部成功，要么全部回滚。
 */
@Service
@Transactional
public class RealtimeEventHandler {

    private final BehaviorEventGenerator eventGenerator;

    public RealtimeEventHandler(BehaviorEventGenerator eventGenerator) {
        this.eventGenerator = eventGenerator;
    }

    public void handle(RealtimeEvent event) {
        if (event.getEventType() == null) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (event.getActorEmployeeId() == null) {
            throw new IllegalArgumentException("actorEmployeeId is required");
        }

        Long actor = event.getActorEmployeeId();
        switch (event.getEventType()) {
            case VIEW -> eventGenerator.generateView(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case LIKE -> eventGenerator.generateLike(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case SHARE -> eventGenerator.generateShare(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case FAVORITE -> eventGenerator.generateFavorite(
                requireContentId(event), actor, event.getOccurTime(), event.getBatchId());
            case COMMENT -> eventGenerator.generateComment(
                requireContentId(event), event.getParentCommentId(), actor, event.getOccurTime(), event.getBatchId());
            case COMMENT_LIKE -> eventGenerator.generateCommentLike(
                requireTargetCommentId(event), actor, event.getOccurTime(), event.getBatchId());
            case LOGIN -> eventGenerator.generateLogin(actor, event.getOccurTime(), event.getBatchId());
        }
    }

    private Long requireContentId(RealtimeEvent event) {
        if (event.getContentId() == null) {
            throw new IllegalArgumentException("contentId is required for " + event.getEventType());
        }
        return event.getContentId();
    }

    private Long requireTargetCommentId(RealtimeEvent event) {
        if (event.getTargetCommentId() == null) {
            throw new IllegalArgumentException("targetCommentId is required for " + event.getEventType());
        }
        return event.getTargetCommentId();
    }
}
```

- [ ] **Step 4: 把 `RealtimeConfig.java` 整个替换成下面的内容（新增 `BehaviorEventGenerator` Bean）**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.repository.ActiveActionLogRepository;
import com.jiaxian.analytics.repository.CommentRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.EngagementLogRepository;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import com.jiaxian.analytics.repository.PointsWeightConfigRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import com.jiaxian.analytics.simulator.BehaviorEventGenerator;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Random;

@Configuration
public class RealtimeConfig {

    public static final String COMMUNITY_EVENTS_TOPIC = "community-events";

    @Bean
    public NewTopic communityEventsTopic() {
        return TopicBuilder.name(COMMUNITY_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    /** 复用 Plan 1 的积分引擎；它本身没有可变状态，可以作为单例在消费线程里共用。 */
    @Bean
    public BehaviorEventGenerator behaviorEventGenerator(PvLogRepository pvLogRepository,
                                                         EngagementLogRepository engagementLogRepository,
                                                         ActiveActionLogRepository activeActionLogRepository,
                                                         ContentAuthorRepository contentAuthorRepository,
                                                         PointsWeightConfigRepository pointsWeightConfigRepository,
                                                         PointsLedgerRepository pointsLedgerRepository,
                                                         CommentRepository commentRepository) {
        return new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random());
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeEventHandlerTest`
Expected: PASS（12 个测试）。

- [ ] **Step 6: 跑全量测试确认 Spring 上下文仍能正常启动（新 Bean 装配无误）**

Run: `cd backend && mvn test`
Expected: 全部通过，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventHandler.java \
        backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeConfig.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventHandlerTest.java
git commit -m "feat: add realtime event handler reusing the points engine"
```

---

### Task 6: Redis 计数器更新器

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeCounterUpdaterTest.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeCounterUpdater.java`

**Key 设计**：`rt:pv:channel:{channelId}:{yyyy-MM-dd}`（VIEW）、`rt:engagement:channel:{channelId}:{yyyy-MM-dd}`（LIKE/SHARE/FAVORITE/COMMENT/COMMENT_LIKE）；日期取事件 `occurTime` 的日期；key 第一次被创建（`INCR` 返回 1）时设置"次日零点过期"。LOGIN 事件不更新任何计数器。

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeCounterUpdaterTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 4, 12, 0);
    private static final String PV_KEY = "rt:pv:channel:7:2026-10-04";
    private static final String ENGAGEMENT_KEY = "rt:engagement:channel:7:2026-10-04";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ContentRepository contentRepository;

    private RealtimeCounterUpdater updater;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        updater = new RealtimeCounterUpdater(redisTemplate, contentRepository);
    }

    private void contentInChannel7() {
        when(contentRepository.findById(10L))
            .thenReturn(Optional.of(Content.builder().id(10L).channelId(7L).build()));
    }

    private RealtimeEvent event(RealtimeEventType type, Long contentId) {
        return RealtimeEvent.builder()
            .eventType(type).contentId(contentId).actorEmployeeId(1L).occurTime(TIME).batchId("b").build();
    }

    @Test
    void viewIncrementsChannelPvCounterOnly() {
        contentInChannel7();
        when(valueOperations.increment(PV_KEY)).thenReturn(5L);

        updater.record(event(RealtimeEventType.VIEW, 10L));

        verify(valueOperations).increment(PV_KEY);
        verify(valueOperations, never()).increment(ENGAGEMENT_KEY);
        verify(redisTemplate, never()).expireAt(anyString(), any(Instant.class));
    }

    @ParameterizedTest
    @EnumSource(value = RealtimeEventType.class,
        names = {"LIKE", "SHARE", "FAVORITE", "COMMENT", "COMMENT_LIKE"})
    void engagementEventsIncrementChannelEngagementCounterOnly(RealtimeEventType type) {
        contentInChannel7();
        when(valueOperations.increment(ENGAGEMENT_KEY)).thenReturn(3L);

        updater.record(event(type, 10L));

        verify(valueOperations).increment(ENGAGEMENT_KEY);
        verify(valueOperations, never()).increment(PV_KEY);
    }

    @Test
    void firstIncrementOfAKeySetsExpiryAtNextMidnight() {
        contentInChannel7();
        when(valueOperations.increment(PV_KEY)).thenReturn(1L);

        updater.record(event(RealtimeEventType.VIEW, 10L));

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(redisTemplate).expireAt(eq(PV_KEY), expiry.capture());
        assertThat(expiry.getValue())
            .isEqualTo(LocalDate.of(2026, 10, 5).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void loginDoesNotTouchRedisOrTheContentRepository() {
        updater.record(event(RealtimeEventType.LOGIN, null));

        verifyNoInteractions(valueOperations, contentRepository);
    }

    @Test
    void eventWithoutContentIdIsIgnored() {
        updater.record(event(RealtimeEventType.COMMENT_LIKE, null));

        verifyNoInteractions(valueOperations, contentRepository);
    }

    @Test
    void unknownContentIsIgnored() {
        when(contentRepository.findById(10L)).thenReturn(Optional.empty());

        updater.record(event(RealtimeEventType.VIEW, 10L));

        verifyNoInteractions(valueOperations);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeCounterUpdaterTest`
Expected: FAIL，编译错误（`RealtimeCounterUpdater` 尚不存在）。

- [ ] **Step 3: 实现计数器更新器**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.repository.ContentRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class RealtimeCounterUpdater {

    private static final String PV_KEY_PREFIX = "rt:pv:channel:";
    private static final String ENGAGEMENT_KEY_PREFIX = "rt:engagement:channel:";

    private final StringRedisTemplate redisTemplate;
    private final ContentRepository contentRepository;

    public RealtimeCounterUpdater(StringRedisTemplate redisTemplate, ContentRepository contentRepository) {
        this.redisTemplate = redisTemplate;
        this.contentRepository = contentRepository;
    }

    public void record(RealtimeEvent event) {
        String keyPrefix = switch (event.getEventType()) {
            case VIEW -> PV_KEY_PREFIX;
            case LIKE, SHARE, FAVORITE, COMMENT, COMMENT_LIKE -> ENGAGEMENT_KEY_PREFIX;
            case LOGIN -> null;
        };
        if (keyPrefix == null || event.getContentId() == null) {
            return;
        }

        contentRepository.findById(event.getContentId()).ifPresent(content ->
            increment(keyPrefix, content.getChannelId(), event.getOccurTime().toLocalDate()));
    }

    private void increment(String keyPrefix, Long channelId, LocalDate date) {
        String key = keyPrefix + channelId + ":" + date;
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expireAt(key, date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeCounterUpdaterTest`
Expected: PASS（10 个测试：1 + 参数化 5 + 4）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeCounterUpdater.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeCounterUpdaterTest.java
git commit -m "feat: add Redis daily PV/engagement counter updater"
```

---

### Task 7: 实时事件生产者（后台调度 + Kafka 发送）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeSimulationConfig.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeStatus.java`
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventProducerTest.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventProducer.java`

- [ ] **Step 1: 创建两个简单的数据类（供测试和实现共用）**

```java
package com.jiaxian.analytics.realtime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RealtimeSimulationConfig {
    private String batchId;
    private int intervalMillis;
    private int eventsPerBatch;
}
```

```java
package com.jiaxian.analytics.realtime;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class RealtimeStatus {
    private final boolean running;
    private final String batchId;
    private final long sentCount;
    private final LocalDateTime startedAt;
}
```

- [ ] **Step 2: 编写失败的单元测试**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.CommentRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeEventProducerTest {

    @Mock private KafkaTemplate<String, RealtimeEvent> kafkaTemplate;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private CommentRepository commentRepository;

    private RealtimeEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new RealtimeEventProducer(kafkaTemplate, employeeRepository, contentRepository,
            commentRepository, new RealtimeEventPlanner(new Random(1)));
    }

    @AfterEach
    void tearDown() {
        producer.stop();
    }

    private List<Employee> employees() {
        return List.of(Employee.builder().id(1L).build(), Employee.builder().id(2L).build());
    }

    private List<Content> contents() {
        return List.of(Content.builder().id(10L).popularityWeight(0.5).build());
    }

    private void stubData() {
        when(employeeRepository.findAll()).thenReturn(employees());
        when(contentRepository.findAll()).thenReturn(contents());
    }

    private void stubSend() {
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(RealtimeEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    private RealtimeSimulationConfig config(int eventsPerBatch) {
        return RealtimeSimulationConfig.builder()
            .batchId("producer-test").intervalMillis(20).eventsPerBatch(eventsPerBatch).build();
    }

    @Test
    void publishBatchSendsOneKeyedMessagePerPlannedEvent() {
        stubSend();

        producer.publishBatch(config(20), employees(), contents());

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RealtimeEvent> events = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(kafkaTemplate, times(20))
            .send(eq(RealtimeConfig.COMMUNITY_EVENTS_TOPIC), keys.capture(), events.capture());
        for (int i = 0; i < 20; i++) {
            assertThat(keys.getAllValues().get(i)).isEqualTo(events.getAllValues().get(i).partitionKey());
            assertThat(events.getAllValues().get(i).getBatchId()).isEqualTo("producer-test");
        }
        assertThat(producer.status().getSentCount()).isEqualTo(20);
    }

    @Test
    void startPublishesInBackgroundAndStopHaltsPublishing() throws Exception {
        stubData();
        stubSend();

        RealtimeStatus started = producer.start(config(5));
        assertThat(started.isRunning()).isTrue();
        assertThat(started.getBatchId()).isEqualTo("producer-test");
        assertThat(started.getStartedAt()).isNotNull();

        await().atMost(5, TimeUnit.SECONDS).until(() -> producer.status().getSentCount() > 0);

        assertThat(producer.stop()).isTrue();
        assertThat(producer.status().isRunning()).isFalse();
        long sentAtStop = producer.status().getSentCount();
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(producer.status().getSentCount()).isEqualTo(sentAtStop);
        assertThat(producer.stop()).isFalse();
    }

    @Test
    void startingTwiceIsRejected() {
        stubData();
        stubSend();
        producer.start(config(5));

        assertThatThrownBy(() -> producer.start(config(5)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already running");
    }

    @Test
    void startFailsWithoutEmployeesOrContent() {
        when(employeeRepository.findAll()).thenReturn(List.of());
        when(contentRepository.findAll()).thenReturn(contents());

        assertThatThrownBy(() -> producer.start(config(5)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("historical backfill");
        assertThat(producer.status().isRunning()).isFalse();
    }

    @Test
    void aFailingBatchDoesNotStopTheSchedule() {
        stubData();
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(RealtimeEvent.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenReturn(CompletableFuture.completedFuture(null));

        producer.start(config(1));

        await().atMost(5, TimeUnit.SECONDS).until(() -> producer.status().getSentCount() > 0);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeEventProducerTest`
Expected: FAIL，编译错误（`RealtimeEventProducer` 尚不存在）。

- [ ] **Step 4: 实现生产者**

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.CommentRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class RealtimeEventProducer {

    private final KafkaTemplate<String, RealtimeEvent> kafkaTemplate;
    private final EmployeeRepository employeeRepository;
    private final ContentRepository contentRepository;
    private final CommentRepository commentRepository;
    private final RealtimeEventPlanner planner;

    private final AtomicLong sentCount = new AtomicLong();
    private ScheduledExecutorService executor;
    private boolean running;
    private String batchId;
    private LocalDateTime startedAt;

    @Autowired
    public RealtimeEventProducer(KafkaTemplate<String, RealtimeEvent> kafkaTemplate,
                                 EmployeeRepository employeeRepository,
                                 ContentRepository contentRepository,
                                 CommentRepository commentRepository) {
        this(kafkaTemplate, employeeRepository, contentRepository, commentRepository,
            new RealtimeEventPlanner(new Random()));
    }

    RealtimeEventProducer(KafkaTemplate<String, RealtimeEvent> kafkaTemplate,
                          EmployeeRepository employeeRepository,
                          ContentRepository contentRepository,
                          CommentRepository commentRepository,
                          RealtimeEventPlanner planner) {
        this.kafkaTemplate = kafkaTemplate;
        this.employeeRepository = employeeRepository;
        this.contentRepository = contentRepository;
        this.commentRepository = commentRepository;
        this.planner = planner;
    }

    public synchronized RealtimeStatus start(RealtimeSimulationConfig config) {
        if (running) {
            throw new IllegalStateException("Realtime simulator is already running (batchId=" + batchId + ")");
        }
        List<Employee> employees = employeeRepository.findAll();
        List<Content> contents = contentRepository.findAll();
        if (employees.isEmpty() || contents.isEmpty()) {
            throw new IllegalStateException("No employees or content found; run the historical backfill first");
        }

        sentCount.set(0);
        batchId = config.getBatchId();
        startedAt = LocalDateTime.now();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> runBatchSafely(config, employees, contents),
            0, config.getIntervalMillis(), TimeUnit.MILLISECONDS);
        running = true;
        return status();
    }

    /** @return true 表示这次调用确实把一个运行中的模拟停掉了；false 表示本来就没在运行 */
    public synchronized boolean stop() {
        if (!running) {
            return false;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        kafkaTemplate.flush();
        running = false;
        return true;
    }

    public synchronized RealtimeStatus status() {
        return new RealtimeStatus(running, batchId, sentCount.get(), startedAt);
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    void publishBatch(RealtimeSimulationConfig config, List<Employee> employees, List<Content> contents) {
        List<RealtimeEvent> events = planner.planBatch(config.getEventsPerBatch(), config.getBatchId(),
            LocalDateTime.now(), employees, contents, commentRepository::findByContentId);
        for (RealtimeEvent event : events) {
            kafkaTemplate.send(RealtimeConfig.COMMUNITY_EVENTS_TOPIC, event.partitionKey(), event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Failed to publish realtime event {}", event, error);
                    }
                });
            sentCount.incrementAndGet();
        }
    }

    // scheduleWithFixedDelay 里的任务一旦抛异常，后续调度会被静默取消，所以这里必须兜住所有异常。
    private void runBatchSafely(RealtimeSimulationConfig config, List<Employee> employees, List<Content> contents) {
        try {
            publishBatch(config, employees, contents);
        } catch (Exception e) {
            log.error("Realtime batch failed, will retry on the next tick", e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeEventProducerTest`
Expected: PASS（5 个测试）。

- [ ] **Step 6: 跑全量测试确认 Spring 能装配 `KafkaTemplate<String, RealtimeEvent>` 注入**

Run: `cd backend && mvn test`
Expected: 全部通过，`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeSimulationConfig.java \
        backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeStatus.java \
        backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventProducer.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventProducerTest.java
git commit -m "feat: add controllable realtime event producer"
```

---

### Task 8: Kafka 消费者

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventConsumerTest.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventConsumer.java`

**说明**：消费者对每条消息先调用事务内的 `RealtimeEventHandler`（落库），事务提交成功之后才调用 `RealtimeCounterUpdater`（更新 Redis）——Redis 不参与数据库事务，所以放在提交之后，避免"库回滚了但计数器已经加过"。任何异常都只记录日志并跳过这条消息；同时按 `batchId` 记录"已处理（含失败）的事件数"，供端到端测试判断队列是否消费完。

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RealtimeEventConsumerTest {

    @Mock private RealtimeEventHandler eventHandler;
    @Mock private RealtimeCounterUpdater counterUpdater;

    private RealtimeEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RealtimeEventConsumer(eventHandler, counterUpdater);
    }

    private RealtimeEvent event(String batchId) {
        return RealtimeEvent.builder()
            .eventType(RealtimeEventType.VIEW).contentId(10L).actorEmployeeId(1L)
            .occurTime(LocalDateTime.of(2026, 10, 4, 12, 0)).batchId(batchId).build();
    }

    @Test
    void successfulEventIsPersistedBeforeCountersAreUpdated() {
        RealtimeEvent event = event("b1");

        consumer.onEvent(event);

        InOrder order = inOrder(eventHandler, counterUpdater);
        order.verify(eventHandler).handle(event);
        order.verify(counterUpdater).record(event);
        assertThat(consumer.getProcessedCount("b1")).isEqualTo(1);
    }

    @Test
    void handlerFailureIsSwallowedAndSkipsTheCounters() {
        RealtimeEvent event = event("b1");
        doThrow(new IllegalArgumentException("bad event")).when(eventHandler).handle(event);

        assertThatCode(() -> consumer.onEvent(event)).doesNotThrowAnyException();

        verifyNoInteractions(counterUpdater);
        assertThat(consumer.getProcessedCount("b1")).isEqualTo(1);
    }

    @Test
    void counterFailureIsSwallowed() {
        RealtimeEvent event = event("b1");
        doThrow(new IllegalStateException("redis down")).when(counterUpdater).record(event);

        assertThatCode(() -> consumer.onEvent(event)).doesNotThrowAnyException();

        assertThat(consumer.getProcessedCount("b1")).isEqualTo(1);
    }

    @Test
    void processedCountsAreTrackedPerBatch() {
        consumer.onEvent(event("a"));
        consumer.onEvent(event("a"));
        consumer.onEvent(event("b"));

        assertThat(consumer.getProcessedCount("a")).isEqualTo(2);
        assertThat(consumer.getProcessedCount("b")).isEqualTo(1);
        assertThat(consumer.getProcessedCount("never-seen")).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeEventConsumerTest`
Expected: FAIL，编译错误（`RealtimeEventConsumer` 尚不存在）。

- [ ] **Step 3: 实现消费者**

```java
package com.jiaxian.analytics.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RealtimeEventConsumer {

    private final RealtimeEventHandler eventHandler;
    private final RealtimeCounterUpdater counterUpdater;
    private final ConcurrentMap<String, AtomicLong> processedByBatch = new ConcurrentHashMap<>();

    public RealtimeEventConsumer(RealtimeEventHandler eventHandler, RealtimeCounterUpdater counterUpdater) {
        this.eventHandler = eventHandler;
        this.counterUpdater = counterUpdater;
    }

    @KafkaListener(topics = RealtimeConfig.COMMUNITY_EVENTS_TOPIC,
        autoStartup = "${realtime.consumer.auto-startup:true}")
    public void onEvent(RealtimeEvent event) {
        try {
            eventHandler.handle(event);
            counterUpdater.record(event);
        } catch (Exception e) {
            log.error("Failed to process realtime event {}, skipping it", event, e);
        } finally {
            processedByBatch
                .computeIfAbsent(String.valueOf(event.getBatchId()), batch -> new AtomicLong())
                .incrementAndGet();
        }
    }

    /** 某个批次里"已经处理过（不论成功还是被跳过）"的事件数。 */
    public long getProcessedCount(String batchId) {
        AtomicLong count = processedByBatch.get(String.valueOf(batchId));
        return count == null ? 0 : count.get();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeEventConsumerTest`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 跑全量测试确认 `@KafkaListener` 装配无误（测试环境下消费者不自动启动）**

Run: `cd backend && mvn test`
Expected: 全部通过，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeEventConsumer.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeEventConsumerTest.java
git commit -m "feat: add realtime Kafka consumer"
```

---

### Task 9: 管理 API（启动 / 停止 / 状态）

**Files:**
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeSimulatorControllerIT.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeSimulatorController.java`

- [ ] **Step 1: 编写失败的集成测试**

测试里用 `@MockBean` 替换掉 `RealtimeEventProducer`，只验证控制器的请求解析、默认值、状态码映射；真实的生产/消费链路由 Task 10 的端到端测试覆盖。

```java
package com.jiaxian.analytics.realtime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeSimulatorControllerIT {

    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort private int port;
    @MockBean private RealtimeEventProducer producer;

    private String url(String path) {
        return "http://localhost:" + port + "/api/admin/simulator/realtime" + path;
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
    }

    private RealtimeStatus runningStatus(String batchId) {
        return new RealtimeStatus(true, batchId, 0, LocalDateTime.of(2026, 10, 4, 12, 0));
    }

    @Test
    void startPassesTheRequestedParametersToTheProducer() {
        when(producer.start(any())).thenReturn(runningStatus("ctrl-batch"));

        ResponseEntity<String> response = postJson("/start",
            "{\"batchId\":\"ctrl-batch\",\"intervalMillis\":500,\"eventsPerBatch\":7}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"running\":true").contains("\"batchId\":\"ctrl-batch\"");
        ArgumentCaptor<RealtimeSimulationConfig> config = ArgumentCaptor.forClass(RealtimeSimulationConfig.class);
        verify(producer).start(config.capture());
        assertThat(config.getValue().getBatchId()).isEqualTo("ctrl-batch");
        assertThat(config.getValue().getIntervalMillis()).isEqualTo(500);
        assertThat(config.getValue().getEventsPerBatch()).isEqualTo(7);
    }

    @Test
    void startWithEmptyBodyUsesDefaults() {
        when(producer.start(any())).thenReturn(runningStatus("generated"));

        ResponseEntity<String> response = postJson("/start", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<RealtimeSimulationConfig> config = ArgumentCaptor.forClass(RealtimeSimulationConfig.class);
        verify(producer).start(config.capture());
        assertThat(config.getValue().getBatchId()).startsWith("realtime-");
        assertThat(config.getValue().getIntervalMillis()).isEqualTo(2000);
        assertThat(config.getValue().getEventsPerBatch()).isEqualTo(10);
    }

    @Test
    void startRejectsNonPositiveParameters() {
        ResponseEntity<String> response = postJson("/start", "{\"intervalMillis\":0,\"eventsPerBatch\":5}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(producer, never()).start(any());
    }

    @Test
    void startWhileRunningReturnsConflict() {
        when(producer.start(any())).thenThrow(new IllegalStateException("Realtime simulator is already running"));

        ResponseEntity<String> response = postJson("/start", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("already running");
    }

    @Test
    void stopReturnsTheFinalStatusWhenItWasRunning() {
        when(producer.stop()).thenReturn(true);
        when(producer.status()).thenReturn(new RealtimeStatus(false, "ctrl-batch", 42, null));

        ResponseEntity<String> response = postJson("/stop", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"running\":false").contains("\"sentCount\":42");
    }

    @Test
    void stopWhenNotRunningReturnsAClearMessageInsteadOfAnError() {
        when(producer.stop()).thenReturn(false);

        ResponseEntity<String> response = postJson("/stop", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("not running");
    }

    @Test
    void statusReturnsTheProducerSnapshot() {
        when(producer.status()).thenReturn(runningStatus("ctrl-batch"));

        ResponseEntity<String> response = restTemplate.getForEntity(url("/status"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"running\":true").contains("\"batchId\":\"ctrl-batch\"");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=RealtimeSimulatorControllerIT`
Expected: FAIL，各接口返回 404（`RealtimeSimulatorController` 尚不存在）。

- [ ] **Step 3: 实现控制器**

```java
package com.jiaxian.analytics.realtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/simulator/realtime")
public class RealtimeSimulatorController {

    private static final int DEFAULT_INTERVAL_MILLIS = 2000;
    private static final int DEFAULT_EVENTS_PER_BATCH = 10;

    private final RealtimeEventProducer producer;

    public RealtimeSimulatorController(RealtimeEventProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest request) {
        StartRequest effective = request == null ? new StartRequest() : request;
        int intervalMillis = effective.getIntervalMillis() == null
            ? DEFAULT_INTERVAL_MILLIS : effective.getIntervalMillis();
        int eventsPerBatch = effective.getEventsPerBatch() == null
            ? DEFAULT_EVENTS_PER_BATCH : effective.getEventsPerBatch();
        if (intervalMillis <= 0 || eventsPerBatch <= 0) {
            return ResponseEntity.badRequest().body("intervalMillis and eventsPerBatch must be positive");
        }
        String batchId = effective.getBatchId() == null || effective.getBatchId().isBlank()
            ? "realtime-" + System.currentTimeMillis() : effective.getBatchId();

        try {
            return ResponseEntity.ok(producer.start(RealtimeSimulationConfig.builder()
                .batchId(batchId)
                .intervalMillis(intervalMillis)
                .eventsPerBatch(eventsPerBatch)
                .build()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        if (!producer.stop()) {
            return ResponseEntity.ok("Realtime simulator is not running");
        }
        return ResponseEntity.ok(producer.status());
    }

    @GetMapping("/status")
    public RealtimeStatus status() {
        return producer.status();
    }

    @Getter
    @Setter
    public static class StartRequest {
        private String batchId;
        private Integer intervalMillis;
        private Integer eventsPerBatch;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=RealtimeSimulatorControllerIT`
Expected: PASS（7 个测试）。

- [ ] **Step 5: 跑全量测试确认没有回归**

Run: `cd backend && mvn test`
Expected: 全部通过，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/realtime/RealtimeSimulatorController.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimeSimulatorControllerIT.java
git commit -m "feat: add realtime simulator start/stop/status API"
```

---

### Task 10: 端到端集成测试（真实 Kafka + OpenGauss + Redis）与手工冒烟验证

**Files:**
- Modify: `backend/src/main/java/com/jiaxian/analytics/repository/PvLogRepository.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/repository/PointsLedgerRepository.java`
- Create: `backend/src/test/java/com/jiaxian/analytics/realtime/RealtimePipelineIT.java`

- [ ] **Step 1: 给两个仓库加按批次查询的派生方法（端到端测试要按批次断言，避免被其他批次/历史数据干扰）**

`PvLogRepository.java` 里，在 `List<PvLog> findByContentId(Long contentId);` 下面追加：

```java
    long countBySimulationBatchId(String simulationBatchId);
```

`PointsLedgerRepository.java` 里，在 `List<PointsLedger> findByEmployeeId(Long employeeId);` 下面追加：

```java
    List<PointsLedger> findBySimulationBatchId(String simulationBatchId);
```

- [ ] **Step 2: 编写端到端测试**

（这个测试依赖的类前面的任务都已经实现了，所以它不是"先红后绿"的单元测试，而是整条链路的验收：第一次运行就应该通过；如果没通过，说明前面某个任务的实现和真实基础设施之间还有差异，要回头修那个任务，而不是改测试。）

```java
package com.jiaxian.analytics.realtime;

import com.jiaxian.analytics.domain.PointsCategory;
import com.jiaxian.analytics.domain.PointsLedger;
import com.jiaxian.analytics.repository.PointsLedgerRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import com.jiaxian.analytics.simulator.HistoricalBackfillService;
import com.jiaxian.analytics.simulator.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 端到端：真实 Kafka（producer → topic → consumer）+ 真实 OpenGauss（积分引擎落库）+ 真实 Redis（今日计数器）。
 * 前提：docker compose 里 opengauss / redis / kafka 都在运行。
 * 只有这个测试打开 Kafka 消费者，并在类结束后关闭上下文，避免消费者残留在缓存的上下文里干扰其他测试。
 */
@SpringBootTest(properties = "realtime.consumer.auto-startup=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimePipelineIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private RealtimeEventProducer producer;
    @Autowired private RealtimeEventConsumer consumer;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    void producedEventsAreConsumedPersistedWithPointsAndCountedInRedis() {
        backfillService.runBackfill(SimulationConfig.builder()
            .batchId("pipeline-seed")
            .employeeCount(20)
            .accountCount(10)
            .teamAccountRatio(0.2)
            .contentCount(30)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(11)
            .build());

        String batchId = "pipeline-it-" + System.currentTimeMillis();
        String today = LocalDate.now().toString();
        long redisPvBefore = sumCounters("rt:pv:channel:*:" + today);
        long redisEngagementBefore = sumCounters("rt:engagement:channel:*:" + today);

        producer.start(RealtimeSimulationConfig.builder()
            .batchId(batchId).intervalMillis(100).eventsPerBatch(20).build());
        try {
            await().atMost(60, TimeUnit.SECONDS).until(() -> producer.status().getSentCount() >= 60);
        } finally {
            producer.stop();
        }

        // 等消费者把这个批次里已发出的事件全部处理完（成功或被跳过都算处理过）
        long sent = producer.status().getSentCount();
        await().atMost(90, TimeUnit.SECONDS).until(() -> consumer.getProcessedCount(batchId) >= sent);

        List<PointsLedger> ledger = pointsLedgerRepository.findBySimulationBatchId(batchId);
        long pvRows = pvLogRepository.countBySimulationBatchId(batchId);

        assertThat(pvRows).isGreaterThan(0);
        assertThat(ledger).extracting(PointsLedger::getCategory)
            .contains(PointsCategory.ACTIVE, PointsCategory.PASSIVE);
        assertThat(sumCounters("rt:pv:channel:*:" + today) - redisPvBefore)
            .isGreaterThan(0)
            .isGreaterThanOrEqualTo(pvRows);
        assertThat(sumCounters("rt:engagement:channel:*:" + today) - redisEngagementBefore).isGreaterThan(0);
    }

    private long sumCounters(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        return redisTemplate.opsForValue().multiGet(keys).stream()
            .filter(Objects::nonNull)
            .mapToLong(Long::parseLong)
            .sum();
    }
}
```

- [ ] **Step 3: 运行端到端测试**

前提：`docker compose ps` 里 opengauss / redis / kafka 都是 `Up`。

Run: `cd backend && mvn test -Dtest=RealtimePipelineIT`
Expected: PASS（1 个测试；耗时可能 10~40 秒，主要是等 Kafka 消费组完成分区分配，属正常）。

如果失败，按下面顺序排查，**不要**靠放宽断言或加长超时蒙混过关：
1. `docker compose ps`——三个容器是否都在运行；
2. 看测试日志里有没有 `Failed to process realtime event`（消费者跳过了事件，日志里有具体异常）；
3. `docker exec jiaxian-kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic community-events`（确认 topic 有 3 个分区）。

- [ ] **Step 4: 跑全量测试确认整体通过（含 Kafka 消费者只在本测试里启动的隔离设计）**

Run: `cd backend && mvn test`
Expected: 全部通过，`BUILD SUCCESS`。连续再跑一次，确认两次都通过（验证同一个消费组第二次启动时从已提交位点继续、不会重复消费旧消息）。

- [ ] **Step 5: 手工冒烟验证（真实启动应用，走一遍 HTTP 接口）**

1. 后台启动应用（本机 8080 被占用，用 8093；这一步是**默认配置**，消费者会自动启动）：
   `cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093`
2. 灌历史数据：
   ```bash
   curl -s -X POST localhost:8093/api/admin/simulator/backfill -H 'Content-Type: application/json' \
     -d '{"batchId":"smoke-seed","employeeCount":30,"accountCount":15,"teamAccountRatio":0.2,"contentCount":50,"historyStart":"2026-01-01","historyEnd":"2026-06-01","randomSeed":3}'
   ```
   Expected: `Backfill completed for batch: smoke-seed`
3. 启动实时模拟：
   ```bash
   curl -s -X POST localhost:8093/api/admin/simulator/realtime/start -H 'Content-Type: application/json' \
     -d '{"batchId":"smoke-realtime","intervalMillis":500,"eventsPerBatch":10}'
   ```
   Expected: JSON，`"running":true,"batchId":"smoke-realtime"`。再发一次同样的请求，Expected: HTTP 409，`already running`。
4. 等 10 秒后查状态：`curl -s localhost:8093/api/admin/simulator/realtime/status`
   Expected: `"running":true`，`sentCount` 大于 0 且持续增长。
5. 看 Redis 计数器：
   `docker exec jiaxian-redis redis-cli --scan --pattern 'rt:*'`，再对其中一个 key 执行 `docker exec jiaxian-redis redis-cli GET <key>` 和 `TTL <key>`
   Expected: 能看到 `rt:pv:channel:<id>:<今天日期>` / `rt:engagement:channel:<id>:<今天日期>`，值大于 0，`TTL` 是一个正数（不是 -1）。
6. 看数据库：
   `docker exec -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U jiaxian_app -d postgres -W "Jiaxian@2026" -c "SELECT count(*) FROM pv_log WHERE simulation_batch_id='smoke-realtime'; SELECT category, count(*) FROM points_ledger WHERE simulation_batch_id='smoke-realtime' GROUP BY category;"`
   Expected: `pv_log` 行数大于 0；`points_ledger` 里 ACTIVE 和 PASSIVE 两类都有。
7. 停止：`curl -s -X POST localhost:8093/api/admin/simulator/realtime/stop`
   Expected: `"running":false`。再停一次，Expected: HTTP 200，`not running`。
8. **杀掉后台应用进程**，并确认 `lsof -i :8093` 为空。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/repository/PvLogRepository.java \
        backend/src/main/java/com/jiaxian/analytics/repository/PointsLedgerRepository.java \
        backend/src/test/java/com/jiaxian/analytics/realtime/RealtimePipelineIT.java
git commit -m "test: add end-to-end realtime pipeline integration test"
```

---

## 计划完成后的验收标准

1. `docker compose up -d` 后 `mvn test`（不带任何 `-Dtest`）全部通过：Plan 1 原有 16 个测试 + 本计划新增的测试，连续跑两次都通过。
2. `RealtimePipelineIT` 证明整条链路真实可用：Producer 往真实 Kafka 发事件 → Consumer 消费 → 通过 `BehaviorEventGenerator` 在 OpenGauss 落下 `pv_log` / `points_ledger`（含 ACTIVE 和 PASSIVE 两类）→ Redis 里"今日 PV / 今日互动数"计数器增长，且 PV 计数增量不小于该批次的 `pv_log` 行数。
3. 手工冒烟（Task 10 Step 5）全部符合预期：启动/重复启动（409）/状态/停止/重复停止（200 + 提示）、Redis key 带 TTL、数据库有新增明细和两类积分。
4. 积分记账逻辑没有出现第二份实现：`grep -rn "PointsLedger.builder" backend/src/main/java/com/jiaxian/analytics/realtime` 无结果（实时链路里所有积分记账都走 `BehaviorEventGenerator`）。
5. 范围边界：本计划没有引入"实时发帖/创建新内容"、排行榜 Sorted Set、重试/死信队列、schema registry、前端页面。

**已知限制（有意不处理，留给后续计划）**：
- 实时模拟运行期间**不要**同时调用历史回填接口：回填会清空所有模拟数据，正在途中的实时事件如果引用了已被删除的内容/员工，消费者会记录错误日志并跳过这些事件（不会卡住，但会有噪音）。
- 开发机上的 Redis 会累积测试/冒烟产生的当天计数器，次日零点自动过期，无需手工清理。
- 事件的 `occurTime` 取生产者规划时刻（"现在"），不做时间加速比；设计文档里提过的"时间加速"留到有明确需求再做。
