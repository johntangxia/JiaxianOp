# 前端 BI 看板（基础 + 总览 + 排行榜）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付前端看板的骨架和两个页面——总览看板（PV 趋势、环比同比、预测、Top N）和积分排行榜（月榜/年榜），并补充前端所需的三组后端小接口。

**Architecture:** 后端新增 `dimension` 包（频道/账号/员工选项、PV 数据范围）和排行榜周期列表接口。前端在现有 Vue3 + TS + Vite 脚手架上加 Vue Router、Element Plus（按需导入）和 ECharts（按模块导入）；接口封装、日期范围、指标卡派生、图表配置全部是纯函数，用 Vitest 单测；页面组件靠 `vue-tsc` + `vite build` + 浏览器里的人工验证。

**Tech Stack:** 后端 Spring Boot 3.2.5 / JdbcTemplate；前端 Vue 3.4、TypeScript 5.4、Vite 5、Element Plus、ECharts、Vue Router、Vitest 2。

**设计文档：** `docs/superpowers/specs/2026-10-04-frontend-dashboard-design.md`（先读它）。

---

## 必读：约定与踩坑记录

后端沿用 Plan 3~6 的约定（日期参数绑定 `Timestamp`、`LIMIT` 用校验过的字面量、集成测试连真实 OpenGauss 且优先 `@Transactional`、夹具用 2019 年和中文名前缀、每个后端任务后跑完整 `cd backend && mvn test`）。与本计划直接相关的新增约定：

1. **前端版本**：现有 `vite@5`，所以测试框架固定 `vitest@^2.1.9`（`vitest@5` 需要 `vite ≥ 6`，会冲突），路由固定 `vue-router@4`（`vue-router@5` 的可选依赖要求 `vite ≥ 7`，npm 会报 ERESOLVE）。
2. **每个前端任务完成后都要通过**：`cd frontend && npm test` 和 `cd frontend && npm run build`（后者包含 `vue-tsc` 类型检查）。
3. **自动导入生成的 `src/components.d.ts`、`src/auto-imports.d.ts` 必须提交**，否则干净克隆上 `vue-tsc` 会因找不到组件类型而失败。
4. **日期一律用本地时间的 `yyyy-MM-dd` 字符串**（`utils/dateRange.ts` 里的 `toIsoDate` / `parseIsoDate`），不要用 `toISOString()`（会转成 UTC，在东八区会差一天）。
5. **后端响应体的错误说明就是一句文字**（400/404），前端直接展示；5xx 只显示通用提示。
6. 手工验证时后端用 `--server.port=8093`（8080 被无关进程占用），前端用环境变量指向它：`VITE_API_TARGET=http://localhost:8093 npm run dev`。用完把两个进程都关掉并确认端口释放（`lsof -i :8093`、`lsof -i :5173`）。
7. 演示数据的灌入顺序：开画像回填 → `POST /api/admin/analytics/pv/rebuild` → `POST /api/admin/analytics/kol/rebuild?asOf=…&weeks=…`（本阶段用不到，但 Plan 8 会用）→ `POST /api/admin/analytics/leaderboard/snapshot`。
8. Element Plus 的 `v-loading` 是指令，`unplugin-vue-components` 默认会解析；如果构建后加载遮罩不出现，检查 `Components({ resolvers: [ElementPlusResolver()] })` 的版本是否解析指令。

## 文件结构

**后端**（新建，`backend/src/main/java/com/jiaxian/analytics/dimension/`，测试在对应 `src/test/java/…/dimension/`）：

| 文件 | 职责 |
|---|---|
| `ChannelOption.java`、`AccountOption.java`、`EmployeeOption.java`、`PvRange.java` | 记录类 |
| `InvalidDimensionQueryException.java` | 400 |
| `DimensionRepository.java` | 频道列表、账号/员工模糊搜索、PV 数据范围 |
| `DimensionService.java` | 校验 `limit` |
| `DimensionController.java`、`DimensionExceptionHandler.java` | 四个接口与 400 处理 |

后端（修改）：`leaderboard/LeaderboardService.java`（加 `periods`）、`leaderboard/LeaderboardController.java`（加接口）、新建 `leaderboard/PeriodOption.java`。

**前端**（`frontend/`，除配置外都在 `src/` 下）：

| 文件 | 职责 |
|---|---|
| `vite.config.ts`、`package.json` | 构建、代理、测试、依赖 |
| `src/api/query.ts`、`http.ts`、`types.ts`、`analytics.ts`、`leaderboard.ts` | 接口封装 |
| `src/utils/format.ts`、`dateRange.ts`、`kpi.ts`、`trendTable.ts` | 纯逻辑 |
| `src/charts/colors.ts`、`trendOption.ts`、`topBarOption.ts`、`leaderboardOption.ts` | 图表配置（纯函数） |
| `src/composables/useAsync.ts` | 加载/错误/过期响应 |
| `src/components/BaseChart.vue`、`AsyncBlock.vue`、`RemoteSelect.vue`、`HealthBadge.vue` | 公共组件 |
| `src/views/OverviewView.vue`、`views/overview/*` | 总览看板 |
| `src/views/LeaderboardView.vue` | 排行榜 |
| `src/router/index.ts`、`src/styles/theme.css`、`src/App.vue`、`src/main.ts` | 骨架 |

测试：前端 `src/**/*.test.ts`；后端 `DimensionOptionsRepositoryIT`、`DimensionControllerIT`、`LeaderboardServiceTest`（追加）、`LeaderboardControllerIT`（追加）。

---

### Task 1: 后端——维度列表与 PV 数据范围接口

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/dimension/{ChannelOption,AccountOption,EmployeeOption,PvRange,InvalidDimensionQueryException,DimensionRepository,DimensionService,DimensionController,DimensionExceptionHandler}.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/dimension/DimensionOptionsRepositoryIT.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/dimension/DimensionControllerIT.java`

- [ ] **Step 1: 写测试**

`backend/src/test/java/com/jiaxian/analytics/dimension/DimensionOptionsRepositoryIT.java`：

```java
package com.jiaxian.analytics.dimension;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.AccountType;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DimensionOptionsRepositoryIT {

    @Autowired private DimensionRepository repository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private JdbcTemplate jdbc;

    private Account account(String name, AccountType type) {
        return accountRepository.save(Account.builder().name(name).type(type).simulationBatchId("dim-test").build());
    }

    private Employee employee(String name, String dept) {
        return employeeRepository.save(
            Employee.builder().name(name).dept(dept).simulationBatchId("dim-test").build());
    }

    @Test
    void channelsAreListedById() {
        Channel a = channelRepository.save(Channel.builder().name("维度测试频道甲").category("测试类").build());
        Channel b = channelRepository.save(Channel.builder().name("维度测试频道乙").category("测试类").build());

        List<ChannelOption> channels = repository.channels();

        assertThat(channels).contains(
            new ChannelOption(a.getId(), "维度测试频道甲", "测试类"),
            new ChannelOption(b.getId(), "维度测试频道乙", "测试类"));
        assertThat(channels).extracting(ChannelOption::id).isSorted();
        assertThat(channels.size()).isGreaterThanOrEqualTo(15);   // 15 个种子频道
    }

    @Test
    void accountsAreSearchedByKeywordIgnoringCase() {
        Account alpha = account("维度测试账号-Alpha", AccountType.PERSONAL);
        Account beta = account("维度测试账号-beta", AccountType.TEAM);
        account("别的账号", AccountType.PERSONAL);

        assertThat(repository.accounts("维度测试账号", 20)).containsExactlyInAnyOrder(
            new AccountOption(alpha.getId(), "维度测试账号-Alpha", "PERSONAL"),
            new AccountOption(beta.getId(), "维度测试账号-beta", "TEAM"));
        assertThat(repository.accounts("ALPHA", 20)).extracting(AccountOption::id).containsExactly(alpha.getId());
        assertThat(repository.accounts("  alpha ", 20)).extracting(AccountOption::id).containsExactly(alpha.getId());
        assertThat(repository.accounts("维度测试账号-不存在", 20)).isEmpty();
    }

    @Test
    void wildcardCharactersInTheKeywordAreMatchedLiterally() {
        Account literal = account("维度测试_100%账号", AccountType.PERSONAL);
        account("维度测试X100Y账号", AccountType.PERSONAL);

        assertThat(repository.accounts("_100%", 20)).extracting(AccountOption::id).containsExactly(literal.getId());
    }

    @Test
    void limitCutsTheListAndEmptyKeywordMatchesEverything() {
        account("维度测试限量-1", AccountType.PERSONAL);
        account("维度测试限量-2", AccountType.PERSONAL);
        account("维度测试限量-3", AccountType.PERSONAL);

        assertThat(repository.accounts("维度测试限量", 2)).hasSize(2);
        assertThat(repository.accounts("维度测试限量", 100)).hasSize(3);
        assertThat(repository.accounts("", 100)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(repository.accounts(null, 100)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void employeesAreSearchedByNameWithTheirDepartment() {
        Employee e = employee("维度测试员工-张三", "维度测试部");
        employee("维度测试员工-李四", null);

        assertThat(repository.employees("张三", 20)).containsExactly(
            new EmployeeOption(e.getId(), "维度测试员工-张三", "维度测试部"));
        assertThat(repository.employees("维度测试员工", 20)).hasSize(2);
    }

    @Test
    void pvRangeIsTheMinAndMaxSummaryDate() {
        Channel channel = channelRepository.save(Channel.builder().name("维度测试频道-范围").category("测试类").build());
        Account account = account("维度测试账号-范围", AccountType.PERSONAL);
        Content content = contentRepository.save(Content.builder().type(ContentType.ARTICLE)
            .channelId(channel.getId()).accountId(account.getId()).publishTime(LocalDateTime.of(2001, 1, 1, 0, 0))
            .popularityWeight(0.5).simulationBatchId("dim-test").build());
        for (LocalDate day : List.of(LocalDate.of(2001, 1, 5), LocalDate.of(2002, 2, 2))) {
            jdbc.update("INSERT INTO pv_summary_daily (content_id, summary_date, pv_count) VALUES (?, ?, 1)",
                content.getId(), Timestamp.valueOf(day.atStartOfDay()));
        }

        PvRange range = repository.pvRange();

        // 库里可能还有别的真实数据，所以只断言范围覆盖了这两天
        assertThat(range.from()).isNotNull().isBeforeOrEqualTo(LocalDate.of(2001, 1, 5));
        assertThat(range.to()).isNotNull().isAfterOrEqualTo(LocalDate.of(2002, 2, 2));
    }
}
```

`backend/src/test/java/com/jiaxian/analytics/dimension/DimensionControllerIT.java`：

```java
package com.jiaxian.analytics.dimension;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DimensionControllerIT {

    @Autowired private TestRestTemplate rest;
    @LocalServerPort private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void channelsEndpointReturnsTheSeededChannels() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/dimensions/channels"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody().size()).isGreaterThanOrEqualTo(15);
        JsonNode first = response.getBody().get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("name")).isTrue();
        assertThat(first.has("category")).isTrue();
    }

    @Test
    void accountAndEmployeeSearchesReturnArrays() {
        ResponseEntity<JsonNode> accounts = rest.getForEntity(
            url("/api/dimensions/accounts?keyword=不存在的维度关键字&limit=5"), JsonNode.class);
        ResponseEntity<JsonNode> employees = rest.getForEntity(
            url("/api/dimensions/employees?limit=3"), JsonNode.class);

        assertThat(accounts.getStatusCode().value()).isEqualTo(200);
        assertThat(accounts.getBody().isArray()).isTrue();
        assertThat(accounts.getBody()).isEmpty();
        assertThat(employees.getStatusCode().value()).isEqualTo(200);
        assertThat(employees.getBody().size()).isLessThanOrEqualTo(3);
    }

    @Test
    void limitOutsideTheSafeRangeIsRejected() {
        for (String path : new String[] {"/api/dimensions/accounts?limit=0", "/api/dimensions/accounts?limit=101",
            "/api/dimensions/employees?limit=0", "/api/dimensions/employees?limit=abc"}) {
            ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(response.getBody()).as(path).contains("limit");
        }
    }

    @Test
    void pvRangeEndpointReturnsFromAndToKeys() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/meta/pv-range"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("from")).isTrue();
        assertThat(response.getBody().has("to")).isTrue();
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='DimensionOptionsRepositoryIT,DimensionControllerIT'`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/dimension/ChannelOption.java`：

```java
package com.jiaxian.analytics.dimension;

public record ChannelOption(long id, String name, String category) {
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/AccountOption.java`：

```java
package com.jiaxian.analytics.dimension;

public record AccountOption(long id, String name, String type) {
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/EmployeeOption.java`：

```java
package com.jiaxian.analytics.dimension;

public record EmployeeOption(long id, String name, String dept) {
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/PvRange.java`：

```java
package com.jiaxian.analytics.dimension;

import java.time.LocalDate;

/** pv_summary_daily 里有数据的日期范围；没有数据时两个值都是 null。 */
public record PvRange(LocalDate from, LocalDate to) {
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/InvalidDimensionQueryException.java`：

```java
package com.jiaxian.analytics.dimension;

/** 请求参数非法，对应 HTTP 400。 */
public class InvalidDimensionQueryException extends RuntimeException {

    public InvalidDimensionQueryException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/DimensionRepository.java`：

```java
package com.jiaxian.analytics.dimension;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 前端筛选和选择器用的维度数据。账号/员工按名称模糊搜索：不区分大小写，关键字里的 % 和 _ 按字面匹配
 * （用 '!' 作转义字符）；LIMIT 以校验过的整数字面量写进 SQL（OpenGauss 不接受绑定参数形式的分页）。
 */
@Repository
public class DimensionRepository {

    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;

    public DimensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChannelOption> channels() {
        return jdbc.query("SELECT id, name, category FROM channel ORDER BY id",
            (rs, rowNum) -> new ChannelOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public List<AccountOption> accounts(String keyword, int limit) {
        requireSafeLimit(limit);
        return jdbc.query("SELECT id, name, type FROM account WHERE LOWER(name) LIKE ? ESCAPE '!'"
                + " ORDER BY name, id LIMIT " + limit,
            (rs, rowNum) -> new AccountOption(rs.getLong(1), rs.getString(2), rs.getString(3)),
            likePattern(keyword));
    }

    public List<EmployeeOption> employees(String keyword, int limit) {
        requireSafeLimit(limit);
        return jdbc.query("SELECT id, name, dept FROM employee WHERE LOWER(name) LIKE ? ESCAPE '!'"
                + " ORDER BY name, id LIMIT " + limit,
            (rs, rowNum) -> new EmployeeOption(rs.getLong(1), rs.getString(2), rs.getString(3)),
            likePattern(keyword));
    }

    /** 汇总表里最早和最晚的日期（A 兼容模式下 DATE 实际是 timestamp，所以走 getTimestamp）。 */
    public PvRange pvRange() {
        return jdbc.queryForObject("SELECT MIN(summary_date), MAX(summary_date) FROM pv_summary_daily",
            (rs, rowNum) -> new PvRange(toDate(rs.getTimestamp(1)), toDate(rs.getTimestamp(2))));
    }

    static String likePattern(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static LocalDate toDate(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate();
    }

    private static void requireSafeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/DimensionService.java`：

```java
package com.jiaxian.analytics.dimension;

import org.springframework.stereotype.Service;

import java.util.List;

/** 校验参数后转给仓库。 */
@Service
public class DimensionService {

    static final int MAX_LIMIT = 100;

    private final DimensionRepository repository;

    public DimensionService(DimensionRepository repository) {
        this.repository = repository;
    }

    public List<ChannelOption> channels() {
        return repository.channels();
    }

    public List<AccountOption> accounts(String keyword, int limit) {
        requireValidLimit(limit);
        return repository.accounts(keyword, limit);
    }

    public List<EmployeeOption> employees(String keyword, int limit) {
        requireValidLimit(limit);
        return repository.employees(keyword, limit);
    }

    public PvRange pvRange() {
        return repository.pvRange();
    }

    private static void requireValidLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidDimensionQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/DimensionController.java`：

```java
package com.jiaxian.analytics.dimension;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 前端筛选/选择器用的只读数据：频道、账号、员工选项和 PV 数据范围。 */
@RestController
@RequestMapping("/api")
public class DimensionController {

    private final DimensionService service;

    public DimensionController(DimensionService service) {
        this.service = service;
    }

    @GetMapping("/dimensions/channels")
    public List<ChannelOption> channels() {
        return service.channels();
    }

    /** keyword 省略表示不过滤；limit 范围 1~100，默认 20。 */
    @GetMapping("/dimensions/accounts")
    public List<AccountOption> accounts(@RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "20") int limit) {
        return service.accounts(keyword, limit);
    }

    @GetMapping("/dimensions/employees")
    public List<EmployeeOption> employees(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "20") int limit) {
        return service.employees(keyword, limit);
    }

    /** PV 汇总表里有数据的日期范围，没有数据时 from/to 都是 null。 */
    @GetMapping("/meta/pv-range")
    public PvRange pvRange() {
        return service.pvRange();
    }
}
```

`backend/src/main/java/com/jiaxian/analytics/dimension/DimensionExceptionHandler.java`：

```java
package com.jiaxian.analytics.dimension;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于维度接口：参数问题统一返回 400 + 一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = DimensionController.class)
public class DimensionExceptionHandler {

    @ExceptionHandler(InvalidDimensionQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidDimensionQueryException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("Invalid value for parameter '" + e.getName() + "'");
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='DimensionOptionsRepositoryIT,DimensionControllerIT'`
Expected: `DimensionOptionsRepositoryIT` 6 个、`DimensionControllerIT` 4 个，全部通过。

如果 `ESCAPE '!'` 或 `LOWER(name) LIKE` 在 OpenGauss 上报语法错误，用 gsql 直接试那条 SQL（`SELECT id FROM account WHERE LOWER(name) LIKE '%a!_b%' ESCAPE '!'`），改成等价写法并在提交说明里写清楚。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add dimension options and PV data range endpoints

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 后端——排行榜周期列表接口

**Files:**
- Create: `backend/src/main/java/com/jiaxian/analytics/leaderboard/PeriodOption.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardService.java`
- Modify: `backend/src/main/java/com/jiaxian/analytics/leaderboard/LeaderboardController.java`
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardServiceTest.java`（追加）
- Test: `backend/src/test/java/com/jiaxian/analytics/leaderboard/LeaderboardControllerIT.java`（追加）

- [ ] **Step 1: 追加测试**

在 `LeaderboardServiceTest.java` 末尾（最后一个 `}` 之前）加：

```java
    // ---- periods ----

    @Test
    void periodsListsLedgerPeriodsPlusTheCurrentOneNewestFirst() {
        when(repository.periodsWithLedger(PeriodType.MONTH))
            .thenReturn(List.of(d(2026, 1, 1), d(2026, 2, 1), d(2026, 10, 1)));

        assertThat(service.periods(PeriodType.MONTH)).containsExactly(
            new PeriodOption("2026-10", Leaderboard.Status.IN_PROGRESS),
            new PeriodOption("2026-02", Leaderboard.Status.ARCHIVED),
            new PeriodOption("2026-01", Leaderboard.Status.ARCHIVED));
    }

    @Test
    void periodsAlwaysIncludeTheCurrentPeriodEvenWithoutLedgerRows() {
        when(repository.periodsWithLedger(PeriodType.MONTH)).thenReturn(List.of(d(2026, 1, 1)));
        when(repository.periodsWithLedger(PeriodType.YEAR)).thenReturn(List.of(d(2025, 1, 1)));

        assertThat(service.periods(PeriodType.MONTH)).containsExactly(
            new PeriodOption("2026-10", Leaderboard.Status.IN_PROGRESS),
            new PeriodOption("2026-01", Leaderboard.Status.ARCHIVED));
        assertThat(service.periods(PeriodType.YEAR)).containsExactly(
            new PeriodOption("2026", Leaderboard.Status.IN_PROGRESS),
            new PeriodOption("2025", Leaderboard.Status.ARCHIVED));
    }
```

在 `LeaderboardControllerIT.java` 末尾（最后一个 `}` 之前）加：

```java
    @Test
    void periodsEndpointStartsWithTheCurrentPeriodInProgress() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/api/leaderboard/month/periods"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().isArray()).isTrue();
        JsonNode first = response.getBody().get(0);
        assertThat(first.get("periodValue").asText())
            .isEqualTo(PeriodType.MONTH.format(PeriodType.MONTH.containing(LocalDate.now())));
        assertThat(first.get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void periodsEndpointRejectsAnUnknownType() {
        assertGetBadRequest("/api/leaderboard/week/periods", "month");
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn test -Dtest='LeaderboardServiceTest,LeaderboardControllerIT'`
Expected: 编译失败（`PeriodOption`、`periods` 不存在）。

- [ ] **Step 3: 写实现**

`backend/src/main/java/com/jiaxian/analytics/leaderboard/PeriodOption.java`：

```java
package com.jiaxian.analytics.leaderboard;

/** 榜单周期选择器的一项。 */
public record PeriodOption(String periodValue, Leaderboard.Status status) {
}
```

在 `LeaderboardService.java` 里：把 `import java.util.List;` 保持，新增 `import java.util.Comparator;`、`import java.util.TreeSet;`，并在类末尾（`leaderboard` 方法之后）加：

```java
    /** 所有有积分流水的周期，再加上当前周期（即使还没有流水），按时间倒序。 */
    public List<PeriodOption> periods(PeriodType type) {
        LocalDate today = LocalDate.now(clock);
        TreeSet<LocalDate> starts = new TreeSet<>(Comparator.reverseOrder());
        starts.addAll(repository.periodsWithLedger(type));
        starts.add(type.containing(today));
        return starts.stream()
            .map(start -> new PeriodOption(type.format(start),
                type.nextStart(start).isAfter(today) ? Leaderboard.Status.IN_PROGRESS : Leaderboard.Status.ARCHIVED))
            .toList();
    }
```

在 `LeaderboardController.java` 里，`leaderboard` 方法之后加（并补 `import java.util.List;`）：

```java
    /** 周期选择器的数据：所有有流水的周期加当前周期，按时间倒序。type 只接受小写 month / year。 */
    @GetMapping("/{type}/periods")
    public List<PeriodOption> periods(@PathVariable String type) {
        return service.periods(PeriodType.fromPath(type));
    }
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd backend && mvn test -Dtest='LeaderboardServiceTest,LeaderboardControllerIT'`
Expected: 全部通过（`LeaderboardServiceTest` 变为 8 个，`LeaderboardControllerIT` 变为 8 个）。

- [ ] **Step 5: 完整测试并提交**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`。

```bash
git add backend/src
git commit -m "feat: add leaderboard period list endpoint

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 前端工具链、路由与整体布局

**Files:**
- Modify: `frontend/package.json`（`npm install` 自动改）、`frontend/vite.config.ts`、`frontend/src/main.ts`、`frontend/src/App.vue`
- Create: `frontend/src/router/index.ts`、`frontend/src/styles/theme.css`、`frontend/src/components/HealthBadge.vue`、`frontend/src/views/OverviewView.vue`（占位）、`frontend/src/views/LeaderboardView.vue`（占位）

- [ ] **Step 1: 安装依赖**

```bash
cd frontend
npm install vue-router@4 element-plus echarts
npm install -D vitest@^2.1.9 unplugin-auto-import unplugin-vue-components
```

Expected: 安装成功。然后在 `package.json` 的 `scripts` 里加一行 `"test": "vitest run"`（紧跟在 `"build"` 之后）。

确认版本：`npm ls vite vitest element-plus echarts vue-router`，应显示 `vite@5.x`、`vitest@2.x`；如果 `unplugin-*` 因为 peer 依赖报冲突，用 `--legacy-peer-deps` 重装并在提交说明里写明。

- [ ] **Step 2: 写配置和骨架**

`frontend/vite.config.ts`：

```ts
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

// 开发时把 /api 代理到后端；后端端口通过 VITE_API_TARGET 指定（默认 8080）
const apiTarget = process.env.VITE_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()], dts: "src/auto-imports.d.ts" }),
    Components({ resolvers: [ElementPlusResolver()], dts: "src/components.d.ts" }),
  ],
  server: {
    port: 5173,
    proxy: {
      "/api": apiTarget,
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
```

`frontend/src/main.ts`：

```ts
import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import "./styles/theme.css";

createApp(App).use(router).mount("#app");
```

`frontend/src/router/index.ts`：

```ts
import { createRouter, createWebHistory } from "vue-router";

export default createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      name: "overview",
      component: () => import("../views/OverviewView.vue"),
      meta: { title: "总览看板" },
    },
    {
      path: "/leaderboard",
      name: "leaderboard",
      component: () => import("../views/LeaderboardView.vue"),
      meta: { title: "积分排行榜" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
```

`frontend/src/styles/theme.css`：

```css
:root {
  --color-primary: #2f6fed;
  --color-forecast: #f08c2c;
  --color-passive: #14a38b;
  --color-up: #d9363e;
  --color-down: #1a9b56;
  --color-text-muted: #6b7280;
  --color-bg: #f5f7fa;
  --color-border: #e5e7eb;
}

html,
body {
  margin: 0;
  background: var(--color-bg);
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif;
  color: #1f2937;
}

.app-shell {
  min-height: 100vh;
}

.app-aside {
  background: #1f2a44;
  color: #fff;
}

.app-aside .brand {
  padding: 20px 20px 16px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
}

.app-aside .brand small {
  font-size: 12px;
  font-weight: 400;
  color: #aab4cc;
}

.app-menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #d6dcec;
  --el-menu-hover-bg-color: #2a3858;
  --el-menu-active-color: #fff;
}

.app-menu .el-menu-item.is-active {
  background: var(--color-primary);
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--color-border);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 16px;
}

.panel-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.muted {
  color: var(--color-text-muted);
  font-size: 12px;
}

.rate-up {
  color: var(--color-up);
}

.rate-down {
  color: var(--color-down);
}

.rate-none {
  color: var(--color-text-muted);
}
```

`frontend/src/components/HealthBadge.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchHealth } from "../api/health";

const status = ref<"checking" | "ok" | "down">("checking");

onMounted(async () => {
  try {
    await fetchHealth();
    status.value = "ok";
  } catch {
    status.value = "down";
  }
});
</script>

<template>
  <el-tag :type="status === 'ok' ? 'success' : status === 'down' ? 'danger' : 'info'" size="small">
    后端{{ status === "ok" ? "正常" : status === "down" ? "不可用" : "检查中" }}
  </el-tag>
</template>
```

`frontend/src/App.vue`：

```vue
<script setup lang="ts">
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { useRoute } from "vue-router";
import HealthBadge from "./components/HealthBadge.vue";

const route = useRoute();
</script>

<template>
  <el-config-provider :locale="zhCn">
    <el-container class="app-shell">
      <el-aside width="200px" class="app-aside">
        <div class="brand">稼先社区<br /><small>运营分析看板</small></div>
        <el-menu :default-active="route.path" router class="app-menu">
          <el-menu-item index="/">总览看板</el-menu-item>
          <el-menu-item index="/leaderboard">积分排行榜</el-menu-item>
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

两个占位页面（后面的任务会整体替换）：

`frontend/src/views/OverviewView.vue`：

```vue
<template>
  <div class="panel">总览看板（建设中）</div>
</template>
```

`frontend/src/views/LeaderboardView.vue`：

```vue
<template>
  <div class="panel">积分排行榜（建设中）</div>
</template>
```

- [ ] **Step 3: 构建验证**

在 `frontend/tsconfig.json` 的 `compilerOptions` 里加 `"skipLibCheck": true`（Element Plus 的类型声明在加入自动导入的 `components.d.ts` 后会让 `vue-tsc` 对库文件报 `GlobalComponents` 类型错误，跳过库文件检查是这类项目的标配）。

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，`vite build` 成功，生成 `src/components.d.ts` 和 `src/auto-imports.d.ts`。

Run: `cd frontend && npm test`
Expected: 此时没有测试文件，`vitest run` 会以"No test files found"退出码 1，所以 test 脚本写成 `"test": "vitest run --passWithNoTests"`，重新运行，Expected: 退出码 0。

- [ ] **Step 4: 提交**

```bash
git add frontend
git commit -m "feat: set up frontend tooling, router and app layout

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 接口封装与纯逻辑工具（含单测）

**Files:**
- Create: `frontend/src/api/{query,http,types,analytics,leaderboard}.ts`
- Create: `frontend/src/utils/{format,dateRange,kpi,trendTable}.ts`
- Create: `frontend/src/composables/useAsync.ts`
- Test: `frontend/src/api/{query,http}.test.ts`、`frontend/src/utils/{format,dateRange,kpi,trendTable}.test.ts`、`frontend/src/composables/useAsync.test.ts`

- [ ] **Step 1: 写测试**

`frontend/src/api/query.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { buildQuery } from "./query";

describe("buildQuery", () => {
  it("returns an empty string when there are no usable params", () => {
    expect(buildQuery()).toBe("");
    expect(buildQuery({ a: undefined, b: null, c: "" })).toBe("");
  });

  it("skips empty values and keeps numbers, booleans and strings", () => {
    expect(buildQuery({ a: 1, b: "x", c: false, d: undefined, e: 0 })).toBe("?a=1&b=x&c=false&e=0");
  });

  it("encodes special characters", () => {
    expect(buildQuery({ keyword: "张 三&1" })).toBe("?keyword=%E5%BC%A0+%E4%B8%89%261");
  });
});
```

`frontend/src/api/http.test.ts`：

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson } from "./http";

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
```

`frontend/src/utils/format.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { formatNumber, formatPeriod, formatRate, rateClass } from "./format";

describe("format", () => {
  it("formats numbers as rounded integers with thousands separators", () => {
    expect(formatNumber(1234567.4)).toBe("1,234,567");
    expect(formatNumber(0)).toBe("0");
    expect(formatNumber(999.5)).toBe("1,000");
  });

  it("formats rates as signed percentages and shows a dash when missing", () => {
    expect(formatRate(0.05)).toBe("+5.0%");
    expect(formatRate(-0.123)).toBe("-12.3%");
    expect(formatRate(0)).toBe("0.0%");
    expect(formatRate(null)).toBe("—");
    expect(formatRate(undefined)).toBe("—");
  });

  it("labels periods by granularity", () => {
    expect(formatPeriod("2026-03-05", "DAY")).toBe("2026-03-05");
    expect(formatPeriod("2026-03-02", "WEEK")).toBe("2026-03-02 周");
    expect(formatPeriod("2026-03-01", "MONTH")).toBe("2026-03");
    expect(formatPeriod("2026-01-01", "YEAR")).toBe("2026");
  });

  it("classifies the direction of a rate", () => {
    expect(rateClass(0.1)).toBe("rate-up");
    expect(rateClass(-0.1)).toBe("rate-down");
    expect(rateClass(0)).toBe("rate-none");
    expect(rateClass(null)).toBe("rate-none");
  });
});
```

`frontend/src/utils/dateRange.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import {
  defaultRange,
  parseIsoDate,
  pickerType,
  pickerValue,
  rangeFromPicker,
  toIsoDate,
} from "./dateRange";

const DATA = { from: "2025-01-01", to: "2026-03-27" };

describe("iso dates", () => {
  it("round-trips local dates without timezone shifts", () => {
    expect(toIsoDate(new Date(2026, 2, 5))).toBe("2026-03-05");
    expect(toIsoDate(parseIsoDate("2026-12-31"))).toBe("2026-12-31");
  });
});

describe("defaultRange", () => {
  it("ends at the last data day and goes back by granularity", () => {
    expect(defaultRange("DAY", DATA)).toEqual({ from: "2026-02-26", to: "2026-03-27" });      // 30 天
    expect(defaultRange("WEEK", DATA)).toEqual({ from: "2025-10-03", to: "2026-03-27" });     // 26 周
    expect(defaultRange("MONTH", DATA)).toEqual({ from: "2025-04-01", to: "2026-03-27" });    // 12 个月
    // 年：5 年 = 2022-01-01，但数据从 2025-01-01 才开始，所以被截断
    expect(defaultRange("YEAR", DATA)).toEqual({ from: "2025-01-01", to: "2026-03-27" });
    expect(defaultRange("YEAR", { from: "2020-01-01", to: "2026-03-27" }))
      .toEqual({ from: "2022-01-01", to: "2026-03-27" });
  });

  it("never starts before the first day with data", () => {
    expect(defaultRange("MONTH", { from: "2025-09-15", to: "2026-03-27" }))
      .toEqual({ from: "2025-09-15", to: "2026-03-27" });
  });

  it("falls back to today when there is no data range", () => {
    expect(defaultRange("MONTH", null, new Date(2026, 9, 4))).toEqual({ from: "2025-11-01", to: "2026-10-04" });
    expect(defaultRange("DAY", { from: null, to: null }, new Date(2026, 9, 4)))
      .toEqual({ from: "2026-09-05", to: "2026-10-04" });
  });
});

describe("date picker conversion", () => {
  it("uses the matching picker type per granularity", () => {
    expect(pickerType("DAY")).toBe("daterange");
    expect(pickerType("WEEK")).toBe("daterange");
    expect(pickerType("MONTH")).toBe("monthrange");
    expect(pickerType("YEAR")).toBe("yearrange");
  });

  it("expands month and year picks to whole periods", () => {
    expect(rangeFromPicker("MONTH", [new Date(2025, 2, 15), new Date(2026, 1, 10)]))
      .toEqual({ from: "2025-03-01", to: "2026-02-28" });
    expect(rangeFromPicker("MONTH", [new Date(2024, 0, 1), new Date(2024, 1, 1)]))
      .toEqual({ from: "2024-01-01", to: "2024-02-29" });
    expect(rangeFromPicker("YEAR", [new Date(2022, 6, 1), new Date(2025, 1, 2)]))
      .toEqual({ from: "2022-01-01", to: "2025-12-31" });
    expect(rangeFromPicker("DAY", [new Date(2026, 2, 1), new Date(2026, 2, 9)]))
      .toEqual({ from: "2026-03-01", to: "2026-03-09" });
  });

  it("turns a range back into picker dates", () => {
    const [a, b] = pickerValue("DAY", { from: "2026-03-01", to: "2026-03-09" });
    expect(toIsoDate(a)).toBe("2026-03-01");
    expect(toIsoDate(b)).toBe("2026-03-09");
  });
});
```

`frontend/src/utils/kpi.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { PvTrend } from "../api/types";
import { deriveKpis } from "./kpi";

const trend: PvTrend = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-04-30",
  points: [
    { periodStart: "2026-01-01", pv: 100, momRate: null, yoyRate: 0.5, complete: true },
    { periodStart: "2026-02-01", pv: 120, momRate: 0.2, yoyRate: null, complete: true },
    { periodStart: "2026-03-01", pv: 60, momRate: -0.5, yoyRate: null, complete: false },
  ],
  forecast: [
    { periodStart: "2026-03-01", value: 130, lower: 110, upper: 150 },
    { periodStart: "2026-04-01", value: 140, lower: 120, upper: 160 },
  ],
};

describe("deriveKpis", () => {
  it("sums the points, picks the last complete period and the first forecast", () => {
    const kpis = deriveKpis(trend);

    expect(kpis.totalPv).toBe(280);
    expect(kpis.latest?.periodStart).toBe("2026-02-01");
    expect(kpis.latest?.momRate).toBe(0.2);
    expect(kpis.nextForecast?.periodStart).toBe("2026-03-01");
  });

  it("has no latest period when nothing is complete and no forecast when there is none", () => {
    const kpis = deriveKpis({
      ...trend,
      points: [{ periodStart: "2026-03-01", pv: 60, momRate: null, yoyRate: null, complete: false }],
      forecast: [],
    });

    expect(kpis.latest).toBeNull();
    expect(kpis.nextForecast).toBeNull();
    expect(kpis.totalPv).toBe(60);
  });
});
```

`frontend/src/utils/trendTable.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { PvTrend } from "../api/types";
import { buildTrendRows } from "./trendTable";

const trend: PvTrend = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-04-30",
  points: [
    { periodStart: "2026-01-01", pv: 100, momRate: null, yoyRate: 0.5, complete: true },
    { periodStart: "2026-02-01", pv: 120, momRate: 0.2, yoyRate: null, complete: true },
    { periodStart: "2026-03-01", pv: 60, momRate: -0.5, yoyRate: null, complete: false },
  ],
  forecast: [
    { periodStart: "2026-03-01", value: 130, lower: 110, upper: 150 },
    { periodStart: "2026-04-01", value: 140, lower: 120, upper: 160 },
  ],
};

describe("buildTrendRows", () => {
  it("lists forecast and actual rows newest first, forecast before actual for the same period", () => {
    const rows = buildTrendRows(trend);

    expect(rows.map((r) => r.key)).toEqual([
      "forecast-2026-04-01",
      "forecast-2026-03-01",
      "actual-2026-03-01",
      "actual-2026-02-01",
      "actual-2026-01-01",
    ]);
  });

  it("labels each row and fills the range only for forecasts", () => {
    const rows = buildTrendRows(trend);

    expect(rows[0]).toMatchObject({ label: "2026-04", pv: 140, kind: "forecast", range: "120 ~ 160", momRate: null });
    expect(rows[2]).toMatchObject({ pv: 60, kind: "incomplete", momRate: -0.5, range: null });
    expect(rows[3]).toMatchObject({ pv: 120, kind: "complete", momRate: 0.2, yoyRate: null });
  });

  it("handles an empty forecast", () => {
    expect(buildTrendRows({ ...trend, forecast: [] }).every((r) => r.kind !== "forecast")).toBe(true);
  });
});
```

`frontend/src/composables/useAsync.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import { useAsync } from "./useAsync";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("useAsync", () => {
  it("tracks loading, data and errors", async () => {
    const ok = useAsync(async (n: number) => n * 2);
    const pending = ok.run(21);
    expect(ok.loading.value).toBe(true);
    await pending;
    expect(ok.loading.value).toBe(false);
    expect(ok.data.value).toBe(42);
    expect(ok.error.value).toBeNull();

    const failing = useAsync(async () => {
      throw new Error("boom");
    });
    await failing.run();
    expect(failing.error.value).toBe("boom");
    expect(failing.data.value).toBeNull();
    expect(failing.loading.value).toBe(false);
  });

  it("ignores a stale response that arrives after a newer request", async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const queue = [first, second];
    const state = useAsync((_label: string) => queue.shift()!.promise);

    const run1 = state.run("old");
    const run2 = state.run("new");
    second.resolve("new result");
    await run2;
    first.resolve("old result");
    await run1;

    expect(state.data.value).toBe("new result");
    expect(state.loading.value).toBe(false);
  });

  it("clears a previous error when a new request starts", async () => {
    let fail = true;
    const state = useAsync(async () => {
      if (fail) {
        throw new Error("first fails");
      }
      return "fine";
    });

    await state.run();
    expect(state.error.value).toBe("first fails");
    fail = false;
    const pending = state.run();
    expect(state.error.value).toBeNull();
    await pending;
    expect(state.data.value).toBe("fine");
  });
});
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd frontend && npm test`
Expected: 所有测试文件因为模块不存在而失败。

- [ ] **Step 3: 写实现**

`frontend/src/api/query.ts`：

```ts
export type QueryParams = Record<string, string | number | boolean | null | undefined>;

/** 把参数对象转成 "?a=1&b=x"，跳过 null、undefined 和空字符串；没有可用参数时返回空字符串。 */
export function buildQuery(params: QueryParams = {}): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") {
      continue;
    }
    search.append(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}
```

`frontend/src/api/http.ts`：

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
```

`frontend/src/api/types.ts`：

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
```

`frontend/src/api/analytics.ts`：

```ts
import { getJson } from "./http";
import type {
  AccountOption,
  ChannelOption,
  EmployeeOption,
  Granularity,
  PvDimension,
  PvFilter,
  PvRange,
  PvTopEntry,
  PvTrend,
} from "./types";

export interface TrendQuery extends PvFilter {
  granularity: Granularity;
  from: string;
  to: string;
  forecastPeriods: number;
}

export interface TopQuery extends PvFilter {
  dimension: PvDimension;
  from: string;
  to: string;
  limit: number;
}

export function fetchTrend(query: TrendQuery): Promise<PvTrend> {
  return getJson<PvTrend>("/api/analytics/pv/trend", { ...query });
}

export function fetchTop(query: TopQuery): Promise<PvTopEntry[]> {
  return getJson<PvTopEntry[]>("/api/analytics/pv/top", { ...query });
}

export function fetchPvRange(): Promise<PvRange> {
  return getJson<PvRange>("/api/meta/pv-range");
}

export function fetchChannels(): Promise<ChannelOption[]> {
  return getJson<ChannelOption[]>("/api/dimensions/channels");
}

export function searchAccounts(keyword: string, limit = 20): Promise<AccountOption[]> {
  return getJson<AccountOption[]>("/api/dimensions/accounts", { keyword, limit });
}

export function searchEmployees(keyword: string, limit = 20): Promise<EmployeeOption[]> {
  return getJson<EmployeeOption[]>("/api/dimensions/employees", { keyword, limit });
}
```

`frontend/src/api/leaderboard.ts`：

```ts
import { getJson } from "./http";
import type { Leaderboard, PeriodOption, PeriodType } from "./types";

export function fetchLeaderboard(type: PeriodType, period: string, limit: number): Promise<Leaderboard> {
  return getJson<Leaderboard>(`/api/leaderboard/${type}`, { period, limit });
}

export function fetchPeriods(type: PeriodType): Promise<PeriodOption[]> {
  return getJson<PeriodOption[]>(`/api/leaderboard/${type}/periods`);
}
```

`frontend/src/utils/format.ts`：

```ts
import type { Granularity } from "../api/types";

/** 四舍五入成整数并加千分位。 */
export function formatNumber(value: number): string {
  return Math.round(value).toLocaleString("zh-CN");
}

/** 0.05 → "+5.0%"，-0.123 → "-12.3%"，缺失 → "—"。 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) {
    return "—";
  }
  const percent = rate * 100;
  return `${percent > 0 ? "+" : ""}${percent.toFixed(1)}%`;
}

/** 周期起点的展示文字：日 yyyy-MM-dd，周 "yyyy-MM-dd 周"，月 yyyy-MM，年 yyyy。 */
export function formatPeriod(periodStart: string, granularity: Granularity): string {
  switch (granularity) {
    case "DAY":
      return periodStart;
    case "WEEK":
      return `${periodStart} 周`;
    case "MONTH":
      return periodStart.slice(0, 7);
    case "YEAR":
      return periodStart.slice(0, 4);
  }
}

/** 涨红跌绿（国内习惯），没有或为 0 用灰色。 */
export function rateClass(rate: number | null | undefined): "rate-up" | "rate-down" | "rate-none" {
  if (rate === null || rate === undefined || rate === 0) {
    return "rate-none";
  }
  return rate > 0 ? "rate-up" : "rate-down";
}
```

`frontend/src/utils/dateRange.ts`：

```ts
import type { Granularity, PvRange } from "../api/types";

export interface DateRange {
  from: string;
  to: string;
}

export type PickerType = "daterange" | "monthrange" | "yearrange";

const pad = (n: number): string => String(n).padStart(2, "0");

/** 本地时间的 yyyy-MM-dd（不要用 toISOString，它会转成 UTC，在东八区会差一天）。 */
export function toIsoDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function parseIsoDate(iso: string): Date {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function addDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return result;
}

/**
 * 默认范围：以数据范围的最后一天为终点，往前取日 30 天、周 26 周、月 12 个月、年 5 年，
 * 起点不早于数据范围的第一天；没有数据范围时以 today 为终点。
 */
export function defaultRange(
  granularity: Granularity,
  pvRange: PvRange | null,
  today: Date = new Date(),
): DateRange {
  const end = pvRange?.to ? parseIsoDate(pvRange.to) : today;
  let start: Date;
  switch (granularity) {
    case "DAY":
      start = addDays(end, -29);
      break;
    case "WEEK":
      start = addDays(end, -7 * 25);
      break;
    case "MONTH":
      start = new Date(end.getFullYear(), end.getMonth() - 11, 1);
      break;
    case "YEAR":
      start = new Date(end.getFullYear() - 4, 0, 1);
      break;
  }
  if (pvRange?.from) {
    const earliest = parseIsoDate(pvRange.from);
    if (start < earliest) {
      start = earliest;
    }
  }
  return { from: toIsoDate(start), to: toIsoDate(end) };
}

export function pickerType(granularity: Granularity): PickerType {
  switch (granularity) {
    case "MONTH":
      return "monthrange";
    case "YEAR":
      return "yearrange";
    default:
      return "daterange";
  }
}

/** 选择器选出的两个日期换算成请求范围：月选择取整月，年选择取整年。 */
export function rangeFromPicker(granularity: Granularity, [start, end]: [Date, Date]): DateRange {
  switch (granularity) {
    case "MONTH":
      return {
        from: toIsoDate(new Date(start.getFullYear(), start.getMonth(), 1)),
        to: toIsoDate(new Date(end.getFullYear(), end.getMonth() + 1, 0)),
      };
    case "YEAR":
      return {
        from: toIsoDate(new Date(start.getFullYear(), 0, 1)),
        to: toIsoDate(new Date(end.getFullYear(), 11, 31)),
      };
    default:
      return { from: toIsoDate(start), to: toIsoDate(end) };
  }
}

export function pickerValue(_granularity: Granularity, range: DateRange): [Date, Date] {
  return [parseIsoDate(range.from), parseIsoDate(range.to)];
}
```

`frontend/src/utils/kpi.ts`：

```ts
import type { ForecastPoint, PvPoint, PvTrend } from "../api/types";

export interface Kpis {
  /** 趋势里所有周期的 PV 合计（周期按粒度向前/向后扩展过，所以可能略多于所选日期范围）。 */
  totalPv: number;
  /** 最近一个已结束的周期，没有则为 null。 */
  latest: PvPoint | null;
  /** 第一期预测，没有预测则为 null。 */
  nextForecast: ForecastPoint | null;
}

export function deriveKpis(trend: PvTrend): Kpis {
  const totalPv = trend.points.reduce((sum, point) => sum + point.pv, 0);
  let latest: PvPoint | null = null;
  for (let i = trend.points.length - 1; i >= 0; i--) {
    if (trend.points[i].complete) {
      latest = trend.points[i];
      break;
    }
  }
  return { totalPv, latest, nextForecast: trend.forecast[0] ?? null };
}
```

`frontend/src/utils/trendTable.ts`：

```ts
import type { PvTrend } from "../api/types";
import { formatNumber, formatPeriod } from "./format";

export interface TrendRow {
  key: string;
  label: string;
  pv: number;
  momRate: number | null;
  yoyRate: number | null;
  kind: "forecast" | "incomplete" | "complete";
  /** 预测行的置信区间，如 "120 ~ 160"；其他行为 null。 */
  range: string | null;
}

/** 明细表的行：预测行和实际行混排，按周期倒序；同一周期预测行排在实际行前面。 */
export function buildTrendRows(trend: PvTrend): TrendRow[] {
  const entries: Array<{ start: string; order: number; row: TrendRow }> = [];
  for (const f of trend.forecast) {
    entries.push({
      start: f.periodStart,
      order: 1,
      row: {
        key: `forecast-${f.periodStart}`,
        label: formatPeriod(f.periodStart, trend.granularity),
        pv: f.value,
        momRate: null,
        yoyRate: null,
        kind: "forecast",
        range: `${formatNumber(f.lower)} ~ ${formatNumber(f.upper)}`,
      },
    });
  }
  for (const p of trend.points) {
    entries.push({
      start: p.periodStart,
      order: 0,
      row: {
        key: `actual-${p.periodStart}`,
        label: formatPeriod(p.periodStart, trend.granularity),
        pv: p.pv,
        momRate: p.momRate,
        yoyRate: p.yoyRate,
        kind: p.complete ? "complete" : "incomplete",
        range: null,
      },
    });
  }
  entries.sort((a, b) => (a.start === b.start ? b.order - a.order : a.start < b.start ? 1 : -1));
  return entries.map((entry) => entry.row);
}
```

`frontend/src/composables/useAsync.ts`：

```ts
import { ref, shallowRef, type Ref } from "vue";

/**
 * 封装"加载中 / 数据 / 错误"。同一个实例连续发起多次请求时，只采用最后一次发起的请求的结果，
 * 先发后到的旧响应会被忽略。
 */
export function useAsync<A extends unknown[], T>(fn: (...args: A) => Promise<T>) {
  const data = shallowRef<T | null>(null) as Ref<T | null>;
  const loading = ref(false);
  const error = ref<string | null>(null);
  let latest = 0;

  async function run(...args: A): Promise<void> {
    const ticket = ++latest;
    loading.value = true;
    error.value = null;
    try {
      const result = await fn(...args);
      if (ticket === latest) {
        data.value = result;
      }
    } catch (e) {
      if (ticket === latest) {
        error.value = e instanceof Error ? e.message : String(e);
        data.value = null;
      }
    } finally {
      if (ticket === latest) {
        loading.value = false;
      }
    }
  }

  return { data, loading, error, run };
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd frontend && npm test`
Expected: 所有测试通过（共 8 个文件）。

Run: `cd frontend && npm run build`
Expected: 类型检查和构建通过。

- [ ] **Step 5: 提交**

```bash
git add frontend
git commit -m "feat: add frontend API client and pure logic with unit tests

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 图表配置生成函数（含单测）

**Files:**
- Create: `frontend/src/charts/{colors,trendOption,topBarOption,leaderboardOption}.ts`
- Test: `frontend/src/charts/{trendOption,topBarOption,leaderboardOption}.test.ts`

- [ ] **Step 1: 写测试**

`frontend/src/charts/trendOption.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { PvTrend } from "../api/types";
import { buildTrendOption, trendTooltip } from "./trendOption";

const trend: PvTrend = {
  granularity: "MONTH",
  from: "2026-01-01",
  to: "2026-04-30",
  points: [
    { periodStart: "2026-01-01", pv: 100, momRate: null, yoyRate: 0.5, complete: true },
    { periodStart: "2026-02-01", pv: 120, momRate: 0.2, yoyRate: null, complete: true },
    { periodStart: "2026-03-01", pv: 60, momRate: -0.5, yoyRate: null, complete: false },
  ],
  forecast: [
    { periodStart: "2026-03-01", value: 130, lower: 110, upper: 150 },
    { periodStart: "2026-04-01", value: 140, lower: 120, upper: 160 },
  ],
};

type Series = { name: string; data: unknown[] };

function seriesOf(option: ReturnType<typeof buildTrendOption>): Series[] {
  return option.series as unknown as Series[];
}

describe("buildTrendOption", () => {
  it("uses the union of actual and forecast periods as the x axis", () => {
    const option = buildTrendOption(trend);

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02", "2026-03", "2026-04"]);
  });

  it("draws incomplete periods as hollow points and leaves forecast-only periods empty", () => {
    const actual = seriesOf(buildTrendOption(trend)).find((s) => s.name === "实际")!;

    expect(actual.data[0]).toBe(100);
    expect(actual.data[1]).toBe(120);
    expect(actual.data[2]).toMatchObject({ value: 60, itemStyle: { color: "#fff" } });
    expect(actual.data[3]).toBeNull();
  });

  it("starts the dashed forecast line at the last complete period", () => {
    const forecast = seriesOf(buildTrendOption(trend)).find((s) => s.name === "预测")!;

    expect(forecast.data).toEqual([null, 120, 130, 140]);
  });

  it("stacks a transparent base and the band width for the confidence interval", () => {
    const series = seriesOf(buildTrendOption(trend));
    const base = series.find((s) => s.name === "区间下限")!;
    const band = series.find((s) => s.name === "预测区间")!;

    expect(base.data).toEqual([null, 120, 110, 120]);
    expect(band.data).toEqual([null, 0, 40, 40]);
  });

  it("only lists the visible series in the legend", () => {
    const legend = buildTrendOption(trend).legend as { data: string[] };

    expect(legend.data).toEqual(["实际", "预测", "预测区间"]);
  });

  it("has no forecast data when there is no forecast", () => {
    const option = buildTrendOption({ ...trend, forecast: [] });
    const series = seriesOf(option);

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-01", "2026-02", "2026-03"]);
    expect(series.find((s) => s.name === "预测")!.data.every((v) => v === null)).toBe(true);
    expect(series.find((s) => s.name === "预测区间")!.data.every((v) => v === null)).toBe(true);
  });

  it("builds the axis from forecast periods alone when there are no points", () => {
    const option = buildTrendOption({ ...trend, points: [] });

    expect((option.xAxis as { data: string[] }).data).toEqual(["2026-03", "2026-04"]);
  });
});

describe("trendTooltip", () => {
  const starts = ["2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01"];

  it("shows actual value with month-over-month and year-over-year", () => {
    const text = trendTooltip(trend, starts, 0);

    expect(text).toContain("2026-01");
    expect(text).toContain("实际 PV：100");
    expect(text).toContain("环比：—");
    expect(text).toContain("同比：+50.0%");
    expect(text).not.toContain("预测");
  });

  it("marks an in-progress period and shows its forecast too", () => {
    const text = trendTooltip(trend, starts, 2);

    expect(text).toContain("实际 PV：60（进行中）");
    expect(text).toContain("环比：-50.0%");
    expect(text).toContain("预测 PV：130");
    expect(text).toContain("置信区间：110 ~ 150");
  });

  it("shows only the forecast for future periods", () => {
    const text = trendTooltip(trend, starts, 3);

    expect(text).toContain("预测 PV：140");
    expect(text).not.toContain("实际");
  });
});
```

`frontend/src/charts/topBarOption.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { PvTopEntry } from "../api/types";
import { buildTopBarOption } from "./topBarOption";

const entries: PvTopEntry[] = [
  { id: 7, name: "半导体", pv: 3456 },
  { id: 3, name: "AI学习", pv: 1200 },
];

describe("buildTopBarOption", () => {
  it("lists names top to bottom in the given order with their PV", () => {
    const option = buildTopBarOption(entries);
    const yAxis = option.yAxis as { data: string[]; inverse: boolean };
    const series = option.series as unknown as Array<{ type: string; data: number[] }>;

    expect(yAxis.data).toEqual(["半导体", "AI学习"]);
    expect(yAxis.inverse).toBe(true);
    expect(series[0].type).toBe("bar");
    expect(series[0].data).toEqual([3456, 1200]);
  });

  it("copes with an empty list", () => {
    const option = buildTopBarOption([]);

    expect((option.yAxis as { data: string[] }).data).toEqual([]);
  });
});
```

`frontend/src/charts/leaderboardOption.test.ts`：

```ts
import { describe, expect, it } from "vitest";
import type { LeaderboardEntry } from "../api/types";
import { buildLeaderboardOption } from "./leaderboardOption";

const entries: LeaderboardEntry[] = [
  { rank: 1, employeeId: 1, name: "员工-1", points: 200, activePoints: 100, passivePoints: 100 },
  { rank: 2, employeeId: 2, name: "员工-2", points: 150, activePoints: 150, passivePoints: 0 },
];

type Series = { name: string; stack: string; data: number[] };

describe("buildLeaderboardOption", () => {
  it("stacks active and passive points per person, top rank first", () => {
    const option = buildLeaderboardOption(entries);
    const series = option.series as unknown as Series[];

    expect((option.yAxis as { data: string[]; inverse: boolean }).data).toEqual(["员工-1", "员工-2"]);
    expect((option.yAxis as { inverse: boolean }).inverse).toBe(true);
    expect(series.map((s) => s.name)).toEqual(["主动积分", "被动积分"]);
    expect(series[0].data).toEqual([100, 150]);
    expect(series[1].data).toEqual([100, 0]);
    expect(series[0].stack).toBe(series[1].stack);
  });
});
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd frontend && npm test`
Expected: 三个新测试文件因为模块不存在而失败。

- [ ] **Step 3: 写实现**

`frontend/src/charts/colors.ts`：

```ts
/** 与 styles/theme.css 里的 CSS 变量保持一致。 */
export const PRIMARY = "#2f6fed";
export const FORECAST = "#f08c2c";
export const FORECAST_BAND = "rgba(240, 140, 44, 0.18)";
export const ACTIVE = "#2f6fed";
export const PASSIVE = "#14a38b";
```

`frontend/src/charts/trendOption.ts`：

```ts
import type { EChartsOption } from "echarts";
import type { PvTrend } from "../api/types";
import { formatNumber, formatPeriod, formatRate } from "../utils/format";
import { FORECAST, FORECAST_BAND, PRIMARY } from "./colors";

/** 横轴：实际周期加上还没出现在其中的预测周期（预测的第一期可能就是进行中的那个周期）。 */
function periodStarts(trend: PvTrend): string[] {
  const starts = trend.points.map((point) => point.periodStart);
  for (const forecast of trend.forecast) {
    if (!starts.includes(forecast.periodStart)) {
      starts.push(forecast.periodStart);
    }
  }
  return starts;
}

/** 悬浮提示：实际值（含进行中标注、环比、同比）和预测值（含置信区间）。 */
export function trendTooltip(trend: PvTrend, starts: string[], index: number): string {
  const start = starts[index];
  const point = trend.points.find((p) => p.periodStart === start);
  const forecast = trend.forecast.find((f) => f.periodStart === start);
  const lines = [`<strong>${formatPeriod(start, trend.granularity)}</strong>`];
  if (point) {
    lines.push(`实际 PV：${formatNumber(point.pv)}${point.complete ? "" : "（进行中）"}`);
    lines.push(`环比：${formatRate(point.momRate)}　同比：${formatRate(point.yoyRate)}`);
  }
  if (forecast) {
    lines.push(`预测 PV：${formatNumber(forecast.value)}`);
    lines.push(`置信区间：${formatNumber(forecast.lower)} ~ ${formatNumber(forecast.upper)}`);
  }
  return lines.join("<br/>");
}

/**
 * 趋势图：实线是实际值（进行中的周期用空心点）；虚线是预测，从最后一个已结束周期接出；
 * 置信区间用两条堆叠的线实现（透明的下限 + 浅色的区间宽度）。
 */
export function buildTrendOption(trend: PvTrend): EChartsOption {
  const starts = periodStarts(trend);
  const indexOf = (start: string): number => starts.indexOf(start);

  const actual: Array<number | null | { value: number; itemStyle: object }> = starts.map(() => null);
  for (const point of trend.points) {
    actual[indexOf(point.periodStart)] = point.complete
      ? point.pv
      : { value: point.pv, itemStyle: { color: "#fff", borderColor: PRIMARY, borderWidth: 2 } };
  }

  const forecastLine: Array<number | null> = starts.map(() => null);
  const bandBase: Array<number | null> = starts.map(() => null);
  const bandWidth: Array<number | null> = starts.map(() => null);
  const lastComplete = [...trend.points].reverse().find((point) => point.complete);
  if (trend.forecast.length > 0 && lastComplete) {
    const bridge = indexOf(lastComplete.periodStart);
    forecastLine[bridge] = lastComplete.pv;
    bandBase[bridge] = lastComplete.pv;
    bandWidth[bridge] = 0;
  }
  for (const forecast of trend.forecast) {
    const i = indexOf(forecast.periodStart);
    forecastLine[i] = forecast.value;
    bandBase[i] = forecast.lower;
    bandWidth[i] = forecast.upper - forecast.lower;
  }

  return {
    color: [PRIMARY, FORECAST],
    grid: { left: 64, right: 24, top: 44, bottom: 36 },
    legend: { data: ["实际", "预测", "预测区间"], top: 4 },
    tooltip: {
      trigger: "axis",
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (params: any) => trendTooltip(trend, starts, Array.isArray(params) ? params[0].dataIndex : params.dataIndex),
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: starts.map((start) => formatPeriod(start, trend.granularity)),
    },
    yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    series: [
      {
        name: "实际",
        type: "line",
        data: actual,
        symbolSize: 7,
        lineStyle: { color: PRIMARY, width: 2 },
        itemStyle: { color: PRIMARY },
      },
      {
        name: "预测",
        type: "line",
        data: forecastLine,
        symbolSize: 7,
        lineStyle: { color: FORECAST, width: 2, type: "dashed" },
        itemStyle: { color: FORECAST },
      },
      {
        name: "区间下限",
        type: "line",
        stack: "band",
        data: bandBase,
        symbol: "none",
        lineStyle: { opacity: 0 },
        silent: true,
        tooltip: { show: false },
      },
      {
        name: "预测区间",
        type: "line",
        stack: "band",
        data: bandWidth,
        symbol: "none",
        lineStyle: { opacity: 0 },
        areaStyle: { color: FORECAST_BAND },
        silent: true,
        tooltip: { show: false },
      },
    ],
  };
}
```

`frontend/src/charts/topBarOption.ts`：

```ts
import type { EChartsOption } from "echarts";
import type { PvTopEntry } from "../api/types";
import { formatNumber } from "../utils/format";
import { PRIMARY } from "./colors";

/** Top N 横向柱状图：第一名在最上面。 */
export function buildTopBarOption(entries: PvTopEntry[]): EChartsOption {
  return {
    grid: { left: 12, right: 72, top: 8, bottom: 8, containLabel: true },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    yAxis: { type: "category", inverse: true, data: entries.map((entry) => entry.name) },
    series: [
      {
        type: "bar",
        data: entries.map((entry) => entry.pv),
        barMaxWidth: 22,
        itemStyle: { color: PRIMARY },
        label: {
          show: true,
          position: "right",
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          formatter: (params: any) => formatNumber(params.value),
        },
      },
    ],
  };
}
```

`frontend/src/charts/leaderboardOption.ts`：

```ts
import type { EChartsOption } from "echarts";
import type { LeaderboardEntry } from "../api/types";
import { formatNumber } from "../utils/format";
import { ACTIVE, PASSIVE } from "./colors";

/** 榜单前几名的主动/被动积分堆叠横向条形图：第一名在最上面。 */
export function buildLeaderboardOption(entries: LeaderboardEntry[]): EChartsOption {
  return {
    color: [ACTIVE, PASSIVE],
    grid: { left: 12, right: 24, top: 36, bottom: 8, containLabel: true },
    legend: { data: ["主动积分", "被动积分"], top: 4 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: { formatter: (value: number) => formatNumber(value) } },
    yAxis: { type: "category", inverse: true, data: entries.map((entry) => entry.name) },
    series: [
      { name: "主动积分", type: "bar", stack: "points", barMaxWidth: 22, data: entries.map((e) => e.activePoints) },
      { name: "被动积分", type: "bar", stack: "points", barMaxWidth: 22, data: entries.map((e) => e.passivePoints) },
    ],
  };
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `cd frontend && npm test`
Expected: 所有测试通过（共 11 个文件）。

Run: `cd frontend && npm run build`
Expected: 类型检查和构建通过（`tooltip.formatter` 的 `params` 用 `any` 是 ECharts 回调类型的常规做法）。

- [ ] **Step 5: 提交**

```bash
git add frontend
git commit -m "feat: add chart option builders with unit tests

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 公共组件

**Files:**
- Create: `frontend/src/components/{BaseChart,AsyncBlock,RemoteSelect}.vue`

组件靠 `vue-tsc` 类型检查、构建和后面的浏览器验证覆盖（设计文档约定不做前端组件测试）。

- [ ] **Step 1: 写组件**

`frontend/src/components/BaseChart.vue`：

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

const props = withDefaults(defineProps<{ option: EChartsOption; height?: string }>(), { height: "360px" });

const container = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;
let observer: ResizeObserver | null = null;

onMounted(() => {
  if (!container.value) {
    return;
  }
  chart = echarts.init(container.value);
  chart.setOption(props.option as echarts.EChartsCoreOption);
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(container.value);
});

// 配置整体替换（不合并），避免切换数据后残留旧系列
watch(
  () => props.option,
  (option) => chart?.setOption(option as echarts.EChartsCoreOption, true),
);

onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <div ref="container" :style="{ width: '100%', height }"></div>
</template>
```

`frontend/src/components/AsyncBlock.vue`：

```vue
<script setup lang="ts">
withDefaults(
  defineProps<{ loading: boolean; error: string | null; empty: boolean; emptyText?: string }>(),
  { emptyText: "暂无数据" },
);
</script>

<template>
  <div v-loading="loading" class="async-block">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-empty v-else-if="empty && !loading" :description="emptyText" />
    <slot v-else />
  </div>
</template>

<style scoped>
.async-block {
  min-height: 80px;
}
</style>
```

`frontend/src/components/RemoteSelect.vue`：

```vue
<script setup lang="ts">
import { computed, ref } from "vue";

interface Option {
  id: number;
  name: string;
}

const props = defineProps<{
  modelValue: number | undefined;
  fetcher: (keyword: string) => Promise<Option[]>;
  placeholder: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: number | undefined] }>();

const options = ref<Option[]>([]);
const selected = ref<Option | null>(null);
const loading = ref(false);

// 已选中的项即使不在最新的搜索结果里也要保留，否则下拉框会把 id 当成文字显示
const visibleOptions = computed(() => {
  const chosen = selected.value;
  return chosen && !options.value.some((option) => option.id === chosen.id)
    ? [chosen, ...options.value]
    : options.value;
});

async function search(keyword: string): Promise<void> {
  loading.value = true;
  try {
    options.value = await props.fetcher(keyword);
  } catch {
    options.value = [];
  } finally {
    loading.value = false;
  }
}

function onVisibleChange(open: boolean): void {
  if (open) {
    void search("");
  }
}

function onChange(value: number | string | undefined): void {
  const id = value === "" || value === undefined ? undefined : Number(value);
  selected.value = id === undefined ? null : (visibleOptions.value.find((option) => option.id === id) ?? null);
  emit("update:modelValue", id);
}
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder"
    @visible-change="onVisibleChange"
    @update:model-value="onChange"
  >
    <el-option v-for="option in visibleOptions" :key="option.id" :label="option.name" :value="option.id" />
  </el-select>
</template>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功（组件目前没有页面引用，这一步主要验证类型）。如果 `vue-tsc` 对 `echarts.ECharts` 或 `EChartsCoreOption` 报类型不存在，改成 `import type { ECharts, EChartsCoreOption } from "echarts/core"`。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add shared chart, async block and remote select components

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 总览看板页面

**Files:**
- Create: `frontend/src/views/overview/{types,PvFilterBar,KpiCards,TrendPanel,TopPanel}.ts|vue`
- Modify: `frontend/src/views/OverviewView.vue`（整体替换占位）

- [ ] **Step 1: 写页面组件**

`frontend/src/views/overview/types.ts`：

```ts
import type { ContentType, Granularity, PvFilter } from "../../api/types";
import type { DateRange } from "../../utils/dateRange";

/** 总览看板的整套查询条件（趋势图和 Top N 共用）。 */
export interface OverviewQuery {
  granularity: Granularity;
  range: DateRange;
  channelId?: number;
  accountId?: number;
  employeeId?: number;
  contentType?: ContentType;
  forecastPeriods: number;
}

export function filterOf(query: OverviewQuery): PvFilter {
  return {
    channelId: query.channelId,
    accountId: query.accountId,
    employeeId: query.employeeId,
    contentType: query.contentType,
  };
}
```

`frontend/src/views/overview/PvFilterBar.vue`：

```vue
<script setup lang="ts">
import { computed } from "vue";
import { searchAccounts, searchEmployees } from "../../api/analytics";
import type { ChannelOption, ContentType, Granularity, PvRange } from "../../api/types";
import RemoteSelect from "../../components/RemoteSelect.vue";
import { defaultRange, pickerType, pickerValue, rangeFromPicker } from "../../utils/dateRange";
import type { OverviewQuery } from "./types";

const props = defineProps<{ modelValue: OverviewQuery; pvRange: PvRange | null; channels: ChannelOption[] }>();
const emit = defineEmits<{ "update:modelValue": [value: OverviewQuery] }>();

const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
  { value: "YEAR", label: "年" },
];

const contentTypes: Array<{ value: ContentType | ""; label: string }> = [
  { value: "", label: "全部类型" },
  { value: "ARTICLE", label: "文章" },
  { value: "VIDEO", label: "视频" },
];

function patch(changes: Partial<OverviewQuery>): void {
  emit("update:modelValue", { ...props.modelValue, ...changes });
}

// 切换粒度时把范围重置为该粒度的默认范围
function onGranularity(value: string | number | boolean | undefined): void {
  const granularity = value as Granularity;
  patch({ granularity, range: defaultRange(granularity, props.pvRange) });
}

const picker = computed(() => pickerValue(props.modelValue.granularity, props.modelValue.range));

function onPicker(value: [Date, Date] | null): void {
  if (value) {
    patch({ range: rangeFromPicker(props.modelValue.granularity, value) });
  }
}

function onChannel(value: number | undefined): void {
  patch({ channelId: value });
}

function onContentType(value: ContentType | ""): void {
  patch({ contentType: value === "" ? undefined : value });
}

function onForecast(value: number | undefined): void {
  patch({ forecastPeriods: value ?? 0 });
}
</script>

<template>
  <div class="panel filter-bar">
    <el-radio-group :model-value="modelValue.granularity" @update:model-value="onGranularity">
      <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
    </el-radio-group>

    <el-date-picker
      :model-value="picker"
      :type="pickerType(modelValue.granularity)"
      :clearable="false"
      range-separator="至"
      start-placeholder="开始"
      end-placeholder="结束"
      @update:model-value="onPicker"
    />

    <el-select
      :model-value="modelValue.channelId"
      clearable
      filterable
      placeholder="全部频道"
      style="width: 150px"
      @update:model-value="onChannel"
    >
      <el-option v-for="c in channels" :key="c.id" :label="c.name" :value="c.id" />
    </el-select>

    <RemoteSelect
      :model-value="modelValue.accountId"
      :fetcher="searchAccounts"
      placeholder="全部账号"
      style="width: 160px"
      @update:model-value="patch({ accountId: $event })"
    />

    <RemoteSelect
      :model-value="modelValue.employeeId"
      :fetcher="searchEmployees"
      placeholder="全部博主"
      style="width: 160px"
      @update:model-value="patch({ employeeId: $event })"
    />

    <el-select
      :model-value="modelValue.contentType ?? ''"
      style="width: 120px"
      @update:model-value="onContentType"
    >
      <el-option v-for="t in contentTypes" :key="t.value" :label="t.label" :value="t.value" />
    </el-select>

    <span class="forecast">
      预测
      <el-input-number
        :model-value="modelValue.forecastPeriods"
        :min="0"
        :max="12"
        size="small"
        @update:model-value="onForecast"
      />
      期
    </span>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.forecast {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-muted);
  font-size: 13px;
}
</style>
```

`frontend/src/views/overview/KpiCards.vue`：

```vue
<script setup lang="ts">
import type { Granularity } from "../../api/types";
import { formatNumber, formatPeriod, formatRate, rateClass } from "../../utils/format";
import type { Kpis } from "../../utils/kpi";

defineProps<{ kpis: Kpis; granularity: Granularity }>();
</script>

<template>
  <div class="kpi-row">
    <div class="panel kpi">
      <div class="kpi-label">所选周期合计 PV</div>
      <div class="kpi-value">{{ formatNumber(kpis.totalPv) }}</div>
    </div>

    <div class="panel kpi">
      <div class="kpi-label">
        最近已结束周期
        <span v-if="kpis.latest" class="muted">（{{ formatPeriod(kpis.latest.periodStart, granularity) }}）</span>
      </div>
      <template v-if="kpis.latest">
        <div class="kpi-value">{{ formatNumber(kpis.latest.pv) }}</div>
        <div class="kpi-sub">
          环比 <span :class="rateClass(kpis.latest.momRate)">{{ formatRate(kpis.latest.momRate) }}</span>
          　同比 <span :class="rateClass(kpis.latest.yoyRate)">{{ formatRate(kpis.latest.yoyRate) }}</span>
        </div>
      </template>
      <div v-else class="kpi-value rate-none">—</div>
    </div>

    <div class="panel kpi">
      <div class="kpi-label">
        下一期预测
        <span v-if="kpis.nextForecast" class="muted">（{{ formatPeriod(kpis.nextForecast.periodStart, granularity) }}）</span>
      </div>
      <template v-if="kpis.nextForecast">
        <div class="kpi-value forecast">{{ formatNumber(kpis.nextForecast.value) }}</div>
        <div class="kpi-sub">
          区间 {{ formatNumber(kpis.nextForecast.lower) }} ~ {{ formatNumber(kpis.nextForecast.upper) }}
        </div>
      </template>
      <div v-else class="kpi-value rate-none">—</div>
    </div>
  </div>
</template>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.kpi-label {
  color: var(--color-text-muted);
  font-size: 13px;
}
.kpi-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 600;
}
.kpi-value.forecast {
  color: var(--color-forecast);
}
.kpi-sub {
  margin-top: 6px;
  font-size: 13px;
}
</style>
```

`frontend/src/views/overview/TrendPanel.vue`：

```vue
<script setup lang="ts">
import { computed } from "vue";
import type { PvTrend } from "../../api/types";
import BaseChart from "../../components/BaseChart.vue";
import { buildTrendOption } from "../../charts/trendOption";
import { formatNumber, formatRate, rateClass } from "../../utils/format";
import { buildTrendRows, type TrendRow } from "../../utils/trendTable";

const props = defineProps<{ trend: PvTrend }>();

const option = computed(() => buildTrendOption(props.trend));
const rows = computed(() => buildTrendRows(props.trend));

const kindText: Record<TrendRow["kind"], string> = {
  forecast: "预测",
  incomplete: "进行中",
  complete: "已结束",
};
const kindType: Record<TrendRow["kind"], "warning" | "primary" | "info"> = {
  forecast: "warning",
  incomplete: "primary",
  complete: "info",
};
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">PV 趋势</h3>
    <BaseChart :option="option" height="360px" />
    <p class="muted">实线为实际值，空心点表示进行中的周期（数据还不完整）；虚线和浅色带为预测值及置信区间。</p>

    <el-table :data="rows" size="small" max-height="360" row-key="key" style="margin-top: 12px">
      <el-table-column prop="label" label="周期" min-width="130" />
      <el-table-column label="PV" align="right" min-width="110">
        <template #default="{ row }">{{ formatNumber(row.pv) }}</template>
      </el-table-column>
      <el-table-column label="环比" align="right" min-width="90">
        <template #default="{ row }">
          <span :class="rateClass(row.momRate)">{{ formatRate(row.momRate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="同比" align="right" min-width="90">
        <template #default="{ row }">
          <span :class="rateClass(row.yoyRate)">{{ formatRate(row.yoyRate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="置信区间" align="right" min-width="130">
        <template #default="{ row }">{{ row.range ?? "—" }}</template>
      </el-table-column>
      <el-table-column label="状态" align="center" min-width="90">
        <template #default="{ row }">
          <el-tag :type="kindType[row.kind as TrendRow['kind']]" size="small" effect="plain">
            {{ kindText[row.kind as TrendRow["kind"]] }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
```

`frontend/src/views/overview/TopPanel.vue`：

```vue
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchTop } from "../../api/analytics";
import type { PvDimension } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildTopBarOption } from "../../charts/topBarOption";
import { useAsync } from "../../composables/useAsync";
import { filterOf, type OverviewQuery } from "./types";

const props = defineProps<{ query: OverviewQuery }>();

const dimension = ref<PvDimension>("CHANNEL");
const limit = ref(10);

const dimensions: Array<{ value: PvDimension; label: string }> = [
  { value: "CHANNEL", label: "频道" },
  { value: "ACCOUNT", label: "账号" },
  { value: "EMPLOYEE", label: "博主" },
  { value: "CONTENT_TYPE", label: "内容类型" },
];

const top = useAsync((query: OverviewQuery, dim: PvDimension, count: number) =>
  fetchTop({ dimension: dim, from: query.range.from, to: query.range.to, limit: count, ...filterOf(query) }),
);

// Top N 按实际日期范围统计（不做周期扩展），筛选条件和趋势图一致
watch(
  [() => props.query, dimension, limit],
  () => void top.run(props.query, dimension.value, limit.value),
  { deep: true, immediate: true },
);

const entries = computed(() => top.data.value ?? []);
const option = computed(() => buildTopBarOption(entries.value));
const chartHeight = computed(() => `${Math.max(entries.value.length, 3) * 34 + 24}px`);
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">PV Top {{ limit }}</h3>
      <div class="controls">
        <el-radio-group v-model="dimension" size="small">
          <el-radio-button v-for="d in dimensions" :key="d.value" :value="d.value">{{ d.label }}</el-radio-button>
        </el-radio-group>
        <el-select v-model="limit" size="small" style="width: 90px">
          <el-option v-for="n in [5, 10, 20]" :key="n" :label="`前 ${n}`" :value="n" />
        </el-select>
      </div>
    </div>

    <AsyncBlock :loading="top.loading.value" :error="top.error.value" :empty="top.data.value !== null && entries.length === 0">
      <BaseChart :option="option" :height="chartHeight" />
    </AsyncBlock>

    <p v-if="dimension === 'EMPLOYEE'" class="muted">
      合著作品每位作者都记完整 PV，所以各博主的 PV 之和会大于全站总数。
    </p>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.controls {
  display: flex;
  gap: 8px;
}
</style>
```

`frontend/src/views/OverviewView.vue`（整体替换）：

```vue
<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { fetchChannels, fetchPvRange, fetchTrend } from "../api/analytics";
import type { ChannelOption, PvRange } from "../api/types";
import AsyncBlock from "../components/AsyncBlock.vue";
import { useAsync } from "../composables/useAsync";
import { defaultRange } from "../utils/dateRange";
import { deriveKpis } from "../utils/kpi";
import KpiCards from "./overview/KpiCards.vue";
import PvFilterBar from "./overview/PvFilterBar.vue";
import TopPanel from "./overview/TopPanel.vue";
import TrendPanel from "./overview/TrendPanel.vue";
import { filterOf, type OverviewQuery } from "./overview/types";

const pvRange = ref<PvRange | null>(null);
const channels = ref<ChannelOption[]>([]);
const query = ref<OverviewQuery | null>(null);
const initializing = ref(true);
const initError = ref<string | null>(null);
const noData = ref(false);

const trend = useAsync((q: OverviewQuery) =>
  fetchTrend({
    granularity: q.granularity,
    from: q.range.from,
    to: q.range.to,
    forecastPeriods: q.forecastPeriods,
    ...filterOf(q),
  }),
);

onMounted(async () => {
  try {
    const [range, channelList] = await Promise.all([fetchPvRange(), fetchChannels()]);
    channels.value = channelList;
    if (!range.from || !range.to) {
      noData.value = true;
    } else {
      pvRange.value = range;
      query.value = { granularity: "MONTH", range: defaultRange("MONTH", range), forecastPeriods: 3 };
    }
  } catch (e) {
    initError.value = e instanceof Error ? e.message : String(e);
  } finally {
    initializing.value = false;
  }
});

// 查询条件任何变化（包括初始化时第一次赋值）都重新取趋势
watch(
  query,
  (q) => {
    if (q) {
      void trend.run(q);
    }
  },
  { deep: true },
);

const kpis = computed(() => (trend.data.value ? deriveKpis(trend.data.value) : null));
</script>

<template>
  <div class="page">
    <AsyncBlock
      :loading="initializing"
      :error="initError"
      :empty="noData"
      empty-text="暂无 PV 数据，请先在后台回填并重算汇总"
    >
      <div v-if="query" class="page">
        <PvFilterBar v-model="query" :pv-range="pvRange" :channels="channels" />

        <AsyncBlock :loading="trend.loading.value" :error="trend.error.value" :empty="false">
          <div v-if="trend.data.value && kpis" class="page">
            <KpiCards :kpis="kpis" :granularity="trend.data.value.granularity" />
            <TrendPanel :trend="trend.data.value" />
          </div>
        </AsyncBlock>

        <TopPanel :query="query" />
      </div>
    </AsyncBlock>
  </div>
</template>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功。常见的类型问题：
- `v-model="query"` 报"不能把 `OverviewQuery | null` 赋给 `OverviewQuery`"：模板里 `query` 已在 `v-if="query"` 内，通常能收窄；若仍报错，把 `v-model` 改成 `:model-value="query" @update:model-value="query = $event"`。
- `row.kind as TrendRow['kind']`：`el-table` 的插槽 `row` 类型是 `any`，所以需要这样断言。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add overview dashboard page with PV trend, forecast and top N

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 积分排行榜页面

**Files:**
- Modify: `frontend/src/views/LeaderboardView.vue`（整体替换占位）

- [ ] **Step 1: 写页面**

`frontend/src/views/LeaderboardView.vue`：

```vue
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchLeaderboard, fetchPeriods } from "../api/leaderboard";
import type { PeriodType } from "../api/types";
import AsyncBlock from "../components/AsyncBlock.vue";
import BaseChart from "../components/BaseChart.vue";
import { buildLeaderboardOption } from "../charts/leaderboardOption";
import { useAsync } from "../composables/useAsync";
import { formatNumber } from "../utils/format";

const type = ref<PeriodType>("month");
const period = ref("");
const limit = ref(20);

const periods = useAsync((t: PeriodType) => fetchPeriods(t));
const board = useAsync((t: PeriodType, p: string, n: number) => fetchLeaderboard(t, p, n));

// 切换月榜/年榜时重新取周期列表，并默认选第一项（当前周期）
watch(
  type,
  async () => {
    await periods.run(type.value);
    period.value = periods.data.value?.[0]?.periodValue ?? "";
  },
  { immediate: true },
);

// 周期或人数变化时取榜单；切换类型时 period 会在周期列表返回后才变，所以不会拿旧周期去查新类型
watch([period, limit], () => {
  if (period.value) {
    void board.run(type.value, period.value, limit.value);
  }
});

const entries = computed(() => board.data.value?.entries ?? []);
const chartOption = computed(() => buildLeaderboardOption(entries.value.slice(0, 10)));
const chartHeight = computed(() => `${Math.min(entries.value.length, 10) * 34 + 56}px`);

function periodLabel(value: string, status: string): string {
  return status === "IN_PROGRESS" ? `${value}（进行中）` : value;
}

function rankClass(rank: number): string {
  return rank === 1 ? "gold" : rank === 2 ? "silver" : rank === 3 ? "bronze" : "";
}

function share(part: number, total: number): string {
  return total > 0 ? `${((part / total) * 100).toFixed(1)}%` : "0%";
}
</script>

<template>
  <div class="page">
    <div class="panel controls">
      <el-radio-group v-model="type">
        <el-radio-button value="month">月榜</el-radio-button>
        <el-radio-button value="year">年榜</el-radio-button>
      </el-radio-group>

      <el-select v-model="period" style="width: 190px" :loading="periods.loading.value" placeholder="选择周期">
        <el-option
          v-for="p in periods.data.value ?? []"
          :key="p.periodValue"
          :label="periodLabel(p.periodValue, p.status)"
          :value="p.periodValue"
        />
      </el-select>

      <el-select v-model="limit" style="width: 110px">
        <el-option v-for="n in [10, 20, 50]" :key="n" :label="`前 ${n} 名`" :value="n" />
      </el-select>

      <template v-if="board.data.value">
        <el-tag :type="board.data.value.status === 'IN_PROGRESS' ? 'warning' : 'info'" effect="plain">
          {{ board.data.value.status === "IN_PROGRESS" ? "进行中" : "已归档" }}
        </el-tag>
        <el-tag type="info" effect="plain">
          {{ board.data.value.source === "LIVE" ? "实时计算" : "归档快照" }}
        </el-tag>
      </template>
    </div>

    <AsyncBlock :loading="periods.loading.value" :error="periods.error.value" :empty="false">
      <AsyncBlock
        :loading="board.loading.value"
        :error="board.error.value"
        :empty="board.data.value !== null && entries.length === 0"
        empty-text="该周期还没有积分数据"
      >
        <div v-if="entries.length > 0" class="page">
          <div class="panel">
            <h3 class="panel-title">前 {{ Math.min(entries.length, 10) }} 名积分构成</h3>
            <BaseChart :option="chartOption" :height="chartHeight" />
          </div>

          <div class="panel">
            <h3 class="panel-title">榜单</h3>
            <el-table :data="entries" size="small" row-key="employeeId">
              <el-table-column label="名次" width="80" align="center">
                <template #default="{ row }">
                  <span class="rank-badge" :class="rankClass(row.rank)">{{ row.rank }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="姓名" min-width="140" />
              <el-table-column label="总分" align="right" min-width="100">
                <template #default="{ row }"><strong>{{ formatNumber(row.points) }}</strong></template>
              </el-table-column>
              <el-table-column label="主动分" align="right" min-width="100">
                <template #default="{ row }">{{ formatNumber(row.activePoints) }}</template>
              </el-table-column>
              <el-table-column label="被动分" align="right" min-width="100">
                <template #default="{ row }">{{ formatNumber(row.passivePoints) }}</template>
              </el-table-column>
              <el-table-column label="主动 / 被动占比" min-width="220">
                <template #default="{ row }">
                  <div class="split-bar" :title="`主动 ${share(row.activePoints, row.points)}　被动 ${share(row.passivePoints, row.points)}`">
                    <span class="active" :style="{ width: share(row.activePoints, row.points) }"></span>
                    <span class="passive" :style="{ width: share(row.passivePoints, row.points) }"></span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </AsyncBlock>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.rank-badge {
  display: inline-block;
  min-width: 24px;
  padding: 2px 6px;
  border-radius: 12px;
  background: #eef1f6;
  font-weight: 600;
  line-height: 1.4;
}
.rank-badge.gold {
  background: #f6c344;
  color: #5a3d00;
}
.rank-badge.silver {
  background: #c7cdd6;
  color: #3b4350;
}
.rank-badge.bronze {
  background: #d9a066;
  color: #4a2c0a;
}
.split-bar {
  display: flex;
  height: 10px;
  overflow: hidden;
  border-radius: 5px;
  background: #eef1f6;
}
.split-bar .active {
  background: var(--color-primary);
}
.split-bar .passive {
  background: var(--color-passive);
}
</style>
```

- [ ] **Step 2: 构建验证**

Run: `cd frontend && npm run build`
Expected: `vue-tsc` 无报错，构建成功。

Run: `cd frontend && npm test`
Expected: 全部通过。

- [ ] **Step 3: 提交**

```bash
git add frontend
git commit -m "feat: add points leaderboard page

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 浏览器验证、说明文档与收尾

**Files:**
- Create: `frontend/README.md`
- Modify: `docs/superpowers/specs/2026-10-04-frontend-dashboard-design.md`（状态行）、`docs/superpowers/specs/2026-09-23-community-analytics-design.md`（一句说明）

- [ ] **Step 1: 启动后端并灌入演示数据**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
```

另开终端：

```bash
curl -s -X POST localhost:8093/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"demo","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":300,"historyStart":"2024-10-01","historyEnd":"2026-03-01","randomSeed":7,"personas":true}'
curl -s -X POST localhost:8093/api/admin/analytics/pv/rebuild
curl -s -X POST localhost:8093/api/admin/analytics/leaderboard/snapshot
curl -s localhost:8093/api/meta/pv-range
curl -s localhost:8093/api/leaderboard/month/periods | head -c 300
```

Expected: 回填返回 `Backfill completed…`；`pv-range` 返回 `{"from":"2024-10-0x","to":"2026-03-xx"}`；周期列表第一项是当前月 `IN_PROGRESS`。

- [ ] **Step 2: 启动前端并在内置浏览器里逐页检查**

```bash
cd frontend && VITE_API_TARGET=http://localhost:8093 npm run dev
```

打开 `http://localhost:5173/`，用浏览器工具检查并截图：

1. **总览看板**（默认月粒度）：筛选栏、三张指标卡、趋势图（实线、预测虚线、浅色置信带、最后一个周期的空心点）、明细表（预测行在最上面）、Top N 面板。悬浮到趋势图的点，确认提示里有环比、同比。
2. 切换粒度（日/周/年）：日期范围重置、图表和表格随之变化；选择一个频道/内容类型：图表和 Top N 一起变化；在账号/博主下拉里输入关键字能搜索；预测期数改成 0：虚线和置信带消失，指标卡里"下一期预测"显示"—"。
3. 把日期范围拉到一个没有数据的区间：显示空状态或全零，不报错。
4. **积分排行榜**：默认当前月（`进行中`，多半为空）；切到有数据的历史月份（`已归档`、`归档快照`）：条形图 + 榜单表 + 占比条；并列名次显示相同；切换年榜和显示人数。
5. 停掉后端再刷新页面：顶栏显示"后端不可用"，页面显示错误提示而不是空白。

人工看一眼：趋势图里预测第一期是否正好接在最后一个已结束周期之后；Top N 博主维度下方有合著说明。

- [ ] **Step 3: 关闭两个进程**

```bash
pkill -f spring-boot:run
pkill -f "vite"
sleep 6
lsof -i :8093 || echo "port 8093 free"
lsof -i :5173 || echo "port 5173 free"
```

Expected: 两个端口都输出 `free`（若仍有进程在监听，再各 kill 一次并等几秒）。

- [ ] **Step 4: 写前端说明文档**

`frontend/README.md`：

````markdown
# 稼先社区运营分析看板（前端）

Vue 3 + TypeScript + Vite + Element Plus + ECharts。目前包含：总览看板（PV 趋势、环比同比、预测、Top N）和积分排行榜（月榜/年榜）。

## 开发

```bash
cd frontend
npm install
VITE_API_TARGET=http://localhost:8080 npm run dev   # 默认就是 8080，后端换了端口就改这里
```

开发服务器在 http://localhost:5173，`/api` 会被代理到后端。

## 常用命令

```bash
npm test         # Vitest 单元测试（纯逻辑和图表配置）
npm run build    # vue-tsc 类型检查 + 生产构建（输出到 dist/）
```

## 演示数据

看板需要后端有数据。启动后端后依次调用：

```bash
curl -X POST localhost:8080/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"demo","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":300,"historyStart":"2024-10-01","historyEnd":"2026-03-01","randomSeed":7,"personas":true}'
curl -X POST localhost:8080/api/admin/analytics/pv/rebuild                 # PV 汇总
curl -X POST localhost:8080/api/admin/analytics/leaderboard/snapshot       # 榜单快照
```

## 目录

```
src/api         后端接口封装（带类型）
src/utils       纯逻辑：格式化、日期范围、指标卡、明细表行
src/charts      ECharts 配置生成函数（纯函数）
src/composables useAsync（加载/错误/过期响应）
src/components  公共组件（图表、异步区块、远程搜索下拉）
src/views       页面
```

`src/components.d.ts` 和 `src/auto-imports.d.ts` 由 `unplugin-*` 自动生成，需要提交到仓库。
````

- [ ] **Step 5: 更新状态并提交**

把 `docs/superpowers/specs/2026-10-04-frontend-dashboard-design.md` 的 `- 状态：已通过讨论确认` 改为 `- 状态：已实现（实施计划见 [前端 BI 看板](../plans/2026-10-04-frontend-dashboard.md)）`。

在 `docs/superpowers/specs/2026-09-23-community-analytics-design.md` 第 8 节"前端页面结构"的代码块**之后**那句"面向对象：仅管理员/运营人员使用的内部BI看板……"**之前**加一行：

```markdown
> 实现进度（见 [前端看板设计](2026-10-04-frontend-dashboard-design.md)）：总览看板和积分排行榜已实现（Element Plus + ECharts，开发时用 Vite 代理 `/api`）；KOL 管理和系统管理页面在下一份计划里实现。
```

Run: `cd backend && mvn test && cd ../frontend && npm test && npm run build`
Expected: 后端 `BUILD SUCCESS`，前端测试通过、构建通过。

```bash
git add docs frontend
git commit -m "docs: add frontend README and mark dashboard spec implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 第 3 节后端补充：频道/账号/员工选项、`pv-range`、周期列表 → Task 1、2。
- 第 4 节技术栈与目录 → Task 3（依赖、Vite 配置、`components.d.ts` 提交约定）和各任务的文件结构。
- 第 5 节通用能力（布局、`AsyncBlock`、`useAsync`、`BaseChart`、图表配置纯函数）→ Task 3、4、5、6。
- 第 6 节总览看板：筛选栏、默认范围、指标卡、趋势图（实线/空心点/虚线/置信带/提示）、明细表、Top N 面板及博主维度说明 → Task 4（`dateRange`、`kpi`、`trendTable`）、Task 5（`trendOption`、`topBarOption`）、Task 7（页面）。
- 第 7 节积分排行榜 → Task 5（`leaderboardOption`）、Task 8（页面：控件、状态标签、堆叠图、榜单表、占比条、空状态、错误）。
- 第 8 节测试与验证：后端 IT 和单测（Task 1、2）、前端 Vitest（Task 4、5）、`vue-tsc` + `vite build` 门槛（各任务）、浏览器人工验证（Task 9）。

**2. 占位符扫描：** 无 TBD/TODO。Task 3 的占位页面是明确的过渡产物，Task 7、8 整体替换。

**3. 类型一致性：** 前端 `PvTrend`/`PvPoint`/`ForecastPoint`/`PvTopEntry`/`LeaderboardEntry`/`PeriodOption` 与后端响应字段一一对应（`periodStart, pv, momRate, yoyRate, complete`；`value, lower, upper`；`id, name, pv`；`rank, employeeId, name, points, activePoints, passivePoints`；`periodValue, status`）；`OverviewQuery`、`filterOf` 在 Task 7 的 `types.ts` 定义，`PvFilterBar`、`TopPanel`、`OverviewView` 都按它使用；`useAsync` 的 `{data, loading, error, run}` 在 Task 4 定义，Task 7、8 一致；`defaultRange/pickerType/rangeFromPicker/pickerValue` 在 Task 4 定义，`PvFilterBar` 按签名使用；后端 `DimensionRepository.accounts/employees/channels/pvRange` 与 `DimensionService` 一致；`LeaderboardService.periods` 与 `PeriodOption` 在 Task 2 定义，控制器使用。
