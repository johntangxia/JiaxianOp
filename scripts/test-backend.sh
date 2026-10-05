#!/usr/bin/env bash
# 跑后端测试：先确保独立的测试库存在，再执行 mvn test，额外参数原样传给 mvn。
# 例：scripts/test-backend.sh                      （全部测试，约 15 分钟，建议后台运行）
#     scripts/test-backend.sh -Dtest=PeopleEndToEndIT
# 测试用独立的库、Kafka 主题、消费组和 Redis 库，演示数据和运行中的后端容器都不受影响。
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/create-test-db.sh
cd backend
exec mvn test "$@"
