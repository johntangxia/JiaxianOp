#!/usr/bin/env python3
"""Export a bounded, public-safe snapshot from the *simulated* local API.

Only GET requests to explicitly listed analytics endpoints are made. This script
never calls simulator, admin, seeding, or database restore operations.
"""

import argparse
import json
import re
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "frontend/src/demo/snapshot.json"
ALLOWED = {
    "/api/meta/pv-range",
    "/api/analytics/pv/trend",
    "/api/analytics/pv/top",
    "/api/channels/cockpit",
    "/api/contest/periods",
    "/api/contest/boards",
    "/api/readers/activity",
    "/api/readers/funnel",
    "/api/readers/segments",
    "/api/creators/overview",
    "/api/creators/ranking",
    "/api/bloggers/runs",
    "/api/bloggers/top1000",
    "/api/users/list",
}


def pick(obj, *keys):
    return {key: obj[key] for key in keys}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8081")
    args = parser.parse_args()

    def get(path, **params):
        if path not in ALLOWED and not re.fullmatch(r"/api/users/\d+", path):
            raise ValueError(f"endpoint not allowed: {path}")
        suffix = "?" + urlencode(params) if params else ""
        with urlopen(args.base_url.rstrip("/") + path + suffix, timeout=30) as response:
            return json.load(response)

    data_range = get("/api/meta/pv-range")
    if not data_range.get("from") or not data_range.get("to"):
        raise ValueError("simulated PV range is empty")
    end = date.fromisoformat(data_range["to"])
    recent = end - timedelta(days=179)
    recent = max(recent, date.fromisoformat(data_range["from"]))
    month_index = end.year * 12 + end.month - 1 - 11
    annual = date(month_index // 12, month_index % 12 + 1, 1)
    annual = max(annual, date.fromisoformat(data_range["from"]))
    to = end.isoformat()
    recent_from = recent.isoformat()
    annual_from = annual.isoformat()

    pv_raw = get("/api/analytics/pv/trend", granularity="MONTH", **{"from": annual_from, "to": to, "forecastPeriods": 3})
    pv = pick(pv_raw, "granularity", "from", "to", "points", "forecast")
    top = {}
    for dimension in ("CHANNEL", "ACCOUNT", "EMPLOYEE", "CONTENT_TYPE"):
        rows = get("/api/analytics/pv/top", dimension=dimension, **{"from": annual_from, "to": to, "limit": 20})
        top[dimension] = [
            {
                "id": row["id"] if dimension in ("CHANNEL", "CONTENT_TYPE") else i,
                "name": (f"演示账号 {i:03d}" if dimension == "ACCOUNT" else f"演示博主 {i:03d}" if dimension == "EMPLOYEE" else row["name"]),
                "pv": row["pv"],
            }
            for i, row in enumerate(rows, 1)
        ]

    cockpit_raw = get("/api/channels/cockpit", **{"from": recent_from, "to": to})
    cockpit = pick(cockpit_raw, "from", "to", "previousFrom", "previousTo", "composite", "domain", "compositeBenchmark", "domainBenchmark")
    for key in ("composite", "domain"):
        cockpit[key] = [pick(row, "id", "name", "category", "current", "previous", "diagnoses") for row in cockpit[key]]

    boards = {}
    for kind in ("WEEK", "MONTH"):
        periods = get("/api/contest/periods", type=kind)
        complete = next((p for p in periods if p["complete"] and p["periodEnd"] <= to), None)
        if complete is None:
            raise ValueError(f"no complete {kind} contest period")
        board = get("/api/contest/boards", type=kind, periodStart=complete["periodStart"])
        boards[kind] = pick(board, "type", "periodStart", "periodEnd", "previousStart", "previousEnd", "previousHasData", "domain", "composite", "improvers")

    reader_activity = get("/api/readers/activity", **{"from": recent_from, "to": to, "granularity": "MONTH"})
    reader_funnel = get("/api/readers/funnel", **{"from": recent_from, "to": to})
    reader_segments = get("/api/readers/segments", **{"from": recent_from, "to": to})
    creator_overview = get("/api/creators/overview", **{"from": recent_from, "to": to, "granularity": "MONTH"})
    ranking_raw = get("/api/creators/ranking", **{"from": recent_from, "to": to, "sort": "POSTS", "limit": 20, "offset": 0})
    creator_ranking = [
        {**pick(row, "posts", "pv", "uv", "interactions", "interactionRate", "completionRate", "newFollowers"),
         "name": f"演示博主 {i:03d}", "dept": row["dept"]}
        for i, row in enumerate(ranking_raw["creators"], 1)
    ]

    runs = get("/api/bloggers/runs")
    as_of = next((run for run in runs if run <= to), None)
    if as_of is None:
        raise ValueError("no KOL run in data range")
    kol_raw = get("/api/bloggers/top1000", asOf=as_of, limit=1000, offset=0)
    selected = []
    for segment in ("STAR_KOL", "STEADY", "RISING", "CHURN_RISK", "DORMANT", "OBSERVE"):
        selected.extend(row for row in kol_raw["items"] if row["segment"] == segment)  # order retains rank
        # Keep at most ten examples per segment; no person identity or profile is exported.
        selected = [row for row in selected if row["segment"] != segment] + [row for row in selected if row["segment"] == segment][:10]
    selected.sort(key=lambda row: row["rank"])
    kol_items = [
        {**pick(row, "rank", "rollingPoints12m", "recencyDays", "frequency", "monetary", "rScore", "fScore", "mScore", "segment", "segmentName", "churnAlert", "recommendation"),
         "employeeId": i, "name": f"演示博主 {i:03d}"}
        for i, row in enumerate(selected, 1)
    ]

    # Two examples per lifecycle stage provide a bounded 360 tour. Internal
    # employee IDs are used only for these read-only requests, never exported.
    user_ids = []
    for stage in ("NEW", "ACTIVE", "RETURNING", "LAPSING", "CHURNED", "DORMANT"):
        page = get("/api/users/list", asOf=to, stage=stage, sort="VIEWS", limit=2, offset=0)
        user_ids.extend(row["employeeId"] for row in page["users"])
    users = []
    for original_id in dict.fromkeys(user_ids):
        profile = get(f"/api/users/{original_id}", asOf=to)
        basic = pick(profile["basic"], "dept", "joinDate", "daysInCommunity", "stage", "stageLabel", "previousStage", "previousStageLabel", "valueSegment", "valueLabel", "creatorStage", "creatorStageLabel")
        basic.update({"employeeId": original_id, "name": f"演示用户 {original_id:03d}"})
        follows = [
            {"accountId": j, "accountName": f"演示账号 {j:03d}", "followTime": row["followTime"], "unfollowTime": row["unfollowTime"]}
            for j, row in enumerate(profile["follows"], 1)
        ]
        users.append({
            "asOf": profile["asOf"],
            "basic": basic,
            "recent": profile["recent"],
            "timeline": profile["timeline"],
            "reading": profile["reading"],
            "follows": follows,
            "points": profile["points"],
            "recentEvents": [{"time": event["time"], "type": event["type"], "refId": None} for event in profile["recentEvents"]],
            "hasPosts": profile["hasPosts"],
            "kol": profile["kol"],
        })

    snapshot = {
        "snapshotDate": to,
        "pv": pv,
        "top": top,
        "cockpit": cockpit,
        "boards": boards,
        "readerActivity": pick(reader_activity, "from", "to", "granularity", "summary", "points"),
        "readerFunnel": pick(reader_funnel, "from", "to", "joined", "steps"),
        "readerSegments": pick(reader_segments, "from", "to", "members", "segments"),
        "creatorOverview": pick(creator_overview, "from", "to", "granularity", "summary", "series", "periods", "dataEnd", "cohorts"),
        "creatorRanking": creator_ranking,
        "kol": {"asOf": as_of, "sourceTotal": kol_raw["total"], "items": kol_items},
        "users": users,
    }
    encoded = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")) + "\n"
    if "员工-" in encoded or "个人账号" in encoded or "团队账号" in encoded:
        raise ValueError("unredacted simulated identity in snapshot")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(encoded, encoding="utf-8")
    print(f"Wrote {OUTPUT} ({len(encoded.encode('utf-8'))} bytes; as of {to})")


if __name__ == "__main__":
    main()
