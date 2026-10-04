# 构建阶段：Maven 打包（跳过测试，测试需要真实的数据库、Redis 和 Kafka）
# 不使用 BuildKit 专属特性（缓存挂载、# syntax 指令），这样没有装 buildx 的环境也能用经典构建器构建。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 依赖单独一层：只要 pom.xml 没变，源码改动时这一层来自缓存，不用重新下载
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
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
