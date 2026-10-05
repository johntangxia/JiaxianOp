#!/usr/bin/env bash
# 建后端集成测试用的独立数据库 jiaxian_test（幂等：已经存在就什么都不做）。
# 测试连这个库、独立的 Kafka 主题/消费组和 Redis 库，不会碰演示数据，也不用停后端容器。
# 用法：scripts/create-test-db.sh      （前提：docker compose 里的 opengauss 在运行）
set -euo pipefail

DB_NAME="${TEST_DB_NAME:-jiaxian_test}"
GS_USERNAME="${GS_USERNAME:-jiaxian_app}"
GS_PASSWORD="${GS_PASSWORD:-Jiaxian@2026}"

# gsql 库名 SQL
gsql() {
  docker exec -e LD_LIBRARY_PATH=/usr/local/opengauss/lib jiaxian-opengauss /usr/local/opengauss/bin/gsql \
    -h 127.0.0.1 -U "$GS_USERNAME" -d "$1" -W "$GS_PASSWORD" -q -t -A -c "$2"
}

exists=$(gsql postgres "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" || true)
if [[ $exists == 1 ]]; then
  echo "测试库 $DB_NAME 已存在。"
else
  gsql postgres "CREATE DATABASE $DB_NAME WITH ENCODING 'UTF8' DBCOMPATIBILITY 'A'" > /dev/null
  echo "已创建测试库 $DB_NAME（表结构会在测试第一次启动时自动建好）。"
fi
# 新库的 public 模式只有超级用户能建表（超级用户不能远程登录），所以在库里建一个和业务账号同名的模式：
# 默认的 search_path 是 "$user", public，表会建在这个模式里
gsql "$DB_NAME" "CREATE SCHEMA IF NOT EXISTS $GS_USERNAME AUTHORIZATION $GS_USERNAME" > /dev/null
