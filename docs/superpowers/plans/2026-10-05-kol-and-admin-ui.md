# KOL 管理与系统管理页面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐前端剩下的五个页面——KOL 名单、博主详情、数据模拟器控制台、积分权重配置、建议文案库维护，并补两处后端能力（积分权重接口、KOL 重算记录列表）。

**Architecture:** 后端新增 `pointsconfig` 包（版本化的积分权重读写）并在 `BloggerController` 加重算记录列表。前端沿用 Plan 7 的骨架（`useAsync`、`AsyncBlock`、`BaseChart`、图表配置纯函数），新增 `sendJson` 写请求封装、四个接口模块、三个纯逻辑工具和三个图表配置函数（都带 Vitest 单测），页面靠 `vue-tsc` + `vite build` + 浏览器里的人工验证。

**Tech Stack:** 后端 Spring Boot 3.2.5 / JdbcTemplate；前端 Vue 3、TypeScript、Vite 5、Element Plus、ECharts、Vue Router 4、Vitest 2。

**设计文档：** `docs/superpowers/specs/2026-10-05-kol-and-admin-ui-design.md`（先读它）。

---

## 必读：约定与踩坑记录

沿用 Plan 3~7 的全部约定。与本计划直接相关的：

1. **后端**：日期参数绑定 `Timestamp`、读取走 `getTimestamp`；`LIMIT` 用校验过的字面量；集成测试连真实 OpenGauss 且优先 `@Transactional`；每个后端任务后跑完整 `cd backend && mvn test`。测试类名不要与已有测试同名（`-Dtest=` 会一起匹配）。
2. **前端**：每个任务完成后都要通过 `cd frontend && npm test` 和 `cd frontend && npm run build`（后者含 `vue-tsc`）；自动生成的 `src/components.d.ts`、`src/auto-imports.d.ts` 要一起提交；日期一律用本地 `yyyy-MM-dd` 字符串（`utils/dateRange.ts`），不要用 `toISOString()`。
3. **Element Plus 的 `el-select` 把空字符串值当成"未选择"**，需要"全部/无"选项时用哨兵值。
4. **`ElMessage` / `ElMessageBox` 由 `unplugin-auto-import` 的 Element Plus 解析器自动导入**，不要手写 import（手写会丢掉消息框的样式）。它们的类型只有在 `vite build`/`vite dev` 经过自动导入插件后才会写进 `src/auto-imports.d.ts`，而 `npm run build` 先跑 `vue-tsc`，所以**第一次用到 `ElMessage` 的任务里要先单独跑一次 `npx vite build`** 重新生成 `auto-imports.d.ts`，然后把它提交（之后 `npm run build` 就能通过）。`el-table` 插槽里的 `row` 是通用行类型，传给函数时在模板里断言，如 `openEdit(row as PlaybookEntry)`。
5. **`v-model` 绑在 `el-input-number` 上时**，它发出的值类型是 `number | undefined`，如果 `vue-tsc` 对"把 `number | undefined` 赋给 `number`"报错，把对应字段类型改成 `number | undefined` 并在使用处用 `?? 默认值`，不要用 `as` 硬转。
6. 手工验证：后端 `--server.port=8093`，前端 `VITE_API_TARGET=http://localhost:8093 npm run dev`；用完关闭两个进程并确认端口释放。如果 Colima 停了，先 `colima start` 并等 OpenGauss/Kafka 就绪。
7. **回填会清空所有模拟数据**（员工、内容、行为、流水），派生的汇总/快照/KOL 结果靠外键级联一起清空；回填后要重新运行数据任务。回填的浏览行为会延续到发布后 30 天，所以 `historyEnd` 要早于今天至少 30 天。
8. 积分权重的引擎语义：取每个动作类型**生效日期最大**的版本，不看事件时间；只影响之后新产生的事件，已有流水不重算。

## 文件结构

**后端**（新建，`backend/src/main/java/com/jiaxian/analytics/pointsconfig/`，测试在 `backend/src/test/java/com/jiaxian/analytics/pointsconfig/`）：

| 文件 | 职责 |
|---|---|
| `WeightVersion.java`、`PointsConfigItem.java`、`WeightUpdateRequest.java` | 记录类 |
| `InvalidPointsConfigException.java`、`PointsConfigNotFoundException.java`、`PointsConfigConflictException.java` | 400 / 404 / 409 |
| `PointsConfigRepository.java` | `points_weight_config` 的读写（JdbcTemplate） |
| `PointsConfigService.java` | 校验、"今天生效"版本的覆盖/新增、删除版本 |
| `PointsConfigController.java`、`PointsConfigExceptionHandler.java` | 三个接口与错误映射 |

后端（修改）：`kol/KolRfmRepository.java`（加 `runDates`）、`kol/BloggerListService.java`（加 `runs`）、`kol/BloggerController.java`（加 `GET /runs`）。

**前端**（`frontend/src/`）：

| 文件 | 职责 |
|---|---|
| `api/http.ts`（改）、`api/types.ts`（改） | `sendJson`；新增类型 |
| `api/kol.ts`、`api/playbook.ts`、`api/pointsConfig.ts`、`api/admin.ts` | 接口封装 |
| `utils/segment.ts`、`utils/actionTypes.ts`、`utils/simulatorForms.ts` | 中文名/颜色字典；回填与实时表单的默认值、校验、请求体 |
| `charts/colors.ts`（改）、`charts/rfmHistoryOption.ts`、`charts/activityOption.ts`、`charts/pointsTrendOption.ts` | 图表配置（纯函数） |
| `views/kol/KolListView.vue`、`SegmentCards.vue`、`KolDetailView.vue`、`StandingCard.vue` | KOL 页面 |
| `views/admin/PlaybookView.vue`、`PointsConfigView.vue` | 文案库、权重页 |
| `views/admin/SimulatorView.vue`、`BackfillPanel.vue`、`RealtimePanel.vue`、`DataTasksPanel.vue` | 模拟器控制台 |
| `App.vue`、`router/index.ts`、`styles/theme.css`（改） | 菜单与路由 |

---

### Task 1: 后端——积分权重接口

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/pointsconfig/{WeightVersion,PointsConfigItem,WeightUpdateRequest,InvalidPointsConfigException,PointsConfigNotFoundException,PointsConfigConflictException,PointsConfigRepository,PointsConfigService,PointsConfigController,PointsConfigExceptionHandler}.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pointsconfig/PointsConfigServiceIT.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/pointsconfig/PointsConfigControllerIT.java`

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/pointsconfig/PointsConfigServiceIT.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import com.jiaxian.analytics.repository.PointsWeightConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PointsConfigServiceIT {

    @Autowired private PointsConfigService service;
    @Autowired private PointsWeightConfigRepository engineRepository;

    private PointsConfigItem item(String actionType) {
        return service.list().stream().filter(i -> i.actionType().equals(actionType)).findFirst().orElseThrow();
    }

    private static boolean hasVersionOn(PointsConfigItem item, LocalDate date) {
        return item.versions().stream().anyMatch(v -> v.effectiveDate().equals(date));
    }

    @Test
    void listsAllFifteenActionTypesWithActiveOnesFirst() {
        List<PointsConfigItem> items = service.list();

        assertThat(items).hasSize(15);
        assertThat(items.subList(0, 7)).allSatisfy(i -> assertThat(i.category()).isEqualTo("ACTIVE"));
        assertThat(items.subList(7, 15)).allSatisfy(i -> assertThat(i.category()).isEqualTo("PASSIVE"));
        assertThat(items).allSatisfy(i -> {
            assertThat(i.versions()).isNotEmpty();
            assertThat(i.current()).isEqualTo(i.versions().get(0));
        });
    }

    @Test
    void seedBaselineVersionIsPresent() {
        PointsConfigItem post = item("POST");

        assertThat(post.category()).isEqualTo("ACTIVE");
        assertThat(post.versions()).anyMatch(v -> v.weight() == 100 && v.effectiveDate().equals(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void updateAddsATodayVersionAndOverwritesItOnTheSameDay() {
        LocalDate today = LocalDate.now();
        PointsConfigItem before = item("LIKE");
        int expectedVersions = before.versions().size() + (hasVersionOn(before, today) ? 0 : 1);

        PointsConfigItem updated = service.update("LIKE", 4);

        assertThat(updated.current().weight()).isEqualTo(4);
        assertThat(updated.current().effectiveDate()).isEqualTo(today);
        assertThat(updated.versions()).hasSize(expectedVersions);

        PointsConfigItem overwritten = service.update("LIKE", 5);

        assertThat(overwritten.current().weight()).isEqualTo(5);
        assertThat(overwritten.versions()).hasSize(expectedVersions);
        // 引擎读到的就是最新版本
        assertThat(engineRepository.findFirstByActionTypeOrderByEffectiveDateDesc("LIKE").orElseThrow().getWeight())
            .isEqualTo(5);
    }

    @Test
    void deletingTheNewestVersionMakesThePreviousOneCurrent() {
        PointsConfigItem updated = service.update("SHARE", 9);
        long createdId = updated.current().id();

        service.deleteVersion(createdId);

        assertThat(item("SHARE").versions()).extracting(WeightVersion::id).doesNotContain(createdId);
        assertThat(item("SHARE").current().id()).isNotEqualTo(createdId);
    }

    @Test
    void theOnlyVersionOfAnActionCannotBeDeleted() {
        PointsConfigItem item = item("COMMENT_LIKED");
        // 先把除最后一个以外的版本都删掉
        for (int i = 0; i < item.versions().size() - 1; i++) {
            service.deleteVersion(item.versions().get(i).id());
        }
        long onlyId = item("COMMENT_LIKED").current().id();

        assertThatThrownBy(() -> service.deleteVersion(onlyId))
            .isInstanceOf(PointsConfigConflictException.class).hasMessageContaining("only");
    }

    @Test
    void unknownActionsAndVersionsAreNotFound() {
        assertThatThrownBy(() -> service.update("NO_SUCH_ACTION", 5)).isInstanceOf(PointsConfigNotFoundException.class);
        assertThatThrownBy(() -> service.deleteVersion(-1L)).isInstanceOf(PointsConfigNotFoundException.class);
    }

    @Test
    void weightMustBeAnIntegerBetweenZeroAndTenThousand() {
        assertThatThrownBy(() -> service.update("LIKE", null))
            .isInstanceOf(InvalidPointsConfigException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> service.update("LIKE", -1)).isInstanceOf(InvalidPointsConfigException.class);
        assertThatThrownBy(() -> service.update("LIKE", 10001)).isInstanceOf(InvalidPointsConfigException.class);
        assertThat(service.update("LIKE", 0).current().weight()).isZero();
        assertThat(service.update("LIKE", 10000).current().weight()).isEqualTo(10000);
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/pointsconfig/PointsConfigControllerIT.java`（走真实 HTTP，会改真实权重，所以用完恢复原状）：

```java
package com.jiaxian.analytics.pointsconfig;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointsConfigControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode likeItem() {
        JsonNode items = rest.getForEntity(url("/api/admin/points-config"), JsonNode.class).getBody();
        for (JsonNode item : items) {
            if (item.get("actionType").asText().equals("LIKE")) {
                return item;
            }
        }
        throw new AssertionError("LIKE not found");
    }

    @Test
    void listReturnsEveryActionTypeWithVersions() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/admin/points-config"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(15);
        JsonNode first = response.getBody().get(0);
        assertThat(first.get("category").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("current").get("weight").isInt()).isTrue();
        assertThat(first.get("versions").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void updateCreatesATodayVersionAndCanBeRolledBack() {
        String today = LocalDate.now().toString();
        Integer previousTodayWeight = null;
        for (JsonNode version : likeItem().get("versions")) {
            if (version.get("effectiveDate").asText().equals(today)) {
                previousTodayWeight = version.get("weight").asInt();
            }
        }
        long createdId = -1;
        try {
            ResponseEntity<JsonNode> response = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
                json("{\"weight\":7}"), JsonNode.class);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("actionType").asText()).isEqualTo("LIKE");
            assertThat(response.getBody().get("current").get("weight").asInt()).isEqualTo(7);
            assertThat(response.getBody().get("current").get("effectiveDate").asText()).isEqualTo(today);
            createdId = response.getBody().get("current").get("id").asLong();
        } finally {
            if (previousTodayWeight == null && createdId > 0) {
                rest.exchange(url("/api/admin/points-config/versions/" + createdId), HttpMethod.DELETE, null, Void.class);
            } else if (previousTodayWeight != null) {
                rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
                    json("{\"weight\":" + previousTodayWeight + "}"), JsonNode.class);
            }
        }
    }

    @Test
    void invalidRequestsAreRejected() {
        ResponseEntity<String> unknown = rest.exchange(url("/api/admin/points-config/NO_SUCH_ACTION"), HttpMethod.PUT,
            json("{\"weight\":5}"), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> negative = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
            json("{\"weight\":-5}"), String.class);
        assertThat(negative.getStatusCode().value()).isEqualTo(400);
        assertThat(negative.getBody()).contains("weight");

        ResponseEntity<String> missing = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
            json("{}"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> notJson = rest.exchange(url("/api/admin/points-config/LIKE"), HttpMethod.PUT,
            json("{\"weight\":\"abc\"}"), String.class);
        assertThat(notJson.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> noVersion = rest.exchange(url("/api/admin/points-config/versions/-1"),
            HttpMethod.DELETE, null, String.class);
        assertThat(noVersion.getStatusCode().value()).isEqualTo(404);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='PointsConfigServiceIT,PointsConfigControllerIT'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/WeightVersion.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import java.time.LocalDate;

public record WeightVersion(long id, int weight, LocalDate effectiveDate) {
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigItem.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import java.util.List;

/** 一个动作类型的权重配置：current 是生效日期最新的版本（引擎使用的那个），versions 新的在前。 */
public record PointsConfigItem(String actionType, String category, WeightVersion current,
                               List<WeightVersion> versions) {
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/WeightUpdateRequest.java`：

```java
package com.jiaxian.analytics.pointsconfig;

public record WeightUpdateRequest(Integer weight) {
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/InvalidPointsConfigException.java`：

```java
package com.jiaxian.analytics.pointsconfig;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidPointsConfigException extends RuntimeException {

    public InvalidPointsConfigException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigNotFoundException.java`：

```java
package com.jiaxian.analytics.pointsconfig;

/** 动作类型或版本不存在，对应 HTTP 404。 */
public class PointsConfigNotFoundException extends RuntimeException {

    public PointsConfigNotFoundException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigConflictException.java`：

```java
package com.jiaxian.analytics.pointsconfig;

/** 操作与现状冲突（比如删除某个动作仅剩的版本），对应 HTTP 409。 */
public class PointsConfigConflictException extends RuntimeException {

    public PointsConfigConflictException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigRepository.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** points_weight_config 的读写。生效日期列在 A 兼容模式下是 timestamp：写入绑定 Timestamp，读取走 getTimestamp。 */
@Repository
public class PointsConfigRepository {

    public record Row(long id, String actionType, String category, int weight, LocalDate effectiveDate) {
    }

    private static final String SELECT =
        "SELECT id, action_type, category, weight, effective_date FROM points_weight_config";

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> new Row(rs.getLong(1), rs.getString(2),
        rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toLocalDateTime().toLocalDate());

    private final JdbcTemplate jdbc;

    public PointsConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部版本，按动作类型分组，组内生效日期新的在前。 */
    public List<Row> all() {
        return jdbc.query(SELECT + " ORDER BY action_type, effective_date DESC, id DESC", MAPPER);
    }

    public Optional<Row> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Row> findByDate(String actionType, LocalDate effectiveDate) {
        return jdbc.query(SELECT + " WHERE action_type = ? AND effective_date = ?", MAPPER,
            actionType, ts(effectiveDate)).stream().findFirst();
    }

    public Optional<String> categoryOf(String actionType) {
        return jdbc.query("SELECT category FROM points_weight_config WHERE action_type = ? LIMIT 1",
            (rs, rowNum) -> rs.getString(1), actionType).stream().findFirst();
    }

    public void insert(String actionType, String category, int weight, LocalDate effectiveDate) {
        jdbc.update("INSERT INTO points_weight_config (action_type, category, weight, effective_date)"
            + " VALUES (?, ?, ?, ?)", actionType, category, weight, ts(effectiveDate));
    }

    public void updateWeight(long id, int weight) {
        jdbc.update("UPDATE points_weight_config SET weight = ? WHERE id = ?", weight, id);
    }

    public long countVersions(String actionType) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM points_weight_config WHERE action_type = ?", Long.class, actionType);
        return count == null ? 0 : count;
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM points_weight_config WHERE id = ?", id);
    }

    private static Timestamp ts(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigService.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 积分权重按生效日期版本化。引擎对每个动作类型取生效日期最大的版本（不看事件发生时间），
 * 所以"修改"只能新增/覆盖一个今天生效的版本：未来日期会立刻生效，过去日期又不会生效。
 */
@Service
public class PointsConfigService {

    static final int MIN_WEIGHT = 0;
    static final int MAX_WEIGHT = 10000;

    private final PointsConfigRepository repository;
    private final Clock clock;

    public PointsConfigService(PointsConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 全部动作类型：主动在前，其次按当前权重降序，再按动作类型。 */
    public List<PointsConfigItem> list() {
        Map<String, List<PointsConfigRepository.Row>> byAction = repository.all().stream()
            .collect(Collectors.groupingBy(PointsConfigRepository.Row::actionType, LinkedHashMap::new,
                Collectors.toList()));
        return byAction.entrySet().stream()
            .map(entry -> toItem(entry.getKey(), entry.getValue()))
            .sorted(Comparator
                .comparing((PointsConfigItem item) -> "ACTIVE".equals(item.category()) ? 0 : 1)
                .thenComparing(item -> -item.current().weight())
                .thenComparing(PointsConfigItem::actionType))
            .toList();
    }

    @Transactional
    public PointsConfigItem update(String actionType, Integer weight) {
        if (weight == null || weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
            throw new InvalidPointsConfigException(
                "weight must be an integer between " + MIN_WEIGHT + " and " + MAX_WEIGHT);
        }
        String category = repository.categoryOf(actionType).orElseThrow(
            () -> new PointsConfigNotFoundException("Unknown action type '" + actionType + "'"));
        LocalDate today = LocalDate.now(clock);
        Optional<PointsConfigRepository.Row> existing = repository.findByDate(actionType, today);
        if (existing.isPresent()) {
            repository.updateWeight(existing.get().id(), weight);
        } else {
            repository.insert(actionType, category, weight, today);
        }
        return list().stream().filter(item -> item.actionType().equals(actionType)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteVersion(long id) {
        PointsConfigRepository.Row row = repository.find(id).orElseThrow(
            () -> new PointsConfigNotFoundException("Weight version " + id + " does not exist"));
        if (repository.countVersions(row.actionType()) <= 1) {
            throw new PointsConfigConflictException(
                "Cannot delete the only version of " + row.actionType());
        }
        repository.delete(id);
    }

    private static PointsConfigItem toItem(String actionType, List<PointsConfigRepository.Row> rows) {
        List<WeightVersion> versions = rows.stream()
            .map(row -> new WeightVersion(row.id(), row.weight(), row.effectiveDate())).toList();
        return new PointsConfigItem(actionType, rows.get(0).category(), versions.get(0), versions);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigController.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分权重管理。修改只影响之后新产生的事件，已有积分流水不会重算（重新回填才会按新权重重新生成）。
 */
@RestController
@RequestMapping("/api/admin/points-config")
public class PointsConfigController {

    private final PointsConfigService service;

    public PointsConfigController(PointsConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<PointsConfigItem> list() {
        return service.list();
    }

    /** 为该动作新增"今天生效"的版本，今天已有版本则覆盖。 */
    @PutMapping("/{actionType}")
    public PointsConfigItem update(@PathVariable String actionType, @RequestBody WeightUpdateRequest request) {
        return service.update(actionType, request.weight());
    }

    @DeleteMapping("/versions/{id}")
    public ResponseEntity<Void> deleteVersion(@PathVariable long id) {
        service.deleteVersion(id);
        return ResponseEntity.noContent().build();
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/pointsconfig/PointsConfigExceptionHandler.java`：

```java
package com.jiaxian.analytics.pointsconfig;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于积分权重接口：参数问题 400，不存在 404，冲突 409，响应体是一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = PointsConfigController.class)
public class PointsConfigExceptionHandler {

    @ExceptionHandler(InvalidPointsConfigException.class)
    public ResponseEntity<String> invalid(InvalidPointsConfigException e) {
        return text(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return text(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadableBody(HttpMessageNotReadableException e) {
        return text(HttpStatus.BAD_REQUEST, "Request body must be JSON like {\"weight\": 5}");
    }

    @ExceptionHandler(PointsConfigNotFoundException.class)
    public ResponseEntity<String> notFound(PointsConfigNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PointsConfigConflictException.class)
    public ResponseEntity<String> conflict(PointsConfigConflictException e) {
        return text(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='PointsConfigServiceIT,PointsConfigControllerIT'`
Expected: `PointsConfigServiceIT` 7 个、`PointsConfigControllerIT` 3 个，全部通过。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add versioned points weight config endpoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 后端——KOL 重算记录列表

**Files:**
- Modify: `backend/src/main/java/com/jiaxian/analytics/kol/KolRfmRepository.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/kol/BloggerListService.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/kol/BloggerController.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerListServiceTest.java`（追加）
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/BloggerListServiceIT.java`（追加）
- Test: `backend/src/test/java/com/jiaxian/analytics/kol/KolControllerIT.java`（追加）

- [ ] **Step 1: 追加测试**

在 `BloggerListServiceTest.java` 末尾（最后一个 `}` 之前）加：

```java

    @Test
    void runsDelegatesToTheRepository() {
        List<LocalDate> dates = List.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 22));
        when(rfmRepository.runDates()).thenReturn(dates);

        assertThat(service.runs()).isEqualTo(dates);
    }
```

在 `BloggerListServiceIT.java` 末尾（最后一个 `}` 之前）加：

```java

    @Test
    void runsListsTheRebuiltDatesNewestFirst() {
        rebuildService.rebuild(AS_OF.minusDays(7));

        List<LocalDate> runs = service.runs();

        assertThat(runs).contains(AS_OF, AS_OF.minusDays(7));
        assertThat(runs).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
```

并在该文件顶部的 import 里补上 `import java.util.List;`（如果还没有）。

在 `KolControllerIT.java` 末尾（最后一个 `}` 之前）加：

```java

    @Test
    void runsEndpointReturnsAnArrayOfDates() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/bloggers/runs"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        for (JsonNode date : response.getBody()) {
            assertThat(date.asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='BloggerListServiceTest,BloggerListServiceIT,KolControllerIT'`
Expected: 编译失败（`runDates`、`runs` 不存在）。

- [ ] **Step 3: 写实现**

在 `KolRfmRepository.java` 里加常量和方法（常量放在 `SCORE_TYPES` 前面，方法放在 `latestAsOf()` 之后）：

```java
    private static final int MAX_RUNS = 104;
```

```java
    /** 已有重算记录的统计日，新的在前，最多 MAX_RUNS 个。 */
    public List<LocalDate> runDates() {
        // LIMIT 用常量字面量（OpenGauss 不接受绑定参数形式的分页）
        return jdbc.query("SELECT DISTINCT as_of_date FROM blogger_rfm_score ORDER BY as_of_date DESC LIMIT " + MAX_RUNS,
            (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime().toLocalDate());
    }
```

在 `BloggerListService.java` 里（`list` 方法之前）加：

```java
    /** 已有重算记录的统计日，新的在前，给名单页的统计日选择器用。 */
    public List<LocalDate> runs() {
        return rfmRepository.runDates();
    }
```

在 `BloggerController.java` 里（`top` 方法之后）加（补 `import java.util.List;`）：

```java
    /** 已有重算记录的统计日（新的在前，最多 104 个）。 */
    @GetMapping("/runs")
    public List<LocalDate> runs() {
        return service.runs();
    }
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='BloggerListServiceTest,BloggerListServiceIT,KolControllerIT'`
Expected: 全部通过（`BloggerListServiceTest` 3 个、`BloggerListServiceIT` 7 个、`KolControllerIT` 7 个）。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add KOL rebuild run dates endpoint

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 前端——写请求封装、类型、接口模块与纯逻辑工具（含单测）

**Files:**
- Modify: `frontend/src/api/http.ts`、`frontend/src/api/types.ts`
- Create: `frontend/src/api/{kol,playbook,pointsConfig,admin}.ts`
- Create: `frontend/src/utils/{segment,actionTypes,simulatorForms}.ts`
- Test: `frontend/src/api/http.test.ts`（整体替换）、`frontend/src/utils/{segment,actionTypes,simulatorForms}.test.ts`

- [ ] **Step 1: 写测试**

`frontend/src/api/http.test.ts`（整体替换，保留原有 `getJson` 的测试并加上 `sendJson`）：

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson, sendJson } from "./http";

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("getJson", () => {
  it("builds the query string and returns the parsed body", async () => {
    const fetchMock = stubFetch(new Response(JSON.stringify({ ok: 1 }), { status: 200 }));

    const result = await getJson<{ ok: number }>("/api/x", { a: 1, b: undefined });

    expect(result).toEqual({ ok: 1 });
    expect(fetchMock).toHaveBeenCalledWith("/api/x?a=1");
  });

  it("turns a 4xx text body into an ApiError with that message", async () => {
    stubFetch(new Response("from must not be after to", { status: 400 }));

    await expect(getJson("/api/x")).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
      message: "from must not be after to",
    });
  });

  it("uses a default message for an empty 4xx body", async () => {
    stubFetch(new Response("", { status: 404 }));

    await expect(getJson("/api/x")).rejects.toMatchObject({ status: 404, message: "请求失败（404）" });
  });

  it("hides the body of 5xx responses behind a generic message", async () => {
    stubFetch(new Response('{"trace":"secret internals"}', { status: 500 }));

    const error = (await getJson("/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe("服务器内部错误（500）");
    expect(error.message).not.toContain("secret");
  });
});

describe("sendJson", () => {
  it("sends a JSON body with the right header and parses a JSON response", async () => {
    const fetchMock = stubFetch(new Response(JSON.stringify({ id: 7 }), { status: 200 }));

    const result = await sendJson<{ id: number }>("PUT", "/api/x/LIKE", { params: { a: 1 }, body: { weight: 4 } });

    expect(result).toEqual({ id: 7 });
    expect(fetchMock).toHaveBeenCalledWith("/api/x/LIKE?a=1", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: '{"weight":4}',
    });
  });

  it("returns a plain-text response as a string", async () => {
    stubFetch(new Response("Backfill completed for batch: demo", { status: 200 }));

    expect(await sendJson<string>("POST", "/api/admin/simulator/backfill", { body: {} })).toBe(
      "Backfill completed for batch: demo",
    );
  });

  it("returns undefined for an empty response and sends no body for DELETE", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));

    expect(await sendJson<void>("DELETE", "/api/x/versions/3")).toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith("/api/x/versions/3", { method: "DELETE" });
  });

  it("turns error responses into an ApiError", async () => {
    stubFetch(new Response("A playbook entry for STAR_KOL / NO_ALERT already exists", { status: 409 }));

    await expect(sendJson("POST", "/api/admin/playbook", { body: {} })).rejects.toMatchObject({
      status: 409,
      message: "A playbook entry for STAR_KOL / NO_ALERT already exists",
    });

    stubFetch(new Response("boom", { status: 502 }));
    await expect(sendJson("POST", "/api/x")).rejects.toMatchObject({ message: "服务器内部错误（502）" });
  });
});
```

`frontend/src/utils/segment.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { SEGMENTS, segmentName, segmentTagType } from "./segment";

describe("segment dictionary", () => {
  it("covers all six segments with unique Chinese names", () => {
    expect(SEGMENTS.map((s) => s.value)).toEqual([
      "STAR_KOL", "STEADY", "RISING", "CHURN_RISK", "DORMANT", "OBSERVE",
    ]);
    expect(new Set(SEGMENTS.map((s) => s.name)).size).toBe(6);
  });

  it("names and colors the segments", () => {
    expect(segmentName("STAR_KOL")).toBe("明星KOL");
    expect(segmentName("CHURN_RISK")).toBe("流失预警");
    expect(segmentTagType("CHURN_RISK")).toBe("danger");
    expect(segmentTagType("STEADY")).toBe("success");
  });
});
```

`frontend/src/utils/actionTypes.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { actionLabel, alertStatusLabel, categoryLabel, eventLabel } from "./actionTypes";

describe("dictionaries", () => {
  it("labels all fifteen points action types in Chinese", () => {
    const codes = ["POST", "LOGIN", "COMMENT", "SHARE", "FAVORITE", "LIKE", "VIEW", "EDITORIAL_PICK",
      "CONTENT_COMMENTED", "CONTENT_SHARED", "COMMENT_REPLIED", "CONTENT_FAVORITED", "CONTENT_LIKED",
      "COMMENT_LIKED", "CONTENT_VIEWED"];

    for (const code of codes) {
      expect(actionLabel(code)).not.toBe(code);
    }
    expect(actionLabel("POST")).toBe("发帖");
    expect(actionLabel("EDITORIAL_PICK")).toBe("入选思想简报");
  });

  it("falls back to the code for unknown action types", () => {
    expect(actionLabel("SOMETHING_NEW")).toBe("SOMETHING_NEW");
  });

  it("labels categories, event types and alert statuses", () => {
    expect(categoryLabel("ACTIVE")).toBe("主动行为");
    expect(categoryLabel("PASSIVE")).toBe("被动互动");
    expect(eventLabel("LOGIN")).toBe("登录");
    expect(eventLabel("COMMENT")).toBe("评论");
    expect(eventLabel("POST")).toBe("发帖");
    expect(alertStatusLabel("NO_ALERT")).toBe("无预警");
    expect(alertStatusLabel("ALERT")).toBe("触发预警");
  });
});
```

`frontend/src/utils/simulatorForms.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import {
  buildBackfillBody,
  buildRealtimeBody,
  defaultBackfillForm,
  defaultRealtimeForm,
  kolAsOfFromPvEnd,
  validateBackfillForm,
} from "./simulatorForms";

describe("defaultBackfillForm", () => {
  it("ends 30 days before today and starts on the first day of the month 18 months earlier", () => {
    const form = defaultBackfillForm(new Date(2026, 9, 5));

    expect(form.historyEnd).toBe("2026-09-05");
    expect(form.historyStart).toBe("2025-03-01");
    expect(form).toMatchObject({
      batchId: "demo", employeeCount: 50, accountCount: 20, teamAccountRatio: 0.25,
      contentCount: 300, randomSeed: 7, personas: true,
    });
  });
});

describe("validateBackfillForm", () => {
  const valid = defaultBackfillForm(new Date(2026, 9, 5));

  it("accepts the defaults", () => {
    expect(validateBackfillForm(valid)).toBeNull();
  });

  it("rejects bad values with a Chinese message", () => {
    expect(validateBackfillForm({ ...valid, batchId: "  " })).toBe("批次名不能为空");
    expect(validateBackfillForm({ ...valid, employeeCount: 0 })).toBe("员工数必须是正整数");
    expect(validateBackfillForm({ ...valid, accountCount: 2.5 })).toBe("账号数必须是正整数");
    expect(validateBackfillForm({ ...valid, contentCount: -1 })).toBe("内容数必须是正整数");
    expect(validateBackfillForm({ ...valid, teamAccountRatio: 1.5 })).toBe("团队账号比例必须在 0 到 1 之间");
    expect(validateBackfillForm({ ...valid, randomSeed: 1.5 })).toBe("随机种子必须是整数");
    expect(validateBackfillForm({ ...valid, historyStart: "2026-09-06", historyEnd: "2026-09-05" }))
      .toBe("开始日期不能晚于结束日期");
  });
});

describe("request bodies", () => {
  it("builds the backfill body with a trimmed batch id", () => {
    const body = buildBackfillBody({ ...defaultBackfillForm(new Date(2026, 9, 5)), batchId: " demo " });

    expect(body).toEqual({
      batchId: "demo", employeeCount: 50, accountCount: 20, teamAccountRatio: 0.25, contentCount: 300,
      historyStart: "2025-03-01", historyEnd: "2026-09-05", randomSeed: 7, personas: true,
    });
  });

  it("omits a blank realtime batch id", () => {
    expect(defaultRealtimeForm()).toEqual({ batchId: "", intervalMillis: 2000, eventsPerBatch: 10 });
    expect(buildRealtimeBody({ batchId: "  ", intervalMillis: 500, eventsPerBatch: 3 }))
      .toEqual({ intervalMillis: 500, eventsPerBatch: 3 });
    expect(buildRealtimeBody({ batchId: " live ", intervalMillis: 500, eventsPerBatch: 3 }))
      .toEqual({ batchId: "live", intervalMillis: 500, eventsPerBatch: 3 });
  });
});

describe("kolAsOfFromPvEnd", () => {
  it("goes back 30 days from the last PV day, across month boundaries", () => {
    expect(kolAsOfFromPvEnd("2026-09-20")).toBe("2026-08-21");
    expect(kolAsOfFromPvEnd("2026-03-05")).toBe("2026-02-03");
    expect(kolAsOfFromPvEnd("2026-01-10")).toBe("2025-12-11");
  });
});
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd frontend && npm test`
Expected: 新测试文件因为模块不存在而失败，`http.test.ts` 因为 `sendJson` 不存在而失败。

- [ ] **Step 3: 写实现**

`frontend/src/api/http.ts`（整体替换）：

```ts
import { buildQuery, type QueryParams } from "./query";

/** 后端返回的错误：4xx 的 message 就是后端那句文字说明，5xx 只给通用提示。 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function readMessage(response: Response): Promise<string> {
  if (response.status >= 500) {
    return `服务器内部错误（${response.status}）`;
  }
  const text = (await response.text()).trim();
  return text || `请求失败（${response.status}）`;
}

export async function getJson<T>(path: string, params?: QueryParams): Promise<T> {
  const response = await fetch(path + buildQuery(params));
  if (!response.ok) {
    throw new ApiError(response.status, await readMessage(response));
  }
  return (await response.json()) as T;
}

export interface SendOptions {
  params?: QueryParams;
  body?: unknown;
}

/** 响应是 JSON 就解析；是纯文本就原样返回字符串；空响应返回 undefined。 */
async function parseBody<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return text as unknown as T;
  }
}

/** POST / PUT / DELETE：有 body 时按 JSON 发送；错误处理与 getJson 一致。 */
export async function sendJson<T>(
  method: "POST" | "PUT" | "DELETE",
  path: string,
  options: SendOptions = {},
): Promise<T> {
  const init: RequestInit = { method };
  if (options.body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(path + buildQuery(options.params), init);
  if (!response.ok) {
    throw new ApiError(response.status, await readMessage(response));
  }
  return parseBody<T>(response);
}
```

`frontend/src/api/types.ts`（整体替换：保留 Plan 7 的全部类型，在末尾追加 KOL、文案库、权重、模拟器类型）：

```ts
export type Granularity = "DAY" | "WEEK" | "MONTH" | "YEAR";
export type ContentType = "ARTICLE" | "VIDEO";
export type PvDimension = "CHANNEL" | "ACCOUNT" | "EMPLOYEE" | "CONTENT_TYPE";

export interface PvPoint {
  periodStart: string;
  pv: number;
  momRate: number | null;
  yoyRate: number | null;
  complete: boolean;
}

export interface ForecastPoint {
  periodStart: string;
  value: number;
  lower: number;
  upper: number;
}

export interface PvTrend {
  granularity: Granularity;
  from: string;
  to: string;
  points: PvPoint[];
  forecast: ForecastPoint[];
}

export interface PvTopEntry {
  id: number | null;
  name: string;
  pv: number;
}

/** 可选过滤条件，多个条件取交集。 */
export interface PvFilter {
  channelId?: number;
  accountId?: number;
  employeeId?: number;
  contentType?: ContentType;
}

export interface PvRange {
  from: string | null;
  to: string | null;
}

export interface ChannelOption {
  id: number;
  name: string;
  category: string;
}

export interface AccountOption {
  id: number;
  name: string;
  type: "PERSONAL" | "TEAM";
}

export interface EmployeeOption {
  id: number;
  name: string;
  dept: string | null;
}

export type PeriodType = "month" | "year";
export type LeaderboardStatus = "IN_PROGRESS" | "ARCHIVED";

export interface LeaderboardEntry {
  rank: number;
  employeeId: number;
  name: string;
  points: number;
  activePoints: number;
  passivePoints: number;
}

export interface Leaderboard {
  periodType: "MONTH" | "YEAR";
  periodValue: string;
  status: LeaderboardStatus;
  source: "LIVE" | "SNAPSHOT";
  entries: LeaderboardEntry[];
}

export interface PeriodOption {
  periodValue: string;
  status: LeaderboardStatus;
}

// ---- KOL ----

export type Segment = "STAR_KOL" | "STEADY" | "RISING" | "CHURN_RISK" | "DORMANT" | "OBSERVE";

export interface BloggerItem {
  employeeId: number;
  name: string;
  rank: number;
  rollingPoints12m: number;
  recencyDays: number | null;
  frequency: number;
  monetary: number;
  rScore: number;
  fScore: number;
  mScore: number;
  segment: Segment;
  segmentName: string;
  churnAlert: boolean;
  recommendation: string | null;
}

export interface BloggerList {
  asOf: string | null;
  total: number;
  segmentCounts: Record<Segment, number>;
  items: BloggerItem[];
}

export interface EmployeeInfo {
  id: number;
  name: string;
  dept: string | null;
  joinDate: string | null;
}

export interface AlertDetail {
  metric: "ACTIVITY" | "POINTS";
  recentDaily: number;
  baselineDaily: number;
  dropRate: number;
}

export interface CurrentStanding {
  asOf: string;
  rank: number;
  segment: Segment;
  segmentName: string;
  churnAlert: boolean;
  recommendation: string | null;
  rollingPoints12m: number;
  recencyDays: number | null;
  frequency: number;
  monetary: number;
  rScore: number;
  fScore: number;
  mScore: number;
  alert: AlertDetail | null;
}

export interface HistoryPoint {
  asOf: string;
  rank: number;
  segment: Segment;
  segmentName: string;
  churnAlert: boolean;
  rScore: number;
  fScore: number;
  mScore: number;
}

export interface SegmentChange {
  asOf: string;
  from: Segment;
  to: Segment;
}

export interface RecentEvent {
  time: string;
  type: "POST" | "COMMENT" | "LOGIN";
  refId: number | null;
}

export interface BloggerProfile {
  employee: EmployeeInfo;
  current: CurrentStanding | null;
  history: HistoryPoint[];
  segmentChanges: SegmentChange[];
  recentEvents: RecentEvent[];
}

export interface ActivityPoint {
  periodStart: string;
  posts: number;
  comments: number;
  logins: number;
  complete: boolean;
}

export interface ActivitySeries {
  granularity: Granularity;
  from: string;
  to: string;
  points: ActivityPoint[];
}

export interface PointsTrendPoint {
  periodStart: string;
  activePoints: number;
  passivePoints: number;
  total: number;
  complete: boolean;
}

export interface PointsTrendSeries {
  granularity: Granularity;
  from: string;
  to: string;
  points: PointsTrendPoint[];
}

export interface KolRebuildResult {
  asOf: string;
  population: number;
  alerts: number;
}

// ---- 建议文案库 ----

export type PlaybookAlertStatus = "NO_ALERT" | "ALERT";

export interface PlaybookEntry {
  id: number;
  segmentLabel: Segment;
  alertStatus: PlaybookAlertStatus;
  recommendationText: string;
}

export interface PlaybookRequest {
  segmentLabel: Segment;
  alertStatus: PlaybookAlertStatus;
  recommendationText: string;
}

// ---- 积分权重 ----

export interface WeightVersion {
  id: number;
  weight: number;
  effectiveDate: string;
}

export interface PointsConfigItem {
  actionType: string;
  category: "ACTIVE" | "PASSIVE";
  current: WeightVersion;
  versions: WeightVersion[];
}

// ---- 模拟器与数据任务 ----

export interface BackfillRequest {
  batchId: string;
  employeeCount: number;
  accountCount: number;
  teamAccountRatio: number;
  contentCount: number;
  historyStart: string;
  historyEnd: string;
  randomSeed: number;
  personas: boolean;
}

export interface RealtimeRequest {
  batchId?: string;
  intervalMillis: number;
  eventsPerBatch: number;
}

export interface RealtimeStatus {
  running: boolean;
  batchId: string | null;
  sentCount: number;
  startedAt: string | null;
}

export interface PvRebuildResult {
  from: string | null;
  to: string | null;
  rowsWritten: number;
}

export interface SnapshotSummary {
  months: number;
  years: number;
  rowsWritten: number;
}
```

`frontend/src/api/kol.ts`：

```ts
import { getJson, sendJson } from "./http";
import type {
  ActivitySeries,
  BloggerList,
  BloggerProfile,
  Granularity,
  KolRebuildResult,
  PointsTrendSeries,
  Segment,
} from "./types";

export interface BloggerQuery {
  asOf?: string;
  segment?: Segment;
  alert?: boolean;
  limit: number;
  offset: number;
}

export function fetchBloggers(query: BloggerQuery): Promise<BloggerList> {
  return getJson<BloggerList>("/api/bloggers/top1000", { ...query });
}

export function fetchRuns(): Promise<string[]> {
  return getJson<string[]>("/api/bloggers/runs");
}

export function fetchProfile(id: number, weeks = 26): Promise<BloggerProfile> {
  return getJson<BloggerProfile>(`/api/bloggers/${id}/profile`, { weeks });
}

export function fetchActivity(id: number, granularity: Granularity): Promise<ActivitySeries> {
  return getJson<ActivitySeries>(`/api/bloggers/${id}/activity`, { granularity });
}

export function fetchPointsTrend(id: number, granularity: Granularity): Promise<PointsTrendSeries> {
  return getJson<PointsTrendSeries>(`/api/bloggers/${id}/points-trend`, { granularity });
}

export function rebuildKol(asOf: string | undefined, weeks: number): Promise<KolRebuildResult[]> {
  return sendJson<KolRebuildResult[]>("POST", "/api/admin/analytics/kol/rebuild", { params: { asOf, weeks } });
}
```

`frontend/src/api/playbook.ts`：

```ts
import { getJson, sendJson } from "./http";
import type { PlaybookEntry, PlaybookRequest } from "./types";

export function fetchPlaybook(): Promise<PlaybookEntry[]> {
  return getJson<PlaybookEntry[]>("/api/admin/playbook");
}

export function createPlaybook(request: PlaybookRequest): Promise<PlaybookEntry> {
  return sendJson<PlaybookEntry>("POST", "/api/admin/playbook", { body: request });
}

export function updatePlaybook(id: number, request: PlaybookRequest): Promise<PlaybookEntry> {
  return sendJson<PlaybookEntry>("PUT", `/api/admin/playbook/${id}`, { body: request });
}

export function deletePlaybook(id: number): Promise<void> {
  return sendJson<void>("DELETE", `/api/admin/playbook/${id}`);
}
```

`frontend/src/api/pointsConfig.ts`：

```ts
import { getJson, sendJson } from "./http";
import type { PointsConfigItem } from "./types";

export function fetchPointsConfig(): Promise<PointsConfigItem[]> {
  return getJson<PointsConfigItem[]>("/api/admin/points-config");
}

export function updateWeight(actionType: string, weight: number): Promise<PointsConfigItem> {
  return sendJson<PointsConfigItem>("PUT", `/api/admin/points-config/${encodeURIComponent(actionType)}`, {
    body: { weight },
  });
}

export function deleteWeightVersion(id: number): Promise<void> {
  return sendJson<void>("DELETE", `/api/admin/points-config/versions/${id}`);
}
```

`frontend/src/api/admin.ts`：

```ts
import { getJson, sendJson } from "./http";
import type { BackfillRequest, PvRebuildResult, RealtimeRequest, RealtimeStatus, SnapshotSummary } from "./types";

/** 历史回填会清空所有模拟数据；成功时后端返回一句文字。 */
export function backfill(body: BackfillRequest): Promise<string> {
  return sendJson<string>("POST", "/api/admin/simulator/backfill", { body });
}

export function startRealtime(body: RealtimeRequest): Promise<RealtimeStatus> {
  return sendJson<RealtimeStatus>("POST", "/api/admin/simulator/realtime/start", { body });
}

/** 停止后返回最终状态；本来就没在运行时后端返回一句文字，调用方应在停止后重新取状态。 */
export function stopRealtime(): Promise<RealtimeStatus | string> {
  return sendJson<RealtimeStatus | string>("POST", "/api/admin/simulator/realtime/stop");
}

export function fetchRealtimeStatus(): Promise<RealtimeStatus> {
  return getJson<RealtimeStatus>("/api/admin/simulator/realtime/status");
}

export function rebuildPv(from?: string, to?: string): Promise<PvRebuildResult> {
  return sendJson<PvRebuildResult>("POST", "/api/admin/analytics/pv/rebuild", { params: { from, to } });
}

export function snapshotAllLeaderboards(): Promise<SnapshotSummary> {
  return sendJson<SnapshotSummary>("POST", "/api/admin/analytics/leaderboard/snapshot");
}
```

`frontend/src/utils/segment.ts`：

```ts
import type { Segment } from "../api/types";

export type TagType = "success" | "primary" | "warning" | "danger" | "info";

export interface SegmentInfo {
  value: Segment;
  name: string;
  tagType: TagType;
}

/** 分层标签的展示顺序、中文名和颜色（和后端 Segment 枚举一致）。 */
export const SEGMENTS: SegmentInfo[] = [
  { value: "STAR_KOL", name: "明星KOL", tagType: "warning" },
  { value: "STEADY", name: "稳定贡献者", tagType: "success" },
  { value: "RISING", name: "成长潜力新人", tagType: "primary" },
  { value: "CHURN_RISK", name: "流失预警", tagType: "danger" },
  { value: "DORMANT", name: "沉寂用户", tagType: "info" },
  { value: "OBSERVE", name: "观察中", tagType: "info" },
];

export function segmentName(segment: Segment): string {
  return SEGMENTS.find((s) => s.value === segment)?.name ?? segment;
}

export function segmentTagType(segment: Segment): TagType {
  return SEGMENTS.find((s) => s.value === segment)?.tagType ?? "info";
}
```

`frontend/src/utils/actionTypes.ts`：

```ts
const ACTION_LABELS: Record<string, string> = {
  POST: "发帖",
  LOGIN: "登录",
  COMMENT: "发表评论",
  SHARE: "分享",
  FAVORITE: "收藏",
  LIKE: "点赞",
  VIEW: "浏览",
  EDITORIAL_PICK: "入选思想简报",
  CONTENT_COMMENTED: "内容获得评论",
  CONTENT_SHARED: "内容被分享",
  COMMENT_REPLIED: "评论获得回复",
  CONTENT_FAVORITED: "内容被收藏",
  CONTENT_LIKED: "内容被点赞",
  COMMENT_LIKED: "评论获赞",
  CONTENT_VIEWED: "内容被浏览",
};

/** 积分动作类型的中文名；不认识的原样返回代码。 */
export function actionLabel(actionType: string): string {
  return ACTION_LABELS[actionType] ?? actionType;
}

export function categoryLabel(category: "ACTIVE" | "PASSIVE"): string {
  return category === "ACTIVE" ? "主动行为" : "被动互动";
}

const EVENT_LABELS: Record<string, string> = { POST: "发帖", COMMENT: "评论", LOGIN: "登录" };

export function eventLabel(type: string): string {
  return EVENT_LABELS[type] ?? type;
}

export function alertStatusLabel(status: "NO_ALERT" | "ALERT"): string {
  return status === "ALERT" ? "触发预警" : "无预警";
}
```

`frontend/src/utils/simulatorForms.ts`：

```ts
import type { BackfillRequest, RealtimeRequest } from "../api/types";
import { parseIsoDate, toIsoDate } from "./dateRange";

export type BackfillForm = BackfillRequest;

export interface RealtimeForm {
  batchId: string;
  intervalMillis: number;
  eventsPerBatch: number;
}

/**
 * 回填默认值：结束日期 = 今天往前 30 天（回填的浏览行为会延续到发布后 30 天，结束得太靠近今天会产生"未来"的数据），
 * 开始日期 = 结束日期往前 18 个月的 1 号。
 */
export function defaultBackfillForm(today: Date = new Date()): BackfillForm {
  const end = new Date(today);
  end.setDate(end.getDate() - 30);
  const start = new Date(end.getFullYear(), end.getMonth() - 18, 1);
  return {
    batchId: "demo",
    employeeCount: 50,
    accountCount: 20,
    teamAccountRatio: 0.25,
    contentCount: 300,
    historyStart: toIsoDate(start),
    historyEnd: toIsoDate(end),
    randomSeed: 7,
    personas: true,
  };
}

const isPositiveInteger = (n: number): boolean => Number.isInteger(n) && n > 0;

/** 返回第一条问题的中文说明，没有问题返回 null。 */
export function validateBackfillForm(form: BackfillForm): string | null {
  if (!form.batchId.trim()) {
    return "批次名不能为空";
  }
  if (!isPositiveInteger(form.employeeCount)) {
    return "员工数必须是正整数";
  }
  if (!isPositiveInteger(form.accountCount)) {
    return "账号数必须是正整数";
  }
  if (!isPositiveInteger(form.contentCount)) {
    return "内容数必须是正整数";
  }
  if (!(form.teamAccountRatio >= 0 && form.teamAccountRatio <= 1)) {
    return "团队账号比例必须在 0 到 1 之间";
  }
  if (!Number.isInteger(form.randomSeed)) {
    return "随机种子必须是整数";
  }
  if (form.historyStart > form.historyEnd) {
    return "开始日期不能晚于结束日期";
  }
  return null;
}

export function buildBackfillBody(form: BackfillForm): BackfillRequest {
  return { ...form, batchId: form.batchId.trim() };
}

export function defaultRealtimeForm(): RealtimeForm {
  return { batchId: "", intervalMillis: 2000, eventsPerBatch: 10 };
}

/** 批次名留空时不发送，由后端生成。 */
export function buildRealtimeBody(form: RealtimeForm): RealtimeRequest {
  const batchId = form.batchId.trim();
  return {
    ...(batchId ? { batchId } : {}),
    intervalMillis: form.intervalMillis,
    eventsPerBatch: form.eventsPerBatch,
  };
}

/**
 * 一键重算时 KOL 的统计日：PV 数据最后一天往前 30 天。
 * 回填的浏览行为会延续到内容发布后 30 天，所以 PV 数据的最后一天比模拟的"现在"（历史结束日）晚约一个月；
 * 直接以它为统计日，刚刚还活跃的人会因为"最近一个月没有登录"被误判为流失。
 */
export function kolAsOfFromPvEnd(pvEnd: string): string {
  const date = parseIsoDate(pvEnd);
  date.setDate(date.getDate() - 30);
  return toIsoDate(date);
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd frontend && npm test`
Expected: 全部通过（新增 `segment`、`actionTypes`、`simulatorForms` 三个测试文件，`http.test.ts` 变为 8 个用例）。

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功（`types.ts` 里新增的类型只有被引用才会被检查，这里主要保证没有语法错误）。

- [ ] **Step 5: 提交**

```bash
git add frontend
git commit -m "feat: add write requests, KOL/admin API modules and form utilities

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 前端——三个图表配置函数（含单测）

**Files:**
- Modify: `frontend/src/charts/colors.ts`
- Create: `frontend/src/charts/{rfmHistoryOption,activityOption,pointsTrendOption}.ts`
- Test: `frontend/src/charts/{rfmHistoryOption,activityOption,pointsTrendOption}.test.ts`

- [ ] **Step 1: 写测试**

`frontend/src/charts/rfmHistoryOption.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { HistoryPoint } from "../api/types";
import { buildRfmHistoryOption, rfmHistoryTooltip } from "./rfmHistoryOption";

const history: HistoryPoint[] = [
  { asOf: "2026-02-22", rank: 2, segment: "STAR_KOL", segmentName: "明星KOL", churnAlert: false, rScore: 5, fScore: 5, mScore: 4 },
  { asOf: "2026-03-01", rank: 7, segment: "CHURN_RISK", segmentName: "流失预警", churnAlert: true, rScore: 2, fScore: 5, mScore: 4 },
];

type Series = { name: string; data: number[] };

describe("buildRfmHistoryOption", () => {
  it("draws one line per score over the weekly runs", () => {
    const option = buildRfmHistoryOption(history);
    const series = option.series as unknown as Series[];

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-02-22", "2026-03-01"]);
    expect(series.map((s) => s.name)).toEqual(["新近度 R", "频次 F", "产出 M"]);
    expect(series[0].data).toEqual([5, 2]);
    expect(series[1].data).toEqual([5, 5]);
    expect(series[2].data).toEqual([4, 4]);
  });

  it("fixes the y axis to the 0-5 score range", () => {
    const yAxis = buildRfmHistoryOption(history).yAxis as { min: number; max: number; interval: number };

    expect(yAxis).toMatchObject({ min: 0, max: 5, interval: 1 });
  });

  it("copes with an empty history", () => {
    const option = buildRfmHistoryOption([]);

    expect((option.xAxis as { data: string[] }).data).toEqual([]);
  });
});

describe("rfmHistoryTooltip", () => {
  it("shows the segment, rank and scores for the week", () => {
    const text = rfmHistoryTooltip(history, 1);

    expect(text).toContain("2026-03-01");
    expect(text).toContain("流失预警（预警）");
    expect(text).toContain("名次 7");
    expect(text).toContain("R 2");
    expect(text).toContain("F 5");
    expect(text).toContain("M 4");
  });

  it("omits the alert note when there is no alert", () => {
    const text = rfmHistoryTooltip(history, 0);

    expect(text).toContain("明星KOL");
    expect(text).not.toContain("预警");
  });
});
```

`frontend/src/charts/activityOption.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { ActivitySeries } from "../api/types";
import { buildActivityOption } from "./activityOption";

const series: ActivitySeries = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-02-28",
  points: [
    { periodStart: "2026-01-01", posts: 2, comments: 1, logins: 20, complete: true },
    { periodStart: "2026-02-01", posts: 0, comments: 3, logins: 18, complete: true },
  ],
};

type Bar = { name: string; stack: string; data: number[] };

describe("buildActivityOption", () => {
  it("stacks posts, comments and logins per period", () => {
    const option = buildActivityOption(series);
    const bars = option.series as unknown as Bar[];

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02"]);
    expect(bars.map((b) => b.name)).toEqual(["发帖", "评论", "登录"]);
    expect(bars[0].data).toEqual([2, 0]);
    expect(bars[1].data).toEqual([1, 3]);
    expect(bars[2].data).toEqual([20, 18]);
    expect(new Set(bars.map((b) => b.stack)).size).toBe(1);
  });

  it("labels weekly periods with the week suffix", () => {
    const option = buildActivityOption({ ...series, granularity: "WEEK" });

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01-01 周", "2026-02-01 周"]);
  });
});
```

`frontend/src/charts/pointsTrendOption.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { PointsTrendSeries } from "../api/types";
import { buildPointsTrendOption } from "./pointsTrendOption";

const series: PointsTrendSeries = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-02-28",
  points: [
    { periodStart: "2026-01-01", activePoints: 500, passivePoints: 40, total: 540, complete: true },
    { periodStart: "2026-02-01", activePoints: 100, passivePoints: 0, total: 100, complete: true },
  ],
};

type Bar = { name: string; stack: string; data: number[] };

describe("buildPointsTrendOption", () => {
  it("stacks active and passive points per period", () => {
    const option = buildPointsTrendOption(series);
    const bars = option.series as unknown as Bar[];

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02"]);
    expect(bars.map((b) => b.name)).toEqual(["主动积分", "被动积分"]);
    expect(bars[0].data).toEqual([500, 100]);
    expect(bars[1].data).toEqual([40, 0]);
    expect(bars[0].stack).toBe(bars[1].stack);
  });
});
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd frontend && npm test`
Expected: 三个新测试文件因为模块不存在而失败。

- [ ] **Step 3: 写实现**

`frontend/src/charts/colors.ts`（整体替换）：

```ts
/** 与 styles/theme.css 里的 CSS 变量保持一致。 */
export const PRIMARY = "#2f6fed";
export const FORECAST = "#f08c2c";
export const FORECAST_BAND = "rgba(240, 140, 44, 0.18)";
export const ACTIVE = "#2f6fed";
export const PASSIVE = "#14a38b";

// 活跃时间轴
export const POSTS = "#8b5cf6";
export const COMMENTS = "#f08c2c";
export const LOGINS = "#2f6fed";

// RFM 三个分数
export const RFM_R = "#2f6fed";
export const RFM_F = "#14a38b";
export const RFM_M = "#f08c2c";
```

`frontend/src/charts/rfmHistoryOption.ts`：

```ts
import type { EChartsOption } from "echarts";
import type { HistoryPoint } from "../api/types";
import { RFM_F, RFM_M, RFM_R } from "./colors";

/** 悬浮提示：当周的分层标签（带预警）、名次和三个分数。 */
export function rfmHistoryTooltip(history: HistoryPoint[], index: number): string {
  const point = history[index];
  return [
    `<strong>${point.asOf}</strong>`,
    `分层：${point.segmentName}${point.churnAlert ? "（预警）" : ""}　名次 ${point.rank}`,
    `R ${point.rScore}　F ${point.fScore}　M ${point.mScore}`,
  ].join("<br/>");
}

/** RFM 三个分数（1~5）每周的变化折线。 */
export function buildRfmHistoryOption(history: HistoryPoint[]): EChartsOption {
  return {
    color: [RFM_R, RFM_F, RFM_M],
    grid: { left: 40, right: 24, top: 44, bottom: 32 },
    legend: { data: ["新近度 R", "频次 F", "产出 M"], top: 4 },
    tooltip: {
      trigger: "axis",
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (params: any) => rfmHistoryTooltip(history, Array.isArray(params) ? params[0].dataIndex : params.dataIndex),
    },
    xAxis: { type: "category", boundaryGap: false, data: history.map((point) => point.asOf) },
    yAxis: { type: "value", min: 0, max: 5, interval: 1 },
    series: [
      { name: "新近度 R", type: "line", data: history.map((point) => point.rScore), symbolSize: 7 },
      { name: "频次 F", type: "line", data: history.map((point) => point.fScore), symbolSize: 7 },
      { name: "产出 M", type: "line", data: history.map((point) => point.mScore), symbolSize: 7 },
    ],
  };
}
```

`frontend/src/charts/activityOption.ts`：

```ts
import type { EChartsOption } from "echarts";
import type { ActivitySeries } from "../api/types";
import { formatPeriod } from "../utils/format";
import { COMMENTS, LOGINS, POSTS } from "./colors";

/** 活跃时间轴：各周期的发帖/评论/登录次数堆叠柱状图。 */
export function buildActivityOption(series: ActivitySeries): EChartsOption {
  const bar = (name: string, data: number[]) => ({ name, type: "bar" as const, stack: "activity", barMaxWidth: 28, data });
  return {
    color: [POSTS, COMMENTS, LOGINS],
    grid: { left: 48, right: 24, top: 40, bottom: 36 },
    legend: { data: ["发帖", "评论", "登录"], top: 4 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "category", data: series.points.map((p) => formatPeriod(p.periodStart, series.granularity)) },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      bar("发帖", series.points.map((p) => p.posts)),
      bar("评论", series.points.map((p) => p.comments)),
      bar("登录", series.points.map((p) => p.logins)),
    ],
  };
}
```

`frontend/src/charts/pointsTrendOption.ts`：

```ts
import type { EChartsOption } from "echarts";
import type { PointsTrendSeries } from "../api/types";
import { formatNumber, formatPeriod } from "../utils/format";
import { ACTIVE, PASSIVE } from "./colors";

/** 积分构成趋势：各周期的主动/被动积分堆叠柱状图。 */
export function buildPointsTrendOption(series: PointsTrendSeries): EChartsOption {
  const bar = (name: string, data: number[]) => ({ name, type: "bar" as const, stack: "points", barMaxWidth: 28, data });
  return {
    color: [ACTIVE, PASSIVE],
    grid: { left: 56, right: 24, top: 40, bottom: 36 },
    legend: { data: ["主动积分", "被动积分"], top: 4 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "category", data: series.points.map((p) => formatPeriod(p.periodStart, series.granularity)) },
    yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    series: [
      bar("主动积分", series.points.map((p) => p.activePoints)),
      bar("被动积分", series.points.map((p) => p.passivePoints)),
    ],
  };
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd frontend && npm test`
Expected: 全部通过。

Run: `cd frontend && npm run build`
Expected: 类型检查和构建通过。

- [ ] **Step 5: 提交**

```bash
git add frontend
git commit -m "feat: add RFM history, activity and points trend chart options

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 前端——菜单、路由与 KOL 名单页

**Files:**
- Modify: `frontend/src/App.vue`、`frontend/src/router/index.ts`、`frontend/src/styles/theme.css`
- Create: `frontend/src/views/kol/SegmentCards.vue`、`frontend/src/views/kol/KolListView.vue`
- Create（占位，后面任务整体替换）：`frontend/src/views/kol/KolDetailView.vue`、`frontend/src/views/admin/SimulatorView.vue`、`frontend/src/views/admin/PointsConfigView.vue`、`frontend/src/views/admin/PlaybookView.vue`

- [ ] **Step 1: 写菜单、路由、样式与名单页**

`frontend/src/router/index.ts`（整体替换）：

```ts
import { createRouter, createWebHistory } from "vue-router";

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "overview", component: () => import("../views/OverviewView.vue"), meta: { title: "总览看板" } },
    {
      path: "/leaderboard",
      name: "leaderboard",
      component: () => import("../views/LeaderboardView.vue"),
      meta: { title: "积分排行榜" },
    },
    { path: "/kol", name: "kol", component: () => import("../views/kol/KolListView.vue"), meta: { title: "KOL 管理" } },
    {
      path: "/kol/:id",
      name: "kol-detail",
      component: () => import("../views/kol/KolDetailView.vue"),
      meta: { title: "博主详情" },
    },
    {
      path: "/admin/simulator",
      name: "simulator",
      component: () => import("../views/admin/SimulatorView.vue"),
      meta: { title: "数据模拟器" },
    },
    {
      path: "/admin/points-config",
      name: "points-config",
      component: () => import("../views/admin/PointsConfigView.vue"),
      meta: { title: "积分权重" },
    },
    {
      path: "/admin/playbook",
      name: "playbook",
      component: () => import("../views/admin/PlaybookView.vue"),
      meta: { title: "建议文案库" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
```

`frontend/src/App.vue`（整体替换）：

```vue
<script setup lang="ts">
import { computed } from "vue";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { useRoute } from "vue-router";
import HealthBadge from "./components/HealthBadge.vue";

const route = useRoute();
// 详情页高亮"KOL 管理"
const activeMenu = computed(() => (route.path.startsWith("/kol") ? "/kol" : route.path));
</script>

<template>
  <el-config-provider :locale="zhCn">
    <el-container class="app-shell">
      <el-aside width="200px" class="app-aside">
        <div class="brand">稼先社区<br /><small>运营分析看板</small></div>
        <el-menu :default-active="activeMenu" :default-openeds="['admin']" router class="app-menu">
          <el-menu-item index="/">总览看板</el-menu-item>
          <el-menu-item index="/leaderboard">积分排行榜</el-menu-item>
          <el-menu-item index="/kol">KOL 管理</el-menu-item>
          <el-sub-menu index="admin">
            <template #title>系统管理</template>
            <el-menu-item index="/admin/simulator">数据模拟器</el-menu-item>
            <el-menu-item index="/admin/points-config">积分权重</el-menu-item>
            <el-menu-item index="/admin/playbook">建议文案库</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="app-header">
          <span class="page-title">{{ route.meta.title }}</span>
          <HealthBadge />
        </el-header>
        <el-main>
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>
```

在 `frontend/src/styles/theme.css` 末尾追加：

```css

.app-menu .el-sub-menu__title:hover {
  background: #2a3858;
}

.app-menu .el-sub-menu .el-menu-item {
  background: transparent;
}

.score-high {
  background: #e6f6ec;
  color: #1a7f46;
}

.score-mid {
  background: #eef1f6;
  color: #4b5563;
}

.score-low {
  background: #fdecec;
  color: #b42318;
}

.score-chip {
  display: inline-block;
  min-width: 18px;
  padding: 0 4px;
  margin-right: 4px;
  border-radius: 4px;
  text-align: center;
  font-size: 12px;
  line-height: 20px;
}

.clickable-row {
  cursor: pointer;
}
```

`frontend/src/views/kol/SegmentCards.vue`：

```vue
<script setup lang="ts">
import type { Segment } from "../../api/types";
import { SEGMENTS } from "../../utils/segment";

defineProps<{ counts: Record<Segment, number>; selected: Segment | undefined }>();
const emit = defineEmits<{ select: [segment: Segment | undefined] }>();
</script>

<template>
  <div class="segment-cards">
    <button
      v-for="s in SEGMENTS"
      :key="s.value"
      type="button"
      class="segment-card"
      :class="{ active: selected === s.value }"
      @click="emit('select', selected === s.value ? undefined : s.value)"
    >
      <el-tag :type="s.tagType" size="small" effect="plain">{{ s.name }}</el-tag>
      <span class="count">{{ counts[s.value] ?? 0 }}</span>
    </button>
  </div>
</template>

<style scoped>
.segment-cards {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
.segment-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  text-align: left;
}
.segment-card:hover {
  border-color: var(--color-primary);
}
.segment-card.active {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(47, 111, 237, 0.18);
}
.count {
  font-size: 24px;
  font-weight: 600;
}
</style>
```

`frontend/src/views/kol/KolListView.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { fetchBloggers, fetchRuns, type BloggerQuery } from "../../api/kol";
import type { BloggerItem, Segment } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { formatNumber } from "../../utils/format";
import { segmentTagType } from "../../utils/segment";
import SegmentCards from "./SegmentCards.vue";

const router = useRouter();

const asOf = ref<string | undefined>();
const segment = ref<Segment | undefined>();
const alertOnly = ref(false);
const page = ref(1);
const pageSize = ref(20);

const runs = useAsync(() => fetchRuns());
const list = useAsync((query: BloggerQuery) => fetchBloggers(query));

function reload(): void {
  void list.run({
    asOf: asOf.value,
    segment: segment.value,
    alert: alertOnly.value ? true : undefined,
    limit: pageSize.value,
    offset: (page.value - 1) * pageSize.value,
  });
}

// 任何筛选变化回到第 1 页；翻页只重新取当前页
function onFilterChange(): void {
  page.value = 1;
  reload();
}

function onPage(value: number): void {
  page.value = value;
  reload();
}

function onPageSize(value: number): void {
  pageSize.value = value;
  onFilterChange();
}

function onSegment(value: Segment | undefined): void {
  segment.value = value;
  onFilterChange();
}

onMounted(async () => {
  await runs.run();
  asOf.value = runs.data.value?.[0];
  reload();
});

const items = computed<BloggerItem[]>(() => list.data.value?.items ?? []);
const noRuns = computed(() => list.data.value !== null && list.data.value.asOf === null);

function open(row: BloggerItem): void {
  void router.push(`/kol/${row.employeeId}`);
}

function scoreClass(score: number): string {
  return score >= 4 ? "score-high" : score <= 2 ? "score-low" : "score-mid";
}
</script>

<template>
  <div class="page">
    <AsyncBlock :loading="runs.loading.value" :error="runs.error.value" :empty="false">
      <div class="page">
        <div class="panel controls">
          <span class="muted">统计日</span>
          <el-select v-model="asOf" style="width: 150px" placeholder="选择统计日" @change="onFilterChange">
            <el-option v-for="date in runs.data.value ?? []" :key="date" :label="date" :value="date" />
          </el-select>
          <el-switch v-model="alertOnly" active-text="仅看预警" @change="onFilterChange" />
        </div>

        <AsyncBlock
          :loading="list.loading.value"
          :error="list.error.value"
          :empty="noRuns"
          empty-text="还没有分层结果，请先在系统管理里运行 KOL 重算"
        >
          <div v-if="list.data.value && !noRuns" class="page">
            <SegmentCards :counts="list.data.value.segmentCounts" :selected="segment" @select="onSegment" />

            <div class="panel">
              <el-table
                :data="items"
                size="small"
                row-key="employeeId"
                row-class-name="clickable-row"
                empty-text="没有符合条件的博主"
                @row-click="open"
              >
                <el-table-column prop="rank" label="名次" width="70" align="center" />
                <el-table-column prop="name" label="姓名" min-width="120" />
                <el-table-column label="分层" min-width="170">
                  <template #default="{ row }">
                    <el-tag :type="segmentTagType(row.segment)" size="small" effect="plain">{{ row.segmentName }}</el-tag>
                    <el-tag v-if="row.churnAlert" type="danger" size="small" effect="dark" style="margin-left: 6px">预警</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="R / F / M" min-width="150">
                  <template #default="{ row }">
                    <span class="score-chip" :class="scoreClass(row.rScore)">{{ row.rScore }}</span>
                    <span class="score-chip" :class="scoreClass(row.fScore)">{{ row.fScore }}</span>
                    <span class="score-chip" :class="scoreClass(row.mScore)">{{ row.mScore }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="近 12 个月积分" align="right" min-width="120">
                  <template #default="{ row }">{{ formatNumber(row.rollingPoints12m) }}</template>
                </el-table-column>
                <el-table-column label="距今（天）" align="right" min-width="90">
                  <template #default="{ row }">{{ row.recencyDays ?? "—" }}</template>
                </el-table-column>
                <el-table-column label="建议文案" min-width="260" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.recommendation ?? "—" }}</template>
                </el-table-column>
              </el-table>

              <el-pagination
                class="pager"
                background
                layout="total, sizes, prev, pager, next"
                :total="list.data.value.total"
                :current-page="page"
                :page-size="pageSize"
                :page-sizes="[20, 50, 100]"
                @current-change="onPage"
                @size-change="onPageSize"
              />
            </div>
          </div>
          <div v-else-if="noRuns" class="muted center">
            <router-link to="/admin/simulator">去运行 KOL 重算</router-link>
          </div>
        </AsyncBlock>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.center {
  text-align: center;
}
</style>
```

四个占位页面（后面任务整体替换）：

`frontend/src/views/kol/KolDetailView.vue`：

```vue
<template>
  <div class="panel">博主详情（建设中）</div>
</template>
```

`frontend/src/views/admin/SimulatorView.vue`：

```vue
<template>
  <div class="panel">数据模拟器（建设中）</div>
</template>
```

`frontend/src/views/admin/PointsConfigView.vue`：

```vue
<template>
  <div class="panel">积分权重（建设中）</div>
</template>
```

`frontend/src/views/admin/PlaybookView.vue`：

```vue
<template>
  <div class="panel">建议文案库（建设中）</div>
</template>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功。常见类型问题：`el-table` 插槽里的 `row` 是 `any`，直接用即可；`@change="onFilterChange"` 的参数类型不影响。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add KOL list page, admin menu and routes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 前端——博主详情页

**Files:**
- Create: `frontend/src/views/kol/StandingCard.vue`
- Modify: `frontend/src/views/kol/KolDetailView.vue`（整体替换占位）

- [ ] **Step 1: 写页面**

`frontend/src/views/kol/StandingCard.vue`：

```vue
<script setup lang="ts">
import type { CurrentStanding } from "../../api/types";
import { formatNumber, formatRate } from "../../utils/format";
import { segmentTagType } from "../../utils/segment";

defineProps<{ current: CurrentStanding | null }>();

const metricText = { ACTIVITY: "活跃次数", POINTS: "积分" } as const;

function scoreClass(score: number): string {
  return score >= 4 ? "score-high" : score <= 2 ? "score-low" : "score-mid";
}
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">当前状态</h3>

    <el-empty v-if="!current" description="不在最近一次名单内" :image-size="60" />

    <template v-else>
      <div class="row">
        <el-tag :type="segmentTagType(current.segment)" effect="plain">{{ current.segmentName }}</el-tag>
        <el-tag v-if="current.churnAlert" type="danger" effect="dark">预警</el-tag>
        <span class="muted">统计日 {{ current.asOf }}　名单名次 {{ current.rank }}　近 12 个月积分 {{ formatNumber(current.rollingPoints12m) }}</span>
      </div>

      <div class="grid">
        <div>
          <div class="muted">新近度 R</div>
          <div><span class="score-chip" :class="scoreClass(current.rScore)">{{ current.rScore }}</span>
            距今 {{ current.recencyDays ?? "—" }} 天</div>
        </div>
        <div>
          <div class="muted">频次 F</div>
          <div><span class="score-chip" :class="scoreClass(current.fScore)">{{ current.fScore }}</span>
            近 90 天 {{ current.frequency }} 次</div>
        </div>
        <div>
          <div class="muted">产出 M</div>
          <div><span class="score-chip" :class="scoreClass(current.mScore)">{{ current.mScore }}</span>
            近 90 天 {{ formatNumber(current.monetary) }} 分</div>
        </div>
      </div>

      <el-alert
        v-if="current.alert"
        type="error"
        :closable="false"
        show-icon
        :title="`流失预警：${metricText[current.alert.metric]}下降 ${formatRate(current.alert.dropRate).replace('+', '')}`"
        :description="`近期日均 ${current.alert.recentDaily.toFixed(2)}，基线日均 ${current.alert.baselineDaily.toFixed(2)}`"
        style="margin-top: 12px"
      />

      <div class="advice">
        <div class="muted">建议</div>
        <div>{{ current.recommendation ?? "暂无匹配的建议文案" }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 14px;
}
.advice {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f7f9fc;
  line-height: 1.6;
}
</style>
```

`frontend/src/views/kol/KolDetailView.vue`（整体替换）：

```vue
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchActivity, fetchPointsTrend, fetchProfile } from "../../api/kol";
import type { Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildActivityOption } from "../../charts/activityOption";
import { buildPointsTrendOption } from "../../charts/pointsTrendOption";
import { buildRfmHistoryOption } from "../../charts/rfmHistoryOption";
import { useAsync } from "../../composables/useAsync";
import { eventLabel } from "../../utils/actionTypes";
import { segmentName } from "../../utils/segment";
import StandingCard from "./StandingCard.vue";

const route = useRoute();
const employeeId = computed(() => Number(route.params.id));
const validId = computed(() => Number.isInteger(employeeId.value) && employeeId.value > 0);

const granularity = ref<Granularity>("WEEK");
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
  { value: "YEAR", label: "年" },
];

const profile = useAsync((id: number) => fetchProfile(id));
const activity = useAsync((id: number, g: Granularity) => fetchActivity(id, g));
const trend = useAsync((id: number, g: Granularity) => fetchPointsTrend(id, g));

function loadSeries(): void {
  if (validId.value) {
    void activity.run(employeeId.value, granularity.value);
    void trend.run(employeeId.value, granularity.value);
  }
}

watch(
  employeeId,
  () => {
    if (validId.value) {
      void profile.run(employeeId.value);
      loadSeries();
    }
  },
  { immediate: true },
);
watch(granularity, loadSeries);

const history = computed(() => profile.data.value?.history ?? []);
const historyOption = computed(() => buildRfmHistoryOption(history.value));
const activityOption = computed(() => (activity.data.value ? buildActivityOption(activity.data.value) : {}));
const trendOption = computed(() => (trend.data.value ? buildPointsTrendOption(trend.data.value) : {}));
const events = computed(() => profile.data.value?.recentEvents ?? []);

function formatTime(time: string): string {
  return time.replace("T", " ").slice(0, 16);
}
</script>

<template>
  <div class="page">
    <router-link to="/kol" class="back">← 返回 KOL 名单</router-link>

    <el-alert v-if="!validId" type="error" title="无效的博主 id" :closable="false" show-icon />

    <AsyncBlock v-else :loading="profile.loading.value" :error="profile.error.value" :empty="false">
      <div v-if="profile.data.value" class="page">
        <div class="panel header">
          <span class="name">{{ profile.data.value.employee.name }}</span>
          <span class="muted">部门：{{ profile.data.value.employee.dept ?? "—" }}</span>
          <span class="muted">入职：{{ profile.data.value.employee.joinDate ?? "—" }}</span>
        </div>

        <StandingCard :current="profile.data.value.current" />

        <div class="panel">
          <h3 class="panel-title">RFM 分数历史</h3>
          <el-empty v-if="history.length === 0" description="还没有每周重算记录" :image-size="60" />
          <div v-else class="history">
            <div class="chart"><BaseChart :option="historyOption" height="280px" /></div>
            <div class="timeline">
              <h4 class="muted">分层标签变化</h4>
              <el-timeline v-if="profile.data.value.segmentChanges.length > 0">
                <el-timeline-item v-for="c in profile.data.value.segmentChanges" :key="c.asOf" :timestamp="c.asOf">
                  {{ segmentName(c.from) }} → {{ segmentName(c.to) }}
                </el-timeline-item>
              </el-timeline>
              <p v-else class="muted">分层标签没有变化</p>
            </div>
          </div>
        </div>

        <div class="panel granularity">
          <span class="muted">时间粒度</span>
          <el-radio-group v-model="granularity" size="small">
            <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </div>

        <div class="panel">
          <h3 class="panel-title">活跃时间轴</h3>
          <AsyncBlock :loading="activity.loading.value" :error="activity.error.value" :empty="false">
            <BaseChart v-if="activity.data.value" :option="activityOption" height="300px" />
          </AsyncBlock>
        </div>

        <div class="panel">
          <h3 class="panel-title">积分构成趋势</h3>
          <AsyncBlock :loading="trend.loading.value" :error="trend.error.value" :empty="false">
            <BaseChart v-if="trend.data.value" :option="trendOption" height="300px" />
          </AsyncBlock>
        </div>

        <div class="panel">
          <h3 class="panel-title">最近事件</h3>
          <el-table :data="events" size="small" empty-text="没有事件记录" max-height="360">
            <el-table-column label="时间" min-width="160">
              <template #default="{ row }">{{ formatTime(row.time) }}</template>
            </el-table-column>
            <el-table-column label="类型" min-width="100">
              <template #default="{ row }">{{ eventLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="关联 id" min-width="100">
              <template #default="{ row }">{{ row.refId ?? "—" }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.back {
  color: var(--color-primary);
  text-decoration: none;
  font-size: 13px;
}
.header {
  display: flex;
  align-items: baseline;
  gap: 16px;
}
.name {
  font-size: 20px;
  font-weight: 600;
}
.history {
  display: grid;
  grid-template-columns: 1fr 240px;
  gap: 16px;
}
.timeline h4 {
  margin: 0 0 8px;
  font-weight: 500;
}
.granularity {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功。`computed(() => (activity.data.value ? buildActivityOption(...) : {}))` 的返回类型是 `EChartsOption | {}`，传给 `BaseChart` 的 `option` 若报类型不匹配，把空分支改成 `({} as EChartsOption)` 并 `import type { EChartsOption } from "echarts"`。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add blogger detail page with RFM history, activity and points charts

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 前端——建议文案库页与积分权重页

**Files:**
- Modify: `frontend/src/views/admin/PlaybookView.vue`、`frontend/src/views/admin/PointsConfigView.vue`（整体替换占位）

- [ ] **Step 1: 写页面**

`frontend/src/views/admin/PlaybookView.vue`（整体替换）：

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { createPlaybook, deletePlaybook, fetchPlaybook, updatePlaybook } from "../../api/playbook";
import type { PlaybookAlertStatus, PlaybookEntry, PlaybookRequest, Segment } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { alertStatusLabel } from "../../utils/actionTypes";
import { SEGMENTS, segmentName, segmentTagType } from "../../utils/segment";

const MAX_TEXT = 1000;

const entries = useAsync(() => fetchPlaybook());
onMounted(() => void entries.run());

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const saving = ref(false);
const form = reactive<{ segmentLabel: Segment; alertStatus: PlaybookAlertStatus; recommendationText: string }>({
  segmentLabel: "STAR_KOL",
  alertStatus: "NO_ALERT",
  recommendationText: "",
});

function openCreate(): void {
  editingId.value = null;
  form.segmentLabel = "STAR_KOL";
  form.alertStatus = "NO_ALERT";
  form.recommendationText = "";
  dialogVisible.value = true;
}

function openEdit(entry: PlaybookEntry): void {
  editingId.value = entry.id;
  form.segmentLabel = entry.segmentLabel;
  form.alertStatus = entry.alertStatus;
  form.recommendationText = entry.recommendationText;
  dialogVisible.value = true;
}

async function save(): Promise<void> {
  if (!form.recommendationText.trim()) {
    ElMessage.warning("请填写建议文案");
    return;
  }
  const request: PlaybookRequest = { ...form };
  saving.value = true;
  try {
    if (editingId.value === null) {
      await createPlaybook(request);
    } else {
      await updatePlaybook(editingId.value, request);
    }
    dialogVisible.value = false;
    ElMessage.success("已保存");
    await entries.run();
  } catch (e) {
    // 后端的 400/409 响应体就是一句说明原因的文字（比如该分层和预警状态已有文案）
    ElMessage.error(e instanceof Error ? e.message : String(e));
  } finally {
    saving.value = false;
  }
}

async function remove(entry: PlaybookEntry): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${segmentName(entry.segmentLabel)} / ${alertStatusLabel(entry.alertStatus)}」的建议文案吗？`,
      "确认删除",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  try {
    await deletePlaybook(entry.id);
    ElMessage.success("已删除");
    await entries.run();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}
</script>

<template>
  <div class="page">
    <div class="panel head">
      <span class="muted">KOL 名单会按"分层 + 是否预警"匹配这里的文案；没有精确匹配时回退到该分层的"无预警"文案。</span>
      <el-button type="primary" @click="openCreate">新增文案</el-button>
    </div>

    <div class="panel">
      <AsyncBlock :loading="entries.loading.value" :error="entries.error.value" :empty="false">
        <el-table :data="entries.data.value ?? []" size="small" row-key="id" empty-text="还没有建议文案">
          <el-table-column label="分层" width="150">
            <template #default="{ row }">
              <el-tag :type="segmentTagType(row.segmentLabel)" size="small" effect="plain">{{ segmentName(row.segmentLabel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="预警状态" width="110">
            <template #default="{ row }">{{ alertStatusLabel(row.alertStatus) }}</template>
          </el-table-column>
          <el-table-column prop="recommendationText" label="建议文案" min-width="360" />
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row as PlaybookEntry)">编辑</el-button>
              <el-button link type="danger" @click="remove(row as PlaybookEntry)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </AsyncBlock>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增文案' : '编辑文案'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="分层">
          <el-select v-model="form.segmentLabel" style="width: 100%">
            <el-option v-for="s in SEGMENTS" :key="s.value" :label="s.name" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="预警状态">
          <el-radio-group v-model="form.alertStatus">
            <el-radio value="NO_ALERT">无预警</el-radio>
            <el-radio value="ALERT">触发预警</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="建议文案">
          <el-input v-model="form.recommendationText" type="textarea" :rows="5" :maxlength="MAX_TEXT" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
</style>
```

`frontend/src/views/admin/PointsConfigView.vue`（整体替换）：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { deleteWeightVersion, fetchPointsConfig, updateWeight } from "../../api/pointsConfig";
import type { PointsConfigItem } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { actionLabel, categoryLabel } from "../../utils/actionTypes";

const config = useAsync(() => fetchPointsConfig());
onMounted(() => void config.run());

const groups = computed(() => {
  const items = config.data.value ?? [];
  return [
    { category: "ACTIVE" as const, items: items.filter((i) => i.category === "ACTIVE") },
    { category: "PASSIVE" as const, items: items.filter((i) => i.category === "PASSIVE") },
  ];
});

const dialogVisible = ref(false);
const editing = ref<PointsConfigItem | null>(null);
const weight = ref<number>(0);
const saving = ref(false);

function openEdit(item: PointsConfigItem): void {
  editing.value = item;
  weight.value = item.current.weight;
  dialogVisible.value = true;
}

async function save(): Promise<void> {
  if (!editing.value) {
    return;
  }
  saving.value = true;
  try {
    await updateWeight(editing.value.actionType, weight.value);
    dialogVisible.value = false;
    ElMessage.success("已新增今天生效的版本");
    await config.run();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  } finally {
    saving.value = false;
  }
}

async function removeVersion(item: PointsConfigItem, id: number, weightValue: number, date: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${actionLabel(item.actionType)}」${date} 生效的版本（权重 ${weightValue}）吗？`,
      "确认删除",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  try {
    await deleteWeightVersion(id);
    ElMessage.success("已删除");
    await config.run();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}
</script>

<template>
  <div class="page">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="修改会新增一个今天生效的版本（今天已有版本则覆盖）；积分引擎对之后新产生的事件使用最新版本，已有积分流水不会重算，需要重新回填才会按新权重重新生成。"
    />

    <AsyncBlock :loading="config.loading.value" :error="config.error.value" :empty="false">
      <div v-for="group in groups" :key="group.category" class="panel">
        <h3 class="panel-title">{{ categoryLabel(group.category) }}</h3>
        <el-table :data="group.items" size="small" row-key="actionType">
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.versions" size="small" class="versions">
                <el-table-column prop="effectiveDate" label="生效日期" min-width="130" />
                <el-table-column prop="weight" label="权重" min-width="90" />
                <el-table-column label="操作" width="110" align="center">
                  <template #default="{ row: version }">
                    <el-tooltip :disabled="row.versions.length > 1" content="每个动作至少保留一个版本">
                      <span>
                        <el-button
                          link
                          type="danger"
                          :disabled="row.versions.length <= 1"
                          @click="removeVersion(row as PointsConfigItem, version.id, version.weight, version.effectiveDate)"
                        >删除</el-button>
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column label="动作" min-width="200">
            <template #default="{ row }">
              {{ actionLabel(row.actionType) }}
              <span class="muted">（{{ row.actionType }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="当前权重" min-width="100">
            <template #default="{ row }"><strong>{{ row.current.weight }}</strong></template>
          </el-table-column>
          <el-table-column label="生效日期" min-width="130">
            <template #default="{ row }">{{ row.current.effectiveDate }}</template>
          </el-table-column>
          <el-table-column label="版本数" width="90" align="center">
            <template #default="{ row }">{{ row.versions.length }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row as PointsConfigItem)">修改</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </AsyncBlock>

    <el-dialog v-model="dialogVisible" :title="`修改权重：${editing ? actionLabel(editing.actionType) : ''}`" width="420px">
      <el-form label-width="90px">
        <el-form-item label="新权重">
          <el-input-number v-model="weight" :min="0" :max="10000" :step="1" />
        </el-form-item>
        <p class="muted">将新增一个今天生效的版本，范围 0 ~ 10000 的整数。</p>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.versions {
  margin: 4px 24px 8px;
  width: calc(100% - 48px);
}
</style>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功。如果 `v-model="weight"` 对 `el-input-number` 报"`number | undefined` 不能赋给 `number`"，把 `weight` 声明为 `ref<number | undefined>(0)`，保存时用 `weight.value ?? 0`。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add playbook maintenance and points weight config pages

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 前端——数据模拟器控制台

**Files:**
- Create: `frontend/src/views/admin/{BackfillPanel,RealtimePanel,DataTasksPanel}.vue`
- Modify: `frontend/src/views/admin/SimulatorView.vue`（整体替换占位）

- [ ] **Step 1: 写页面**

`frontend/src/views/admin/BackfillPanel.vue`：

```vue
<script setup lang="ts">
import { reactive, ref } from "vue";
import { backfill } from "../../api/admin";
import { buildBackfillBody, defaultBackfillForm, validateBackfillForm } from "../../utils/simulatorForms";

const form = reactive(defaultBackfillForm());
const running = ref(false);
const result = ref<string | null>(null);
const error = ref<string | null>(null);

async function submit(): Promise<void> {
  const problem = validateBackfillForm(form);
  if (problem) {
    ElMessage.warning(problem);
    return;
  }
  try {
    await ElMessageBox.confirm(
      "回填会清空所有模拟数据（员工、内容、行为、积分流水），以及依赖它们的汇总、快照和 KOL 结果。确定继续吗？",
      "确认回填",
      { type: "warning", confirmButtonText: "清空并回填", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  running.value = true;
  result.value = null;
  error.value = null;
  try {
    result.value = await backfill(buildBackfillBody(form));
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    running.value = false;
  }
}
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">历史回填</h3>
    <el-form label-width="120px" class="form">
      <el-form-item label="批次名"><el-input v-model="form.batchId" style="width: 220px" /></el-form-item>
      <el-form-item label="员工数"><el-input-number v-model="form.employeeCount" :min="1" /></el-form-item>
      <el-form-item label="账号数"><el-input-number v-model="form.accountCount" :min="1" /></el-form-item>
      <el-form-item label="团队账号比例">
        <el-input-number v-model="form.teamAccountRatio" :min="0" :max="1" :step="0.05" :precision="2" />
      </el-form-item>
      <el-form-item label="内容数"><el-input-number v-model="form.contentCount" :min="1" /></el-form-item>
      <el-form-item label="历史起止日期">
        <el-date-picker v-model="form.historyStart" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
        <span class="sep">至</span>
        <el-date-picker v-model="form.historyEnd" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
      </el-form-item>
      <el-form-item label="随机种子"><el-input-number v-model="form.randomSeed" /></el-form-item>
      <el-form-item label="博主画像"><el-switch v-model="form.personas" active-text="开启（明星/新人/流失/沉寂）" /></el-form-item>
      <p class="muted hint">结束日期建议早于今天至少 30 天：回填的浏览行为会延续到内容发布后 30 天。</p>
      <el-form-item>
        <el-button type="danger" :loading="running" @click="submit">{{ running ? "回填中…" : "清空并回填" }}</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="result" type="success" :title="result" :closable="false" show-icon />
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
  </div>
</template>

<style scoped>
.sep {
  margin: 0 8px;
  color: var(--color-text-muted);
}
.hint {
  margin: 0 0 12px 120px;
}
</style>
```

`frontend/src/views/admin/RealtimePanel.vue`：

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { fetchRealtimeStatus, startRealtime, stopRealtime } from "../../api/admin";
import type { RealtimeStatus } from "../../api/types";
import { buildRealtimeBody, defaultRealtimeForm } from "../../utils/simulatorForms";

const form = reactive(defaultRealtimeForm());
const status = ref<RealtimeStatus | null>(null);
const error = ref<string | null>(null);
const busy = ref(false);
let timer: number | undefined;

async function refresh(): Promise<void> {
  try {
    status.value = await fetchRealtimeStatus();
    error.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function start(): Promise<void> {
  busy.value = true;
  try {
    status.value = await startRealtime(buildRealtimeBody(form));
    error.value = null;
  } catch (e) {
    // 已经在运行时后端返回 409，响应体是一句说明
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function stop(): Promise<void> {
  busy.value = true;
  try {
    await stopRealtime();
    await refresh();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

// 页面存在期间每 2 秒轮询一次状态，离开页面即停止
onMounted(() => {
  void refresh();
  timer = window.setInterval(() => void refresh(), 2000);
});
onBeforeUnmount(() => window.clearInterval(timer));
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">实时模拟</h3>
    <div class="status">
      <el-tag :type="status?.running ? 'success' : 'info'" effect="plain">{{ status?.running ? "运行中" : "已停止" }}</el-tag>
      <template v-if="status?.running">
        <span>批次：{{ status.batchId }}</span>
        <span>已发送事件：{{ status.sentCount }}</span>
        <span class="muted">启动于 {{ status.startedAt?.replace("T", " ").slice(0, 19) }}</span>
      </template>
      <span v-else-if="status" class="muted">上次批次：{{ status.batchId ?? "—" }}，已发送 {{ status.sentCount }}</span>
    </div>

    <el-form inline class="form">
      <el-form-item label="批次名"><el-input v-model="form.batchId" placeholder="留空自动生成" style="width: 170px" /></el-form-item>
      <el-form-item label="间隔（毫秒）"><el-input-number v-model="form.intervalMillis" :min="100" :step="100" /></el-form-item>
      <el-form-item label="每批事件数"><el-input-number v-model="form.eventsPerBatch" :min="1" /></el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="busy" :disabled="status?.running" @click="start">启动</el-button>
        <el-button :loading="busy" :disabled="!status?.running" @click="stop">停止</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
  </div>
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
}
</style>
```

`frontend/src/views/admin/DataTasksPanel.vue`：

```vue
<script setup lang="ts">
import { reactive, ref } from "vue";
import { fetchPvRange } from "../../api/analytics";
import { rebuildPv, snapshotAllLeaderboards } from "../../api/admin";
import { rebuildKol } from "../../api/kol";
import { kolAsOfFromPvEnd } from "../../utils/simulatorForms";

type Status = "pending" | "running" | "done" | "error";
interface Step {
  name: string;
  status: Status;
  detail: string;
}

const message = (e: unknown): string => (e instanceof Error ? e.message : String(e));

// ---- 单个任务 ----
const pvRange = ref<[string, string] | null>(null);
const pvResult = ref<string | null>(null);
const pvError = ref<string | null>(null);
const pvBusy = ref(false);

async function runPv(): Promise<string> {
  const result = await rebuildPv(pvRange.value?.[0], pvRange.value?.[1]);
  return `写入 ${result.rowsWritten} 行汇总`;
}

async function onPv(): Promise<void> {
  pvBusy.value = true;
  pvResult.value = null;
  pvError.value = null;
  try {
    pvResult.value = await runPv();
  } catch (e) {
    pvError.value = message(e);
  } finally {
    pvBusy.value = false;
  }
}

const snapResult = ref<string | null>(null);
const snapError = ref<string | null>(null);
const snapBusy = ref(false);

async function runSnapshot(): Promise<string> {
  const result = await snapshotAllLeaderboards();
  return `重算 ${result.months} 个月、${result.years} 个年，写入 ${result.rowsWritten} 行`;
}

async function onSnapshot(): Promise<void> {
  snapBusy.value = true;
  snapResult.value = null;
  snapError.value = null;
  try {
    snapResult.value = await runSnapshot();
  } catch (e) {
    snapError.value = message(e);
  } finally {
    snapBusy.value = false;
  }
}

const kol = reactive<{ asOf: string | undefined; weeks: number }>({ asOf: undefined, weeks: 12 });
const kolResult = ref<string | null>(null);
const kolError = ref<string | null>(null);
const kolBusy = ref(false);

async function runKol(asOf: string | undefined, weeks: number): Promise<string> {
  const results = await rebuildKol(asOf, weeks);
  const last = results[results.length - 1];
  return last
    ? `重算 ${results.length} 周，最后统计日 ${last.asOf}：名单 ${last.population} 人，预警 ${last.alerts} 人`
    : "没有结果";
}

async function onKol(): Promise<void> {
  kolBusy.value = true;
  kolResult.value = null;
  kolError.value = null;
  try {
    kolResult.value = await runKol(kol.asOf, kol.weeks);
  } catch (e) {
    kolError.value = message(e);
  } finally {
    kolBusy.value = false;
  }
}

// ---- 一键重算全部 ----
const steps = ref<Step[]>([]);
const allBusy = ref(false);

async function runStep(index: number, task: () => Promise<string>): Promise<void> {
  steps.value[index].status = "running";
  try {
    steps.value[index].detail = await task();
    steps.value[index].status = "done";
  } catch (e) {
    steps.value[index].status = "error";
    steps.value[index].detail = message(e);
    throw e;
  }
}

async function runAll(): Promise<void> {
  allBusy.value = true;
  steps.value = [
    { name: "PV 汇总重算", status: "pending", detail: "" },
    { name: "榜单快照重算", status: "pending", detail: "" },
    { name: "KOL 重算", status: "pending", detail: "" },
  ];
  try {
    await runStep(0, async () => {
      const result = await rebuildPv();
      return `写入 ${result.rowsWritten} 行汇总`;
    });
    await runStep(1, runSnapshot);
    // 模拟数据是历史的：以今天为统计日所有人都会变成沉寂；而 PV 数据的最后一天又比模拟的"现在"晚约 30 天
    // （回填的浏览行为会延续到发布后 30 天），所以统计日取它往前 30 天
    const range = await fetchPvRange().catch(() => null);
    await runStep(2, () => runKol(range?.to ? kolAsOfFromPvEnd(range.to) : undefined, 12));
  } catch {
    // 失败的那一步已经记录了原因，后面的步骤不再执行
  } finally {
    allBusy.value = false;
  }
}

const stepType = { pending: "info", running: "warning", done: "success", error: "danger" } as const;
const stepText = { pending: "等待", running: "进行中", done: "完成", error: "失败" } as const;
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">数据任务（重算派生数据）</h3>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="历史回填会清空汇总、快照和 KOL 结果，回填之后请运行下面的数据任务。"
      style="margin-bottom: 12px"
    />

    <div class="all">
      <el-button type="primary" :loading="allBusy" @click="runAll">一键重算全部</el-button>
      <span class="muted">按"PV 汇总 → 榜单快照 → KOL 重算（统计日取 PV 数据最后一天往前 30 天，12 周）"顺序执行</span>
    </div>
    <ul v-if="steps.length > 0" class="steps">
      <li v-for="step in steps" :key="step.name">
        <el-tag :type="stepType[step.status]" size="small" effect="plain">{{ stepText[step.status] }}</el-tag>
        {{ step.name }}<span v-if="step.detail" class="muted">：{{ step.detail }}</span>
      </li>
    </ul>

    <el-divider />

    <div class="task">
      <strong>PV 汇总</strong>
      <el-date-picker v-model="pvRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
        start-placeholder="全部历史" end-placeholder="（可选）" style="width: 260px" />
      <el-button :loading="pvBusy" @click="onPv">重算</el-button>
      <span v-if="pvResult" class="ok">{{ pvResult }}</span>
      <span v-if="pvError" class="bad">{{ pvError }}</span>
    </div>

    <div class="task">
      <strong>榜单快照</strong>
      <span class="muted">重算所有已结束且有流水的月和年</span>
      <el-button :loading="snapBusy" @click="onSnapshot">重算</el-button>
      <span v-if="snapResult" class="ok">{{ snapResult }}</span>
      <span v-if="snapError" class="bad">{{ snapError }}</span>
    </div>

    <div class="task">
      <strong>KOL 分层</strong>
      <el-date-picker v-model="kol.asOf" type="date" value-format="YYYY-MM-DD" placeholder="统计日（默认今天）" style="width: 180px" />
      <el-input-number v-model="kol.weeks" :min="1" :max="52" />
      <span class="muted">周</span>
      <el-button :loading="kolBusy" @click="onKol">重算</el-button>
      <span v-if="kolResult" class="ok">{{ kolResult }}</span>
      <span v-if="kolError" class="bad">{{ kolError }}</span>
    </div>
  </div>
</template>

<style scoped>
.all {
  display: flex;
  align-items: center;
  gap: 12px;
}
.steps {
  margin: 12px 0 0;
  padding-left: 18px;
  line-height: 2;
  font-size: 13px;
}
.task {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.task strong {
  width: 80px;
}
.ok {
  color: var(--color-down);
  font-size: 13px;
}
.bad {
  color: var(--color-up);
  font-size: 13px;
}
</style>
```

`frontend/src/views/admin/SimulatorView.vue`（整体替换）：

```vue
<script setup lang="ts">
import BackfillPanel from "./BackfillPanel.vue";
import DataTasksPanel from "./DataTasksPanel.vue";
import RealtimePanel from "./RealtimePanel.vue";
</script>

<template>
  <div class="page">
    <BackfillPanel />
    <DataTasksPanel />
    <RealtimePanel />
  </div>
</template>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功。可能的类型问题：
- `reactive(defaultBackfillForm())` 上的 `v-model` 绑定 `el-input-number`（`number | undefined` 赋给 `number`）：按"约定 5"处理——把 `BackfillForm` 里对应的字段在面板里用 `computed` 的 getter/setter 包一层（setter 里用 `value ?? 默认值`），不要用 `as` 硬转；
- `el-date-picker` 的 `v-model` 绑 `string | undefined`：`value-format="YYYY-MM-DD"` 时模型是字符串，可以直接绑定；`kol.asOf` 声明为 `string | undefined` 即可。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add simulator console with backfill, realtime and data tasks

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 浏览器验证、说明文档与收尾

**Files:**
- Modify: `frontend/README.md`、`docs/superpowers/specs/2026-10-05-kol-and-admin-ui-design.md`（状态行）、`docs/superpowers/specs/2026-09-23-community-analytics-design.md`（一句说明）

- [ ] **Step 1: 启动后端和前端**

确认基础设施在运行（`docker ps` 能看到 `jiaxian-opengauss`、`jiaxian-redis`、`jiaxian-kafka`；Colima 停了就 `colima start` 并等 OpenGauss 就绪）。

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
```

另开终端：

```bash
cd frontend && VITE_API_TARGET=http://localhost:8093 npm run dev
```

用浏览器打开 `http://localhost:5173/admin/simulator`。

- [ ] **Step 2: 在内置浏览器里走完整条路径并截图**

1. **数据模拟器**：用较小的参数回填（员工 50、账号 20、内容 150，其余默认，画像开），点"清空并回填"→ 确认框 → 等待完成，看到成功提示；点"一键重算全部"，三步依次变成"完成"，每步有结果摘要；单独试一次"PV 汇总"（日期范围留空）。
2. **实时模拟**：点"启动"，状态区变为"运行中"，已发送事件数每 2 秒增加；点"停止"，状态变为"已停止"；再启动一次确认 409 之类的提示（运行中再启动按钮是禁用的，所以这里主要验证状态）。
3. **KOL 名单**：统计日下拉有选项；六个分层卡片有人数，点击"流失预警"只剩该分层的人，再点取消；打开"仅看预警"；翻页/改每页条数回到第 1 页；表头列都正常，R/F/M 有颜色。
4. **博主详情**：点开一个流失预警的博主：当前状态卡有预警详情和建议文案；RFM 历史折线和标签变化时间线；切换粒度（日/周/月/年），活跃时间轴和积分构成图都跟着变；最近事件列表。直接访问 `/kol/999999999` 显示"Employee … does not exist"。
5. **积分权重**：两个分组、15 个动作；修改"点赞"为 4，成功后版本数 +1、当前权重 4、生效日期是今天；展开看到历史版本；删除刚才的版本，恢复原状；只剩一个版本的动作删除按钮是禁用的。
6. **建议文案库**：6 条种子；新增一条已存在的（分层 + 预警状态重复）→ 显示后端的 409 文字；新增 `观察中 / 触发预警`、编辑、删除（二次确认），最后恢复原状。
7. 全程控制台没有错误。

- [ ] **Step 3: 关闭两个进程**

```bash
pkill -f spring-boot:run
pkill -f vite
sleep 6
lsof -i :8093 || echo "8093 free"
lsof -i :5173 || echo "5173 free"
```

Expected: 两个端口都输出 `free`（若仍有进程在监听，再各 kill 一次并等几秒）。

- [ ] **Step 4: 更新文档并提交**

把 `frontend/README.md` 的第一段改为"目前包含：总览看板、积分排行榜、KOL 管理（名单 + 博主详情）、系统管理（数据模拟器、积分权重、建议文案库）。"，并把"演示数据"一节的说明改成"在系统管理 → 数据模拟器里回填并一键重算全部，或按下面的命令手动调用"，保留原有的 curl 命令。

把 `docs/superpowers/specs/2026-10-05-kol-and-admin-ui-design.md` 的 `- 状态：已通过讨论确认` 改为 `- 状态：已实现（实施计划见 [KOL 管理与系统管理页面](../plans/2026-10-05-kol-and-admin-ui.md)）`。

在 `docs/superpowers/specs/2026-09-23-community-analytics-design.md` 里，把第 8 节中 Plan 7 添加的那行"实现进度"替换为：

```markdown
> 实现进度（见 [前端看板设计](2026-10-04-frontend-dashboard-design.md)、[KOL 与系统管理页面设计](2026-10-05-kol-and-admin-ui-design.md)）：总览看板、积分排行榜、KOL 管理（名单 + 博主详情）、系统管理（数据模拟器控制台、积分权重配置、建议文案库）全部页面已实现；积分权重按生效日期版本化，改权重只影响之后新产生的事件。
```

Run: `cd backend && mvn test && cd ../frontend && npm test && npm run build`
Expected: 后端 `BUILD SUCCESS`，前端测试和构建通过。

```bash
git add docs frontend
git commit -m "docs: mark KOL and admin UI spec as implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 3.1 积分权重接口（版本化、今天生效、0~10000、404/409、不新建动作类型）→ Task 1。
- 3.2 重算记录列表 → Task 2。
- 4 菜单与路由 → Task 5（`App.vue`、`router/index.ts`、占位页）。
- 5.1 KOL 名单页 → Task 5；5.2 博主详情页 → Task 6（含图表函数 Task 4）；5.3 模拟器控制台 → Task 8（含表单工具 Task 3）；5.4 积分权重页、5.5 建议文案库页 → Task 7。
- 6 前端公共部分：`sendJson`、四个接口模块、字典和表单工具、三个图表函数 → Task 3、4。
- 7 测试与验证：后端 IT（Task 1、2）、前端单测（Task 3、4）、构建门槛（每个任务）、浏览器人工验证（Task 9）。

**2. 占位符扫描：** 无 TBD/TODO。Task 5 里的四个占位页面是明确的过渡产物，分别在 Task 6、7、8 整体替换。

**3. 类型一致性：** 前端 `BloggerItem`/`BloggerList`/`BloggerProfile`/`CurrentStanding`/`HistoryPoint`/`SegmentChange`/`RecentEvent`/`ActivitySeries`/`PointsTrendSeries` 与后端 Plan 5、6 的响应字段一一对应；`PlaybookEntry`/`PlaybookRequest` 与 `PlaybookController` 一致；`PointsConfigItem`/`WeightVersion` 与 Task 1 的后端记录类一致（`actionType, category, current, versions` / `id, weight, effectiveDate`）；`RealtimeStatus {running, batchId, sentCount, startedAt}` 与后端 `RealtimeStatus` 的 getter 字段一致；`PvRebuildResult`、`SnapshotSummary`、`KolRebuildResult` 与对应控制器的响应一致；`sendJson`/`getJson` 的签名在 Task 3 定义，各接口模块和页面按它调用；`buildBackfillBody`/`validateBackfillForm`/`defaultBackfillForm`/`buildRealtimeBody`/`defaultRealtimeForm` 在 Task 3 定义，`BackfillPanel`、`RealtimePanel` 使用；`segmentName`/`segmentTagType`/`SEGMENTS`、`actionLabel`/`categoryLabel`/`eventLabel`/`alertStatusLabel` 在 Task 3 定义，各页面使用。
