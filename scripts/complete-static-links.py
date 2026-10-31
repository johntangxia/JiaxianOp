#!/usr/bin/env python3
"""Fill public demo drill links with read-only, anonymized simulated reports."""

import argparse
import json
import re
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1] / "frontend/src/demo"
FOLDER = ROOT / "fixtures"
snapshot = json.loads((ROOT / "snapshot.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--base-url", default="http://localhost:8081")
base = parser.parse_args().base_url.rstrip("/")


def load(name):
    return json.loads((FOLDER / f"{name}.json").read_text(encoding="utf-8"))


def save(name, value):
    target = FOLDER / f"{name}.json"
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"
    if "员工-" in text or "个人账号-" in text or "团队账号-" in text:
        raise ValueError(f"unredacted identity in {name}")
    target.write_text(text, encoding="utf-8")
    print(f"Updated {target} ({target.stat().st_size} bytes)")


def get(path, **query):
    if not re.fullmatch(r"/api/(?:users/\d+|drill/(?:accounts|bloggers|contents)/\d+|channels/\d+/detail)", path):
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
        value = re.sub(r"员工-(\d+)", lambda m: f"演示用户 {int(m.group(1)) + 1:03d}", value)
        value = re.sub(r"(?:个人|团队)账号-(\d+)", lambda m: f"演示账号 {int(m.group(1)) + 1:03d}", value)
    return value


def main():
    core = load("core")
    channels = load("channels")
    accounts = load("accounts")
    bloggers = load("bloggers")
    contents = load("contents")
    creators = load("creators")
    readers = load("readers")
    users = load("users")
    growth = load("growth")
    from_day, to_day = snapshot["cockpit"]["from"], snapshot["snapshotDate"]
    granularities = ("DAY", "WEEK", "MONTH")

    # Every account exposed by the compare selector can open its detail.
    account_ids = {item["id"] for item in core["dimensions"]["accounts"]}
    for account_id in sorted(account_ids):
        key = str(account_id)
        if key in accounts["accountsByGranularity"]:
            continue
        variants = {g: scrub(get(f"/api/drill/accounts/{account_id}", **{"from": from_day, "to": to_day, "granularity": g})) for g in granularities}
        accounts["accounts"][key] = variants["WEEK"]
        accounts["accountsByGranularity"][key] = variants

    # Every displayed creator or compare blogger can open its author view.
    blogger_ids = {item["id"] for item in core["dimensions"]["employees"]}
    for ranking in creators["creatorRanking"].values():
        blogger_ids.update(row["employeeId"] for row in ranking["creators"])
    for account in accounts["accounts"].values():
        blogger_ids.update(row["id"] for row in account["authors"])
    for blogger_id in sorted(blogger_ids):
        key = str(blogger_id)
        if key in bloggers["bloggersByGranularity"]:
            continue
        variants = {g: scrub(get(f"/api/drill/bloggers/{blogger_id}", **{"from": from_day, "to": to_day, "granularity": g})) for g in granularities}
        bloggers["bloggers"][key] = variants["WEEK"]
        bloggers["bloggersByGranularity"][key] = variants

    # Keep drill tables inside the published graph: each visible link works.
    public_contents = set(contents["contents"])
    for account in accounts["accountsByGranularity"].values():
        for report in account.values():
            report["authors"] = [row for row in report["authors"] if str(row["id"]) in bloggers["bloggers"]]
            report["topContents"] = [row for row in report["topContents"] if str(row["contentId"]) in public_contents]
    for blogger in bloggers["bloggersByGranularity"].values():
        for report in blogger.values():
            report["accounts"] = [row for row in report["accounts"] if str(row["id"]) in accounts["accounts"]]
            report["topContents"] = [row for row in report["topContents"] if str(row["contentId"]) in public_contents]
    for key, variants in accounts["accountsByGranularity"].items():
        accounts["accounts"][key] = variants["WEEK"]
    for key, variants in bloggers["bloggersByGranularity"].items():
        bloggers["bloggers"][key] = variants["WEEK"]

    # User 360 links from the silent-reader list and blogger alerts.
    user_ids = {row["employeeId"] for row in readers["readerLurkers"]["lurkers"]}
    user_ids.update(row["entityId"] for row in growth["growth"]["alerts"]["alerts"] if row["entityType"] == "BLOGGER" and row["entityId"])
    users["users"]["profiles"] = {}
    for user_id in sorted(user_ids):
        profile = scrub(get(f"/api/users/{user_id}", asOf=to_day))
        profile["recentEvents"] = [{**event, "refId": None} for event in profile["recentEvents"]]
        profile["follows"] = [{**row, "accountId": i, "accountName": f"演示账号 {i:03d}"} for i, row in enumerate(profile["follows"], 1)]
        users["users"]["profiles"][str(user_id)] = profile

    # Hot-content links use a 30-day drill window; contest links use their
    # respective competition period, both distinct from the cockpit window.
    hot_from = (date.fromisoformat(to_day) - timedelta(days=29)).isoformat()
    hot_ids = {row["contentId"] for group in ("hot", "potential") for row in growth["growth"]["hot"][group]}
    contents["hotContentsByGranularity"] = {}
    for content_id in sorted(hot_ids):
        key = str(content_id)
        contents["hotContentsByGranularity"][key] = {g: scrub(get(f"/api/drill/contents/{content_id}", **{"from": hot_from, "to": to_day, "granularity": g})) for g in granularities}

    boards = snapshot["boards"]
    channels["contestChannelDetails"] = {}
    for board in boards.values():
        for row in board["domain"] + board["composite"]:
            channel_id = row["channelId"]
            for g in granularities:
                key = f"{board['periodStart']}|{board['periodEnd']}|{channel_id}|{g}"
                channels["contestChannelDetails"][key] = scrub(get(f"/api/channels/{channel_id}/detail", **{"from": board["periodStart"], "to": board["periodEnd"], "granularity": g}))

    save("core", core)
    save("channels", channels)
    save("accounts", accounts)
    save("bloggers", bloggers)
    save("contents", contents)
    save("users", users)


if __name__ == "__main__":
    main()
