#!/usr/bin/env bash
# 灌入演示数据：历史回填 → PV 汇总 → 榜单快照 → KOL 重算。
# 用法：scripts/seed-demo.sh            （默认调用 http://localhost:8081）
#       API_URL=http://localhost:8081 CONTENT_COUNT=150 scripts/seed-demo.sh
# 注意：历史回填会清空所有模拟数据（员工、内容、行为、积分流水）以及依赖它们的汇总、快照和 KOL 结果。
set -euo pipefail

API_URL="${API_URL:-http://localhost:8081}"
EMPLOYEE_COUNT="${EMPLOYEE_COUNT:-1000}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-100}"
CONTENT_COUNT="${CONTENT_COUNT:-1500}"
RANDOM_SEED="${RANDOM_SEED:-7}"

# 结束日期 = 今天（不含，数据到昨天为止；浏览由登录产生，不会越过结束日）；
# 开始日期 = 结束日期往前 18 个月的 1 号。
read -r HISTORY_START HISTORY_END < <(python3 - <<'PY'
import datetime as d
end = d.date.today()
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

# 不传统计日：后端默认取最近一次登录/发帖的日期（模拟的"现在"），以今天为统计日所有人都会变成沉寂
echo "4/4 KOL 重算（统计日取最近一次登录/发帖的日期，12 周）..."
call POST "/api/admin/analytics/kol/rebuild?weeks=12" > /dev/null

echo "演示数据已就绪。"
