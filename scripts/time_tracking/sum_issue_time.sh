#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Sums all "Actual: ~<time>" session comments logged on a GitHub issue
# and compares the total against its estimate (either an explicit
# "Estimate: ~<time>" comment, or derived from the issue's size-S/M/L
# label per the mapping in IMPLEMENTATION_BACKLOG.md: S=4h, M=12h,
# L=24h -- L is a floor, not a ceiling, since Large is open-ended by
# definition).
#
# Usage:
#   ./sum_issue_time.sh <issue-number>
#
# Typical flow:
#   1. When starting an issue:
#        gh issue comment N --body "Estimate: ~4h (size-S)"
#   2. At the end of each work session:
#        gh issue comment N --body "Actual: ~45m -- brief note on what got done"
#   3. Before closing, to see the running total:
#        ./sum_issue_time.sh N
#   4. Close with the total copied in:
#        gh issue close N --comment "Actual total: ~3h 30m across 5 sessions vs 4h estimated (size-S). Closing."
# =====================================================================

if [ $# -ne 1 ]; then
  echo "Usage: ./sum_issue_time.sh <issue-number>"
  exit 1
fi

ISSUE_NUM="$1"
TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT

gh issue view "$ISSUE_NUM" --json title,labels,comments > "$TMP_FILE"

ISSUE_NUM="$ISSUE_NUM" TMP_FILE="$TMP_FILE" python3 << 'PYEOF'
import json
import os
import re

issue_num = os.environ["ISSUE_NUM"]
tmp_file = os.environ["TMP_FILE"]

with open(tmp_file) as f:
    data = json.load(f)

title = data["title"]
labels = [l["name"] for l in data["labels"]]
comments = data["comments"]

SIZE_HOURS = {"size-S": 4, "size-M": 12, "size-L": 24}


def parse_time(s):
    """Parse a string like '45m', '1h', '1.5h', '1h 30m' into total minutes.
    Returns None if no recognizable time value is found."""
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


# Determine estimate: prefer an explicit "Estimate: ~..." comment,
# otherwise derive it from the size-S/M/L label.
estimate_minutes = None
estimate_source = None

for c in comments:
    body = c["body"]
    m = re.search(r"Estimate:\s*~?\s*([^\n(]+)", body, re.IGNORECASE)
    if m:
        parsed = parse_time(m.group(1))
        if parsed:
            estimate_minutes = parsed
            estimate_source = "explicit comment"
            break

if estimate_minutes is None:
    for label in labels:
        if label in SIZE_HOURS:
            estimate_minutes = SIZE_HOURS[label] * 60
            estimate_source = f"derived from {label} label"
            break

# Sum every "Actual: ~<time> -- <note>" comment.
sessions = []
for c in comments:
    body = c["body"]
    m = re.search(r"Actual:\s*~?\s*([^\n\u2014-]+)[\u2014-]?\s*(.*)", body, re.IGNORECASE)
    if m:
        parsed = parse_time(m.group(1))
        if parsed:
            note = m.group(2).strip()
            sessions.append((parsed, note))

total_minutes = sum(s[0] for s in sessions)

print(f"Issue #{issue_num}: {title}")
if estimate_minutes:
    print(f"Estimate: {fmt(estimate_minutes)} ({estimate_source})")
else:
    print("Estimate: none found (no size-S/M/L label and no explicit 'Estimate:' comment)")

print(f"Sessions logged: {len(sessions)}")
for minutes, note in sessions:
    label = note if note else "(no note)"
    print(f"  {fmt(minutes):>8}  {label}")

print(f"Actual total: {fmt(total_minutes)}")

if estimate_minutes and total_minutes > 0:
    diff = total_minutes - estimate_minutes
    pct = (abs(diff) / estimate_minutes) * 100
    direction = "over" if diff > 0 else "under"
    print(f"Variance: {fmt(abs(diff))} {direction} estimate ({pct:.0f}%)")
elif not sessions:
    print("No 'Actual:' session comments found yet.")
PYEOF
