#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Sets up Wave 4.5 (module boundary hardening) in the GitHub backlog:
# creates the wave-4.5 label, then opens its six NFR issues.
#
# STATUS: the `gh label create` line below was already run ad hoc on
# 2026-09-20, before this script existed to record it -- included here
# anyway (label creation is idempotent via --force) so this script is
# a complete, accurate record of Wave 4.5's GitHub setup, not just the
# parts that happened to get scripted first. The six `gh issue create`
# calls below have NOT been run yet.
#
# Usage:
#   Run from inside the cloned subscribe-master repo, with gh already
#   authenticated.
#
#     chmod +x wave_4.5_nfr_add.sh
#     ./wave_4.5_nfr_add.sh
# =====================================================================

gh label create "wave-4.5" --color "e8860c" \
  --description "Wave 4.5 in IMPLEMENTATION_BACKLOG.md" --force

gh issue create --title "[NFR-26] Domain module boundaries (package encapsulation)" \
  --label "wave-4.5" --label "size-M" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-26`).

Part of Wave 4.5 in `IMPLEMENTATION_BACKLOG.md` — see that wave'\''s rationale and `ARCHITECTURE.md` §13 for why this is scheduled here rather than earlier or later.'

gh issue create --title "[NFR-27] Module boundary enforcement tooling (Spring Modulith verification)" \
  --label "wave-4.5" --label "size-M" --label "standing-convention" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-27`).

Part of Wave 4.5 in `IMPLEMENTATION_BACKLOG.md`. Depends on `NFR-26`.

**Standing convention** -- enforced continuously on every future PR once wired in, not a one-time task. See `IMPLEMENTATION_BACKLOG.md` for the full explanation of this distinction.'

gh issue create --title "[NFR-28] Event-driven cross-module communication convention" \
  --label "wave-4.5" --label "size-M" --label "standing-convention" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-28`).

Part of Wave 4.5 in `IMPLEMENTATION_BACKLOG.md`.

**Standing convention** -- applies to every cross-domain reaction built from this wave forward, not a one-time task.'

gh issue create --title "[NFR-29] Domain-partitioned database schema" \
  --label "wave-4.5" --label "size-L" --label "standing-convention" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-29`).

Part of Wave 4.5 in `IMPLEMENTATION_BACKLOG.md`. The largest and riskiest item in this wave -- see `ARCHITECTURE.md` §13.4 for the explicit scope boundary (schema partitioning within one Postgres instance, not separate databases).

**Standing convention** for every migration written from Wave 5 onward, in addition to the one-time retrofit of what already exists.'

gh issue create --title "[NFR-30] Domain metadata (domain:* labels) on GitHub issues" \
  --label "wave-4.5" --label "size-S" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-30`).

Part of Wave 4.5 in `IMPLEMENTATION_BACKLOG.md`. Depends on `NFR-26`'\''s design decisions for the remaining ambiguous domains.

Script already written: `scripts/one_time/tag_issues_by_domain.sh` -- partially filled in, with a TODO block for what `NFR-26` still needs to settle. Run and complete it as part of closing this issue.'

gh issue create --title "[NFR-31] ERD domain-partitioning update" \
  --label "wave-4.5" --label "size-S" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-31`).

Part of Wave 4.5 in `IMPLEMENTATION_BACKLOG.md`. Sequenced last -- documents the outcome of `NFR-26`/`NFR-29`, not a prediction of it.'