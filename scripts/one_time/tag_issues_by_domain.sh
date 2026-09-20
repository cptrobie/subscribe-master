#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Tags existing GitHub issues with a domain:* label, so issues become
# filterable/reportable by domain once module boundaries exist (NFR-30).
#
# Deliberately NOT run as part of writing this script. The full
# issue -> domain mapping below is intentionally partial: only issues
# whose domain is unambiguous regardless of how NFR-26's design work
# resolves are filled in. The rest (anything touching auth/customer/
# session boundaries specifically) is left as a TODO until NFR-26
# actually decides those boundaries -- tagging them now would mean
# relabeling once that decision lands, rather than tagging once,
# correctly. See NFR-30's notes in subscribe_master_requirements.md.
#
# Usage:
#   Run from inside the cloned subscribe-master repo, with gh already
#   authenticated. Safe to re-run -- gh label create uses --force, and
#   gh issue edit --add-label is idempotent (adding a label an issue
#   already has is a no-op).
#
#     chmod +x tag_issues_by_domain.sh
#     ./tag_issues_by_domain.sh
# =====================================================================

# (search term, domain label) -- extend this list as NFR-26 settles
# the remaining boundaries, then re-run.
declare -a ISSUES=(
  "FR-07 Subscription CRUD|domain:subscriptions"
  "FR-08 Automatic next-payment|domain:subscriptions"
  "FR-09 Subscription status|domain:subscriptions"
  "FR-10 Pagination|domain:subscriptions"
  "FR-11 Soft delete|domain:subscriptions"
  "FR-27 Category grouping|domain:subscriptions"
  "FR-13 Base currency|domain:currency"
  "FR-14 Real-time exchange rate|domain:currency"
  "FR-15 Exchange rate caching|domain:currency"
  "FR-17 Rate date transparency|domain:currency"
  "FR-16 Resilience|domain:currency"
  "FR-12 Payment history|domain:payments"
  "FR-29 Payment retry|domain:payments"
  "FR-28 Partial refund|domain:payments"
  "FR-18 Daily scheduled|domain:scheduling"
  "FR-19 Payment-due warning|domain:scheduling"
  "FR-20 Notification strategy|domain:scheduling"
  "FR-21 Scheduler concurrency|domain:scheduling"
  "FR-22 Annual cost report|domain:reporting"
  "FR-23 Report contents|domain:reporting"
  "FR-24 Most expensive|domain:reporting"
  "FR-25 Total monthly spend|domain:reporting"
  "FR-26 Monthly cost trend|domain:reporting"
  "NFR-17 Audit logging|domain:auditlog"
  "NFR-18 Distributed|domain:auditlog"

  # TODO (NFR-26 must decide these first):
  # FR-01/02/03/04/05/06/30/31/32 -- auth vs. customer boundary
  # FR-32's session cleanup half -- auth vs. customer (CustomerSession)
  # NFR-02 (global exception handling) -- domain:shared vs. left untagged
  # NFR-08/09/12/13/14/15/16/19/20/21/22/23/24/25/06/10/11 -- cross-cutting
  #   platform/governance NFRs; may deserve a domain:platform label of
  #   their own rather than forcing them into a business domain, but
  #   that's itself an NFR-26 decision, not one to make here.
)

for entry in "${ISSUES[@]}"; do
  IFS='|' read -r search_term domain_label <<< "$entry"

  gh label create "$domain_label" --color "5319e7" \
    --description "Owning domain, per NFR-26/30" --force >/dev/null

  echo "Looking up issue for: $search_term ..."
  result=$(gh issue list --search "in:title \"$search_term\"" --state all \
            --json number,title --limit 5)
  count=$(echo "$result" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")

  if [ "$count" -eq 0 ]; then
    echo "  ⚠️  No issue found matching '$search_term' — skipping. Check manually."
    continue
  fi
  if [ "$count" -gt 1 ]; then
    echo "  ⚠️  Multiple issues matched '$search_term' — skipping to avoid tagging the wrong one:"
    echo "$result" | python3 -c "import json,sys; [print(f'    #{i[\"number\"]}: {i[\"title\"]}') for i in json.load(sys.stdin)]"
    continue
  fi

  issue_number=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['number'])")
  echo "  Tagging #$issue_number with $domain_label"
  gh issue edit "$issue_number" --add-label "$domain_label"
done

echo ""
echo "Done. Review the TODO block above for domains NFR-26 still needs to settle."
