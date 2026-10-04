# 部署与整合 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把后端和前端容器化，在现有 `docker-compose.yml` 里用 `app` profile 一条命令拉起全栈（OpenGauss + Redis + Kafka + Spring Boot + Nginx 托管的 Vue），并补上演示数据脚本、冒烟测试脚本和项目 README，最后在本机真实构建并验证。

**Architecture:** 基础设施服务不加 profile，`docker compose up -d` 行为不变；`backend`、`web` 两个应用服务带 `profiles: ["app"]`。Kafka 改成 `INTERNAL`（`kafka:29092`，容器间）和 `EXTERNAL`（`localhost:9092`，宿主机）双监听。Nginx 托管前端静态资源并把 `/api` 反向代理给后端。

**Tech Stack:** Docker Compose、`maven:3.9-eclipse-temurin-17` / `eclipse-temurin:17-jre-jammy`、`node:22-alpine` / `nginx:1.27-alpine`、bash + curl + python3。

**设计文档：** `docs/superpowers/specs/2026-10-05-deployment-design.md`（先读它）。

---

## 必读：约定与踩坑记录

1. **本机用 Colima 跑 Docker**：重启电脑后要 `colima start`，再等 OpenGauss（首次可能要几十秒）和 Kafka 就绪。
2. **8080 端口被无关进程占用**：所以前端默认暴露 `8088`、后端默认暴露 `8081`（都可用 `.env` 改）。开发时手工启动后端仍用 `--server.port=8093`。
3. **改完 `docker-compose.yml` 要用 `docker compose up -d` 让基础设施按新配置重建**；Kafka 容器重建后主题由应用启动时的 `NewTopic` 自动创建。
4. **宿主机上的 `mvn test` 依赖基础设施在 `localhost` 上**（`localhost:5432`、`6379`、`9092`），所以 Kafka 的 `EXTERNAL` 监听必须仍然宣告 `localhost:9092`，改完要重新跑一遍完整 `mvn test`。
5. **Kafka 的 bitnami 镜像里的脚本在 `/opt/bitnami/kafka/bin/`**；OpenGauss 的 `gsql` 在 `/usr/local/opengauss/bin/`，需要设置 `LD_LIBRARY_PATH=/usr/local/opengauss/lib`；OpenGauss 的初始超级用户 `opengauss` 不能远程登录，应用用的是 `GS_USERNAME` 创建的 `jiaxian_app`。
6. **时区**：后端用系统默认时区取"今天"、写时间戳，容器默认是 UTC，会和浏览器所在时区差几个小时，所以后端容器要设置 `TZ`（默认 `Asia/Shanghai`）。
7. **Nginx 反向代理要用 Docker 内置 DNS（`resolver 127.0.0.11`）和变量形式的 `proxy_pass`**，否则后端容器重启换了 IP 后 Nginx 还在用旧地址。
8. **`docker compose build` 需要联网**拉基础镜像和 Maven/npm 依赖；后端用 BuildKit 缓存挂载保存 `~/.m2`。
9. 脚本需要可执行权限（`chmod +x scripts/*.sh`）。
10. `.env` 不提交（加进 `.gitignore`）；`.env.example` 提交。

## 文件结构

新建/修改：

| 文件 | 说明 |
|---|---|
| `docker-compose.yml`（改） | Kafka 双监听、健康检查、Kafka 数据卷；新增 `backend`、`web`（`profiles: ["app"]`） |
| `backend/Dockerfile`、`backend/.dockerignore` | 后端镜像 |
| `frontend/Dockerfile`、`frontend/nginx.conf`、`frontend/.dockerignore` | 前端镜像与 Nginx 配置 |
| `.env.example`、`.gitignore`（改） | 配置示例 |
| `scripts/seed-demo.sh`、`scripts/smoke-test.sh` | 演示数据与冒烟测试 |
| `README.md` | 项目说明 |

---

### Task 1: 基础设施——Kafka 双监听、健康检查与数据卷

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: 改 compose（只改基础设施部分，应用服务下个任务加）**

`docker-compose.yml`（整体替换）：

```yaml
services:
  opengauss:
    image: opengauss/opengauss:5.0.0
    container_name: jiaxian-opengauss
    environment:
      GS_PASSWORD: "${GS_PASSWORD:-Jiaxian@2026}"
      GS_USERNAME: "${GS_USERNAME:-jiaxian_app}"
    ports:
      - "5432:5432"
    volumes:
      - opengauss-data:/var/lib/opengauss/data
    healthcheck:
      # 用业务账号执行 select 1；$$ 是为了不让 compose 提前展开，交给容器里的 shell 取环境变量
      test: ["CMD-SHELL", "LD_LIBRARY_PATH=/usr/local/opengauss/lib /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U $${GS_USERNAME} -d postgres -W \"$${GS_PASSWORD}\" -c 'select 1' > /dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 30
      start_period: 40s
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    container_name: jiaxian-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  kafka:
    image: bitnamilegacy/kafka:3.6.2
    container_name: jiaxian-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: "0"
      KAFKA_CFG_PROCESS_ROLES: "controller,broker"
      # INTERNAL 给容器之间用（kafka:29092），EXTERNAL 给宿主机用（localhost:9092，IDE 和 mvn test）
      KAFKA_CFG_LISTENERS: "INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093"
      KAFKA_CFG_ADVERTISED_LISTENERS: "INTERNAL://kafka:29092,EXTERNAL://localhost:9092"
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT"
      KAFKA_CFG_INTER_BROKER_LISTENER_NAME: "INTERNAL"
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: "0@kafka:9093"
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      ALLOW_PLAINTEXT_LISTENER: "yes"
    volumes:
      - kafka-data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "/opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 20
      start_period: 20s
    restart: unless-stopped

volumes:
  opengauss-data:
  kafka-data:
```

- [ ] **Step 2: 让基础设施按新配置重建并等待健康**

```bash
docker compose up -d
for i in $(seq 1 60); do
  unhealthy=$(docker compose ps --format '{{.Name}} {{.Health}}' | grep -v healthy || true)
  [ -z "$unhealthy" ] && break
  sleep 5
done
docker compose ps --format '{{.Name}} {{.Status}}'
```

Expected: `jiaxian-opengauss`、`jiaxian-redis`、`jiaxian-kafka` 都是 `Up … (healthy)`。如果 Kafka 因为数据卷权限启动失败（日志里有 `Permission denied`），去掉 `kafka-data` 卷和对应的 `volumes:` 条目，在提交说明里写明。

- [ ] **Step 3: 确认宿主机上的现有测试流程没被破坏**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`，全部测试通过（特别是 `RealtimePipelineIT`、`RealtimeInfrastructureIT`，它们通过 `localhost:9092` 连 Kafka）。

- [ ] **Step 4: 提交**

```bash
git add docker-compose.yml
git commit -m "chore: add Kafka dual listeners, health checks and a Kafka data volume

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 后端镜像

**Files:**
- Create: `backend/Dockerfile`、`backend/.dockerignore`

- [ ] **Step 1: 写文件**

`backend/Dockerfile`：

```dockerfile
# syntax=docker/dockerfile:1

# ---- 构建阶段：Maven 打包（跳过测试，测试需要真实的数据库、Redis 和 Kafka） ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 依赖单独一层并用缓存挂载保存 ~/.m2，源码改动时不用重新下载
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
    && cp target/*.jar /build/app.jar

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre-jammy
# curl 供 compose 的健康检查使用
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

`backend/.dockerignore`：

```
target/
.idea/
*.iml
```

- [ ] **Step 2: 构建镜像**

Run: `cd backend && DOCKER_BUILDKIT=1 docker build -t jiaxian-backend:local . 2>&1 | tail -15`
Expected: 构建成功，最后一行类似 `naming to docker.io/library/jiaxian-backend:local`。首次构建要下载 Maven 依赖，可能要几分钟。

如果 `cp target/*.jar` 报错（匹配到多个文件），说明 `target/` 里除了可执行 jar 还有别的 `.jar`：改成 `cp target/analytics-*.jar /build/app.jar` 并在提交说明里写明。

- [ ] **Step 3: 验证镜像的内容与用户**

Run: `docker run --rm --entrypoint sh jiaxian-backend:local -c 'id -un; java -version 2>&1 | head -1; ls -l /app/app.jar; which curl'`
Expected: 输出 `app`、`openjdk version "17…`、`app.jar` 的大小（几十 MB）、`/usr/bin/curl`。

- [ ] **Step 4: 提交**

```bash
git add backend/Dockerfile backend/.dockerignore
git commit -m "feat: add backend Docker image

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 前端镜像与 Nginx 配置

**Files:**
- Create: `frontend/Dockerfile`、`frontend/nginx.conf`、`frontend/.dockerignore`

- [ ] **Step 1: 写文件**

`frontend/Dockerfile`：

```dockerfile
# syntax=docker/dockerfile:1

# ---- 构建阶段：vue-tsc 类型检查 + vite build ----
FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- 运行阶段：Nginx 托管静态资源，并把 /api 反向代理给后端 ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
```

`frontend/nginx.conf`：

```nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    gzip on;
    gzip_min_length 1024;
    gzip_types text/css application/javascript application/json image/svg+xml;

    # 用 Docker 内置 DNS + 变量形式的 proxy_pass：后端容器重启换了 IP 后，Nginx 不会还抓着旧地址
    resolver 127.0.0.11 valid=10s;

    location /api/ {
        set $backend http://backend:8080;
        proxy_pass $backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 5s;
        # 历史回填一次要一两分钟
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    # 构建产物的文件名带内容哈希，可以长期缓存
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    location = /index.html {
        add_header Cache-Control "no-cache";
    }

    # SPA：/kol、/kol/12 这类前端路由刷新时回退到首页
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

`frontend/.dockerignore`：

```
node_modules/
dist/
.vite/
```

- [ ] **Step 2: 构建镜像并单独验证 Nginx 配置与静态资源**

```bash
cd frontend && DOCKER_BUILDKIT=1 docker build -t jiaxian-web:local . 2>&1 | tail -8
docker run --rm jiaxian-web:local nginx -t
```

Expected: 构建成功；`nginx -t` 输出 `syntax is ok` 和 `test is successful`。

再启动一个临时容器验证 SPA 回退和缓存头（此时没有后端，只测静态部分）：

```bash
docker run -d --rm --name web-probe -p 18088:80 jiaxian-web:local
sleep 2
curl -s -o /dev/null -w "首页 %{http_code}\n" localhost:18088/
curl -s -o /dev/null -w "深层路径 %{http_code}\n" localhost:18088/kol/12
curl -sI localhost:18088/ | grep -i cache-control
ASSET=$(curl -s localhost:18088/ | grep -o '/assets/[^"]*\.js' | head -1)
curl -sI "localhost:18088$ASSET" | grep -iE "HTTP/|cache-control"
docker stop web-probe
```

Expected: 首页 200、深层路径 200（回退到首页）；首页 `Cache-Control: no-cache`；JS 资源 200 且 `Cache-Control: public, immutable`（外加 `Expires` 一年后）。

- [ ] **Step 3: 提交**

```bash
git add frontend/Dockerfile frontend/nginx.conf frontend/.dockerignore
git commit -m "feat: add frontend Docker image with Nginx SPA hosting and API proxy

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: compose 应用服务、配置示例与全栈拉起

**Files:**
- Modify: `docker-compose.yml`、`.gitignore`
- Create: `.env.example`

- [ ] **Step 1: 写配置**

`docker-compose.yml`（整体替换，在 Task 1 的基础上加两个应用服务）：

```yaml
services:
  opengauss:
    image: opengauss/opengauss:5.0.0
    container_name: jiaxian-opengauss
    environment:
      GS_PASSWORD: "${GS_PASSWORD:-Jiaxian@2026}"
      GS_USERNAME: "${GS_USERNAME:-jiaxian_app}"
    ports:
      - "5432:5432"
    volumes:
      - opengauss-data:/var/lib/opengauss/data
    healthcheck:
      # 用业务账号执行 select 1；$$ 是为了不让 compose 提前展开，交给容器里的 shell 取环境变量
      test: ["CMD-SHELL", "LD_LIBRARY_PATH=/usr/local/opengauss/lib /usr/local/opengauss/bin/gsql -h 127.0.0.1 -U $${GS_USERNAME} -d postgres -W \"$${GS_PASSWORD}\" -c 'select 1' > /dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 30
      start_period: 40s
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    container_name: jiaxian-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  kafka:
    image: bitnamilegacy/kafka:3.6.2
    container_name: jiaxian-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: "0"
      KAFKA_CFG_PROCESS_ROLES: "controller,broker"
      # INTERNAL 给容器之间用（kafka:29092），EXTERNAL 给宿主机用（localhost:9092，IDE 和 mvn test）
      KAFKA_CFG_LISTENERS: "INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093"
      KAFKA_CFG_ADVERTISED_LISTENERS: "INTERNAL://kafka:29092,EXTERNAL://localhost:9092"
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT"
      KAFKA_CFG_INTER_BROKER_LISTENER_NAME: "INTERNAL"
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: "0@kafka:9093"
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      ALLOW_PLAINTEXT_LISTENER: "yes"
    volumes:
      - kafka-data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "/opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 20
      start_period: 20s
    restart: unless-stopped

  # ---- 应用服务：`docker compose --profile app up -d --build` 才会启动 ----
  backend:
    profiles: ["app"]
    build:
      context: ./backend
    container_name: jiaxian-backend
    environment:
      DB_URL: "jdbc:postgresql://opengauss:5432/postgres"
      DB_USERNAME: "${GS_USERNAME:-jiaxian_app}"
      DB_PASSWORD: "${GS_PASSWORD:-Jiaxian@2026}"
      REDIS_HOST: "redis"
      KAFKA_BOOTSTRAP_SERVERS: "kafka:29092"
      JAVA_OPTS: "${JAVA_OPTS:--XX:MaxRAMPercentage=75}"
      # 后端用系统默认时区取"今天"和写时间戳，容器默认是 UTC，会和浏览器所在时区差几个小时
      TZ: "${TZ:-Asia/Shanghai}"
    ports:
      - "${API_PORT:-8081}:8080"
    depends_on:
      opengauss:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -fs http://localhost:8080/api/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 18
      start_period: 40s
    restart: unless-stopped

  web:
    profiles: ["app"]
    build:
      context: ./frontend
    container_name: jiaxian-web
    ports:
      - "${WEB_PORT:-8088}:80"
    depends_on:
      backend:
        condition: service_healthy
    restart: unless-stopped

volumes:
  opengauss-data:
  kafka-data:
```

`.env.example`：

```bash
# 复制为 .env 后按需修改（.env 不会提交到仓库）
#   cp .env.example .env

# 数据库账号。默认口令只适合演示，部署前请改掉
# （OpenGauss 要求口令同时包含大小写字母、数字和特殊字符）
GS_USERNAME=jiaxian_app
GS_PASSWORD=Jiaxian@2026

# 宿主机上暴露的端口：前端（Nginx）和后端（直接调接口用）
WEB_PORT=8088
API_PORT=8081

# 后端 JVM 参数
JAVA_OPTS=-XX:MaxRAMPercentage=75

# 后端容器的时区
TZ=Asia/Shanghai
```

在 `.gitignore` 的"# OS"之前追加：

```
# Local environment
.env
```

- [ ] **Step 2: 校验 compose 文件并拉起全栈**

```bash
docker compose config --quiet && echo "compose 配置有效"
docker compose --profile app config --services
docker compose --profile app up -d --build
for i in $(seq 1 60); do
  unhealthy=$(docker compose --profile app ps --format '{{.Name}} {{.Health}}' | grep -v healthy || true)
  [ -z "$unhealthy" ] && break
  sleep 5
done
docker compose --profile app ps --format '{{.Name}} {{.Status}}'
```

Expected：
- `docker compose config --quiet` 无输出后打印"compose 配置有效"；
- `--services` 列出 5 个服务（`opengauss`、`redis`、`kafka`、`backend`、`web`）；
- 构建成功后，5 个容器都是 `Up … (healthy)`（`web` 没有健康检查，显示 `Up` 即可）。

如果 `backend` 一直不健康：`docker logs jiaxian-backend --tail 50`，常见原因是 Kafka 或 OpenGauss 还没就绪（等一会）、`DB_URL` 的主机名写错、口令不一致。

确认没有 profile 时不会带出应用服务：

```bash
docker compose config --services
```

Expected: 只有 `opengauss`、`redis`、`kafka`。

- [ ] **Step 3: 快速手工验证**

```bash
curl -s localhost:8081/api/health; echo
curl -s localhost:8088/api/health; echo
curl -s -o /dev/null -w "web / %{http_code}\n" localhost:8088/
curl -s -o /dev/null -w "web /kol %{http_code}\n" localhost:8088/kol
curl -s localhost:8088/api/admin/points-config | head -c 120; echo
docker exec jiaxian-backend date
```

Expected：两个 `/api/health` 都返回 `OK`（直连和经 Nginx）；首页和 `/kol` 都是 200；权重接口返回 JSON；`date` 显示的是上海时区（`CST`）。

- [ ] **Step 4: 提交**

```bash
git add docker-compose.yml .env.example .gitignore
git commit -m "feat: add backend and web services to compose under the app profile

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 演示数据脚本与冒烟测试

**Files:**
- Create: `scripts/seed-demo.sh`、`scripts/smoke-test.sh`

- [ ] **Step 1: 写脚本**

`scripts/seed-demo.sh`：

```bash
#!/usr/bin/env bash
# 灌入演示数据：历史回填 → PV 汇总 → 榜单快照 → KOL 重算。
# 用法：scripts/seed-demo.sh            （默认调用 http://localhost:8081）
#       API_URL=http://localhost:8081 CONTENT_COUNT=150 scripts/seed-demo.sh
# 注意：历史回填会清空所有模拟数据（员工、内容、行为、积分流水）以及依赖它们的汇总、快照和 KOL 结果。
set -euo pipefail

API_URL="${API_URL:-http://localhost:8081}"
EMPLOYEE_COUNT="${EMPLOYEE_COUNT:-50}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-20}"
CONTENT_COUNT="${CONTENT_COUNT:-300}"
RANDOM_SEED="${RANDOM_SEED:-7}"

# 结束日期 = 今天往前 30 天（回填的浏览行为会延续到发布后 30 天，太靠近今天会产生"未来"的数据）；
# 开始日期 = 结束日期往前 18 个月的 1 号。
read -r HISTORY_START HISTORY_END < <(python3 - <<'PY'
import datetime as d
end = d.date.today() - d.timedelta(days=30)
months = end.year * 12 + (end.month - 1) - 18
print(d.date(months // 12, months % 12 + 1, 1), end)
PY
)

# call METHOD PATH [JSON_BODY]：失败（HTTP >= 400）就退出并打印原因，成功时把响应体打印出来
call() {
  local method=$1 path=$2 body=${3:-} out status
  local args=(-sS -m 900 -w $'\n%{http_code}' -X "$method" "$API_URL$path")
  if [[ -n $body ]]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  out=$(curl "${args[@]}")
  status=${out##*$'\n'}
  out=${out%$'\n'*}
  if [[ $status -ge 400 ]]; then
    echo "失败（HTTP $status）$method $path：$out" >&2
    exit 1
  fi
  printf '%s\n' "$out"
}

echo "等待后端就绪：$API_URL ..."
for _ in $(seq 1 60); do
  if curl -fs -m 3 "$API_URL/api/health" > /dev/null; then
    break
  fi
  sleep 2
done
curl -fs -m 3 "$API_URL/api/health" > /dev/null || { echo "后端在 $API_URL 上不可用" >&2; exit 1; }

echo "1/4 历史回填（$HISTORY_START ~ $HISTORY_END，可能要一两分钟）..."
call POST /api/admin/simulator/backfill "{\"batchId\":\"demo\",\"employeeCount\":$EMPLOYEE_COUNT,\"accountCount\":$ACCOUNT_COUNT,\"teamAccountRatio\":0.25,\"contentCount\":$CONTENT_COUNT,\"historyStart\":\"$HISTORY_START\",\"historyEnd\":\"$HISTORY_END\",\"randomSeed\":$RANDOM_SEED,\"personas\":true}"

echo "2/4 PV 汇总重算 ..."
call POST /api/admin/analytics/pv/rebuild

echo "3/4 榜单快照重算 ..."
call POST /api/admin/analytics/leaderboard/snapshot

# 模拟数据是历史的：PV 数据的最后一天比模拟的"现在"晚约 30 天，所以 KOL 统计日取它往前 30 天
PV_END=$(call GET /api/meta/pv-range | python3 -c 'import json, sys; print(json.load(sys.stdin)["to"])')
KOL_AS_OF=$(python3 -c "import datetime as d; print(d.date.fromisoformat('$PV_END') - d.timedelta(days=30))")
echo "4/4 KOL 重算（统计日 $KOL_AS_OF，12 周）..."
call POST "/api/admin/analytics/kol/rebuild?asOf=$KOL_AS_OF&weeks=12" > /dev/null

echo "演示数据已就绪。"
```

`scripts/smoke-test.sh`：

```bash
#!/usr/bin/env bash
# 冒烟测试：在全栈已拉起、演示数据已灌入（scripts/seed-demo.sh）之后执行。
# 用法：scripts/smoke-test.sh            （默认检查 http://localhost:8088）
#       WEB_URL=http://localhost:8088 scripts/smoke-test.sh
set -uo pipefail

WEB_URL="${WEB_URL:-http://localhost:8088}"
failed=0

ok() { echo "  ✓ $1"; }
bad() { echo "  ✗ $1" >&2; failed=1; }

status_of() { curl -s -m 20 -o /dev/null -w '%{http_code}' "$1"; }

expect_status() { # 描述 URL 期望状态码
  local got
  got=$(status_of "$2")
  if [[ $got == "$3" ]]; then ok "$1"; else bad "$1（期望 $3，实际 $got）"; fi
}

# 描述 URL "python 布尔表达式"（变量 d 是解析后的 JSON）
expect_json() {
  if curl -fs -m 30 "$2" | python3 -c "import json, sys; d = json.load(sys.stdin); sys.exit(0 if ($3) else 1)" 2> /dev/null; then
    ok "$1"
  else
    bad "$1（$2 的响应不满足：$3）"
  fi
}

# URL "python 表达式" → 打印表达式的值，供后面拼 URL 用
json_value() { curl -fs -m 30 "$1" | python3 -c "import json, sys; d = json.load(sys.stdin); print($2)"; }

echo "前端与反向代理（$WEB_URL）"
expect_status "首页" "$WEB_URL/" 200
expect_status "SPA 深层路径 /kol（回退到首页）" "$WEB_URL/kol" 200
expect_status "SPA 深层路径 /kol/12" "$WEB_URL/kol/12" 200
if [[ $(curl -fs -m 10 "$WEB_URL/api/health") == "OK" ]]; then ok "经 Nginx 访问 /api/health"; else bad "经 Nginx 访问 /api/health"; fi

echo "PV 分析"
FROM=$(json_value "$WEB_URL/api/meta/pv-range" 'd["from"]') || FROM=""
TO=$(json_value "$WEB_URL/api/meta/pv-range" 'd["to"]') || TO=""
if [[ -z $FROM || $FROM == None || -z $TO || $TO == None ]]; then
  bad "PV 数据范围为空：请先运行 scripts/seed-demo.sh"
else
  ok "PV 数据范围 $FROM ~ $TO"
  expect_json "趋势（月，含预测）" "$WEB_URL/api/analytics/pv/trend?granularity=MONTH&from=$FROM&to=$TO&forecastPeriods=3" 'len(d["points"]) > 0'
  expect_json "Top N（频道）" "$WEB_URL/api/analytics/pv/top?dimension=CHANNEL&from=$FROM&to=$TO&limit=5" 'len(d) > 0'
  expect_json "Top N（博主）" "$WEB_URL/api/analytics/pv/top?dimension=EMPLOYEE&from=$FROM&to=$TO&limit=5" 'len(d) > 0'
fi
expect_status "趋势接口拒绝非法粒度" "$WEB_URL/api/analytics/pv/trend?granularity=HOURLY&from=2026-01-01&to=2026-02-01" 400

echo "积分排行榜"
expect_json "月榜周期列表" "$WEB_URL/api/leaderboard/month/periods" 'len(d) >= 2'
PERIOD=$(json_value "$WEB_URL/api/leaderboard/month/periods" 'd[1]["periodValue"]') || PERIOD=""
if [[ -n $PERIOD ]]; then
  expect_json "已结束月份的榜单（$PERIOD）" "$WEB_URL/api/leaderboard/month?period=$PERIOD&limit=10" 'len(d["entries"]) > 0'
fi
expect_status "榜单拒绝未来周期" "$WEB_URL/api/leaderboard/year?period=2999" 400

echo "KOL 管理"
expect_json "重算记录" "$WEB_URL/api/bloggers/runs" 'len(d) > 0'
expect_json "KOL 名单" "$WEB_URL/api/bloggers/top1000?limit=5" 'd["total"] > 0 and len(d["items"]) > 0'
ID=$(json_value "$WEB_URL/api/bloggers/top1000?limit=1" 'd["items"][0]["employeeId"]') || ID=""
if [[ -n $ID ]]; then
  expect_json "博主详情（id=$ID）" "$WEB_URL/api/bloggers/$ID/profile" 'd["current"] is not None and len(d["history"]) > 0'
  expect_json "博主活跃时间轴" "$WEB_URL/api/bloggers/$ID/activity?granularity=MONTH" 'len(d["points"]) > 0'
  expect_json "博主积分趋势" "$WEB_URL/api/bloggers/$ID/points-trend?granularity=MONTH" 'len(d["points"]) > 0'
fi
expect_status "名单拒绝非法分层" "$WEB_URL/api/bloggers/top1000?segment=VIP" 400

echo "系统管理与维度"
expect_json "积分权重（15 个动作）" "$WEB_URL/api/admin/points-config" 'len(d) == 15'
expect_json "建议文案库" "$WEB_URL/api/admin/playbook" 'len(d) >= 6'
expect_json "频道列表" "$WEB_URL/api/dimensions/channels" 'len(d) >= 15'
expect_json "员工搜索" "$WEB_URL/api/dimensions/employees?limit=5" 'len(d) > 0'
expect_json "实时模拟状态" "$WEB_URL/api/admin/simulator/realtime/status" '"running" in d'

echo
if [[ $failed -eq 0 ]]; then
  echo "冒烟测试全部通过。"
else
  echo "冒烟测试有失败项。" >&2
  exit 1
fi
```

- [ ] **Step 2: 加权限并灌入演示数据**

```bash
chmod +x scripts/seed-demo.sh scripts/smoke-test.sh
bash -n scripts/seed-demo.sh && bash -n scripts/smoke-test.sh && echo "语法检查通过"
CONTENT_COUNT=150 scripts/seed-demo.sh
```

Expected: 依次打印 4 步，回填返回 `Backfill completed for batch: demo`，PV 汇总返回行数，快照返回月/年数量，最后一行 `演示数据已就绪。`。后端在容器里，回填大约一分钟。

- [ ] **Step 3: 跑冒烟测试**

Run: `scripts/smoke-test.sh`
Expected: 每一项都是 `✓`，最后一行 `冒烟测试全部通过。`，退出码 0。

再验证脚本在后端不可用时确实会失败（不是总返回成功）：

```bash
WEB_URL=http://localhost:18999 scripts/smoke-test.sh; echo "退出码 $?"
```

Expected: 大部分检查显示 `✗`，最后 `冒烟测试有失败项。`，退出码 `1`。

- [ ] **Step 4: 提交**

```bash
git add scripts
git commit -m "feat: add demo data seeding and smoke test scripts

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 项目 README

**Files:**
- Create: `README.md`

- [ ] **Step 1: 写文档**

`README.md`：

````markdown
# 稼先社区运营分析平台

面向公司内网"稼先社区"（类今日头条的内容社区）的运营分析平台：监控、分析和预测社区的浏览量（PV）和人气，识别头部博主（KOL）并给出运营建议。**这是一个模拟/验证参考项目**——社区本身的数据由内置的模拟器产生，目的是验证分析方案；组件和 `docker-compose.yml` 同时作为正式团队的最小可运行参考环境。

## 能做什么

- **PV 多维分析**：按频道、账号、博主、内容类型切片，日/周/月/年粒度，环比、同比和简单趋势预测（移动平均 + 漂移，带置信区间）。
- **积分排行榜**：员工有"主动行为"和"被动互动"两类积分（发帖、登录、被浏览、被点赞……），月榜/年榜按**当期**积分排名，已结束的周期有归档快照。
- **KOL 管理**：对近 12 个月积分前 1000 名做 RFM 打分和分层（明星 KOL、稳定贡献者、成长潜力新人、流失预警、沉寂用户、观察中），流失预警，运营建议文案库，博主详情（RFM 历史轨迹、活跃时间轴、积分构成）。
- **数据模拟器**：历史批量回填 + 实时事件（Kafka）模拟，带"博主画像"，让每个分层都能在模拟数据里出现。
- **系统管理**：积分权重（按生效日期版本化）、建议文案库、数据任务（重算汇总/快照/KOL 结果）。

## 架构

```
浏览器 ──▶ Nginx（托管 Vue 静态资源，/api 反向代理）
                      │
                      ▼
                Spring Boot 后端 ──▶ OpenGauss（明细 + 汇总 + 快照）
                      │   ▲
                      │   └── Kafka（实时模拟事件 → 消费者落库、更新 Redis 计数器）
                      └────▶ Redis（今日实时计数器）
```

| 目录 | 内容 |
|---|---|
| `backend/` | Spring Boot 3（Java 17）：模拟器、积分引擎、Kafka 流水线、分析接口 |
| `frontend/` | Vue 3 + TypeScript + Vite + Element Plus + ECharts |
| `scripts/` | 演示数据脚本、冒烟测试脚本 |
| `docs/superpowers/` | 设计文档（`specs/`）和实施计划（`plans/`） |
| `docker-compose.yml` | 基础设施（OpenGauss、Redis、Kafka）和应用（后端、Nginx）的编排 |

## 快速开始：全栈容器

前提：Docker（macOS 上可以用 Colima，先 `colima start`）。

```bash
cp .env.example .env                       # 可选：按需修改口令和端口
docker compose --profile app up -d --build # 构建并拉起全栈（首次构建要几分钟）
scripts/seed-demo.sh                       # 灌入演示数据（历史回填 + 重算，约一两分钟）
scripts/smoke-test.sh                      # 可选：冒烟测试
```

然后打开 http://localhost:8088 。后端也直接暴露在 http://localhost:8081（调管理接口方便）。

停止：`docker compose --profile app down`（加 `-v` 会同时删除数据卷）。

## 开发模式

只拉起基础设施，后端和前端在本机跑：

```bash
docker compose up -d                                                       # OpenGauss + Redis + Kafka
cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8093
cd frontend && npm install && VITE_API_TARGET=http://localhost:8093 npm run dev   # http://localhost:5173
```

> 如果本机 8080 没被占用，后端可以不加 `--server.port`，前端也不用设置 `VITE_API_TARGET`。

## 测试

```bash
docker compose up -d           # 后端的集成测试连真实的 OpenGauss、Redis 和 Kafka
cd backend && mvn test         # 后端：单元测试 + 集成测试
cd frontend && npm test        # 前端：纯逻辑和图表配置的单元测试
cd frontend && npm run build   # 前端：类型检查 + 生产构建
```

## 配置

通过环境变量（Docker 下写在 `.env` 里，见 `.env.example`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `GS_USERNAME` / `GS_PASSWORD` | `jiaxian_app` / `Jiaxian@2026` | 数据库业务账号。**默认口令只适合演示，部署前请修改**（OpenGauss 要求同时包含大小写字母、数字和特殊字符） |
| `WEB_PORT` | `8088` | 前端（Nginx）暴露在宿主机的端口 |
| `API_PORT` | `8081` | 后端暴露在宿主机的端口 |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | 后端 JVM 参数 |
| `TZ` | `Asia/Shanghai` | 后端容器时区（后端用系统时区取"今天"） |

后端自身的配置在 `backend/src/main/resources/application.yml`，数据库、Redis、Kafka 地址都可以用环境变量覆盖（`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`KAFKA_BOOTSTRAP_SERVERS`）；KOL 分析的参数在 `analytics.kol.*`（名单人数、统计窗口、流失预警阈值等）。

## 演示流程

1. 打开 http://localhost:8088/admin/simulator，点"清空并回填"（或运行 `scripts/seed-demo.sh`），再点"一键重算全部"。
2. **总览看板**：切换日/周/月/年，用频道/账号/博主/内容类型筛选；趋势图的虚线和浅色带是预测和置信区间。
3. **积分排行榜**：切换月榜/年榜和周期，看主动/被动积分构成。
4. **KOL 管理**：点击分层卡片筛选；点进一位"流失预警"的博主，看 RFM 历史、标签变化和活跃时间轴的断崖。
5. **系统管理**：修改积分权重（只影响之后新产生的事件）、维护建议文案库、启动实时模拟并观察已发送事件数。

## 常见问题

- **`colima is not running` / 连不上 Docker**：`colima start`，然后等 OpenGauss 和 Kafka 就绪（`docker compose ps` 都是 `healthy`）。
- **端口被占用**：在 `.env` 里改 `WEB_PORT`、`API_PORT`；本机 `8080` 常被别的进程占用，所以默认用了 `8088`/`8081`。
- **OpenGauss 首次启动慢**：初始化要几十秒，`backend` 会等它健康后才启动，耐心等 `docker compose --profile app ps`。
- **历史回填要一两分钟**：整个回填在一个事务里，期间页面按钮显示"回填中…"，数据库里也看不到新数据，完成后才一次性可见。
- **Vite 开发服务器第一次打开某个页面时控制台有一条 404**：开发模式下依赖重新优化造成的，刷新即可，生产构建没有。
- **KOL 页面全是"沉寂"**：模拟数据是历史的，以"今天"为统计日所有人都不活跃了。用控制台的"一键重算全部"，它会自动选一个合适的统计日。

## 已知限制

- 所有 `/api/admin/**` 接口（包括会清空数据的历史回填）**没有鉴权**，这是整个项目一直约定的范围——**必须部署在内网，不能暴露到公网**。
- 单实例部署，不含高可用、HTTPS、日志收集和监控告警。
- 默认口令仅用于演示。

## 设计文档与实施计划

按建设顺序：

| 阶段 | 设计 | 计划 |
|---|---|---|
| 总体设计 | [community-analytics-design](docs/superpowers/specs/2026-09-23-community-analytics-design.md) | — |
| 基础设施与数据模型、历史回填模拟器 | 同上 | [infra-and-data-model](docs/superpowers/plans/2026-09-23-infra-and-data-model.md) |
| 积分引擎与 Kafka 实时流水线 | [points-engine-kafka-pipeline](docs/superpowers/specs/2026-09-23-points-engine-kafka-pipeline-design.md) | [kafka-realtime-pipeline](docs/superpowers/plans/2026-10-04-kafka-realtime-pipeline.md) |
| PV 多维聚合与预测 | [pv-analytics](docs/superpowers/specs/2026-10-04-pv-analytics-design.md) | [pv-analytics](docs/superpowers/plans/2026-10-04-pv-analytics.md) |
| 积分排行榜 | [leaderboard](docs/superpowers/specs/2026-10-04-leaderboard-design.md) | [leaderboard](docs/superpowers/plans/2026-10-04-leaderboard.md) |
| KOL 分层与流失预警 | [kol-segmentation](docs/superpowers/specs/2026-10-04-kol-segmentation-design.md) | [kol-segmentation](docs/superpowers/plans/2026-10-04-kol-segmentation.md) |
| 博主详情页接口 | [blogger-profile](docs/superpowers/specs/2026-10-04-blogger-profile-design.md) | [blogger-profile](docs/superpowers/plans/2026-10-04-blogger-profile.md) |
| 前端：总览与排行榜 | [frontend-dashboard](docs/superpowers/specs/2026-10-04-frontend-dashboard-design.md) | [frontend-dashboard](docs/superpowers/plans/2026-10-04-frontend-dashboard.md) |
| 前端：KOL 与系统管理 | [kol-and-admin-ui](docs/superpowers/specs/2026-10-05-kol-and-admin-ui-design.md) | [kol-and-admin-ui](docs/superpowers/plans/2026-10-05-kol-and-admin-ui.md) |
| 部署与整合 | [deployment](docs/superpowers/specs/2026-10-05-deployment-design.md) | [deployment](docs/superpowers/plans/2026-10-05-deployment.md) |
````

- [ ] **Step 2: 校验 README 里的链接都指向真实存在的文件**

```bash
grep -o '(docs/superpowers/[^)]*)' README.md | tr -d '()' | sort -u | while read -r f; do
  [ -f "$f" ] && echo "ok   $f" || echo "缺失 $f"
done
```

Expected: 全部是 `ok`，没有"缺失"。如果有缺失，对照 `ls docs/superpowers/specs docs/superpowers/plans` 修正文件名。

- [ ] **Step 3: 提交**

```bash
git add README.md
git commit -m "docs: add project README with quick start, configuration and demo flow

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 浏览器验证与收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-10-05-deployment-design.md`（状态行）、`docs/superpowers/specs/2026-09-23-community-analytics-design.md`（一句说明）

- [ ] **Step 1: 全栈容器在运行并有演示数据**

确认 Task 4/5 的全栈仍在运行：`docker compose --profile app ps --format '{{.Name}} {{.Status}}'`；演示数据已灌入（`curl -s localhost:8088/api/meta/pv-range` 的 `to` 不是 null）。

- [ ] **Step 2: 在内置浏览器里检查生产部署的页面**

打开 `http://localhost:8088`（这是 Nginx 托管的生产构建，不是 Vite 开发服务器），逐页检查并截图：

1. **总览看板**：趋势图、预测虚线、Top N；换粒度/筛选正常。
2. **积分排行榜**：周期下拉、榜单、堆叠条。
3. **KOL 管理**：分层卡片、名单；点进一位博主看详情（刷新详情页 `/kol/<id>` 也能正常显示，验证 SPA 回退）。
4. **系统管理**：数据模拟器页（启动/停止实时模拟，观察计数增长）、积分权重页、建议文案库页。
5. 浏览器控制台**没有错误**（生产构建没有 Vite 开发模式的 404）。
6. 顶栏显示"后端正常"。

- [ ] **Step 3: 确认单独起基础设施时开发流程不受影响**

```bash
docker compose --profile app down
docker compose up -d
docker compose ps --format '{{.Name}} {{.Status}}'
cd backend && mvn test
```

Expected: 只有 `opengauss`、`redis`、`kafka` 三个容器在运行且健康；`mvn test` 全部通过。

- [ ] **Step 4: 更新文档状态并提交**

把 `docs/superpowers/specs/2026-10-05-deployment-design.md` 的 `- 状态：已通过讨论确认` 改为 `- 状态：已实现（实施计划见 [部署与整合](../plans/2026-10-05-deployment.md)）`。

在 `docs/superpowers/specs/2026-09-23-community-analytics-design.md` 第 9 节"部署"那一段之后加一行：

```markdown
> 实现进度（见 [部署设计](2026-10-05-deployment-design.md)）：`docker compose --profile app up -d --build` 一条命令拉起全栈（Nginx 托管前端并反向代理 `/api`，Kafka 使用 `INTERNAL`/`EXTERNAL` 双监听）；`scripts/seed-demo.sh` 灌入演示数据，`scripts/smoke-test.sh` 做冒烟测试；根目录 `README.md` 汇总了运行方式、配置和已知限制。
```

```bash
git add docs
git commit -m "docs: mark deployment spec as implemented

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

之后按 `superpowers:finishing-a-development-branch` 收尾；推送到 GitHub 只在用户要求时进行。

---

## Self-Review

**1. 设计文档覆盖：**
- 第 3 节 compose 布局（基础设施无 profile、应用服务 `app` profile、端口、数据卷）→ Task 1、4。
- 第 4 节 Kafka 双监听 → Task 1（含"宿主机测试不被破坏"的验证）。
- 第 5 节健康检查与启动顺序 → Task 1（基础设施）、Task 4（`backend`、`web` 的健康检查和 `depends_on`）。
- 第 6 节镜像（两阶段、`curl`、`JAVA_OPTS`、环境变量、Nginx 配置的 SPA 回退/缓存/gzip/反向代理/超时、`.dockerignore`）→ Task 2、3。
- 第 7 节配置（`.env.example`、口令只定义一处、默认口令提醒）→ Task 4（`.env.example`、compose 里 `GS_PASSWORD` 同时用于 OpenGauss 和后端）、Task 6（README 配置表）。
- 第 8 节演示数据脚本与冒烟测试 → Task 5。
- 第 9、10 节文档与已知限制 → Task 6。
- 第 11 节验证（构建、拉起、脚本、浏览器、基础设施单独拉起后 `mvn test`）→ Task 4、5、7。

**2. 占位符扫描：** 无 TBD/TODO。各"如果……则……"的排查说明都给了具体的应对步骤。

**3. 一致性：** 端口默认值（`WEB_PORT=8088`、`API_PORT=8081`）在 compose、`.env.example`、脚本默认 `API_URL`/`WEB_URL`、README 里一致；Kafka 内部地址 `kafka:29092` 与后端容器的 `KAFKA_BOOTSTRAP_SERVERS` 一致；`GS_USERNAME`/`GS_PASSWORD` 在 OpenGauss、`backend` 的 `DB_USERNAME`/`DB_PASSWORD`、README 之间一致；演示数据的默认参数与页面的 `defaultBackfillForm` 一致（50 员工、20 账号、0.25、300 内容、种子 7、画像开、结束日期今天往前 30 天、开始日期往前 18 个月的 1 号）；KOL 统计日"PV 最后一天往前 30 天"与页面的 `kolAsOfFromPvEnd` 一致。
