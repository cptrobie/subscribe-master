#!/usr/bin/env bash
set -euo pipefail

# =====================================================================
# Opens the thirteen observability and log-management issues: NFR-36
# through NFR-44 and FR-41 through FR-44. See
# design-docs/actuator_observability.md for the design and
# subscribe_master_requirements.md for each requirement.
#
# STATUS: NOT run yet. Wave labels below follow the "Wave placement"
# section of design-docs/actuator_observability.md (decided 2026-10-23).
# Run only after the requirement rows and backlog entries have been
# moved into subscribe_master_requirements.md / IMPLEMENTATION_BACKLOG.md,
# since every issue body points there. The TBD guard below stays, in
# case a placement is reset while under review.
#
# After running, check labels with:
#   ./scripts/issue_hygiene/check_domain_labels.sh
#
# Usage:
#   Run from inside the cloned subscribe-master repo, with gh already
#   authenticated.
#
#     chmod +x nfr-36_actuator_observability_add.sh
#     ./nfr-36_actuator_observability_add.sh
# =====================================================================

WAVE_NFR_36="wave-4"
WAVE_NFR_37="wave-6"
WAVE_NFR_38="wave-4"
WAVE_NFR_39="wave-10"
WAVE_NFR_40="wave-10"
WAVE_NFR_41="wave-2"
WAVE_NFR_42="wave-4"
WAVE_NFR_43="wave-5"
WAVE_NFR_44="wave-8"
WAVE_FR_41="wave-7"
WAVE_FR_42="wave-7"
WAVE_FR_43="wave-10"
WAVE_FR_44="wave-8"

for wave in "$WAVE_NFR_36" "$WAVE_NFR_37" "$WAVE_NFR_38" "$WAVE_NFR_39" \
  "$WAVE_NFR_40" "$WAVE_NFR_41" "$WAVE_NFR_42" "$WAVE_NFR_43" "$WAVE_NFR_44" \
  "$WAVE_FR_41" "$WAVE_FR_42" "$WAVE_FR_43" "$WAVE_FR_44"; do
  if [ "$wave" = "TBD" ]; then
    echo "Set every WAVE_* value before running -- wave placement is still TBD." >&2
    exit 1
  fi
done

gh issue create --title "[NFR-36] Actuator baseline (management port, health + info only, separate security chain)" \
  --label "$WAVE_NFR_36" --label "size-M" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` for the full requirement description and rationale (search for `NFR-36`), and `design-docs/actuator_observability.md` for scope and acceptance criteria.

No dependencies. Adds a second `SecurityFilterChain` for actuator — `SecurityConfig`'\''s own chain makes Spring Boot'\''s default actuator security back off. `health` is anonymous and status-only; people use the staff API, never the management port.'

gh issue create --title "[NFR-37] Health model (liveness/readiness; Postgres + Vault, never mail)" \
  --label "$WAVE_NFR_37" --label "size-S" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-37`) and `design-docs/actuator_observability.md`.

Depends on `NFR-36`. Mail is excluded from readiness because `FR-30` deliberately succeeds when the verification email can'\''t be sent.'

gh issue create --title "[NFR-38] Build identity in /actuator/info" \
  --label "$WAVE_NFR_38" --label "size-S" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-38`) and `design-docs/actuator_observability.md`.

Depends on `NFR-36`. git-commit-id plugin configured with `failOnNoGitDirectory=false`.'

gh issue create --title "[NFR-39] Metrics contract (Prometheus registry, common tags, naming, bounded cardinality)" \
  --label "$WAVE_NFR_39" --label "size-M" --label "domain:dev/techops" --label "standing-convention" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-39`) and `design-docs/actuator_observability.md`.

Depends on `NFR-36`. **Amends `NFR-32`:** Prometheus becomes Grafana'\''s second data source alongside PostgreSQL.

**Standing convention** -- closes once the naming/tag rules are documented in `ARCHITECTURE.md` §6.6 and its checklist lines are added to `IMPLEMENTATION_BACKLOG.md`; enforced per PR from then on.'

gh issue create --title "[NFR-40] Initial business metrics" \
  --label "$WAVE_NFR_40" --label "size-M" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-40`) and `design-docs/actuator_observability.md` for the metric names and tags.

Depends on `NFR-39`; pairs with `NFR-32` -- each metric gets a panel or alert, or a recorded reason why not.'

gh issue create --title "[NFR-41] audit_logs.details column (what changed, old → new)" \
  --label "$WAVE_NFR_41" --label "size-S" --label "domain:auditlog" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-41`) and `design-docs/actuator_observability.md`.

No dependencies. Adds a nullable `details JSONB` column so an audit row can say what changed. **Amends `NFR-17`.** Needed by `FR-42`–`FR-44` and `NFR-44`.'

gh issue create --title "[NFR-42] Request tracing via Micrometer Tracing + Tempo; drop trace_spans" \
  --label "$WAVE_NFR_42" --label "size-M" --label "domain:auditlog" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-42`) and `design-docs/actuator_observability.md`.

Depends on `NFR-36`. Re-implements `NFR-18`: `trace_spans` is schema-only (no code uses it) and Micrometer Tracing exports to a tracing backend, so the table is dropped by a new migration. Adds Tempo to `compose.yaml` as a Grafana data source (amends `NFR-32`).'

gh issue create --title "[NFR-43] Application logs to console, or console + app_logs (buffered, non-blocking)" \
  --label "$WAVE_NFR_43" --label "size-M" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-43`) and `design-docs/actuator_observability.md`.

No dependencies. Console always on; the table is an optional second destination fed by a bounded, non-blocking buffer and background batch inserts. Adds the `log_settings` table. Audit writes are deliberately not buffered. **Amends `NFR-23`.**'

gh issue create --title "[NFR-44] Log archive and retention job (app_logs, audit_logs)" \
  --label "$WAVE_NFR_44" --label "size-L" --label "domain:auditlog" \
  --body 'See `subscribe_master_requirements.md` (search for `NFR-44`) and `design-docs/actuator_observability.md`.

Depends on `NFR-41`, `NFR-43` and `FR-21` (ShedLock). Archives old rows to dated `.jsonl.gz` files under a config-only base directory, deletes archived rows and expired archives, enforces audit retention floors, and audits each run as `log_archiver`. **Amends `NFR-17`.**'

gh issue create --title "[FR-41] Staff system status endpoint (read-only, RBAC)" \
  --label "$WAVE_FR_41" --label "size-S" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `FR-41`) and `design-docs/actuator_observability.md`.

Depends on `NFR-36`, `NFR-38` and `FR-06`. Guarded by the existing `view_system_health` permission; staff never call the management port.'

gh issue create --title "[FR-42] Staff log-level management (persisted, audited)" \
  --label "$WAVE_FR_42" --label "size-M" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `FR-42`) and `design-docs/actuator_observability.md`.

Depends on `FR-06` and `NFR-41`. New `manage_log_levels` permission; overrides saved and applied on every instance; each change audited with the staff member as actor and old/new levels in `details`.'

gh issue create --title "[FR-43] Staff application-log settings (destination, minimum level, archive settings)" \
  --label "$WAVE_FR_43" --label "size-M" --label "domain:dev/techops" \
  --body 'See `subscribe_master_requirements.md` (search for `FR-43`) and `design-docs/actuator_observability.md`.

Depends on `FR-06`, `NFR-41`, `NFR-43` and `NFR-44`. New `manage_app_log_settings` permission. Archive settings are editable only while the destination is console + table (409 otherwise). Each change audited with old → new values.'

gh issue create --title "[FR-44] Staff audit-log archive settings (bounded by config floors)" \
  --label "$WAVE_FR_44" --label "size-S" --label "domain:auditlog" \
  --body 'See `subscribe_master_requirements.md` (search for `FR-44`) and `design-docs/actuator_observability.md`.

Depends on `FR-06`, `NFR-41` and `NFR-44`. New `manage_audit_log_settings` permission, `admin` only. Values below the configured retention floors are rejected. Each change audited with old → new values.'
