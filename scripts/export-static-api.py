#!/usr/bin/env python3
"""Capture additional read-only, simulated API examples for the public demo."""

import argparse
import json
import re
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DEMO = ROOT / "frontend/src/demo"
snapshot = json.loads((DEMO / "snapshot.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--base-url", default="http://localhost:8081")
base = parser.parse_args().base_url.rstrip("/")
start, end = snapshot["cockpit"]["from"], snapshot["snapshotDate"]


def get(path, **query):
    # Explicitly limit export to read-only analytics routes.
    if not path.startswith(("/api/analytics/", "/api/dimensions/", "/api/channels/", "/api/drill/", "/api/compare", "/api/contest/", "/api/readers/", "/api/creators/", "/api/users/", "/api/growth/", "/api/leaderboard/", "/api/bloggers/")):
        raise ValueError(f"endpoint not allowed: {path}")
    suffix = "?" + urlencode(query) if query else ""
    with urlopen(base + path + suffix, timeout=30) as response:
        return json.load(response)


def scrub(value):
    if isinstance(value, list):
        return [scrub(item) for item in value]
    if isinstance(value, dict):
        return {key: scrub(item) for key, item in value.items()}
    if isinstance(value, str):
        value = re.sub(r"员工-(\d+)", lambda match: f"演示用户 {int(match.group(1)) + 1:03d}", value)
        value = re.sub(r"(?:个人|团队)账号-(\d+)", lambda match: f"演示账号 {int(match.group(1)) + 1:03d}", value)
    return value


def main():
    result = {
        "dimensions": {
            "channels": get("/api/dimensions/channels"),
            "accounts": scrub(get("/api/dimensions/accounts", keyword="", limit=100)),
            "employees": scrub(get("/api/dimensions/employees", keyword="", limit=100)),
        },
        "pvTrends": {}, "topByGranularity": {},
        "channelDetails": {}, "channelDetailsByGranularity": {}, "channelAccounts": {},
        "accounts": {}, "accountsByGranularity": {}, "bloggers": {}, "bloggersByGranularity": {},
        "contents": {}, "contentsByGranularity": {},
        "readerActivity": {}, "readerRetention": {}, "readerFunnel": snapshot["readerFunnel"],
        "readerSegments": snapshot["readerSegments"],
        "creatorOverview": {}, "creatorRanking": {},
        "users": {}, "growth": {}, "leaderboard": {}, "kolProfiles": {}, "kolActivity": {}, "kolPoints": {},
        "kolActivityByGranularity": {}, "kolPointsByGranularity": {},
    }

    last = date.fromisoformat(end)
    overview_starts = {
        "DAY": (last - timedelta(days=29)).isoformat(),
        "WEEK": (last - timedelta(days=175)).isoformat(),
        "MONTH": snapshot["pv"]["from"],
        "YEAR": "2025-04-01",
    }
    for granularity, beginning in overview_starts.items():
        result["pvTrends"][granularity] = get("/api/analytics/pv/trend", granularity=granularity, **{"from": beginning, "to": end, "forecastPeriods": 3})
        result["topByGranularity"][granularity] = {}
        for dimension in ("CHANNEL", "ACCOUNT", "EMPLOYEE", "CONTENT_TYPE"):
            result["topByGranularity"][granularity][dimension] = scrub(get("/api/analytics/pv/top", dimension=dimension, **{"from": beginning, "to": end, "limit": 20}))

    for granularity in ("DAY", "WEEK", "MONTH"):
        result["readerActivity"][granularity] = get("/api/readers/activity", **{"from": start, "to": end, "granularity": granularity})
    for granularity in ("WEEK", "MONTH"):
        result["readerRetention"][granularity] = get("/api/readers/retention", **{"from": start, "to": end, "granularity": granularity, "periods": 12})
        result["creatorOverview"][granularity] = get("/api/creators/overview", **{"from": start, "to": end, "granularity": granularity})
    result["readerLurkers"] = scrub(get("/api/readers/lurkers", **{"from": start, "to": end, "limit": 100, "offset": 0}))
    result["creatorLadder"] = get("/api/creators/ladder", asOf=end)
    result["creatorConcentration"] = get("/api/creators/concentration", **{"from": start, "to": end})
    for sort in ("POSTS", "PV", "INTERACTION_RATE", "COMPLETION", "NEW_FOLLOWERS"):
        result["creatorRanking"][sort] = scrub(get("/api/creators/ranking", **{"from": start, "to": end, "sort": sort, "limit": 100, "offset": 0}))

    channel_rows = snapshot["cockpit"]["composite"] + snapshot["cockpit"]["domain"]
    account_ids = set()
    content_ids = set()
    for row in channel_rows:
        channel_id = row["id"]
        detail = scrub(get(f"/api/channels/{channel_id}/detail", **{"from": start, "to": end, "granularity": "WEEK"}))
        detail["topContents"] = detail["topContents"][:3]
        result["channelDetails"][str(channel_id)] = detail
        result["channelDetailsByGranularity"][str(channel_id)] = {"WEEK": detail}
        for granularity in ("DAY", "MONTH"):
            result["channelDetailsByGranularity"][str(channel_id)][granularity] = scrub(get(f"/api/channels/{channel_id}/detail", **{"from": start, "to": end, "granularity": granularity}))
        content_ids.update(item["contentId"] for item in detail["topContents"])
        if row["category"] == "领域频道":
            accounts = scrub(get(f"/api/drill/channels/{channel_id}/accounts", **{"from": start, "to": end}))
            accounts["accounts"] = accounts["accounts"][:3]
            result["channelAccounts"][str(channel_id)] = accounts
            account_ids.update(item["id"] for item in accounts["accounts"])

    blogger_ids = set()
    for account_id in sorted(account_ids):
        account = scrub(get(f"/api/drill/accounts/{account_id}", **{"from": start, "to": end, "granularity": "WEEK"}))
        account["authors"] = account["authors"][:3]
        account["topContents"] = account["topContents"][:3]
        result["accounts"][str(account_id)] = account
        result["accountsByGranularity"][str(account_id)] = {"WEEK": account}
        for granularity in ("DAY", "MONTH"):
            result["accountsByGranularity"][str(account_id)][granularity] = scrub(get(f"/api/drill/accounts/{account_id}", **{"from": start, "to": end, "granularity": granularity}))
        blogger_ids.update(item["id"] for item in account["authors"])
        content_ids.update(item["contentId"] for item in account["topContents"])

    for blogger_id in sorted(blogger_ids):
        blogger = scrub(get(f"/api/drill/bloggers/{blogger_id}", **{"from": start, "to": end, "granularity": "WEEK"}))
        blogger["topContents"] = blogger["topContents"][:3]
        blogger["accounts"] = [item for item in blogger["accounts"] if str(item["id"]) in result["accounts"]]
        result["bloggers"][str(blogger_id)] = blogger
        result["bloggersByGranularity"][str(blogger_id)] = {"WEEK": blogger}
        for granularity in ("DAY", "MONTH"):
            result["bloggersByGranularity"][str(blogger_id)][granularity] = scrub(get(f"/api/drill/bloggers/{blogger_id}", **{"from": start, "to": end, "granularity": granularity}))
        content_ids.update(item["contentId"] for item in blogger["topContents"])

    for content_id in sorted(content_ids):
        result["contents"][str(content_id)] = scrub(get(f"/api/drill/contents/{content_id}", **{"from": start, "to": end, "granularity": "WEEK"}))
        result["contentsByGranularity"][str(content_id)] = {"WEEK": result["contents"][str(content_id)]}
        for granularity in ("DAY", "MONTH"):
            result["contentsByGranularity"][str(content_id)][granularity] = scrub(get(f"/api/drill/contents/{content_id}", **{"from": start, "to": end, "granularity": granularity}))

    result["users"]["lifecycle"] = get("/api/users/lifecycle", asOf=end)
    result["users"]["value"] = get("/api/users/value", asOf=end)
    result["users"]["interests"] = get("/api/users/interests", asOf=end, days=90)
    result["growth"]["departments"] = get("/api/growth/departments", days=30, asOf=end)
    result["growth"]["hot"] = scrub(get("/api/growth/hot-contents", limit=20, asOf=end))
    result["growth"]["alerts"] = scrub(get("/api/growth/alerts", limit=100, offset=0))
    for kind in ("month", "year"):
        periods = get(f"/api/leaderboard/{kind}/periods")
        result["leaderboard"][kind] = {"periods": periods[:3], "boards": {}}
        for period in periods[:3]:
            result["leaderboard"][kind]["boards"][period["periodValue"]] = scrub(get(f"/api/leaderboard/{kind}", period=period["periodValue"], limit=50))

    # Keep KOL example IDs from the public sample while pulling their profiles
    # using rank as the temporary link to local, simulated source records.
    source_kol = get("/api/bloggers/top1000", asOf=snapshot["kol"]["asOf"], limit=1000, offset=0)
    by_rank = {item["rank"]: item["employeeId"] for item in source_kol["items"]}
    for item in snapshot["kol"]["items"]:
        demo_id = item["employeeId"]
        source_id = by_rank[item["rank"]]
        profile = scrub(get(f"/api/bloggers/{source_id}/profile", weeks=26))
        profile["employee"]["id"] = demo_id
        profile["employee"]["name"] = item["name"]
        profile["recentEvents"] = [{**event, "refId": None} for event in profile["recentEvents"]]
        result["kolProfiles"][str(demo_id)] = profile
        result["kolActivity"][str(demo_id)] = get(f"/api/bloggers/{source_id}/activity", granularity="WEEK")
        result["kolPoints"][str(demo_id)] = get(f"/api/bloggers/{source_id}/points-trend", granularity="WEEK")
        result["kolActivityByGranularity"][str(demo_id)] = {"WEEK": result["kolActivity"][str(demo_id)]}
        result["kolPointsByGranularity"][str(demo_id)] = {"WEEK": result["kolPoints"][str(demo_id)]}
        for granularity in ("DAY", "MONTH", "YEAR"):
            result["kolActivityByGranularity"][str(demo_id)][granularity] = get(f"/api/bloggers/{source_id}/activity", granularity=granularity)
            result["kolPointsByGranularity"][str(demo_id)][granularity] = get(f"/api/bloggers/{source_id}/points-trend", granularity=granularity)

    text = json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n"
    if "员工-" in text or "个人账号-" in text or "团队账号-" in text:
        raise ValueError("unredacted identity in exported fixture")
    groups = {
        "core": ["dimensions", "pvTrends", "topByGranularity"],
        "channels": ["channelDetails", "channelDetailsByGranularity", "channelAccounts"],
        "accounts": ["accounts", "accountsByGranularity"],
        "bloggers": ["bloggers", "bloggersByGranularity"],
        "contents": ["contents", "contentsByGranularity"],
        "readers": ["readerActivity", "readerRetention", "readerFunnel", "readerSegments", "readerLurkers"],
        "creators": ["creatorOverview", "creatorRanking", "creatorLadder", "creatorConcentration"],
        "users": ["users"],
        "growth": ["growth"],
        "leaderboard": ["leaderboard"],
        "kol": ["kolProfiles", "kolActivity", "kolPoints", "kolActivityByGranularity", "kolPointsByGranularity"],
    }
    folder = DEMO / "fixtures"
    folder.mkdir(parents=True, exist_ok=True)
    for name, keys in groups.items():
        target = folder / f"{name}.json"
        target.write_text(json.dumps({key: result[key] for key in keys}, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
        print(f"Wrote {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
