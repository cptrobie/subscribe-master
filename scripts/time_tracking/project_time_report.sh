#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Whole-project time tracking report. Loops through every issue that
# carries a wave-X label and prints one line per issue, grouped by
# wave, showing its estimate (explicit "Estimate:" comment, or a
# size-S/M/L-derived fallback), logged actual time (0h 0m if nothing
# has been logged yet), and the variance between them. Untouched
# issues are listed too -- this is a full-project inventory, not just
# a progress-so-far summary.
#
# Usage: ./project_time_report.sh
# =====================================================================

echo "Fetching all issues..."
gh issue list --state all --limit 100 --json number,title,labels,state > /tmp/all_issues_meta.json

COUNT=$(python3 -c 'import json; print(len(json.load(open("/tmp/all_issues_meta.json"))))')
echo "Found $COUNT issues. Fetching comments for each (this takes a moment)..."
echo ""

python3 << 'PYEOF'
import json
import re
import subprocess

with open("/tmp/all_issues_meta.json") as f:
    issues_meta = json.load(f)


def run_gh(args):
    result = subprocess.run(["gh"] + args, capture_output=True, text=True)
    if result.returncode != 0:
        return None
    return result.stdout


def parse_time(s):
    total = 0.0
    found = False
    for match in re.finditer(r"(\d+(?:\.\d+)?)\s*(?:h|hr|hrs|hour|hours)\b", s, re.IGNORECASE):
        total += float(match.group(1)) * 60
        found = True
    for match in re.finditer(r"(\d+(?:\.\d+)?)\s*(?:m|min|mins|minute|minutes)\b", s, re.IGNORECASE):
        total += float(match.group(1))
        found = True
    return total if found else None


def fmt(minutes):
    h = int(minutes // 60)
    m = int(round(minutes % 60))
    if h and m:
        return f"{h}h {m}m"
    if h:
        return f"{h}h"
    return f"{m}m"


SIZE_HOURS = {"size-S": 4, "size-M": 12, "size-L": 24}

waves = {}
for issue in issues_meta:
    num = issue["number"]
    labels = [l["name"] for l in issue["labels"]]

    wave_num = None
    for label in labels:
        if label.startswith("wave-"):
            wave_num = int(label.split("-")[1])
            break
    if wave_num is None:
        continue

    out = run_gh(["issue", "view", str(num), "--json", "comments"])
    if out is None:
        continue
    comments = json.loads(out)["comments"]

    explicit_estimate_minutes = None
    for c in comments:
        m = re.search(r"Estimate:\s*~?\s*([^\n(]+)", c["body"], re.IGNORECASE)
        if m:
            parsed = parse_time(m.group(1))
            if parsed:
                explicit_estimate_minutes = parsed
                break

    actual_minutes = 0.0
    session_count = 0
    for c in comments:
        m = re.search(r"Actual:\s*~?\s*([^\n\u2014-]+)", c["body"], re.IGNORECASE)
        if m:
            parsed = parse_time(m.group(1))
            if parsed:
                actual_minutes += parsed
                session_count += 1

    estimate_minutes = explicit_estimate_minutes
    if estimate_minutes is None:
        for label in labels:
            if label in SIZE_HOURS:
                estimate_minutes = SIZE_HOURS[label] * 60
                break

    waves.setdefault(wave_num, []).append({
        "number": num,
        "title": issue["title"],
        "state": issue["state"],
        "estimate": estimate_minutes,
        "actual": actual_minutes,
        "sessions": session_count,
    })

print("Subscribe Master -- Time Tracking Report")
print("=" * 50)
print("")

grand_estimate = 0.0
grand_actual = 0.0
grand_issue_count = 0
grand_started_count = 0

for wave_num in sorted(waves.keys()):
    items = sorted(waves[wave_num], key=lambda i: i["number"])

    all_closed = all(i["state"] == "CLOSED" for i in items)
    any_touched = any(i["state"] == "CLOSED" or i["actual"] > 0 for i in items)

    if not any_touched:
        continue  # future, fully untouched wave -- skip entirely

    wave_estimate = sum(i["estimate"] or 0 for i in items)
    wave_actual = sum(i["actual"] for i in items)

    if all_closed:
        variance_str = ""
        if wave_estimate:
            diff = wave_actual - wave_estimate
            pct = (abs(diff) / wave_estimate) * 100
            direction = "over" if diff > 0 else "under"
            variance_str = f"  ({fmt(abs(diff))} {direction}, {pct:.0f}%)"
        print(f"Wave {wave_num} subtotal: est {fmt(wave_estimate) if wave_estimate else 'n/a'}, actual {fmt(wave_actual) if wave_actual else '0h 0m'}{variance_str}  CLOSED")
    else:
        print(f"Wave {wave_num}")
        for item in items:
            est_str = fmt(item["estimate"]) if item["estimate"] else "none"

            if item["actual"] > 0:
                act_str = fmt(item["actual"])
                diff = item["actual"] - item["estimate"] if item["estimate"] else None
                if diff is not None:
                    pct = (abs(diff) / item["estimate"]) * 100
                    direction = "over" if diff > 0 else "under"
                    variance_str = f"  ({fmt(abs(diff))} {direction}, {pct:.0f}%)"
                else:
                    variance_str = ""
            else:
                act_str = "0h 0m"
                variance_str = "  (not started)"

            print(f"  #{item['number']:<4} {item['title'][:45]:<45} est {est_str:<8} actual {act_str:<10}{variance_str}  {item['state']}")

        print(f"  Wave {wave_num} subtotal: est {fmt(wave_estimate) if wave_estimate else 'n/a'}, actual {fmt(wave_actual) if wave_actual else '0h 0m'}")

    print("")

    grand_estimate += wave_estimate
    grand_actual += wave_actual
    grand_issue_count += len(items)
    grand_started_count += sum(1 for i in items if i["state"] == "CLOSED" or i["actual"] > 0)

print("=" * 50)
print(f"Project total: est {fmt(grand_estimate) if grand_estimate else 'n/a'}, actual {fmt(grand_actual) if grand_actual else '0h 0m'}")
print(f"({grand_started_count} of {grand_issue_count} issues in shown waves have logged time or are closed)")
PYEOF
