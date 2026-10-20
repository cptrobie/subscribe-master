#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Lists every GitHub issue (open and closed) that doesn't have exactly
# one domain:* label -- either none, or more than one (NFR-30).
#
# Recurring, not one-time: run it after opening new issues (e.g. after
# an *_add.sh script for a new requirement), or before a time report
# that groups by domain. The taxonomy itself is documented in
# scripts/one_time/tag_issues_by_domain.sh.
#
# Exits 0 when every issue is labelled correctly, 1 otherwise, so it
# can gate a script or a CI job later if that's ever wanted.
#
# Usage (from inside the cloned repo, gh authenticated):
#   ./scripts/issue_hygiene/check_domain_labels.sh
# =====================================================================

# Comfortably above the current issue count; raise it if the project
# ever gets near it, or the check silently stops covering the oldest.
LIMIT=500

problems=$(gh issue list --state all --limit "$LIMIT" \
  --json number,title,state,labels \
  --jq '.[]
        | {number, title, state,
           domains: [.labels[].name | select(startswith("domain:"))]}
        | select(.domains | length != 1)
        | "#\(.number) [\(.state)] \(.title)  ->  "
          + (if (.domains | length) == 0 then "no domain label"
             else "multiple: " + (.domains | join(", ")) end)')

if [ -z "$problems" ]; then
  echo "✅ Every issue has exactly one domain:* label."
  exit 0
fi

echo "Issues without exactly one domain:* label:"
echo "$problems"
exit 1
