#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Gives every GitHub issue (open and closed) exactly one domain:* label,
# so issues are filterable/reportable by domain (NFR-30).
#
# Rewritten 2026-10-20 when NFR-30 was picked up ahead of Wave 4.5: the
# earlier version matched issues by title search and deliberately left
# the auth/customer boundary and the cross-cutting NFRs as a TODO until
# NFR-26. The decision now is that every existing issue needs a domain,
# so the mapping below covers all of them, keyed by issue number. Issues
# whose boundary NFR-26 may still move are tagged by where their code
# lives (or will live) today; if NFR-26 moves one, swap the label.
#
# Domain taxonomy:
#   domain:auth           Security and user identity: registration,
#                         login, tokens, verification, 2FA, RBAC,
#                         per-user isolation
#   domain:customer       Customer profile data (contact details,
#                         preferences) -- storing it, not proving it
#   domain:messaging      Outbound message delivery (the message module:
#                         email, SMS)
#   domain:subscriptions  Subscriptions and the provider catalog
#   domain:currency       Exchange rates and base-currency conversion
#   domain:payments       Payments, retries, refunds, money integrity
#   domain:scheduling     Scheduled jobs and the notifications they drive
#   domain:reporting      Reports and statistics
#   domain:auditlog       Audit logging and request tracing
#   domain:platform       Application code or conventions spanning
#                         business domains
#   domain:dev/techops    Building, running, deploying and monitoring the
#                         application, plus the project's own tooling
#
# Safe to re-run:
#   - gh label create --force only (re)sets colour and description.
#   - An issue that already has its mapped label is skipped.
#   - An issue that has a *different* domain:* label is reported and
#     skipped, never double-labelled -- resolve those by hand.
#   - Each issue's title is checked for its expected requirement ID
#     first, so a wrong number in the mapping can't label the wrong
#     issue.
#
# Usage (from inside the cloned repo, gh authenticated):
#   bash scripts/one_time/tag_issues_by_domain.sh
#
# Afterwards, scripts/issue_hygiene/check_domain_labels.sh should report
# nothing missing.
# =====================================================================

# issue number | requirement ID (must appear in the title) | domain label
declare -a ISSUES=(
  # --- auth: security and user identity ---
  "11|FR-01|domain:auth"
  "12|FR-02|domain:auth"
  "13|FR-03|domain:auth"
  "14|FR-04|domain:auth"
  "16|FR-06|domain:auth"
  "17|FR-05|domain:auth"
  "93|FR-30|domain:auth"
  "94|FR-31|domain:auth"
  "95|FR-32|domain:auth"
  "96|FR-33|domain:auth"
  "97|FR-34|domain:auth"
  "104|FR-38|domain:auth"
  "105|FR-39|domain:auth"
  "110|FR-40|domain:auth"
  "116|NFR-34|domain:auth"
  # --- customer / messaging ---
  "102|FR-36|domain:customer"
  "103|FR-37|domain:messaging"
  # --- subscriptions ---
  "18|FR-07|domain:subscriptions"
  "19|FR-08|domain:subscriptions"
  "20|FR-09|domain:subscriptions"
  "22|FR-10|domain:subscriptions"
  "23|FR-11|domain:subscriptions"
  "24|FR-27|domain:subscriptions"
  "101|FR-35|domain:subscriptions"
  # --- currency ---
  "25|FR-13|domain:currency"
  "26|FR-14|domain:currency"
  "27|FR-15|domain:currency"
  "28|NFR-05|domain:currency"
  "29|FR-17|domain:currency"
  "30|FR-16|domain:currency"
  # --- payments ---
  "31|FR-12|domain:payments"
  "32|NFR-03|domain:payments"
  "33|FR-29|domain:payments"
  "34|FR-28|domain:payments"
  # --- scheduling ---
  "35|FR-18|domain:scheduling"
  "36|FR-19|domain:scheduling"
  "37|FR-20|domain:scheduling"
  "38|FR-21|domain:scheduling"
  # --- reporting ---
  "39|FR-22|domain:reporting"
  "40|FR-23|domain:reporting"
  "41|FR-24|domain:reporting"
  "42|FR-25|domain:reporting"
  "43|FR-26|domain:reporting"
  # --- auditlog ---
  "45|NFR-17|domain:auditlog"
  "46|NFR-18|domain:auditlog"
  # --- dev/techops ---
  "1|NFR-13|domain:dev/techops"
  "2|NFR-12|domain:dev/techops"
  "3|NFR-08|domain:dev/techops"
  "4|NFR-20|domain:dev/techops"
  "5|NFR-19|domain:dev/techops"
  "54|NFR-21|domain:dev/techops"
  "59|NFR-22|domain:dev/techops"
  "84|NFR-30|domain:dev/techops"
  "87|NFR-32|domain:dev/techops"
  # --- platform ---
  "6|NFR-09|domain:platform"
  "7|NFR-01|domain:platform"
  "8|NFR-14|domain:platform"
  "9|NFR-15|domain:platform"
  "10|NFR-16|domain:platform"
  "15|NFR-02|domain:platform"
  "21|NFR-04|domain:platform"
  "44|NFR-07|domain:platform"
  "47|NFR-06|domain:platform"
  "48|NFR-10|domain:platform"
  "49|NFR-11|domain:platform"
  "50|NFR-09|domain:platform"
  "66|NFR-23|domain:platform"
  "68|NFR-24|domain:platform"
  "75|NFR-25|domain:platform"
  "80|NFR-26|domain:platform"
  "81|NFR-27|domain:platform"
  "82|NFR-28|domain:platform"
  "83|NFR-29|domain:platform"
  "85|NFR-31|domain:platform"
  "111|NFR-33|domain:platform"
)

echo "Ensuring domain labels exist ..."
for label in $(printf '%s\n' "${ISSUES[@]}" | cut -d'|' -f3 | sort -u); do
  gh label create "$label" --color "5319e7" \
    --description "Owning domain, per NFR-26/30" --force >/dev/null
done

added=0
skipped=0
conflicts=0
for entry in "${ISSUES[@]}"; do
  IFS='|' read -r number req_id domain_label <<< "$entry"

  title=$(gh issue view "$number" --json title --jq '.title')
  if [[ "$title" != "[$req_id]"* ]]; then
    echo "  ⚠️  #$number title is '$title', expected [$req_id] -- skipping. Check the mapping."
    conflicts=$((conflicts + 1))
    continue
  fi

  current=$(gh issue view "$number" --json labels \
              --jq '[.labels[].name | select(startswith("domain:"))] | join(",")')
  if [ "$current" = "$domain_label" ]; then
    skipped=$((skipped + 1))
    continue
  fi
  if [ -n "$current" ]; then
    echo "  ⚠️  #$number [$req_id] already has '$current', mapping says '$domain_label' -- skipping. Resolve by hand."
    conflicts=$((conflicts + 1))
    continue
  fi

  echo "  Tagging #$number [$req_id] with $domain_label"
  gh issue edit "$number" --add-label "$domain_label" >/dev/null
  added=$((added + 1))
done

echo ""
echo "Done: $added labelled, $skipped already correct, $conflicts needing attention."
