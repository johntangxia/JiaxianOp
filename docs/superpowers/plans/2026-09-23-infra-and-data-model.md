# 基础设施与数据模型 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建稼先社区运营分析模拟项目的后端/前端脚手架、本地依赖环境（OpenGauss+Redis+Kafka），落地数据模型（维度表+事实表+积分表），并实现历史数据批量回填模拟器，跑通"生成一批带真实感（幂律热度分布+双边积分记账）的模拟历史数据并写入数据库"这条链路。

**Architecture:** Spring Boot（Maven，Java 17）后端 + Spring Boot 内置的 `schema.sql`/`data.sql` 初始化机制管理 OpenGauss schema + Spring Data JPA 访问；docker-compose 拉起 OpenGauss/Redis/Kafka 三个依赖（Redis/Kafka 本阶段只起容器，不接入业务逻辑，留给后续计划）；历史回填模拟器是纯后端服务，通过 REST API 触发，直接批量写库，不经过 Kafka。前端本阶段只做 Vite+Vue3+TS 脚手架和一个健康检查页面，验证前后端联通。

**Tech Stack:** Java 17 / Spring Boot 3.2 / Spring Data JPA / OpenGauss（openGauss JDBC 驱动）/ Lombok / JUnit 5；Vue 3 + TypeScript + Vite；Docker Compose。

**关于弃用 Flyway：** 原计划用 Flyway 做版本化迁移，但实际执行 Task 2 时发现 Flyway 社区版会因为 openGauss 的版本号自报为 "PostgreSQL 9.2"（openGauss 为兼容性做的伪装）而触发 Flyway 的商业许可限制，拒绝连接（`PostgreSQL 9.2 is no longer supported by Flyway Community Edition`），且没有简单的版本兼容组合能绕过。改用 Spring Boot 自带的 `spring.sql.init`（`schema.sql`/`data.sql`）机制，不引入任何迁移工具依赖——用两个持续追加的 SQL 文件代替版本化迁移脚本，牺牲了"迁移历史可追溯"这个能力，换取不被数据库方言绑死的简单性。

---

## 参考设计文档

本计划依据 [`docs/superpowers/specs/2026-09-23-community-analytics-design.md`](../specs/2026-09-23-community-analytics-design.md) 第 2/3/4 节实现。本计划**只**覆盖该设计文档里的：维度表、事实表、积分表（不含汇总表/排行榜快照/RFM表/建议文案库，这些留给后续计划按需新增迁移脚本），以及历史批量回填模式（不含 Kafka 实时模拟模式，留给下一份计划）。

## 文件结构

```
JiaxianOp/
├── docker-compose.yml
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/jiaxian/analytics/
│       │   │   ├── AnalyticsApplication.java
│       │   │   ├── health/HealthController.java
│       │   │   ├── domain/
│       │   │   │   ├── Employee.java
│       │   │   │   ├── Channel.java
│       │   │   │   ├── Account.java
│       │   │   │   ├── AccountType.java
│       │   │   │   ├── AccountMember.java
│       │   │   │   ├── Content.java
│       │   │   │   ├── ContentType.java
│       │   │   │   ├── ContentAuthor.java
│       │   │   │   ├── Comment.java
│       │   │   │   ├── PvLog.java
│       │   │   │   ├── EngagementLog.java
│       │   │   │   ├── EngagementActionType.java
│       │   │   │   ├── ActiveActionLog.java
│       │   │   │   ├── ActiveActionType.java
│       │   │   │   ├── EditorialPickLog.java
│       │   │   │   ├── PointsWeightConfig.java
│       │   │   │   ├── PointsCategory.java
│       │   │   │   └── PointsLedger.java
│       │   │   ├── repository/
│       │   │   │   ├── EmployeeRepository.java
│       │   │   │   ├── ChannelRepository.java
│       │   │   │   ├── AccountRepository.java
│       │   │   │   ├── AccountMemberRepository.java
│       │   │   │   ├── ContentRepository.java
│       │   │   │   ├── ContentAuthorRepository.java
│       │   │   │   ├── CommentRepository.java
│       │   │   │   ├── PvLogRepository.java
│       │   │   │   ├── EngagementLogRepository.java
│       │   │   │   ├── ActiveActionLogRepository.java
│       │   │   │   ├── EditorialPickLogRepository.java
│       │   │   │   ├── PointsWeightConfigRepository.java
│       │   │   │   └── PointsLedgerRepository.java
│       │   │   └── simulator/
│       │   │       ├── SimulationConfig.java
│       │   │       ├── PopularityDistribution.java
│       │   │       ├── EntityGenerator.java
│       │   │       ├── BehaviorEventGenerator.java
│       │   │       ├── EditorialPickGenerator.java
│       │   │       ├── HistoricalBackfillService.java
│       │   │       └── SimulatorController.java
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── schema.sql
│       │       └── data.sql
│       └── test/java/com/jiaxian/analytics/
│           ├── health/HealthControllerTest.java
│           ├── repository/DimensionRepositoryIT.java
│           ├── repository/FactAndPointsRepositoryIT.java
│           ├── simulator/PopularityDistributionTest.java
│           ├── simulator/EntityGeneratorTest.java
│           ├── simulator/BehaviorEventGeneratorTest.java
│           ├── simulator/EditorialPickGeneratorTest.java
│           └── simulator/HistoricalBackfillServiceIT.java
└── frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tsconfig.json
    ├── index.html
    └── src/
        ├── main.ts
        ├── App.vue
        └── api/health.ts
```

**关于集成测试的前提条件**：所有带 `IT` 后缀的测试类需要连接真实 OpenGauss（通过 `docker-compose up -d opengauss` 启动），这是一个刻意选择——openGauss 目前没有官方维护的 Testcontainers 镜像，与其用不保真的替代数据库（如用纯 PostgreSQL 容器）掩盖潜在的方言差异，不如直接对真实 openGauss 跑集成测试，这也更符合"参考实现"的定位。每个 Task 的集成测试步骤都会注明这个前提。

## 执行过程中发现的修正记录

这份计划在执行 Task 1/2 时，实际跑起来的 openGauss 镜像暴露出几个和最初假设不一致的地方，记录在这里，供后续所有任务参照（Task 1 的原始代码块保留不动，作为历史记录；下面是实际生效的版本）：

1. **openGauss 的初始超级用户是 `opengauss`，不是 `gaussdb`**——`docker-compose.yml` 里只设 `GS_PASSWORD` 时，镜像创建的默认账号叫 `opengauss`。（第 3 条会说明为什么后续所有验证命令改用 `jiaxian_app` 而不是 `opengauss`。）
2. **`gsql` 不在容器默认 `PATH` 上**，需要用完整路径 `/usr/local/opengauss/bin/gsql`，并显式设置 `LD_LIBRARY_PATH=/usr/local/opengauss/lib`，所有验证命令已相应调整。
3. **openGauss 的初始用户（`opengauss`）被硬编码禁止远程/TCP 连接**（`FATAL: Forbid remote connection with initial user`），只能通过本地 socket（容器内 `gsql`）登录，应用程序必须用另一个专门创建的账号连接。已在 `docker-compose.yml` 里给 `opengauss` 服务加了 `GS_USERNAME: "jiaxian_app"` 环境变量（复用同一个 `GS_PASSWORD` 作为密码，这是镜像入口脚本的固定行为），并手动执行了一次性授权：
   ```sql
   ALTER USER jiaxian_app CREATEDB;
   GRANT ALL PRIVILEGES ON DATABASE postgres TO jiaxian_app;
   GRANT ALL PRIVILEGES ON SCHEMA public TO jiaxian_app;
   ```
   已验证 `jiaxian_app` 可以远程连接并建表。**这个授权是一次性手工操作，不在任何迁移脚本里**——如果之后有人删掉 `opengauss-data` 这个 docker volume 重新初始化，需要在 Task 1 验证步骤之后、Task 2 开始前，重新手工执行这三行 SQL（用 `-U opengauss` 登录后执行）。后续所有任务里，Spring Boot `application.yml` 的数据源账号统一使用 `jiaxian_app`，不用 `opengauss`（超级用户只用于人工排查，不用于应用连接）。
4. **`schema.sql`/`data.sql` 建的表实际落在 `jiaxian_app` schema 里，不是 `public`**——openGauss 用 `GS_USERNAME` 创建新用户时会连带自动建一个同名 schema（`jiaxian_app`），而 `search_path` 默认是 `"$user",public`，所以 `jiaxian_app` 用户建表时优先落进 `jiaxian_app` schema。这不影响任何功能（应用自己一直用 `jiaxian_app` 连接，读写都在同一个 schema，Hibernate 的 `ddl-auto: validate` 也一样能找到表），纯粹是人工用 `gsql` 排查时的一个坑：如果用 `opengauss` 超级用户登录后直接 `\dt`，看不到任何表（因为它的 `search_path` 是 `opengauss,public`，两边都没有这些表），必须先 `SET search_path TO jiaxian_app;` 或者直接用 `jiaxian_app` 账号登录查看（本计划所有验证命令已经统一用 `jiaxian_app` 登录，见上一条）。

---

### Task 1: docker-compose 本地依赖环境

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: 编写 docker-compose.yml**

```yaml
version: "3.8"

services:
  opengauss:
    image: opengauss/opengauss:5.0.0
    container_name: jiaxian-opengauss
    environment:
      GS_PASSWORD: "Jiaxian@2026"
      GS_USERNAME: "jiaxian_app"
    ports:
      - "5432:5432"
    volumes:
      - opengauss-data:/var/lib/opengauss/data
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    container_name: jiaxian-redis
    ports:
      - "6379:6379"
    restart: unless-stopped

  kafka:
    image: bitnami/kafka:3.6
    container_name: jiaxian-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: "0"
      KAFKA_CFG_PROCESS_ROLES: "controller,broker"
      KAFKA_CFG_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_CFG_ADVERTISED_LISTENERS: "PLAINTEXT://localhost:9092"
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: "0@kafka:9093"
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      ALLOW_PLAINTEXT_LISTENER: "yes"
    restart: unless-stopped

volumes:
  opengauss-data:
```

- [ ] **Step 2: 启动并验证**

Run: `docker-compose up -d`
Expected: 三个容器（jiaxian-opengauss / jiaxian-redis / jiaxian-kafka）都进入 `running`/`healthy` 状态，`docker-compose ps` 中 State 列无 `Exit`。

- [ ] **Step 3: 验证 OpenGauss 可连接**

Run: `docker exec -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U jiaxian_app -d postgres -W "Jiaxian@2026" -c "\l"`
Expected: 输出数据库列表（至少包含 `postgres` 库），不报连接错误。

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "chore: add docker-compose for openGauss/redis/kafka local env"
```

---

### Task 2: Spring Boot 后端脚手架与健康检查

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/jiaxian/analytics/AnalyticsApplication.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/health/HealthController.java`
- Create: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/jiaxian/analytics/health/HealthControllerTest.java`

- [ ] **Step 1: 编写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
    <relativePath/>
  </parent>

  <groupId>com.jiaxian</groupId>
  <artifactId>analytics</artifactId>
  <version>0.1.0</version>
  <name>analytics</name>
  <description>Jiaxian community operations analytics simulation backend</description>

  <properties>
    <java.version>17</java.version>
    <opengauss-jdbc.version>5.0.0</opengauss-jdbc.version>
  </properties>

  <repositories>
    <repository>
      <id>huaweicloud</id>
      <url>https://repo.huaweicloud.com/repository/maven/</url>
    </repository>
  </repositories>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.opengauss</groupId>
      <artifactId>opengauss-jdbc</artifactId>
      <version>${opengauss-jdbc.version}</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 编写 application.yml**

```yaml
spring:
  application:
    name: jiaxian-analytics
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: jiaxian_app
    password: "Jiaxian@2026"
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

server:
  port: 8080
```

**说明**：`spring.jpa.defer-datasource-initialization: true` 是必需的——默认情况下，classpath 上有 JPA 时，Hibernate 会在 `schema.sql`/`data.sql` 执行之前就先做 `ddl-auto: validate` 校验，这时表还不存在会直接报错。设为 `true` 后，Spring Boot 会保证 `schema.sql`/`data.sql` 先跑完，Hibernate 的 validate 再执行。`schema.sql`/`data.sql` 里的语句必须是幂等的（`CREATE TABLE IF NOT EXISTS`、`INSERT ... ON CONFLICT DO NOTHING`），因为 `mode: always` 意味着每次应用启动（包括每个 `@SpringBootTest` 测试类）都会重新执行一遍这两个文件。

- [ ] **Step 3: 编写失败的健康检查测试**

```java
package com.jiaxian.analytics.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void healthEndpointReturnsOk() {
        ResponseEntity<String> response =
            restTemplate.getForEntity("http://localhost:" + port + "/api/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("OK");
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

前提：`docker-compose up -d opengauss`。此时 `schema.sql`/`data.sql` 还不存在（Task 3/5/7 才会创建），`spring.sql.init.mode: always` 在没有找到这两个文件时会直接跳过，不报错——Spring Boot 启动只需要数据源本身能连通（用 `jiaxian_app` 账号）。

Run: `cd backend && mvn test -Dtest=HealthControllerTest`
Expected: FAIL，报 404（`HealthController` 尚不存在）。

- [ ] **Step 5: 实现 HealthController**

```java
package com.jiaxian.analytics.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public String health() {
        return "OK";
    }
}
```

- [ ] **Step 6: 编写启动类**

```java
package com.jiaxian.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=HealthControllerTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/jiaxian/analytics/AnalyticsApplication.java \
        backend/src/main/java/com/jiaxian/analytics/health/HealthController.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/jiaxian/analytics/health/HealthControllerTest.java
git commit -m "feat: bootstrap Spring Boot backend with health check endpoint"
```

---

### Task 3: 维度表 Schema DDL

**Files:**
- Create: `backend/src/main/resources/schema.sql`

- [ ] **Step 1: 编写 schema.sql**

```sql
CREATE TABLE IF NOT EXISTS employee (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    dept                VARCHAR(100),
    join_date           DATE,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS channel (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS account (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('PERSONAL', 'TEAM')),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS account_member (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          BIGINT NOT NULL REFERENCES account(id),
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS content (
    id                  BIGSERIAL PRIMARY KEY,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('ARTICLE', 'VIDEO')),
    channel_id          BIGINT NOT NULL REFERENCES channel(id),
    account_id          BIGINT NOT NULL REFERENCES account(id),
    publish_time        TIMESTAMP NOT NULL,
    popularity_weight   DOUBLE PRECISION NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS content_author (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS comment (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    parent_comment_id   BIGINT REFERENCES comment(id),
    create_time         TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_account_member_account ON account_member(account_id);
CREATE INDEX IF NOT EXISTS idx_account_member_employee ON account_member(employee_id);
CREATE INDEX IF NOT EXISTS idx_content_channel ON content(channel_id);
CREATE INDEX IF NOT EXISTS idx_content_account ON content(account_id);
CREATE INDEX IF NOT EXISTS idx_content_author_content ON content_author(content_id);
CREATE INDEX IF NOT EXISTS idx_content_author_employee ON content_author(employee_id);
CREATE INDEX IF NOT EXISTS idx_comment_content ON comment(content_id);
CREATE INDEX IF NOT EXISTS idx_comment_parent ON comment(parent_comment_id);
```

**说明**：`channel` 表加了 `UNIQUE (name)` 约束——这是 Task 7 的种子数据要用 `ON CONFLICT (name) DO NOTHING` 做幂等插入所必需的（因为 `spring.sql.init.mode: always` 每次启动都会重新执行 `data.sql`）。所有 `CREATE TABLE`/`CREATE INDEX` 都加了 `IF NOT EXISTS`，同样是为了幂等——`schema.sql` 会在每次应用启动（包括每个 `@SpringBootTest`）时重新执行一遍。

- [ ] **Step 2: 运行验证 schema.sql 生效**

前提：`docker-compose up -d opengauss`

Run: `cd backend && mvn spring-boot:run` （启动后 Ctrl+C 停止即可，只是触发 `schema.sql` 执行）
Expected: 控制台无报错，日志中能看到 Spring Boot 执行了 `schema.sql`（关键字如 `Executing SQL script from class path resource [schema.sql]`）。

Run: `docker exec -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U jiaxian_app -d postgres -W "Jiaxian@2026" -c "\dt"`
Expected: 输出包含 `employee, channel, account, account_member, content, content_author, comment` 这些表。

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/schema.sql
git commit -m "feat: add schema.sql DDL for dimension tables"
```

---

### Task 4: 维度表 JPA 实体与 Repository

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/Employee.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/Channel.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/Account.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/AccountType.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/AccountMember.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/Content.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/ContentType.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/ContentAuthor.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/Comment.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/EmployeeRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/ChannelRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/AccountRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/AccountMemberRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/ContentRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/ContentAuthorRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/CommentRepository.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/repository/DimensionRepositoryIT.java`

- [ ] **Step 1: 编写失败的集成测试**

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DimensionRepositoryIT {

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountMemberRepository accountMemberRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private CommentRepository commentRepository;

    @Test
    void savesAndLoadsFullDimensionGraph() {
        Employee author1 = employeeRepository.save(
            Employee.builder().name("张三").dept("终端").joinDate(LocalDate.of(2020, 1, 1))
                .simulationBatchId("test-batch").build());
        Employee author2 = employeeRepository.save(
            Employee.builder().name("李四").dept("半导体").joinDate(LocalDate.of(2021, 3, 1))
                .simulationBatchId("test-batch").build());

        Channel channel = channelRepository.save(
            Channel.builder().name("半导体").category("专业领域").build());

        Account teamAccount = accountRepository.save(
            Account.builder().name("芯观察").type(AccountType.TEAM).simulationBatchId("test-batch").build());

        accountMemberRepository.save(
            AccountMember.builder().accountId(teamAccount.getId()).employeeId(author1.getId())
                .simulationBatchId("test-batch").build());
        accountMemberRepository.save(
            AccountMember.builder().accountId(teamAccount.getId()).employeeId(author2.getId())
                .simulationBatchId("test-batch").build());

        Content content = contentRepository.save(
            Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
                .accountId(teamAccount.getId()).publishTime(LocalDateTime.now())
                .popularityWeight(0.8).simulationBatchId("test-batch").build());

        contentAuthorRepository.save(
            ContentAuthor.builder().contentId(content.getId()).employeeId(author1.getId())
                .simulationBatchId("test-batch").build());
        contentAuthorRepository.save(
            ContentAuthor.builder().contentId(content.getId()).employeeId(author2.getId())
                .simulationBatchId("test-batch").build());

        Comment rootComment = commentRepository.save(
            Comment.builder().contentId(content.getId()).employeeId(author2.getId())
                .createTime(LocalDateTime.now()).simulationBatchId("test-batch").build());
        Comment reply = commentRepository.save(
            Comment.builder().contentId(content.getId()).employeeId(author1.getId())
                .parentCommentId(rootComment.getId()).createTime(LocalDateTime.now())
                .simulationBatchId("test-batch").build());

        assertThat(contentAuthorRepository.findByContentId(content.getId())).hasSize(2);
        assertThat(accountMemberRepository.findByAccountId(teamAccount.getId())).hasSize(2);
        assertThat(commentRepository.findByParentCommentId(rootComment.getId())).containsExactly(reply);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

前提：`docker-compose up -d opengauss`，且 Task 3 的迁移已跑过。

Run: `cd backend && mvn test -Dtest=DimensionRepositoryIT`
Expected: FAIL，编译错误（实体类和 Repository 尚不存在）。

- [ ] **Step 3: 编写实体类**

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "employee")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String dept;

    @Column(name = "join_date")
    private LocalDate joinDate;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "channel")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Channel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String category;
}
```

```java
package com.jiaxian.analytics.domain;

public enum AccountType {
    PERSONAL, TEAM
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "account")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "account_member")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AccountMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

public enum ContentType {
    ARTICLE, VIDEO
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "content")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Content {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private ContentType type;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    @Column(name = "popularity_weight")
    private Double popularityWeight;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "content_author")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContentAuthor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "comment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

- [ ] **Step 4: 编写 Repository 接口**

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.AccountMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountMemberRepository extends JpaRepository<AccountMember, Long> {
    List<AccountMember> findByAccountId(Long accountId);
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Content;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, Long> {
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.ContentAuthor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentAuthorRepository extends JpaRepository<ContentAuthor, Long> {
    List<ContentAuthor> findByContentId(Long contentId);
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByParentCommentId(Long parentCommentId);
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=DimensionRepositoryIT`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/domain backend/src/main/java/com/jiaxian/analytics/repository \
        backend/src/test/java/com/jiaxian/analytics/repository/DimensionRepositoryIT.java
git commit -m "feat: add dimension table entities and repositories"
```

---

### Task 5: 事实表与积分表 Schema DDL

**Files:**
- Modify: `backend/src/main/resources/schema.sql`（在 Task 3 已有内容后追加）

- [ ] **Step 1: 追加 DDL 到 schema.sql**

在 `schema.sql` 文件末尾追加以下内容：

```sql
CREATE TABLE IF NOT EXISTS pv_log (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    viewer_employee_id  BIGINT NOT NULL REFERENCES employee(id),
    view_time           TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS engagement_log (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT REFERENCES content(id),
    comment_id          BIGINT REFERENCES comment(id),
    actor_employee_id   BIGINT NOT NULL REFERENCES employee(id),
    action_type         VARCHAR(20) NOT NULL CHECK (action_type IN ('LIKE', 'SHARE', 'FAVORITE')),
    occur_time          TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64),
    CHECK (content_id IS NOT NULL OR comment_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS active_action_log (
    id                  BIGSERIAL PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    action_type         VARCHAR(20) NOT NULL CHECK (action_type IN
        ('POST', 'COMMENT', 'LOGIN', 'SHARE', 'FAVORITE', 'LIKE', 'VIEW')),
    ref_id              BIGINT,
    occur_time          TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS editorial_pick_log (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    pick_time           TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS points_weight_config (
    id              BIGSERIAL PRIMARY KEY,
    action_type     VARCHAR(30) NOT NULL,
    category        VARCHAR(10) NOT NULL CHECK (category IN ('ACTIVE', 'PASSIVE')),
    weight          INT NOT NULL,
    effective_date  DATE NOT NULL,
    UNIQUE (action_type, effective_date)
);

CREATE TABLE IF NOT EXISTS points_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    action_type         VARCHAR(30) NOT NULL,
    category            VARCHAR(10) NOT NULL CHECK (category IN ('ACTIVE', 'PASSIVE')),
    points              INT NOT NULL,
    ref_id              BIGINT,
    occur_time          TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_pv_log_content ON pv_log(content_id);
CREATE INDEX IF NOT EXISTS idx_pv_log_view_time ON pv_log(view_time);
CREATE INDEX IF NOT EXISTS idx_engagement_log_content ON engagement_log(content_id);
CREATE INDEX IF NOT EXISTS idx_engagement_log_comment ON engagement_log(comment_id);
CREATE INDEX IF NOT EXISTS idx_active_action_log_employee ON active_action_log(employee_id);
CREATE INDEX IF NOT EXISTS idx_editorial_pick_content ON editorial_pick_log(content_id);
CREATE INDEX IF NOT EXISTS idx_points_ledger_employee_time ON points_ledger(employee_id, occur_time);
```

**说明**：`points_weight_config` 加了 `UNIQUE (action_type, effective_date)`，原因同 Task 3 的 `channel.name`——Task 7 的种子数据要靠这个约束做 `ON CONFLICT DO NOTHING` 幂等插入。

- [ ] **Step 2: 运行验证 schema.sql 生效**

Run: `cd backend && mvn spring-boot:run` （启动后 Ctrl+C 停止）
Expected: 无报错，日志显示 `schema.sql` 执行成功。

Run: `docker exec -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U jiaxian_app -d postgres -W "Jiaxian@2026" -c "\dt"`
Expected: 新增 `pv_log, engagement_log, active_action_log, editorial_pick_log, points_weight_config, points_ledger` 六张表。

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/schema.sql
git commit -m "feat: extend schema.sql with fact and points tables"
```

---

### Task 6: 事实表/积分表 JPA 实体与 Repository

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/PvLog.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/EngagementLog.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/EngagementActionType.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/ActiveActionLog.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/ActiveActionType.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/EditorialPickLog.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/PointsCategory.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/PointsWeightConfig.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/domain/PointsLedger.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/PvLogRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/EngagementLogRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/ActiveActionLogRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/EditorialPickLogRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/PointsWeightConfigRepository.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/repository/PointsLedgerRepository.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/repository/FactAndPointsRepositoryIT.java`

- [ ] **Step 1: 编写失败的集成测试**

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FactAndPointsRepositoryIT {

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private EngagementLogRepository engagementLogRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private EditorialPickLogRepository editorialPickLogRepository;
    @Autowired private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void savesAndQueriesFactAndPointsRecords() {
        Employee viewer = employeeRepository.save(
            Employee.builder().name("王五").simulationBatchId("test-batch").build());
        Employee author = employeeRepository.save(
            Employee.builder().name("赵六").simulationBatchId("test-batch").build());
        Channel channel = channelRepository.save(
            Channel.builder().name("移动无线").category("专业领域").build());
        Account account = accountRepository.save(
            Account.builder().name("赵六的账号").type(AccountType.PERSONAL)
                .simulationBatchId("test-batch").build());
        Content content = contentRepository.save(
            Content.builder().type(ContentType.ARTICLE).channelId(channel.getId())
                .accountId(account.getId()).publishTime(LocalDateTime.now())
                .popularityWeight(0.5).simulationBatchId("test-batch").build());

        pvLogRepository.save(PvLog.builder().contentId(content.getId())
            .viewerEmployeeId(viewer.getId()).viewTime(LocalDateTime.now())
            .simulationBatchId("test-batch").build());

        engagementLogRepository.save(EngagementLog.builder().contentId(content.getId())
            .actorEmployeeId(viewer.getId()).actionType(EngagementActionType.LIKE)
            .occurTime(LocalDateTime.now()).simulationBatchId("test-batch").build());

        activeActionLogRepository.save(ActiveActionLog.builder().employeeId(viewer.getId())
            .actionType(ActiveActionType.LIKE).refId(content.getId())
            .occurTime(LocalDateTime.now()).simulationBatchId("test-batch").build());

        editorialPickLogRepository.save(EditorialPickLog.builder().contentId(content.getId())
            .pickTime(LocalDateTime.now()).simulationBatchId("test-batch").build());

        pointsWeightConfigRepository.save(PointsWeightConfig.builder()
            .actionType("LIKE").category(PointsCategory.PASSIVE).weight(3)
            .effectiveDate(LocalDate.of(2026, 1, 1)).build());

        pointsLedgerRepository.save(PointsLedger.builder().employeeId(author.getId())
            .actionType("LIKE").category(PointsCategory.PASSIVE).points(3)
            .refId(content.getId()).occurTime(LocalDateTime.now())
            .simulationBatchId("test-batch").build());

        assertThat(pvLogRepository.findByContentId(content.getId())).hasSize(1);
        assertThat(engagementLogRepository.findByContentId(content.getId())).hasSize(1);
        List<PointsLedger> ledger = pointsLedgerRepository.findByEmployeeId(author.getId());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getPoints()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=FactAndPointsRepositoryIT`
Expected: FAIL，编译错误（实体类和 Repository 尚不存在）。

- [ ] **Step 3: 编写实体类**

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pv_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PvLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "viewer_employee_id")
    private Long viewerEmployeeId;

    @Column(name = "view_time")
    private LocalDateTime viewTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

public enum EngagementActionType {
    LIKE, SHARE, FAVORITE
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "engagement_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EngagementLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "actor_employee_id")
    private Long actorEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private EngagementActionType actionType;

    @Column(name = "occur_time")
    private LocalDateTime occurTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

public enum ActiveActionType {
    POST, COMMENT, LOGIN, SHARE, FAVORITE, LIKE, VIEW
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "active_action_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ActiveActionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type")
    private ActiveActionType actionType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "occur_time")
    private LocalDateTime occurTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "editorial_pick_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EditorialPickLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "pick_time")
    private LocalDateTime pickTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

```java
package com.jiaxian.analytics.domain;

public enum PointsCategory {
    ACTIVE, PASSIVE
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "points_weight_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PointsWeightConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_type")
    private String actionType;

    @Enumerated(EnumType.STRING)
    private PointsCategory category;

    private Integer weight;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;
}
```

```java
package com.jiaxian.analytics.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "points_ledger")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PointsLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "action_type")
    private String actionType;

    @Enumerated(EnumType.STRING)
    private PointsCategory category;

    private Integer points;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "occur_time")
    private LocalDateTime occurTime;

    @Column(name = "simulation_batch_id")
    private String simulationBatchId;
}
```

- [ ] **Step 4: 编写 Repository 接口**

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PvLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PvLogRepository extends JpaRepository<PvLog, Long> {
    List<PvLog> findByContentId(Long contentId);
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.EngagementLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EngagementLogRepository extends JpaRepository<EngagementLog, Long> {
    List<EngagementLog> findByContentId(Long contentId);
    List<EngagementLog> findByCommentId(Long commentId);
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.ActiveActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActiveActionLogRepository extends JpaRepository<ActiveActionLog, Long> {
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.EditorialPickLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EditorialPickLogRepository extends JpaRepository<EditorialPickLog, Long> {
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PointsWeightConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointsWeightConfigRepository extends JpaRepository<PointsWeightConfig, Long> {
    Optional<PointsWeightConfig> findFirstByActionTypeOrderByEffectiveDateDesc(String actionType);
}
```

```java
package com.jiaxian.analytics.repository;

import com.jiaxian.analytics.domain.PointsLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsLedgerRepository extends JpaRepository<PointsLedger, Long> {
    List<PointsLedger> findByEmployeeId(Long employeeId);
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=FactAndPointsRepositoryIT`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/domain backend/src/main/java/com/jiaxian/analytics/repository \
        backend/src/test/java/com/jiaxian/analytics/repository/FactAndPointsRepositoryIT.java
git commit -m "feat: add fact and points table entities and repositories"
```

---

### Task 7: 频道种子数据与积分权重种子数据

**Files:**
- Create: `backend/src/main/resources/data.sql`

- [ ] **Step 1: 编写频道种子数据**

```sql
INSERT INTO channel (name, category) VALUES
    ('关注', '综合频道'),
    ('推荐', '综合频道'),
    ('热点', '综合频道'),
    ('视频', '综合频道'),
    ('直播', '综合频道'),
    ('AI学习', '综合频道'),
    ('终端', '专业领域'),
    ('移动无线', '专业领域'),
    ('固定网络', '专业领域'),
    ('半导体', '专业领域'),
    ('IT计算', '专业领域'),
    ('智能汽车', '专业领域'),
    ('知识产权', '专业领域'),
    ('制造', '专业领域'),
    ('供应链', '专业领域')
ON CONFLICT (name) DO NOTHING;
```

- [ ] **Step 2: 在同一个 data.sql 文件里追加积分权重种子数据**

```sql
INSERT INTO points_weight_config (action_type, category, weight, effective_date) VALUES
    ('POST', 'ACTIVE', 100, '2026-01-01'),
    ('LOGIN', 'ACTIVE', 20, '2026-01-01'),
    ('COMMENT', 'ACTIVE', 15, '2026-01-01'),
    ('SHARE', 'ACTIVE', 15, '2026-01-01'),
    ('FAVORITE', 'ACTIVE', 5, '2026-01-01'),
    ('LIKE', 'ACTIVE', 3, '2026-01-01'),
    ('VIEW', 'ACTIVE', 1, '2026-01-01'),
    ('EDITORIAL_PICK', 'PASSIVE', 300, '2026-01-01'),
    ('CONTENT_COMMENTED', 'PASSIVE', 15, '2026-01-01'),
    ('CONTENT_SHARED', 'PASSIVE', 15, '2026-01-01'),
    ('COMMENT_REPLIED', 'PASSIVE', 15, '2026-01-01'),
    ('CONTENT_FAVORITED', 'PASSIVE', 5, '2026-01-01'),
    ('CONTENT_LIKED', 'PASSIVE', 3, '2026-01-01'),
    ('COMMENT_LIKED', 'PASSIVE', 3, '2026-01-01'),
    ('CONTENT_VIEWED', 'PASSIVE', 1, '2026-01-01')
ON CONFLICT (action_type, effective_date) DO NOTHING;
```

**说明**：两段 `INSERT` 都用 `ON CONFLICT ... DO NOTHING`，配合 Task 3/5 给 `channel.name` 和 `points_weight_config(action_type, effective_date)` 加的 `UNIQUE` 约束，保证 `data.sql` 在每次应用启动时重复执行也不会报重复键错误或产生重复行。

- [ ] **Step 3: 运行验证 data.sql 生效**

Run: `cd backend && mvn spring-boot:run` （启动后 Ctrl+C 停止）
Expected: 无报错，日志显示 `data.sql` 执行成功。

Run: `docker exec -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U jiaxian_app -d postgres -W "Jiaxian@2026" -c "SELECT count(*) FROM channel; SELECT count(*) FROM points_weight_config;"`
Expected: `channel` 15 行，`points_weight_config` 15 行。再重复跑一次 `mvn spring-boot:run` 后重新执行这条 `SELECT count(*)`，行数应该还是 15/15（验证幂等性），不会变成 30/30。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/data.sql
git commit -m "feat: seed channel list and points weight config"
```

---

### Task 8: 幂律热度分布工具类

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/PopularityDistribution.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/PopularityDistributionTest.java`

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PopularityDistributionTest {

    @Test
    void generatesWeightsBetweenZeroExclusiveAndOneInclusive() {
        PopularityDistribution distribution = new PopularityDistribution(new Random(42));

        List<Double> weights = distribution.generateWeights(1000);

        assertThat(weights).hasSize(1000);
        assertThat(weights).allMatch(w -> w > 0.0 && w <= 1.0);
    }

    @Test
    void topDecileCapturesMajorityOfTotalWeight() {
        PopularityDistribution distribution = new PopularityDistribution(new Random(42));

        List<Double> weights = distribution.generateWeights(1000);
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double topDecileSum = weights.stream()
            .sorted((a, b) -> Double.compare(b, a))
            .limit(100)
            .mapToDouble(Double::doubleValue)
            .sum();

        assertThat(topDecileSum / total).isGreaterThan(0.5);
    }

    @Test
    void sameSeedProducesSameSequence() {
        PopularityDistribution d1 = new PopularityDistribution(new Random(7));
        PopularityDistribution d2 = new PopularityDistribution(new Random(7));

        assertThat(d1.generateWeights(50)).isEqualTo(d2.generateWeights(50));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=PopularityDistributionTest`
Expected: FAIL，编译错误（`PopularityDistribution` 尚不存在）。

- [ ] **Step 3: 实现幂律分布工具类**

用帕累托分布（Pareto distribution）逆变换采样生成 (0, 1] 范围内的热度权重，形状参数越小、长尾越明显：

```java
package com.jiaxian.analytics.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PopularityDistribution {

    private static final double SHAPE = 1.2;
    private final Random random;

    public PopularityDistribution(Random random) {
        this.random = random;
    }

    /**
     * 用帕累托分布逆变换采样，返回归一化到 (0, 1] 的热度权重列表，
     * 数值越大代表越"热"，符合内容热度二八效应的分布特征。
     */
    public List<Double> generateWeights(int count) {
        List<Double> raw = new ArrayList<>(count);
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            double u = 1.0 - random.nextDouble();
            double sample = Math.pow(u, -1.0 / SHAPE);
            raw.add(sample);
            max = Math.max(max, sample);
        }

        List<Double> normalized = new ArrayList<>(count);
        for (double v : raw) {
            normalized.add(v / max);
        }
        return normalized;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=PopularityDistributionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/PopularityDistribution.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/PopularityDistributionTest.java
git commit -m "feat: add pareto-based popularity distribution generator"
```

---

### Task 9: 基础数据生成器（员工/账号/内容）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/SimulationConfig.java`
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/EntityGenerator.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/EntityGeneratorTest.java`

- [ ] **Step 1: 编写配置类 SimulationConfig**

```java
package com.jiaxian.analytics.simulator;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class SimulationConfig {
    private String batchId;
    private int employeeCount;
    private int accountCount;
    private double teamAccountRatio;
    private int contentCount;
    private LocalDate historyStart;
    private LocalDate historyEnd;
    private long randomSeed;
}
```

- [ ] **Step 2: 编写失败的单元测试**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityGeneratorTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AccountMemberRepository accountMemberRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private ContentAuthorRepository contentAuthorRepository;

    @Test
    void generatesConfiguredNumberOfEmployeesAccountsAndContent() {
        when(employeeRepository.save(any())).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId((long) (Math.random() * 1_000_000));
            return e;
        });
        when(accountRepository.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId((long) (Math.random() * 1_000_000));
            return a;
        });
        when(contentRepository.save(any())).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            c.setId((long) (Math.random() * 1_000_000));
            return c;
        });
        List<Channel> channels = new ArrayList<>();
        for (long i = 1; i <= 15; i++) {
            channels.add(Channel.builder().id(i).name("channel-" + i).category("专业领域").build());
        }
        when(channelRepository.findAll()).thenReturn(channels);

        EntityGenerator generator = new EntityGenerator(
            employeeRepository, accountRepository, accountMemberRepository,
            channelRepository, contentRepository, contentAuthorRepository);

        SimulationConfig config = SimulationConfig.builder()
            .batchId("test-batch")
            .employeeCount(50)
            .accountCount(20)
            .teamAccountRatio(0.1)
            .contentCount(200)
            .historyStart(LocalDate.of(2025, 1, 1))
            .historyEnd(LocalDate.of(2026, 9, 23))
            .randomSeed(42)
            .build();

        EntityGenerator.GeneratedEntities result = generator.generate(config);

        assertThat(result.getEmployees()).hasSize(50);
        assertThat(result.getAccounts()).hasSize(20);
        assertThat(result.getContents()).hasSize(200);

        ArgumentCaptor<AccountMember> memberCaptor = ArgumentCaptor.forClass(AccountMember.class);
        verify(accountMemberRepository, atLeastOnce()).save(memberCaptor.capture());
        assertThat(memberCaptor.getAllValues()).allMatch(m -> "test-batch".equals(m.getSimulationBatchId()));

        long teamAccounts = result.getAccounts().stream()
            .filter(a -> a.getType() == AccountType.TEAM).count();
        assertThat(teamAccounts).isEqualTo(2);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=EntityGeneratorTest`
Expected: FAIL，编译错误（`EntityGenerator` 尚不存在）。此测试需要额外依赖 `org.mockito:mockito-junit-jupiter`，在 `backend/pom.xml` 的 `spring-boot-starter-test` 依赖中已默认包含 Mockito，无需单独添加。

- [ ] **Step 4: 实现 EntityGenerator**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EntityGenerator {

    private final EmployeeRepository employeeRepository;
    private final AccountRepository accountRepository;
    private final AccountMemberRepository accountMemberRepository;
    private final ChannelRepository channelRepository;
    private final ContentRepository contentRepository;
    private final ContentAuthorRepository contentAuthorRepository;

    public EntityGenerator(EmployeeRepository employeeRepository,
                            AccountRepository accountRepository,
                            AccountMemberRepository accountMemberRepository,
                            ChannelRepository channelRepository,
                            ContentRepository contentRepository,
                            ContentAuthorRepository contentAuthorRepository) {
        this.employeeRepository = employeeRepository;
        this.accountRepository = accountRepository;
        this.accountMemberRepository = accountMemberRepository;
        this.channelRepository = channelRepository;
        this.contentRepository = contentRepository;
        this.contentAuthorRepository = contentAuthorRepository;
    }

    public GeneratedEntities generate(SimulationConfig config) {
        Random random = new Random(config.getRandomSeed());
        String batchId = config.getBatchId();

        List<Employee> employees = new ArrayList<>();
        String[] depts = {"终端", "移动无线", "固定网络", "半导体", "IT计算", "智能汽车", "知识产权", "制造", "供应链"};
        for (int i = 0; i < config.getEmployeeCount(); i++) {
            Employee employee = employeeRepository.save(Employee.builder()
                .name("员工-" + i)
                .dept(depts[random.nextInt(depts.length)])
                .joinDate(LocalDate.of(2018 + random.nextInt(8), 1 + random.nextInt(12), 1 + random.nextInt(28)))
                .simulationBatchId(batchId)
                .build());
            employees.add(employee);
        }

        List<Account> accounts = new ArrayList<>();
        int teamAccountCount = (int) Math.round(config.getAccountCount() * config.getTeamAccountRatio());
        for (int i = 0; i < config.getAccountCount(); i++) {
            boolean isTeam = i < teamAccountCount;
            Account account = accountRepository.save(Account.builder()
                .name((isTeam ? "团队账号-" : "个人账号-") + i)
                .type(isTeam ? AccountType.TEAM : AccountType.PERSONAL)
                .simulationBatchId(batchId)
                .build());
            accounts.add(account);

            int memberCount = isTeam ? 2 + random.nextInt(3) : 1;
            for (int m = 0; m < memberCount; m++) {
                Employee member = employees.get(random.nextInt(employees.size()));
                accountMemberRepository.save(AccountMember.builder()
                    .accountId(account.getId())
                    .employeeId(member.getId())
                    .simulationBatchId(batchId)
                    .build());
            }
        }

        List<Channel> channels = channelRepository.findAll();
        PopularityDistribution popularityDistribution = new PopularityDistribution(random);
        List<Double> weights = popularityDistribution.generateWeights(config.getContentCount());

        long historyDays = ChronoUnit.DAYS.between(config.getHistoryStart(), config.getHistoryEnd());
        List<Content> contents = new ArrayList<>();
        for (int i = 0; i < config.getContentCount(); i++) {
            Account account = accounts.get(random.nextInt(accounts.size()));
            Channel channel = channels.get(random.nextInt(channels.size()));
            LocalDateTime publishTime = config.getHistoryStart()
                .plusDays(random.nextInt((int) Math.max(historyDays, 1)))
                .atTime(random.nextInt(24), random.nextInt(60));

            Content content = contentRepository.save(Content.builder()
                .type(random.nextDouble() < 0.7 ? ContentType.ARTICLE : ContentType.VIDEO)
                .channelId(channel.getId())
                .accountId(account.getId())
                .publishTime(publishTime)
                .popularityWeight(weights.get(i))
                .simulationBatchId(batchId)
                .build());
            contents.add(content);

            List<AccountMember> members = accountMemberRepository.findByAccountId(account.getId());
            int authorCount = Math.max(1, Math.min(members.size(), 1 + random.nextInt(2)));
            for (int a = 0; a < authorCount && a < members.size(); a++) {
                contentAuthorRepository.save(ContentAuthor.builder()
                    .contentId(content.getId())
                    .employeeId(members.get(a).getEmployeeId())
                    .simulationBatchId(batchId)
                    .build());
            }
        }

        return new GeneratedEntities(employees, accounts, contents);
    }

    @Getter
    public static class GeneratedEntities {
        private final List<Employee> employees;
        private final List<Account> accounts;
        private final List<Content> contents;

        public GeneratedEntities(List<Employee> employees, List<Account> accounts, List<Content> contents) {
            this.employees = employees;
            this.accounts = accounts;
            this.contents = contents;
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=EntityGeneratorTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/SimulationConfig.java \
        backend/src/main/java/com/jiaxian/analytics/simulator/EntityGenerator.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/EntityGeneratorTest.java
git commit -m "feat: add entity generator for employees/accounts/content"
```

---

### Task 10: 行为事件生成器（双边积分记账）

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/BehaviorEventGenerator.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/BehaviorEventGeneratorTest.java`

**说明**：这是积分引擎的核心逻辑——把「点赞/分享/收藏/浏览」这些一次性动作，展开成两条 `points_ledger` 记录（操作人主动分 + 内容作者被动分，团队账号多作者按均分处理），并同步写 `pv_log`/`engagement_log`/`active_action_log`。评论（含二级回复）在 Task 11 单独实现，因为它有独立的父子关系逻辑。

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BehaviorEventGeneratorTest {

    @Mock private PvLogRepository pvLogRepository;
    @Mock private EngagementLogRepository engagementLogRepository;
    @Mock private ActiveActionLogRepository activeActionLogRepository;
    @Mock private ContentAuthorRepository contentAuthorRepository;
    @Mock private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Mock private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void likeEventCreditsBothActorAndContentAuthorsSplitEvenly() {
        Long contentId = 100L;
        Long actorId = 1L;
        Long author1 = 2L;
        Long author2 = 3L;

        when(contentAuthorRepository.findByContentId(contentId)).thenReturn(List.of(
            ContentAuthor.builder().contentId(contentId).employeeId(author1).build(),
            ContentAuthor.builder().contentId(contentId).employeeId(author2).build()
        ));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("LIKE"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("LIKE").category(PointsCategory.ACTIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("CONTENT_LIKED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("CONTENT_LIKED").category(PointsCategory.PASSIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            new Random(1));

        generator.generateLike(contentId, actorId, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(3)).save(ledgerCaptor.capture());

        List<PointsLedger> entries = ledgerCaptor.getAllValues();
        PointsLedger actorEntry = entries.stream()
            .filter(e -> e.getEmployeeId().equals(actorId)).findFirst().orElseThrow();
        assertThat(actorEntry.getCategory()).isEqualTo(PointsCategory.ACTIVE);
        assertThat(actorEntry.getPoints()).isEqualTo(3);

        List<PointsLedger> authorEntries = entries.stream()
            .filter(e -> !e.getEmployeeId().equals(actorId)).toList();
        assertThat(authorEntries).hasSize(2);
        assertThat(authorEntries).allMatch(e -> e.getCategory() == PointsCategory.PASSIVE);
        // 3分被动积分按2个共同作者均分，各得1（整数截断，验证均分逻辑生效）
        assertThat(authorEntries).allMatch(e -> e.getPoints() == 1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=BehaviorEventGeneratorTest`
Expected: FAIL，编译错误（`BehaviorEventGenerator` 尚不存在）。

- [ ] **Step 3: 实现 BehaviorEventGenerator**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

public class BehaviorEventGenerator {

    private final PvLogRepository pvLogRepository;
    private final EngagementLogRepository engagementLogRepository;
    private final ActiveActionLogRepository activeActionLogRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final Random random;

    public BehaviorEventGenerator(PvLogRepository pvLogRepository,
                                   EngagementLogRepository engagementLogRepository,
                                   ActiveActionLogRepository activeActionLogRepository,
                                   ContentAuthorRepository contentAuthorRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   Random random) {
        this.pvLogRepository = pvLogRepository;
        this.engagementLogRepository = engagementLogRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.random = random;
    }

    public void generateView(Long contentId, Long viewerId, LocalDateTime occurTime, String batchId) {
        pvLogRepository.save(PvLog.builder()
            .contentId(contentId).viewerEmployeeId(viewerId).viewTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(viewerId, ActiveActionType.VIEW, "VIEW", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_VIEWED", occurTime, batchId);
    }

    public void generateLike(Long contentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .contentId(contentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.LIKE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.LIKE, "LIKE", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_LIKED", occurTime, batchId);
    }

    public void generateShare(Long contentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .contentId(contentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.SHARE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.SHARE, "SHARE", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_SHARED", occurTime, batchId);
    }

    public void generateFavorite(Long contentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .contentId(contentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.FAVORITE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.FAVORITE, "FAVORITE", contentId, occurTime, batchId);
        creditContentAuthorsPassive(contentId, "CONTENT_FAVORITED", occurTime, batchId);
    }

    public void generateLogin(Long employeeId, LocalDateTime occurTime, String batchId) {
        recordActive(employeeId, ActiveActionType.LOGIN, "LOGIN", null, occurTime, batchId);
    }

    private void recordActive(Long employeeId, ActiveActionType actionType, String weightActionType,
                               Long refId, LocalDateTime occurTime, String batchId) {
        activeActionLogRepository.save(ActiveActionLog.builder()
            .employeeId(employeeId).actionType(actionType).refId(refId)
            .occurTime(occurTime).simulationBatchId(batchId).build());

        int weight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc(weightActionType)
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for " + weightActionType));

        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(employeeId).actionType(weightActionType).category(PointsCategory.ACTIVE)
            .points(weight).refId(refId).occurTime(occurTime).simulationBatchId(batchId).build());
    }

    private void creditContentAuthorsPassive(Long contentId, String weightActionType,
                                               LocalDateTime occurTime, String batchId) {
        List<ContentAuthor> authors = contentAuthorRepository.findByContentId(contentId);
        if (authors.isEmpty()) {
            return;
        }

        int totalWeight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc(weightActionType)
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for " + weightActionType));

        int perAuthorPoints = totalWeight / authors.size();
        for (ContentAuthor author : authors) {
            pointsLedgerRepository.save(PointsLedger.builder()
                .employeeId(author.getEmployeeId()).actionType(weightActionType)
                .category(PointsCategory.PASSIVE).points(perAuthorPoints)
                .refId(contentId).occurTime(occurTime).simulationBatchId(batchId).build());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=BehaviorEventGeneratorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/BehaviorEventGenerator.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/BehaviorEventGeneratorTest.java
git commit -m "feat: add behavior event generator with dual-sided points accounting"
```

---

### Task 11: 评论生成器（含二级回复与双边积分）

**Files:**
- Modify: `backend/src/main/java/com/jiaxian/analytics/simulator/BehaviorEventGenerator.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/BehaviorEventGeneratorTest.java`

- [ ] **Step 1: 在测试文件中追加失败的评论测试**

在 `BehaviorEventGeneratorTest.java` 中新增以下测试方法，并在类顶部追加 `@Mock private CommentRepository commentRepository;` 字段和对应的构造参数：

```java
    @Mock private CommentRepository commentRepository;

    @Test
    void postingTopLevelCommentCreditsCommenterAndContentAuthor() {
        Long contentId = 200L;
        Long commenterId = 10L;
        Long contentAuthorId = 20L;

        when(commentRepository.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(999L);
            return c;
        });
        when(contentAuthorRepository.findByContentId(contentId)).thenReturn(List.of(
            ContentAuthor.builder().contentId(contentId).employeeId(contentAuthorId).build()
        ));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT").category(PointsCategory.ACTIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("CONTENT_COMMENTED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("CONTENT_COMMENTED").category(PointsCategory.PASSIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateComment(contentId, null, commenterId, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(2)).save(captor.capture());

        List<PointsLedger> entries = captor.getAllValues();
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(commenterId)
            && e.getCategory() == PointsCategory.ACTIVE && e.getPoints() == 15);
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(contentAuthorId)
            && e.getCategory() == PointsCategory.PASSIVE && e.getPoints() == 15);
    }

    @Test
    void replyingToCommentCreditsReplierAndOriginalCommenterNotContentAuthor() {
        Long contentId = 200L;
        Long parentCommentId = 300L;
        Long originalCommenterId = 20L;
        Long replierId = 30L;

        Comment parentComment = Comment.builder().id(parentCommentId)
            .contentId(contentId).employeeId(originalCommenterId).build();
        when(commentRepository.findById(parentCommentId)).thenReturn(java.util.Optional.of(parentComment));
        when(commentRepository.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(999L);
            return c;
        });
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT").category(PointsCategory.ACTIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_REPLIED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT_REPLIED").category(PointsCategory.PASSIVE).weight(15)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateComment(contentId, parentCommentId, replierId, LocalDateTime.now(), "test-batch");

        verify(contentAuthorRepository, never()).findByContentId(any());
        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(2)).save(captor.capture());

        List<PointsLedger> entries = captor.getAllValues();
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(replierId)
            && e.getCategory() == PointsCategory.ACTIVE && e.getPoints() == 15);
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(originalCommenterId)
            && e.getCategory() == PointsCategory.PASSIVE && e.getPoints() == 15);
    }

    @Test
    void likingCommentCreditsLikerAndCommentAuthor() {
        Long commentId = 400L;
        Long likerId = 40L;
        Long commentAuthorId = 50L;

        Comment comment = Comment.builder().id(commentId).employeeId(commentAuthorId).build();
        when(commentRepository.findById(commentId)).thenReturn(java.util.Optional.of(comment));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("LIKE"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("LIKE").category(PointsCategory.ACTIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_LIKED"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("COMMENT_LIKED").category(PointsCategory.PASSIVE).weight(3)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        BehaviorEventGenerator generator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, new Random(1));

        generator.generateCommentLike(commentId, likerId, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<EngagementLog> engagementCaptor = ArgumentCaptor.forClass(EngagementLog.class);
        verify(engagementLogRepository).save(engagementCaptor.capture());
        assertThat(engagementCaptor.getValue().getCommentId()).isEqualTo(commentId);
        assertThat(engagementCaptor.getValue().getContentId()).isNull();

        ArgumentCaptor<PointsLedger> captor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository, times(2)).save(captor.capture());

        List<PointsLedger> entries = captor.getAllValues();
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(likerId)
            && e.getCategory() == PointsCategory.ACTIVE && e.getPoints() == 3);
        assertThat(entries).anyMatch(e -> e.getEmployeeId().equals(commentAuthorId)
            && e.getCategory() == PointsCategory.PASSIVE && e.getPoints() == 3);
    }
```

同时，把测试类中已有的 `BehaviorEventGenerator` 构造调用（Task 10 的 `likeEventCreditsBothActorAndContentAuthorsSplitEvenly` 测试里那一处）追加一个 `commentRepository` 参数（紧跟在 `pointsLedgerRepository` 之后、`random` 之前）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=BehaviorEventGeneratorTest`
Expected: FAIL，编译错误（`generateComment` 方法和新构造参数尚不存在）。

- [ ] **Step 3: 修改 BehaviorEventGenerator，加入评论逻辑**

在构造函数中新增 `CommentRepository commentRepository` 字段和参数（追加在 `pointsLedgerRepository` 之后）：

```java
    private final CommentRepository commentRepository;

    public BehaviorEventGenerator(PvLogRepository pvLogRepository,
                                   EngagementLogRepository engagementLogRepository,
                                   ActiveActionLogRepository activeActionLogRepository,
                                   ContentAuthorRepository contentAuthorRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   CommentRepository commentRepository,
                                   Random random) {
        this.pvLogRepository = pvLogRepository;
        this.engagementLogRepository = engagementLogRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.commentRepository = commentRepository;
        this.random = random;
    }
```

新增 `generateComment` 方法（放在 `generateLogin` 方法之后）：

```java
    public Comment generateComment(Long contentId, Long parentCommentId, Long commenterId,
                                    LocalDateTime occurTime, String batchId) {
        Comment saved = commentRepository.save(Comment.builder()
            .contentId(contentId).employeeId(commenterId).parentCommentId(parentCommentId)
            .createTime(occurTime).simulationBatchId(batchId).build());

        recordActive(commenterId, ActiveActionType.COMMENT, "COMMENT", contentId, occurTime, batchId);

        if (parentCommentId == null) {
            creditContentAuthorsPassive(contentId, "CONTENT_COMMENTED", occurTime, batchId);
        } else {
            Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new IllegalStateException("Parent comment not found: " + parentCommentId));
            int weight = pointsWeightConfigRepository
                .findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_REPLIED")
                .map(PointsWeightConfig::getWeight)
                .orElseThrow(() -> new IllegalStateException("Missing points weight config for COMMENT_REPLIED"));
            pointsLedgerRepository.save(PointsLedger.builder()
                .employeeId(parent.getEmployeeId()).actionType("COMMENT_REPLIED")
                .category(PointsCategory.PASSIVE).points(weight)
                .refId(parentCommentId).occurTime(occurTime).simulationBatchId(batchId).build());
        }

        return saved;
    }
```

新增 `generateCommentLike` 方法（放在 `generateComment` 方法之后），用于模拟"评论获赞"这个被动积分场景：

```java
    public void generateCommentLike(Long commentId, Long actorId, LocalDateTime occurTime, String batchId) {
        engagementLogRepository.save(EngagementLog.builder()
            .commentId(commentId).actorEmployeeId(actorId)
            .actionType(EngagementActionType.LIKE).occurTime(occurTime)
            .simulationBatchId(batchId).build());

        recordActive(actorId, ActiveActionType.LIKE, "LIKE", commentId, occurTime, batchId);

        Comment comment = commentRepository.findById(commentId)
            .orElseThrow(() -> new IllegalStateException("Comment not found: " + commentId));
        int weight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc("COMMENT_LIKED")
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for COMMENT_LIKED"));
        pointsLedgerRepository.save(PointsLedger.builder()
            .employeeId(comment.getEmployeeId()).actionType("COMMENT_LIKED")
            .category(PointsCategory.PASSIVE).points(weight)
            .refId(commentId).occurTime(occurTime).simulationBatchId(batchId).build());
    }
```

在文件顶部 import 区加入：

```java
import com.jiaxian.analytics.repository.CommentRepository;
```

（若已通过通配符 `import com.jiaxian.analytics.repository.*;` 引入则跳过此步。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=BehaviorEventGeneratorTest`
Expected: PASS（4 个测试全部通过：Task 10 的 1 个 + 本任务新增的 3 个——直接评论、回复评论、评论获赞，注意 Task 10 原有的 like 测试也需要能正常编译通过，因为构造参数已变化）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/BehaviorEventGenerator.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/BehaviorEventGeneratorTest.java
git commit -m "feat: add comment generation with two-level reply support"
```

---

### Task 12: 编辑精选（思想简报）事件生成器

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/EditorialPickGenerator.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/EditorialPickGeneratorTest.java`

- [ ] **Step 1: 编写失败的单元测试**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EditorialPickGeneratorTest {

    @Mock private ContentAuthorRepository contentAuthorRepository;
    @Mock private EditorialPickLogRepository editorialPickLogRepository;
    @Mock private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Mock private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void picksTopContentByPopularityAndCreditsAuthors() {
        Content top = Content.builder().id(1L).popularityWeight(0.95).build();
        Content mid = Content.builder().id(2L).popularityWeight(0.5).build();
        Content low = Content.builder().id(3L).popularityWeight(0.1).build();

        when(contentAuthorRepository.findByContentId(1L)).thenReturn(List.of(
            ContentAuthor.builder().contentId(1L).employeeId(100L).build()));
        when(pointsWeightConfigRepository.findFirstByActionTypeOrderByEffectiveDateDesc("EDITORIAL_PICK"))
            .thenReturn(java.util.Optional.of(PointsWeightConfig.builder()
                .actionType("EDITORIAL_PICK").category(PointsCategory.PASSIVE).weight(300)
                .effectiveDate(LocalDate.of(2026, 1, 1)).build()));

        EditorialPickGenerator generator = new EditorialPickGenerator(
            contentAuthorRepository, editorialPickLogRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, new Random(1));

        generator.pickTopContent(List.of(top, mid, low), 1, LocalDateTime.now(), "test-batch");

        ArgumentCaptor<EditorialPickLog> pickCaptor = ArgumentCaptor.forClass(EditorialPickLog.class);
        verify(editorialPickLogRepository).save(pickCaptor.capture());
        assertThat(pickCaptor.getValue().getContentId()).isEqualTo(1L);

        ArgumentCaptor<PointsLedger> ledgerCaptor = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getEmployeeId()).isEqualTo(100L);
        assertThat(ledgerCaptor.getValue().getPoints()).isEqualTo(300);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=EditorialPickGeneratorTest`
Expected: FAIL，编译错误（`EditorialPickGenerator` 尚不存在）。

- [ ] **Step 3: 实现 EditorialPickGenerator**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class EditorialPickGenerator {

    private final ContentAuthorRepository contentAuthorRepository;
    private final EditorialPickLogRepository editorialPickLogRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final Random random;

    public EditorialPickGenerator(ContentAuthorRepository contentAuthorRepository,
                                   EditorialPickLogRepository editorialPickLogRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   Random random) {
        this.contentAuthorRepository = contentAuthorRepository;
        this.editorialPickLogRepository = editorialPickLogRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.random = random;
    }

    /**
     * 从候选内容里按热度权重降序取前 pickCount 篇，模拟"入选思想简报"编辑精选动作。
     */
    public void pickTopContent(List<Content> candidates, int pickCount, LocalDateTime pickTime, String batchId) {
        int weight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc("EDITORIAL_PICK")
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for EDITORIAL_PICK"));

        candidates.stream()
            .sorted(Comparator.comparingDouble(Content::getPopularityWeight).reversed())
            .limit(pickCount)
            .forEach(content -> {
                editorialPickLogRepository.save(EditorialPickLog.builder()
                    .contentId(content.getId()).pickTime(pickTime).simulationBatchId(batchId).build());

                List<ContentAuthor> authors = contentAuthorRepository.findByContentId(content.getId());
                int perAuthorPoints = authors.isEmpty() ? 0 : weight / authors.size();
                for (ContentAuthor author : authors) {
                    pointsLedgerRepository.save(PointsLedger.builder()
                        .employeeId(author.getEmployeeId()).actionType("EDITORIAL_PICK")
                        .category(PointsCategory.PASSIVE).points(perAuthorPoints)
                        .refId(content.getId()).occurTime(pickTime).simulationBatchId(batchId).build());
                }
            });
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=EditorialPickGeneratorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/EditorialPickGenerator.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/EditorialPickGeneratorTest.java
git commit -m "feat: add editorial pick (thought briefing) event generator"
```

---

### Task 13: 历史回填编排服务

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/HistoricalBackfillService.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/HistoricalBackfillServiceIT.java`

**说明**：这个服务把 Task 8-12 的生成器串起来，按 `SimulationConfig` 跑一整轮历史回填，并支持"清空旧模拟数据后重跑"。因为要验证端到端数据真的落库、且旧数据被正确清空，这里用真实 OpenGauss 做集成测试，而不是 mock。

- [ ] **Step 1: 编写失败的集成测试**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HistoricalBackfillServiceIT {

    @Autowired private HistoricalBackfillService backfillService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;

    @Test
    void runBackfillPopulatesDataAndRerunClearsPreviousBatch() {
        SimulationConfig firstRun = SimulationConfig.builder()
            .batchId("batch-1")
            .employeeCount(20)
            .accountCount(10)
            .teamAccountRatio(0.2)
            .contentCount(30)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(1)
            .build();

        backfillService.runBackfill(firstRun);

        assertThat(employeeRepository.count()).isEqualTo(20);
        assertThat(contentRepository.count()).isEqualTo(30);
        assertThat(pvLogRepository.count()).isGreaterThan(0);
        assertThat(pointsLedgerRepository.count()).isGreaterThan(0);

        SimulationConfig secondRun = SimulationConfig.builder()
            .batchId("batch-2")
            .employeeCount(15)
            .accountCount(8)
            .teamAccountRatio(0.2)
            .contentCount(20)
            .historyStart(LocalDate.of(2026, 1, 1))
            .historyEnd(LocalDate.of(2026, 3, 1))
            .randomSeed(2)
            .build();

        backfillService.runBackfill(secondRun);

        assertThat(employeeRepository.count()).isEqualTo(15);
        assertThat(contentRepository.count()).isEqualTo(20);
        assertThat(employeeRepository.findAll()).allMatch(e -> "batch-2".equals(e.getSimulationBatchId()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

前提：`docker-compose up -d opengauss`

Run: `cd backend && mvn test -Dtest=HistoricalBackfillServiceIT`
Expected: FAIL，编译错误（`HistoricalBackfillService` 尚不存在）。

- [ ] **Step 3: 实现 HistoricalBackfillService**

```java
package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

@Service
public class HistoricalBackfillService {

    private final EmployeeRepository employeeRepository;
    private final AccountRepository accountRepository;
    private final AccountMemberRepository accountMemberRepository;
    private final ChannelRepository channelRepository;
    private final ContentRepository contentRepository;
    private final ContentAuthorRepository contentAuthorRepository;
    private final CommentRepository commentRepository;
    private final PvLogRepository pvLogRepository;
    private final EngagementLogRepository engagementLogRepository;
    private final ActiveActionLogRepository activeActionLogRepository;
    private final EditorialPickLogRepository editorialPickLogRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;

    public HistoricalBackfillService(EmployeeRepository employeeRepository,
                                      AccountRepository accountRepository,
                                      AccountMemberRepository accountMemberRepository,
                                      ChannelRepository channelRepository,
                                      ContentRepository contentRepository,
                                      ContentAuthorRepository contentAuthorRepository,
                                      CommentRepository commentRepository,
                                      PvLogRepository pvLogRepository,
                                      EngagementLogRepository engagementLogRepository,
                                      ActiveActionLogRepository activeActionLogRepository,
                                      EditorialPickLogRepository editorialPickLogRepository,
                                      PointsWeightConfigRepository pointsWeightConfigRepository,
                                      PointsLedgerRepository pointsLedgerRepository) {
        this.employeeRepository = employeeRepository;
        this.accountRepository = accountRepository;
        this.accountMemberRepository = accountMemberRepository;
        this.channelRepository = channelRepository;
        this.contentRepository = contentRepository;
        this.contentAuthorRepository = contentAuthorRepository;
        this.commentRepository = commentRepository;
        this.pvLogRepository = pvLogRepository;
        this.engagementLogRepository = engagementLogRepository;
        this.activeActionLogRepository = activeActionLogRepository;
        this.editorialPickLogRepository = editorialPickLogRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
    }

    @Transactional
    public void runBackfill(SimulationConfig config) {
        clearPreviousSimulationData();

        Random random = new Random(config.getRandomSeed());

        EntityGenerator entityGenerator = new EntityGenerator(
            employeeRepository, accountRepository, accountMemberRepository,
            channelRepository, contentRepository, contentAuthorRepository);
        EntityGenerator.GeneratedEntities entities = entityGenerator.generate(config);

        BehaviorEventGenerator eventGenerator = new BehaviorEventGenerator(
            pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository,
            commentRepository, random);

        for (Content content : entities.getContents()) {
            // 热度权重越高，围绕这篇内容产生的浏览/点赞/评论事件越多，制造二八效应。
            int viewCount = 1 + (int) (content.getPopularityWeight() * 50);
            for (int i = 0; i < viewCount; i++) {
                var viewer = entities.getEmployees().get(random.nextInt(entities.getEmployees().size()));
                LocalDateTime occurTime = randomTimeAfter(content.getPublishTime(), random);
                eventGenerator.generateView(content.getId(), viewer.getId(), occurTime, config.getBatchId());

                if (random.nextDouble() < 0.1) {
                    eventGenerator.generateLike(content.getId(), viewer.getId(), occurTime, config.getBatchId());
                }
                if (random.nextDouble() < 0.05) {
                    eventGenerator.generateShare(content.getId(), viewer.getId(), occurTime, config.getBatchId());
                }
                if (random.nextDouble() < 0.03) {
                    eventGenerator.generateFavorite(content.getId(), viewer.getId(), occurTime, config.getBatchId());
                }
                if (random.nextDouble() < 0.08) {
                    var comment = eventGenerator.generateComment(
                        content.getId(), null, viewer.getId(), occurTime, config.getBatchId());
                    if (random.nextDouble() < 0.3) {
                        var commentLiker = entities.getEmployees().get(random.nextInt(entities.getEmployees().size()));
                        eventGenerator.generateCommentLike(comment.getId(), commentLiker.getId(), occurTime, config.getBatchId());
                    }
                }
            }
        }

        for (var employee : entities.getEmployees()) {
            int loginDays = 5 + random.nextInt(20);
            for (int i = 0; i < loginDays; i++) {
                LocalDateTime loginTime = randomTimeInRange(
                    config.getHistoryStart().atStartOfDay(), config.getHistoryEnd().atStartOfDay(), random);
                eventGenerator.generateLogin(employee.getId(), loginTime, config.getBatchId());
            }
        }

        EditorialPickGenerator editorialPickGenerator = new EditorialPickGenerator(
            contentAuthorRepository, editorialPickLogRepository,
            pointsWeightConfigRepository, pointsLedgerRepository, random);
        int pickCount = Math.max(1, entities.getContents().size() / 20);
        editorialPickGenerator.pickTopContent(entities.getContents(), pickCount,
            config.getHistoryEnd().atStartOfDay(), config.getBatchId());
    }

    private void clearPreviousSimulationData() {
        pointsLedgerRepository.deleteAll();
        editorialPickLogRepository.deleteAll();
        activeActionLogRepository.deleteAll();
        engagementLogRepository.deleteAll();
        pvLogRepository.deleteAll();
        commentRepository.deleteAll();
        contentAuthorRepository.deleteAll();
        contentRepository.deleteAll();
        accountMemberRepository.deleteAll();
        accountRepository.deleteAll();
        employeeRepository.deleteAll();
    }

    private LocalDateTime randomTimeAfter(LocalDateTime start, Random random) {
        long maxOffsetHours = 24L * 30;
        long offset = (long) (random.nextDouble() * maxOffsetHours);
        return start.plusHours(offset);
    }

    private LocalDateTime randomTimeInRange(LocalDateTime start, LocalDateTime end, Random random) {
        long rangeHours = ChronoUnit.HOURS.between(start, end);
        long offset = (long) (random.nextDouble() * Math.max(rangeHours, 1));
        return start.plusHours(offset);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=HistoricalBackfillServiceIT`
Expected: PASS（注意：这个测试涉及较多数据生成，运行时间可能到十几秒，属正常现象）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/HistoricalBackfillService.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/HistoricalBackfillServiceIT.java
git commit -m "feat: add historical backfill orchestration service"
```

---

### Task 14: 回填触发 REST API

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/simulator/SimulatorController.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/simulator/SimulatorControllerIT.java`

- [ ] **Step 1: 编写失败的集成测试**

```java
package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorControllerIT {

    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort private int port;

    @Test
    void triggersBackfillAndReturnsAccepted() {
        String requestBody = """
            {
              "batchId": "api-test-batch",
              "employeeCount": 10,
              "accountCount": 5,
              "teamAccountRatio": 0.2,
              "contentCount": 15,
              "historyStart": "2026-01-01",
              "historyEnd": "2026-02-01",
              "randomSeed": 5
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/admin/simulator/backfill", request, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("api-test-batch");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=SimulatorControllerIT`
Expected: FAIL，返回 404（`SimulatorController` 尚不存在）。

- [ ] **Step 3: 实现 SimulatorController**

```java
package com.jiaxian.analytics.simulator;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/simulator")
public class SimulatorController {

    private final HistoricalBackfillService backfillService;

    public SimulatorController(HistoricalBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/backfill")
    public String backfill(@RequestBody BackfillRequest request) {
        SimulationConfig config = SimulationConfig.builder()
            .batchId(request.getBatchId())
            .employeeCount(request.getEmployeeCount())
            .accountCount(request.getAccountCount())
            .teamAccountRatio(request.getTeamAccountRatio())
            .contentCount(request.getContentCount())
            .historyStart(request.getHistoryStart())
            .historyEnd(request.getHistoryEnd())
            .randomSeed(request.getRandomSeed())
            .build();

        backfillService.runBackfill(config);
        return "Backfill completed for batch: " + request.getBatchId();
    }

    @Getter @Setter
    public static class BackfillRequest {
        private String batchId;
        private int employeeCount;
        private int accountCount;
        private double teamAccountRatio;
        private int contentCount;
        private LocalDate historyStart;
        private LocalDate historyEnd;
        private long randomSeed;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=SimulatorControllerIT`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/jiaxian/analytics/simulator/SimulatorController.java \
        backend/src/test/java/com/jiaxian/analytics/simulator/SimulatorControllerIT.java
git commit -m "feat: add REST endpoint to trigger historical backfill"
```

---

### Task 15: 前端脚手架与健康检查页面

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/api/health.ts`

- [ ] **Step 1: 编写 package.json**

```json
{
  "name": "jiaxian-analytics-frontend",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.4.21"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.4",
    "typescript": "^5.4.5",
    "vite": "^5.2.8",
    "vue-tsc": "^2.0.11"
  }
}
```

- [ ] **Step 2: 编写 vite.config.ts**

```typescript
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
```

- [ ] **Step 3: 编写 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "types": ["vite/client"]
  },
  "include": ["src/**/*.ts", "src/**/*.vue"]
}
```

- [ ] **Step 4: 编写 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <title>稼先社区运营分析看板</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 5: 编写 API 调用模块**

```typescript
export async function fetchHealth(): Promise<string> {
  const response = await fetch("/api/health");
  if (!response.ok) {
    throw new Error(`Health check failed with status ${response.status}`);
  }
  return response.text();
}
```

- [ ] **Step 6: 编写 App.vue**

```vue
<script setup lang="ts">
import { ref, onMounted } from "vue";
import { fetchHealth } from "./api/health";

const status = ref("检查中...");

onMounted(async () => {
  try {
    status.value = await fetchHealth();
  } catch (e) {
    status.value = "后端不可用";
  }
});
</script>

<template>
  <main>
    <h1>稼先社区运营分析看板</h1>
    <p>后端状态：{{ status }}</p>
  </main>
</template>
```

- [ ] **Step 7: 编写 main.ts**

```typescript
import { createApp } from "vue";
import App from "./App.vue";

createApp(App).mount("#app");
```

- [ ] **Step 8: 运行验证前后端联通**

前提：后端已启动（`cd backend && mvn spring-boot:run`）。

Run: `cd frontend && npm install && npm run dev`
Expected: 终端输出本地访问地址（如 `http://localhost:5173`）；用浏览器打开后页面显示"后端状态：OK"。

- [ ] **Step 9: Commit**

```bash
git add frontend/
git commit -m "feat: bootstrap Vue3+TS frontend with backend health check page"
```

---

## 计划完成后的验收标准

1. `docker-compose up -d` 能一次性拉起 OpenGauss/Redis/Kafka。
2. `cd backend && mvn test` 全部通过（需先启动 OpenGauss）。
3. 调用 `POST /api/admin/simulator/backfill`（body 参考 Task 14 测试用例）后，能在数据库里查到对应数量的员工/账号/内容，以及非零的 `pv_log`/`points_ledger` 记录，且积分权重与设计文档第 3.3 节完全一致。
4. 前端页面能访问并显示后端健康状态。
5. 本计划范围内没有引入 Kafka 生产/消费逻辑、汇总表、排行榜、RFM 分层——这些留给后续计划。
