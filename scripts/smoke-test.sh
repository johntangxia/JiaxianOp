#!/usr/bin/env bash
# 冒烟测试：在全栈已拉起、演示数据已灌入（scripts/seed-demo.sh）之后执行。
# 用法：scripts/smoke-test.sh            （默认检查 http://localhost:8090）
#       WEB_URL=http://localhost:8090 scripts/smoke-test.sh
set -uo pipefail

WEB_URL="${WEB_URL:-http://localhost:8090}"
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
expect_json "频道列表（5 综合 + 23 领域）" "$WEB_URL/api/dimensions/channels" \
  'sum(1 for c in d if c["category"] == "综合频道") == 5 and sum(1 for c in d if c["category"] == "领域频道") == 23'
expect_json "员工搜索" "$WEB_URL/api/dimensions/employees?limit=5" 'len(d) > 0'
expect_json "实时模拟状态" "$WEB_URL/api/admin/simulator/realtime/status" '"running" in d'

echo
if [[ $failed -eq 0 ]]; then
  echo "冒烟测试全部通过。"
else
  echo "冒烟测试有失败项。" >&2
  exit 1
fi
