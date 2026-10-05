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

echo "频道驾驶舱"
COCKPIT_FROM=$(python3 -c "import datetime as d; print(d.date.fromisoformat('$TO') - d.timedelta(days=29))")
expect_json "驾驶舱（5 综合 + 23 领域，含诊断和基准）" "$WEB_URL/api/channels/cockpit?from=$COCKPIT_FROM&to=$TO" \
  'len(d["composite"]) == 5 and len(d["domain"]) == 23 and all("diagnoses" in r for r in d["domain"]) and "OWN_ENTRY_SHARE" in d["domainBenchmark"]'
expect_json "驾驶舱：领域 PV 合计大于 0" "$WEB_URL/api/channels/cockpit?from=$COCKPIT_FROM&to=$TO" \
  'sum(r["current"]["content"]["pv"] for r in d["domain"]) > 0'
CHANNEL_ID=$(json_value "$WEB_URL/api/channels/cockpit?from=$COCKPIT_FROM&to=$TO" 'max(d["domain"], key=lambda r: r["current"]["content"]["pv"])["id"]')
expect_json "频道详情（趋势、构成、Top 内容）" "$WEB_URL/api/channels/$CHANNEL_ID/detail?from=$COCKPIT_FROM&to=$TO&granularity=WEEK" \
  'len(d["trend"]) > 0 and len(d["composition"]) == 5 and len(d["topContents"]) > 0'
expect_status "驾驶舱拒绝颠倒的日期范围" "$WEB_URL/api/channels/cockpit?from=$TO&to=$COCKPIT_FROM" 400
expect_status "不存在的频道返回 404" "$WEB_URL/api/channels/999999999/detail?from=$COCKPIT_FROM&to=$TO" 404

echo "频道竞赛榜"
expect_json "竞赛周期（月）" "$WEB_URL/api/contest/periods?type=MONTH" 'len(d) > 0'
CONTEST_MONTH=$(json_value "$WEB_URL/api/contest/periods?type=MONTH" 'next((p["periodStart"] for p in d if p["complete"]), d[0]["periodStart"])')
expect_json "竞赛榜（领域 23 个、综合 5 个、各 5 个维度）" "$WEB_URL/api/contest/boards?type=MONTH&periodStart=$CONTEST_MONTH" \
  'len(d["domain"]) == 23 and len(d["composite"]) == 5 and all(len(e["dimensions"]) == 5 for e in d["domain"] + d["composite"])'
expect_status "竞赛榜拒绝不是 1 号的月榜周期" "$WEB_URL/api/contest/boards?type=MONTH&periodStart=2025-06-02" 400

echo "下钻与对比"
expect_json "频道下的账号" "$WEB_URL/api/drill/channels/$CHANNEL_ID/accounts?from=$COCKPIT_FROM&to=$TO" 'len(d["accounts"]) > 0'
ACCOUNT_ID=$(json_value "$WEB_URL/api/drill/channels/$CHANNEL_ID/accounts?from=$COCKPIT_FROM&to=$TO" 'd["accounts"][0]["id"]')
expect_json "账号下钻（趋势、作者、频道分布、Top 内容、关注者）" "$WEB_URL/api/drill/accounts/$ACCOUNT_ID?from=$COCKPIT_FROM&to=$TO&granularity=WEEK" \
  'len(d["trend"]) > 0 and len(d["authors"]) > 0 and len(d["channels"]) > 0 and d["current"]["followers"] is not None'
DRILL_BLOGGER=$(json_value "$WEB_URL/api/drill/accounts/$ACCOUNT_ID?from=$COCKPIT_FROM&to=$TO&granularity=WEEK" 'd["authors"][0]["id"]')
expect_json "博主下钻（作者视角）" "$WEB_URL/api/drill/bloggers/$DRILL_BLOGGER?from=$COCKPIT_FROM&to=$TO" \
  'd["current"]["followers"] is None and len(d["accounts"]) > 0'
DRILL_CONTENT=$(json_value "$WEB_URL/api/drill/accounts/$ACCOUNT_ID?from=$COCKPIT_FROM&to=$TO&granularity=WEEK" 'd["topContents"][0]["contentId"]')
expect_json "内容下钻（入口曝光、标签、作者）" "$WEB_URL/api/drill/contents/$DRILL_CONTENT?from=$COCKPIT_FROM&to=$TO" \
  'len(d["tags"]) >= 3 and len(d["authors"]) >= 1 and len(d["composition"]) == 5'
SECOND_CHANNEL=$(json_value "$WEB_URL/api/channels/cockpit?from=$COCKPIT_FROM&to=$TO" 'sorted(d["domain"], key=lambda r: -r["current"]["content"]["pv"])[1]["id"]')
expect_json "对比两个领域频道" "$WEB_URL/api/compare?type=CHANNEL&ids=$CHANNEL_ID,$SECOND_CHANNEL&from=$COCKPIT_FROM&to=$TO&granularity=WEEK" \
  'len(d["subjects"]) == 2 and d["subjects"][0]["ownEntry"] is not None'
expect_status "对比拒绝只选一个对象" "$WEB_URL/api/compare?type=CHANNEL&ids=$CHANNEL_ID&from=$COCKPIT_FROM&to=$TO" 400
expect_status "不存在的账号返回 404" "$WEB_URL/api/drill/accounts/999999999?from=$COCKPIT_FROM&to=$TO" 404

echo "读者侧分析"
READER_TO=$(json_value "$WEB_URL/api/readers/range" 'd["to"]')
READER_FROM=$(python3 -c "import datetime as d; print(d.date.fromisoformat('$READER_TO') - d.timedelta(days=179))")
expect_json "活跃与增长（DAU/WAU/MAU 与趋势）" "$WEB_URL/api/readers/activity?from=$READER_FROM&to=$READER_TO&granularity=WEEK" \
  'len(d["points"]) > 0 and d["summary"]["mau"] >= d["summary"]["wau"] >= d["summary"]["dau"] and d["summary"]["members"] > 0'
expect_json "留存同期群（按月）" "$WEB_URL/api/readers/retention?granularity=MONTH&from=$READER_FROM&to=$READER_TO" \
  'len(d["cohorts"]) > 0 and all(len(c["retention"]) == d["periods"] for c in d["cohorts"])'
expect_json "激活漏斗（5 步）" "$WEB_URL/api/readers/funnel?from=$READER_FROM&to=$READER_TO" 'len(d["steps"]) == 5 and d["joined"] > 0'
expect_json "读者分层（5 类，合计 = 成员数）" "$WEB_URL/api/readers/segments?from=$READER_FROM&to=$READER_TO" \
  'len(d["segments"]) == 5 and sum(s["count"] for s in d["segments"]) == d["members"]'
expect_json "沉默读者名单（有姓名）" "$WEB_URL/api/readers/lurkers?from=$READER_FROM&to=$READER_TO&limit=5" \
  'd["total"] > 0 and len(d["lurkers"]) > 0 and all(l["name"] for l in d["lurkers"])'
expect_status "读者分析拒绝非法的同期群粒度" "$WEB_URL/api/readers/retention?granularity=DAY&from=$READER_FROM&to=$READER_TO" 400
expect_status "沉默读者名单拒绝超大的 limit" "$WEB_URL/api/readers/lurkers?from=$READER_FROM&to=$READER_TO&limit=101" 400

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
