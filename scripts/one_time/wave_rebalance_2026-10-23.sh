#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Applies the 2026-10-23 wave rebalance to existing GitHub issues
# (every open wave targeted at 60-70h, capped at 80h -- see
# IMPLEMENTATION_BACKLOG.md):
#
#   - creates the wave-5.5 label ("Two-factor authentication & contact
#     preferences")
#   - FR-33, FR-34, FR-36: wave-2 -> wave-5.5
#   - FR-37, FR-38:        wave-8 -> wave-5.5
#
# Issues are found by their "[ID] " title prefix rather than hardcoded
# numbers; the script stops if any ID matches zero or several issues.
#
# NOT included on purpose: closing NFR-18. It is re-implemented by
# NFR-42 and closes when NFR-42 merges (Wave 4), not now.
#
# The new observability issues (NFR-36..44, FR-41..44) are created
# separately by nfr-36_actuator_observability_add.sh, already carrying
# their rebalanced wave labels.
#
# STATUS: NOT run yet. Run after the backlog changes are merged.
#
# Usage (from inside the cloned repo, gh authenticated):
#   ./scripts/one_time/wave_rebalance_2026-10-23.sh
# =====================================================================

issue_number() {
  local id="$1"
  local matches
  matches=$(gh issue list --state all --limit 500 --json number,title \
    --jq ".[] | select(.title | startswith(\"[$id] \")) | .number")
  if [ "$(printf '%s\n' "$matches" | grep -c .)" -ne 1 ]; then
    echo "Expected exactly one issue titled \"[$id] ...\", found: ${matches:-none}" >&2
    exit 1
  fi
  printf '%s' "$matches"
}

move() {
  local id="$1" from="$2" to="$3"
  local n
  n=$(issue_number "$id")
  gh issue edit "$n" --remove-label "$from" --add-label "$to"
  gh issue comment "$n" --body "Moved from \`$from\` to \`$to\` in the 2026-10-23 wave rebalance (every open wave targeted at 60–70h, capped at 80h). See \`IMPLEMENTATION_BACKLOG.md\`."
  echo "#$n [$id]: $from -> $to"
}

gh label create "wave-5.5" --color "5319e7" \
  --description "Wave 5.5 in IMPLEMENTATION_BACKLOG.md" --force

move "FR-36" "wave-2" "wave-5.5"
move "FR-33" "wave-2" "wave-5.5"
move "FR-34" "wave-2" "wave-5.5"
move "FR-37" "wave-8" "wave-5.5"
move "FR-38" "wave-8" "wave-5.5"
