#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Closes the Wave 0 issues that are genuinely complete and verified
# (confirmed working locally AND in CI as of today). Finds each issue
# by searching its title, since the actual issue numbers assigned by
# create_github_issues.sh were never captured.
#
# Deliberately NOT closed here, and why:
#   - NFR-09 (Swagger/OpenAPI) — not actually done; no springdoc
#     dependency or config exists yet.
#   - NFR-01, NFR-14, NFR-15, NFR-16 (standing conventions) — these
#     are "establish and enforce continuously," and there's no code
#     yet to have actually enforced them against. Closing these now
#     would claim more than is true. Revisit once Wave 1 has real
#     code to check them against.
#
# Usage:
#   Run from inside the cloned subscribe-master repo directory, with
#   gh already authenticated.
#
#     chmod +x close_wave0_issues.sh
#     ./close_wave0_issues.sh
# =====================================================================

CLOSE_COMMENT="Closing — verified complete and working as of today's session: confirmed locally via \`mvn verify\` and in CI (green GitHub Actions run). See commit 0205af7 and the CI pipeline for details."

# (search term, full title substring to confirm exact match)
declare -a ISSUES=(
  "NFR-13|Set up Flyway and wire migrations into the build"
  "NFR-12|Docker Compose single-command startup"
  "NFR-08|Environment-based configuration"
  "NFR-20|Vault integration for secrets management"
  "NFR-19|CI/CD pipeline for build, test, and deploy"
)

for entry in "${ISSUES[@]}"; do
  IFS='|' read -r search_term title_fragment <<< "$entry"

  echo "Looking up issue for: $search_term ..."

  # Search by title, request number/title/state as JSON
  result=$(gh issue list --search "in:title \"$search_term\"" --state all \
            --json number,title,state --limit 5)

  count=$(echo "$result" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")

  if [ "$count" -eq 0 ]; then
    echo "  ⚠️  No issue found matching '$search_term' — skipping. Check manually."
    continue
  fi

  if [ "$count" -gt 1 ]; then
    echo "  ⚠️  Multiple issues matched '$search_term' — skipping to avoid closing the wrong one. Check manually:"
    echo "$result" | python3 -c "import json,sys; [print(f'    #{i[\"number\"]}: {i[\"title\"]}') for i in json.load(sys.stdin)]"
    continue
  fi

  issue_number=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['number'])")
  issue_state=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['state'])")
  issue_title=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['title'])")

  if [ "$issue_state" == "CLOSED" ]; then
    echo "  ✔️  #$issue_number ($issue_title) is already closed — nothing to do."
    continue
  fi

  echo "  Closing #$issue_number: $issue_title"
  gh issue close "$issue_number" --comment "$CLOSE_COMMENT"
done

echo ""
echo "Done. Review output above for any skipped/ambiguous matches."
