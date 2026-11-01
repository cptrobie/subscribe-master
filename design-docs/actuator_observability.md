# Observability: Actuator, Metrics, Tracing and Log Management — Design Decisions

Captures the design decisions for `NFR-36`–`NFR-44` and `FR-41`–`FR-44`, ahead
of implementation:

- **Actuator:** health, probes and build information on a separate management
  port.
- **Metrics:** a Micrometer metrics contract with a Prometheus registry, and the
  first business metrics.
- **Tracing:** Micrometer Tracing in place of the unused `trace_spans` table.
- **Log management:** application logs optionally written to `app_logs`, one
  archive-and-retention job for `app_logs` and `audit_logs`, and staff endpoints
  to manage log levels and log settings — every change attributed to the named
  staff member in `audit_logs`.

**Not sourced from `task.pdf`** — added deliberately. Amends `NFR-17`, `NFR-18`,
`NFR-23`, `NFR-32` and `ARCHITECTURE.md` §6 — see "Impact on existing
requirements and documents".

**Adapted from `phase1-observability-issues.md`**, a requirements breakdown
written for a different system (its `projectwork.*` metrics, sync sessions, rule
evaluation, tablet network path and Phase 3 shared library don't exist here).
Its structure is kept for the Actuator and metrics part; its scope is rewritten
for this application, and the parts that don't apply are listed under "Dropped
from the source document". The tracing and log-management requirements were
added in review. This document supersedes it. Like `task.pdf`, the source
breakdown is kept outside the repository.

> **Adopted 2026-10-23.** The requirement rows and backlog entries in the
> appendices have been added to `subscribe_master_requirements.md` and
> `IMPLEMENTATION_BACKLOG.md`; the appendices stay as the record of what was
> added. The remaining open decisions are listed below.

## The requirement set

| ID | Requirement | Size | Wave | Depends on |
|---|---|---|---|---|
| `NFR-36` | Actuator baseline: management port, `health` + `info` only, separate security chain, no secret-bearing endpoints | M (12h) | 2 | nothing |
| `NFR-37` | Health model: liveness/readiness probes; Postgres and Vault decide readiness, mail does not | S (4h) | 5 | `NFR-36` |
| `NFR-38` | Build identity (version, git commit) in `/actuator/info` | S (4h) | 2 | `NFR-36` |
| `NFR-39` | Metrics contract: Prometheus registry, common tags, naming, cardinality guard **(standing)** | M (12h) | 10 | `NFR-36` |
| `NFR-40` | Initial business metrics (login, verification send, idempotency, optimistic-lock conflicts) | M (12h) | 10 | `NFR-39`; panels via `NFR-32` |
| `NFR-41` | `audit_logs.details`: a nullable JSON column recording what changed (old/new values) | S (4h) | 2 | nothing |
| `NFR-42` | Request tracing via Micrometer Tracing (OpenTelemetry) exported to Tempo; trace ids in log lines; `trace_spans` dropped | M (12h) | 4 | `NFR-36`; Grafana data source via `NFR-32` |
| `NFR-43` | Application logs to console only, or console + `app_logs` — buffered, batched writes that never slow a request | M (12h) | 5 | nothing |
| `NFR-44` | Log archive and retention job for `app_logs` and `audit_logs`: export to dated archive files, delete archived rows, delete expired archives | L (24h) | 9 | `NFR-41`, `NFR-43`, `FR-21` |
| `FR-41` | Staff system status endpoint (health, build version, log levels — read-only) | S (4h) | 7 | `NFR-36`, `NFR-38`, `FR-06` |
| `FR-42` | Staff log-level management: change or reset a logger's level at runtime, persisted, audited | M (12h) | 7 | `FR-06`, `NFR-41` |
| `FR-43` | Staff application-log settings: destination, minimum table level, and (table only) archive settings | M (12h) | 10 | `FR-06`, `NFR-41`, `NFR-43`, `NFR-44` |
| `FR-44` | Staff audit-log archive settings, bounded by retention floors set in config | S (4h) | 9 | `FR-06`, `NFR-41`, `NFR-44` |

## Wave placement

**Rule:** whatever would cause rework after Wave 4.5 lands before or in it.
Everything else goes in one or two at a time, in waves where its dependencies
already exist. Waves 5 onward are built with module boundaries and
domain-partitioned schema from their first migration (Wave 4.5's own
rationale), so log management built later needs no retrofit. Changes the
modulith refactor itself forces on the pieces that land earlier are expected and
welcome — they're part of what Wave 4.5 is meant to teach.

| Wave | Adds | Hours | Why there |
|---|---|---|---|
| 2 | `NFR-41` (first), `NFR-36`, `NFR-38` | +20 | `NFR-41` before `FR-06`/`FR-35`, so their audit rows record old → new from the start, and before `NFR-28` (4.5) turns audit calls into events, so those events are designed with `details` once. `NFR-36` lands in the same wave as `FR-06`'s `SecurityConfig` changes and makes Modulith's actuator endpoint available for 4.5; `NFR-38` rides along |
| 4 | `NFR-42` | +12 | Tracing lets you watch cross-module calls become events during `NFR-28`; dropping `trace_spans` first keeps `NFR-29` from moving it into a domain schema and `NFR-31` from redrawing it, only for it to be dropped. Off-theme, so the wave title gains "& request tracing" |
| 4.5 | nothing added | 0 | One note on `NFR-26`: decide which module owns log management (`app_logs`, `log_settings`, `logger_level_overrides`, the archive job) — `auditlog` or a new module — so `NFR-43` onward is built in the right place |
| 5 | `NFR-43`, `NFR-37` | +16 | Errors are stored in a table before payments arrive in Wave 6; readiness probes are ready before deployment work (`NFR-21`, Wave 6) |
| 7 | `FR-41`, `FR-42` | +16 | Runtime log levels are most useful when debugging payment retries |
| 9 | `NFR-44`, `FR-44` | +28 | The archive job needs ShedLock (`FR-21`, Wave 8); placed in Wave 9 rather than 8 to keep Wave 8 under the 80h cap |
| 10 | `NFR-39`, `NFR-40`, `FR-43` | +36 | Metrics beside `NFR-32`'s dashboards (no orphan metrics); `FR-43` needs `NFR-44` |

These placements are part of a wider rebalance of every open wave (target
60–70h, cap 80h), recorded in `IMPLEMENTATION_BACKLOG.md`. Of that rebalance, the
parts that touch this set: `NFR-18` closes when `NFR-42` merges (Wave 4), since
`NFR-42` re-implements it, so Wave 10 no longer carries it.

Order within Wave 2: `NFR-41` → `FR-06` → `FR-35` → `NFR-36` → `NFR-38` →
`FR-05`. Within Wave 4: the existing items, then `NFR-42`.

**`NFR-42` needs a Grafana before `NFR-32` exists.** Tempo has no screen of its
own, so `NFR-42` brings up a minimal Grafana in `compose.yaml` with only the
Tempo data source. `NFR-32` (Wave 10) later adds the PostgreSQL and Prometheus
sources and the dashboards to that same Grafana.

New work: ~128h, in four slices:

- **Actuator:** `NFR-36`–`NFR-38`, ~20h, no outside dependencies.
- **Metrics and tracing:** `NFR-39`, `NFR-40`, `NFR-42`, ~36h.
- **Log storage:** `NFR-41`, `NFR-43`, `NFR-44`, ~40h. `NFR-44` needs ShedLock (`FR-21`).
- **Staff endpoints:** `FR-41`–`FR-44`, ~32h, all after `FR-06`.

## Core rules

**Actuator**

- **Actuator never shares the application port.** It runs on a separate
  `management.server.port`; `/actuator/*` on the application port returns 404
  through the existing `GlobalExceptionHandler` path.
- **Actuator has its own security chain.** `SecurityConfig` defines its own
  `SecurityFilterChain`, so Spring Boot's default actuator security backs off and
  actuator would otherwise fall under `anyRequest().authenticated()` (health
  returning 401). A second chain, scoped with `EndpointRequest`, owns actuator.
  A customer or staff JWT grants no actuator access.
- **Exposure is an allowlist.** `NFR-36` exposes exactly `health` and `info`;
  `NFR-39` adds `prometheus`. `loggers`, `env`, `configprops`, `beans`,
  `heapdump`, `threaddump` and `metrics` are not exposed over HTTP. JMX exposure
  is off.
- **People don't use the management port.** It serves probes and the metrics
  scraper. Everything a person does — viewing status, changing a log level,
  changing log settings — goes through the staff API, so it's attributed to that
  person.
- **Actuator traffic is logged by exception, not by request.** Rejected
  authentication on the management port logs at WARN. Reads, and above all probe
  traffic (health is hit every few seconds), log at DEBUG at most, so probes
  don't flood the log.
- **Readiness covers what the application cannot serve without.** Postgres and
  Vault decide readiness. Mail does **not** — `FR-30` deliberately returns 201
  when the verification email can't be sent, so a mail outage must never take the
  instance out of service. Stripe and cbu.uz (later waves) follow the same rule:
  measured, not part of readiness. Liveness covers only the application itself.

**Metrics**

- **Each domain module owns its own metrics.** Code records metrics through
  `MeterRegistry` directly; there is no shared `observability` package every
  module depends on. This keeps the metrics work from adding the cross-module
  coupling Wave 4.5 (§13) is meant to remove.
- **Config lives in `application.yaml`.** A `management:` block alongside the
  rest, not a separate `application-observability.yml` profile — profiles here are
  `dev`/`prod`, set externally per environment, and an extra profile would need
  adding to every environment's active list.

**Audit attribution**

- **Every runtime change is made by a named staff member and audited.** Log
  levels and log settings are changed through staff endpoints under `FR-06`'s
  data-driven permissions. Each change writes an `audit_logs` row with
  `actor_type = staff` and `actor_id` = that person's `staff_users.id` — the same
  shape as customer entries — plus the old and new values in `details`
  (`NFR-41`). There is no shared ops credential and no `ops` actor type.
- **System jobs use reserved system actors.** The archive job (`NFR-44`) records
  its own runs as a `log_archiver` actor with a reserved `SystemActors` id,
  following the `retry_scheduler` precedent (`V9`).
- **`NFR-23` already covers DEBUG.** It governs what code logs at *every* level,
  so turning a logger up exposes nothing a correctly written log statement
  wouldn't. A DEBUG statement that logs a secret is an `NFR-23` defect in that
  statement, not a reason to forbid level changes.

**Writing logs**

- **Console logging is always on.** Application logs go to the console in every
  mode. The table is an optional *second* destination, never a replacement: if
  the database is down, errors still need somewhere to go, and log lines written
  before the database and Flyway are ready can't reach a table anyway.
- **Logging never slows a request.** Application-log rows are buffered in memory
  and written to `app_logs` in batches by a background task. The task flushes
  when the batch fills or a short interval passes, whichever comes first. If the
  buffer fills (e.g. the database is down), the oldest lines are dropped and
  counted rather than making a request wait.
- **Audit logs are never buffered.** An audit row is evidence; it can't sit in
  memory where a crash would lose it. Audit writes stay one immediate insert in
  their own transaction (`AuditLogService`, `REQUIRES_NEW`), as today.

**Runtime settings and archives**

- **Runtime settings are saved, shared and survive restarts.** Log levels and
  log settings are stored in the database. Every instance applies them at startup
  and re-reads them on a short interval, so a change reaches every instance, not
  just the one that handled the request.
- **Archive files stay inside one directory set in config.** The base directory
  (`app.logs.archive.base-dir`) is never settable through the API. The API only
  picks a single subfolder name under it.
- **Audit retention has floors the API can't lower.** Minimums for "archive
  after" and "delete archives after" are set in config. Whoever can shorten audit
  retention can erase evidence, so the floor sits outside the API, and the
  permission is `admin`-only. The archive job is the only sanctioned way an
  `audit_logs` row is ever deleted.

## Per-requirement scope

### `NFR-36` — Actuator baseline

Adds `spring-boot-starter-actuator`. Management port configured (random in tests;
Spring Boot's random-port test support covers the management port too — verify).
Exposure exactly `health`, `info`; JMX exposure excluded. New
`SecurityFilterChain` for actuator (ordered before the application chain,
`EndpointRequest.toAnyEndpoint()`), `health` and `info` permitted anonymously.
`show-details: never`. The mail health contributor is disabled or excluded from
the aggregate (see `NFR-37`) so `/actuator/health` doesn't report DOWN whenever
Mailpit isn't running.

**Acceptance**
- `GET /actuator/health` on the application port → 404; on the management port →
  `{"status":"UP"}` with no details.
- A valid customer or staff JWT gets no access to any actuator endpoint beyond the
  anonymous ones.
- Exposure lists exactly `health`, `info`; JMX exposure excluded.
- Integration test: the response bodies of every exposed endpoint contain none of
  the test environment's secret values (JWT private key, datasource password,
  Vault token). Replaces the source document's CI regex-scan job — the same goal
  as `NFR-23`'s no-secrets rule, checked by a test.
- `ARCHITECTURE.md` §6.6 (new) documents the endpoints, the port, the security
  chain and the "people use the staff API" rule.

### `NFR-37` — Health model

Enables the liveness and readiness probe groups. Readiness includes the
readiness state, `db` and Spring Cloud Vault's health contributor (confirm its
registered name during implementation); liveness includes only the liveness
state. Mail excluded from both groups. Considers `server.shutdown: graceful`
alongside the probes — a liveness restart in the middle of a scheduled job is how
the stale `shedlock` rows in §11 happen, so probe settings belong in the
scheduling discussion too, not only deployment. Graceful shutdown also gives
`NFR-43`'s buffer the chance to flush.

**Acceptance**
- `/actuator/health/liveness` and `/actuator/health/readiness` respond on the
  management port.
- With Postgres unavailable, readiness is DOWN and liveness stays UP.
- With the SMTP server unavailable, readiness stays UP.

### `NFR-38` — Build identity

`spring-boot-maven-plugin`'s `build-info` goal plus the git-commit-id plugin,
configured with `failOnNoGitDirectory=false` so a future Docker build without
`.git` still builds (it then reports no git section rather than failing). `info`
shows artifact, version, build time, branch, commit id and commit time.

**Acceptance**
- `/actuator/info` from a CI-built jar shows the commit it was built from.
- `mvn verify` succeeds in a copy of the project with no `.git` directory.

### `NFR-39` — Metrics contract (standing)

Adds `micrometer-registry-prometheus`; exposes `prometheus` on the management
port. A `MeterFilter` adds common tags `application`, `environment` (from the
active profile) and `version` (from build info). Custom metric names are
`subscribemaster.<module>.<thing>`, dot-separated lowercase. Tags only ever take
values from a fixed set; a `MeterFilter` denies known identifier tag keys
(customer, subscription, email) and caps `uri` values on `http.server.requests`.
HTTP server metrics use templated URIs (Spring's default — `/api/v1/subscriptions/{id}`;
unmatched routes tagged `UNKNOWN`). Outbound HTTP calls are only metered when the
client is built from Spring's auto-configured `RestClient.Builder` — a client from
`RestClient.create()` records nothing, so this goes in the checklist.

Telemetry is fail-open by design: Prometheus *pulls* from the application, so an
unavailable Prometheus never touches request handling. No separate requirement
for this; noted here so it isn't re-raised.

**Standing convention** — closes once the naming/tag rules are documented in
§6.6 and the checklist lines below are added; enforced per PR from then on.

**Acceptance**
- Every series in `/actuator/prometheus` carries the three common tags.
- No tag value in `/actuator/prometheus` contains a raw identifier.
- `NFR-32` and `ARCHITECTURE.md` §6.5 amended (see below); a Prometheus service
  added to `compose.yaml`.

### `NFR-40` — Initial business metrics

Rewritten for this application. Each one reads a condition already on §11's
monitoring list or a gap noted under `NFR-34`:

| Metric | Type | Tags | Why |
|---|---|---|---|
| `subscribemaster.auth.login` | counter | `outcome` = `success` \| `bad_credentials` \| `locked` \| `unverified` | Lockout spikes, `FR-31` blocks |
| `subscribemaster.messaging.verification.send` | counter | `outcome` = `sent` \| `failed` | §11's silent mail outage; `NFR-34`'s tabled dashboard metric |
| `subscribemaster.idempotency.request` | counter | `endpoint` (templated), `outcome` = `first` \| `replayed` \| `mismatch` | Retry behaviour of `NFR-33`/`NFR-35` |
| `subscribemaster.concurrency.conflict` | counter | `entity` = `customer_subscription` \| `payment_history` | §11's optimistic-lock spike, today only visible as 409s |

Payment retry/refund metrics are added when Waves 6–7 build those flows, under
`NFR-39`'s checklist line, not here. `NFR-43` and `NFR-44` add their own
metrics (dropped log rows, archive runs) under the same convention.

**Acceptance**
- Each metric appears in `/actuator/prometheus` after one exercise of its code
  path (integration test).
- Each metric has a named panel or alert in `NFR-32`'s dashboard set, or the
  issue records why not — no orphan metrics. (This is why `NFR-40` and `NFR-32`
  want to sit close together.)

### `NFR-41` — `audit_logs.details`

`audit_logs` can record who did what to which resource, but not *what changed*:
`resource_id` is a UUID, and there is no free column. `NFR-34` hit the same gap
with the SMTP cause. Adds a nullable `details JSONB` column (next free
migration), a matching field on `AuditLog`, and a `recordEvent` overload that
takes it. Existing callers are unchanged; rows without details stay `NULL`.

Convention for `details`: a small JSON object with only the values a reviewer
needs — for a change, `{"from": …, "to": …}` plus whatever names the thing changed
(e.g. `"logger"`, `"setting"`). Never secrets or personal data (`NFR-23` applies
here too). **Amends `NFR-17`.**

**Acceptance**
- An audit row written with details reads back with the same JSON; one written
  without it has `NULL`.
- `ARCHITECTURE.md` §6.1 documents the column and its convention.

### `NFR-42` — Request tracing via Micrometer Tracing; `trace_spans` dropped

**Why the table goes.** `trace_spans` (`V6`) satisfies `NFR-18` at the schema
level only — no code writes or reads it. The standard Spring way to trace
requests is Micrometer Tracing with the OpenTelemetry bridge, which exports spans
over OTLP to a tracing backend; it never writes to a relational table. Keeping
the table would mean writing a custom exporter — a worse Tempo inside the primary
database, against §6.2's own advice. With Micrometer Tracing in place, nothing
would ever populate the table, so it is dropped. Metrics (`NFR-39`) don't replace
it — they answer "how slow on average / at p99", traces answer "why was *this*
request slow".

**Scope**
- Micrometer Tracing with the OpenTelemetry bridge and an OTLP exporter.
- Tempo added to `compose.yaml`; Grafana gets Tempo as a data source (amends
  `NFR-32`, which today plans to read `trace_spans` from Postgres).
- Trace and span ids in every log line (Spring Boot adds log correlation when
  tracing is present — confirm the Boot 4 property names), so all log lines for
  one request can be found together. While this is one deployable, that is the
  main day-to-day payoff. When `NFR-43`'s table is on, rows carry the trace id
  too.
- Sampling: everything in dev, a configured probability elsewhere.
- Context propagation into async work (`VerificationEmailListener` today,
  scheduled jobs later), so its spans join the triggering request's trace.
- A forward migration (next free number) that drops `trace_spans`. `V6` is kept
  as written — the history stays accurate.
- `NFR-18`'s row re-scoped: met by Micrometer Tracing + Tempo, not by a table.

Spring Modulith's observability support (Wave 4.5) produces spans for calls
between modules; it builds on this rather than competing with it.

**Acceptance**
- A request to any `/api/v1` endpoint produces a trace in Tempo, visible in
  Grafana, with child spans for its database calls.
- Log lines written during that request carry its trace id.
- A verification email sent after registration appears in the registration's
  trace.
- `trace_spans` no longer exists after migration; no code or document still
  refers to it as live (V6's own comment excepted).

### `NFR-43` — Application logs to console only, or console + table

Puts the unused `app_logs` table to work as an optional second destination.

**Destination and settings.** Two modes: `console` (default) or
`console_and_table`. Also a minimum level for the table, default WARN, so DEBUG
and INFO stay on the console only. Both live in a new `log_settings` table: one
row per target (`application`, `audit`), seeded with defaults, carrying
`NFR-44`'s archive settings too so there is one settings table, not two.
`@Version` for optimistic locking (`NFR-04`), plus `updated_by` (staff id,
`NULL` for seeded defaults) and `updated_at`. `FR-43` makes these editable; until
then they're changed only by migration.

**The writer.**
- A Logback appender attached once the application context and database are
  ready; log lines before that go to the console only.
- Lines at or above the minimum level go into a bounded in-memory queue. Adding
  to the queue never blocks.
- A background task writes batches with plain JDBC batch inserts (not JPA). It
  flushes when the batch size is reached or the flush interval passes, whichever
  comes first, and once more on shutdown.
- **If the queue is full**, the oldest lines are dropped. The drops are counted
  in a metric (`subscribemaster.logging.table.dropped`) and reported in one
  console WARN per interval.
- **No feedback loop:** loggers for the database driver, the connection pool,
  Hibernate, Spring JDBC and the appender itself never go to the table, so a
  failed insert can't log itself into another insert.
- **Config, not API:** buffer size, batch size and flush interval have defaults
  in `application.yaml` and aren't exposed through the API.
- **Picking up changes:** each instance re-reads its destination and minimum
  level from `log_settings` on a short interval (config, default 30s).

**Schema changes to `app_logs`** (next free migration). The table is empty, so
these are safe to make in place:
- **Level names:** the `level` check changes to Logback's names (`trace`,
  `debug`, `info`, `warn`, `error`). The current `warning`/`critical` have no
  Logback equivalent.
- **New columns:** `thread`, `trace_id` (`NULL` until `NFR-42`) and `exception`
  (stack trace text).
- **Logger name:** `source` already exists and holds it.

**Acceptance**
- In `console` mode no rows are written to `app_logs`.
- In `console_and_table` mode a WARN logged by application code appears in
  `app_logs` within the flush interval; an INFO (below the default minimum)
  doesn't.
- With the database unavailable, requests still succeed at normal speed; lines
  are dropped and counted, and console output is unaffected.
- On graceful shutdown, buffered lines are written before the application stops.
- Nothing from the excluded loggers reaches the table.

### `NFR-44` — Log archive and retention job

One scheduled job, run daily under ShedLock (`FR-21`) so it runs once even with
several instances. It handles both tables from their `log_settings` rows.

**Each run, per target:**
1. Select rows older than `archive_after_days`, in chunks.
2. Write each chunk to a compressed JSON Lines file (`.jsonl.gz`) in
   `<base-dir>/<archive_subfolder>/`, named from `file_name_pattern`.
3. Delete those rows only after the file is fully written and closed.
4. Delete archive files in that folder older than `delete_archives_after_days`.
5. Record the run in `audit_logs`, with actor `log_archiver` and a reserved
   `SystemActors` id. Actions are `LOGS_ARCHIVED` and `LOG_ARCHIVES_DELETED`,
   with `details` naming the target, row count, date range and file.

**For `app_logs`:** retention applies whenever rows exist, including after the
destination is switched back to `console`. Rows left in the table are drained
under the last saved settings rather than kept forever.

**File naming rules** (enforced when settings are saved, `FR-43`/`FR-44`):
- **Tokens:** `{date}` (run date, `yyyy-MM-dd`), `{seq}` (per-run sequence),
  `{from}` / `{to}` (the date range of the rows in the file).
- **Required:** `{date}` and `{seq}`, so two runs never overwrite each other.
- **Allowed characters:** letters, digits, `.`, `_`, `-` and the tokens. No
  slashes, because the folder is a separate setting. Maximum 100 characters.
- **Extension:** always `.jsonl.gz`, added automatically, not part of the
  pattern.

**Folder rules.** The base directory comes from config
(`app.logs.archive.base-dir`). `archive_subfolder` is one plain folder name:
letters, digits, `.`, `_`, `-`. It can't be absolute, can't contain `..` and
can't contain separators.

**Audit floors.** Configured minimums for audit retention:
`app.logs.audit.min-archive-after-days` and
`app.logs.audit.min-delete-after-days`. The job refuses to run with audit
settings below them, which covers settings changed by migration as well as by
API.

**Crash between writing a file and deleting its rows:** the next run archives
the same rows again, so they can appear in two files. That's accepted as a
duplicate rather than a loss, and it's documented in §6.1.

**Schema** (next free migration): `audit_logs` checks gain actor
`'log_archiver'` and actions `'logs_archived'`, `'log_archives_deleted'`.

**Acceptance**
- Rows older than the setting end up in a correctly named `.jsonl.gz` under the
  base directory and are gone from the table; newer rows are untouched.
- Archives older than the deletion setting are removed; newer ones kept.
- Two instances triggering the job at once produce one run (ShedLock).
- Each run writes its audit rows with details.
- Audit settings below the configured floors stop the audit part of the run
  with an ERROR log line, while the `app_logs` part still runs.
- The job's outcome and duration are metered (`NFR-39` convention).

### `FR-41` — Staff system status endpoint

A staff-only `GET /api/v1/staff/system/status` (exact path left to
implementation) returning overall health with component status, build version
and current log levels — read-only, delegated to Actuator's endpoint beans or
`LoggingSystem`. Guarded by the **existing** `view_system_health` permission
(seeded in `V1.1`, granted to the `auditor` role) per
`staff_authorization_design.md`, never a hardcoded role. Staff never call the
management port.

**Acceptance**
- A staff token with the permission gets the status; without it, 403; a customer
  token, 403.
- The same staff token gets nothing from the management port's non-anonymous
  endpoints.

### `FR-42` — Staff log-level management

- `PUT /api/v1/staff/system/loggers/{name}` sets a logger's level.
- `DELETE /api/v1/staff/system/loggers/{name}` resets it to the configured level.
- The current levels are read through `FR-41`.
- Paths are left to implementation.

Guarded by a new `manage_log_levels` permission.

**Persistence.** Overrides live in a new `logger_level_overrides` table: logger
name, level, `@Version`, `updated_by`, `updated_at`. Every instance applies them
at startup and re-reads them on the same interval as `NFR-43`, so a change
reaches every instance and survives restarts.

**Auditing.** Each set or reset writes `LOG_LEVEL_CHANGED` or `LOG_LEVEL_RESET`
with the staff member as actor, and `details` = `{"logger": …, "from": …, "to":
…}`. It also logs one INFO line, so a reader of the application log sees why the
output just changed.

**Schema** (next free migration):
- the `manage_log_levels` permission, granted to `admin` explicitly — `V1.1`
  only gave `admin` the permissions that existed at the time;
- the two audit actions;
- the overrides table.

**Acceptance**
- With `manage_log_levels`, a `PUT` changes the level on every instance within
  the refresh interval, and the change survives a restart.
- Each change writes one audit row: actor `staff` with the caller's
  `staff_users.id`, and `details` showing the old and new levels.
- A `DELETE` returns the logger to its configured level and is audited.
- Without the permission, 403; with a customer token, 403.
- Unknown level values are rejected with 400.

### `FR-43` — Staff application-log settings

`GET` and `PUT /api/v1/staff/logs/application/settings` (path left to
implementation).

**Permissions:**
- Read needs the existing `view_app_logs`.
- Change needs a new `manage_app_log_settings`, granted to `admin`.

**What can be changed:**
- the destination (`console` / `console_and_table`) and the minimum table level;
- the archive settings: archive after N days, archive subfolder, file name
  pattern, delete archives after N days.

**Archive settings are editable only while the destination is
`console_and_table`.** In `console` mode they read back normally, but a change
returns 409 with a message saying the table is off. The job keeps applying the
last saved values to any rows still in the table.

**Saving rules:**
- `PUT` carries the version it was based on. A stale version returns 409
  (`NFR-04`).
- Pattern and subfolder are validated per `NFR-44`'s rules.
- The response includes a preview of the next archive file name.

**Auditing:** every saved change writes one `LOG_SETTINGS_CHANGED` row with the
staff member as actor and `details` = the changed fields, old → new. Unchanged
fields are left out.

**Acceptance**
- Switching to `console_and_table` starts table writes on every instance within
  the refresh interval; switching back stops them.
- An archive-setting change in `console` mode returns 409; the same change in
  table mode is saved and audited.
- An invalid pattern (no `{date}`, a slash, `..`) or subfolder returns 400.
- A stale version returns 409.
- Permission checks as above; a customer token, 403.

### `FR-44` — Staff audit-log archive settings

`GET` and `PUT /api/v1/staff/logs/audit/settings` (path left to implementation).

**Permissions:**
- Read needs the existing `view_audit_logs`.
- Change needs a new `manage_audit_log_settings`, granted to `admin` **only**.
  `auditor` stays read-only, so the people reviewing the audit trail can't change
  its retention.

**What can be changed:** the same archive settings as `FR-43`. There's no
destination, because audit logs always go to the table.

**Rules:**
- The two day values can't go below the configured floors (400, naming the
  floor).
- Versioning, validation, preview and auditing are the same as `FR-43`.

**Acceptance**
- A change at or above the floors is saved and audited with old → new values.
- A change below either floor returns 400 and isn't saved.
- `auditor` can read but gets 403 on change.

## Impact on existing requirements and documents

| Where | Change | With |
|---|---|---|
| `NFR-32` notes, `ARCHITECTURE.md` §6.5 | "No separate metrics store" no longer holds — Prometheus becomes Grafana's second data source alongside PostgreSQL. | `NFR-39` |
| `ARCHITECTURE.md` §6 intro, §6.4 | §6 is built on three logging concerns; metrics are a fourth observability concern. §6.4's "where does a new event belong" gains "a count or duration you'd alert on → a metric". | `NFR-39` |
| `ARCHITECTURE.md` §6.6 (new) | Endpoints, ports, security chain, tag contract, how to add a metric or health indicator. Replaces the source document's separate "engineering note". | `NFR-36`, `NFR-39` |
| `ARCHITECTURE.md` §11 | Conditions with a metric (optimistic-lock spike, verification send failures) read "watch metric X", not "grep logs / query". | `NFR-40` |
| `NFR-17` notes, `ARCHITECTURE.md` §6.1 | `details` column and its convention; runtime changes attributed to the named staff member; the archive job is the only sanctioned deletion of `audit_logs` rows, bounded by config floors. | `NFR-41`, `NFR-44` |
| `ARCHITECTURE.md` §11 (actor check) | "Every `actor_id` resolves to a real row" must treat the new `log_archiver` reserved id like `RETRY_SCHEDULER`'s. | `NFR-44` |
| `NFR-18` row, `V6` (via a new drop migration) | Re-scoped: met by Micrometer Tracing + Tempo; `trace_spans` dropped. | `NFR-42` |
| `ARCHITECTURE.md` §6 intro table, §6.2, §6.5, §10, §11, §12 | `trace_spans` leaves the "three logging concerns" table (traces become external, like metrics); §6.5 Grafana reads Tempo; §10's checklist line, §11's growth alert and §12's trade-off drop their `trace_spans` half. | `NFR-42` |
| `subscribe_master_erd.drawio`, `PRESENTATION_OUTLINE.md` | Remove `trace_spans`; add `log_settings` and `logger_level_overrides`; `app_logs`' new columns. | `NFR-42`, `NFR-43`, `FR-42` |
| `NFR-32` notes | Grafana itself arrives earlier, with `NFR-42` (Wave 4), holding only the Tempo data source; `NFR-32` adds PostgreSQL and Prometheus sources and the dashboards. | `NFR-42` |
| `NFR-26` notes (Wave 4.5) | Decide which module owns log management, so `NFR-43` onward is built in the right module. | wave plan |
| `IMPLEMENTATION_BACKLOG.md` wave headings and notes | Part of the wider wave rebalance: new titles, Wave 2's order line, and `NFR-18` closed by `NFR-42`. | wave plan |
| `NFR-23` notes, `ARCHITECTURE.md` §6.2, §12 | `app_logs` is now an optional, buffered second destination with a minimum level and archive-based retention. §6.2's "expect it to outgrow the primary database" is answered by the minimum level and the archive job rather than by moving it out. | `NFR-43`, `NFR-44` |
| `staff_authorization_design.md`, `V1.1` follow-up | Three new permissions (`manage_log_levels`, `manage_app_log_settings`, `manage_audit_log_settings`), each granted to `admin` explicitly. | `FR-42`–`FR-44` |
| `FR-21` (ShedLock, Wave 8) | Becomes a dependency of `NFR-44` (Wave 9), and through it of `FR-43`/`FR-44`. | `NFR-44` |
| `NFR-21` | Hosting must provide an internal-only management port, configurable probe paths, and a durable volume (or shared storage) for archive files — files inside a container are lost on redeploy. Network policy and a non-local dashboard share `NFR-21`'s blocked status. | note only |
| `load_testing_open_questions.md` | Item 3 (no metrics) is answered by `NFR-39`; item 4's "something to observe the app" by Prometheus/Grafana. | `NFR-39` |
| `README.md` tool list, `compose.yaml` | Prometheus and Tempo services; management port in the commented-out `app` service, with a healthcheck on `/actuator/health/readiness`; an archive volume. | `NFR-37`, `NFR-39`, `NFR-42`, `NFR-44` |
| `NFR-34` notes | Its tabled "dashboard metrics for send failure" is delivered by `NFR-40`. Its missing SMTP cause could now go in `details` — a possible follow-up, not in scope here. | `NFR-40`, `NFR-41` |

**Known future requirement, not part of this set:** a secondary, always-in-sync
database for failover. These designs don't block it, but they're affected by it:
- **Slower writes:** synchronous replication makes every write wait for the
  replica, so `NFR-43`'s batching matters more and audit inserts get slightly
  slower.
- **Failover window:** batches that fail during a switchover stay buffered and
  are retried, with the oldest dropped if the buffer fills.
- **Archive files:** database replication doesn't copy them, so they need their
  own durable storage. That's one more reason the base directory is config.
- **ShedLock:** its lock lives in the database, so after a failover the archive
  job continues on the new primary.

## Checklist lines to add to `IMPLEMENTATION_BACKLOG.md`

Each added in the PR that establishes the rule, following `FR-06`'s precedent —
not before.

| Checklist | Line | Added with |
|---|---|---|
| New service / business logic | If ops would watch this outcome (a failure, retry or conflict), record a metric under `subscribemaster.*` with fixed-set tags only — never a customer or subscription id (NFR-39) | `NFR-39` |
| New service / business logic | Each new metric has a dashboard panel or alert, or the issue says why not (NFR-40) | `NFR-40` |
| New service / business logic | A setting changeable at runtime is saved in the database, applied on every instance, and each change is audited with the staff member as actor and old → new values in `details` (NFR-41) | `NFR-41` |
| New external integration | Decide readiness vs. metric-only: in readiness only if no request can be served without it (NFR-37) | `NFR-37` |
| New external integration | Client built from Spring's auto-configured `RestClient.Builder`, so outbound calls are metered (NFR-39) | `NFR-39` |
| New scheduled job | Run outcome and duration metered, so a job that quietly stops running is visible (NFR-40) | `NFR-40` |
| New scheduled job; new service (async listeners) | Trace context propagated into async/scheduled work, so its spans join a trace instead of starting orphaned ones (NFR-42) | `NFR-42` |

## Dropped from the source document

| Source item | Why |
|---|---|
| OBS-1.5's metrics (`projectwork.sync.*`, `rule.evaluation`, `auth.admin.attempts`) | Another system's domain; replaced by `NFR-40`'s table |
| Custom indicators for an identity provider and file storage (OBS-1.2) | Neither exists — JWTs are issued in-house, nothing stores files |
| `loggers` over HTTP | Replaced by the staff API (`FR-42`), so every level change is attributed to a named staff member rather than a shared credential |
| `metrics` over HTTP, `show-details: when-authorized` (OBS-1.1, NF.1) | `prometheus` covers metrics; health details go to staff via `FR-41`, so health stays status-only |
| `application-observability.yml` profile, Phase 3 extraction (NF.5) | Single deployable (§13.1); profiles are externally set per environment |
| CI job scanning endpoint output with regexes (NF.2) | Replaced by an integration test against known secret values |
| 5,000-series cap and ≤ 2% p99 overhead under load test (NF.3) | The other project's numbers; no load testing exists yet. The cardinality filter stays in `NFR-39`; the load-test assertion belongs to the load-testing requirement once scoped |
| Fail-open test (NF.4) | Pull-based scraping makes it true by construction; noted under `NFR-39` |
| Network policy, dashboard in a non-local environment (NF.1, NF.5) | Blocked on `NFR-21` (no hosting yet), like `NFR-32`'s staging/prod part |
| Milestone, priority labels, epic issue | This project uses waves, `size-*` and `domain:*` labels, one issue per requirement |

## Open decisions

1. **Wave placement — decided** (see "Wave placement").
2. **Prometheus scrape credential.** When `prometheus` is exposed (`NFR-39`):
   rely on management-port isolation alone, or give the scraper its own
   read-only credential in Vault. Recommended: its own. Scrapes are reads, so
   they're not audited.
3. **Tracking issue.** None proposed (no epic convention; hours stay on the
   requirement issues). The backlog groups the thirteen under one heading.
4. **Domain labels.** Proposed:
   - `domain:auditlog` for `NFR-41`, `NFR-42`, `NFR-44` and `FR-44` (audit and
     request tracing);
   - `domain:dev/techops` for `NFR-36`–`NFR-40`, `NFR-43` and `FR-41`–`FR-43`
     (running and monitoring the application).

   Labelled by what's managed, not by who calls the endpoint.
5. **The source document — decided.** `phase1-observability-issues.md` stays
   outside the repository, as `task.pdf` does; this document is the one source
   of truth.
6. **Default values.** Placeholders until you pick them:

   | Setting | Application | Audit |
   |---|---|---|
   | Destination / minimum table level | `console` / WARN | — |
   | Archive after | 30 days | 365 days (floor: 365) |
   | Archive subfolder | `application` | `audit` |
   | File name pattern | `app-logs-{date}-{seq}` | `audit-logs-{date}-{seq}` |
   | Delete archives after | 90 days | 2555 days, about 7 years (floor: 2555) |
   | Settings refresh interval | 30s | 30s |
7. **Auto-revert for log levels.** With overrides saved, a logger set to DEBUG
   stays there until someone resets it. Optional: an expiry after which it
   resets itself, audited as `LOG_LEVEL_RESET` under a reserved system actor.
   Not in `FR-42`'s scope unless you want it.

---

## Appendix A — Draft rows for `subscribe_master_requirements.md`

FR table (columns: ID | Category | Requirement | Source | Status | Notes):

```markdown
| FR-41 | Auth & User Management | Staff system status endpoint: overall health, build version and current log levels, read-only, behind staff RBAC | Additional | ➖ N/A | Application-level only, no schema change. Staff see operational status through the application's own API under `FR-06`'s data-driven permissions — the existing `view_system_health` permission (`V1.1`, granted to `auditor`) — never through the Actuator management port (see `actuator_observability.md`). Depends on `NFR-36`, `NFR-38` and `FR-06`. **Not sourced from `task.pdf`.** |
| FR-42 | Auth & User Management | Staff log-level management: change or reset a logger's level at runtime, saved and applied on every instance, each change audited | Additional | ➖ N/A | New `logger_level_overrides` table and a new `manage_log_levels` permission (granted to `admin` explicitly — `V1.1` only gave `admin` the permissions that existed then). Every instance applies overrides at startup and on a short refresh interval, so changes reach all instances and survive restarts. Each set/reset writes `LOG_LEVEL_CHANGED`/`LOG_LEVEL_RESET` with the staff member as actor (`staff_users.id`) and `{"logger","from","to"}` in `details` (`NFR-41`), plus an INFO log line. Replaces exposing Actuator's `loggers` endpoint, so changes are attributed to a named person, not a shared credential. Not an `NFR-23` conflict: `NFR-23` governs what code logs at every level. Depends on `FR-06` and `NFR-41`. **Not sourced from `task.pdf`.** |
| FR-43 | Auth & User Management | Staff application-log settings: destination (console, or console + table), minimum table level, and — while the table is on — archive settings (archive after, subfolder, file name pattern, delete archives after) | Additional | ➖ N/A | Read with the existing `view_app_logs`; change with a new `manage_app_log_settings` (granted to `admin`). Archive settings return 409 while the destination is `console`; the archive job keeps draining existing rows under the last saved values. Optimistic locking on save (`NFR-04`); pattern/subfolder validated per `NFR-44`; the response previews the next file name. Each change writes `LOG_SETTINGS_CHANGED` with the staff member as actor and changed fields old → new in `details`. Depends on `FR-06`, `NFR-41`, `NFR-43` and `NFR-44`. **Not sourced from `task.pdf`.** |
| FR-44 | Auth & User Management | Staff audit-log archive settings (archive after, subfolder, file name pattern, delete archives after), bounded by retention floors set in config | Additional | ➖ N/A | Read with the existing `view_audit_logs`; change with a new `manage_audit_log_settings`, granted to `admin` only, so `auditor` stays read-only over the trail it reviews. Values below the configured floors are rejected (400). Same locking, validation, preview and auditing as `FR-43`. Depends on `FR-06`, `NFR-41` and `NFR-44`. **Not sourced from `task.pdf`.** |
```

NFR table (columns: ID | Category | Requirement | Status | Notes):

```markdown
| NFR-36 | Observability / Security | Spring Boot Actuator baseline: separate management port, only `health` and `info` exposed, own security chain, no secret-bearing endpoints | ➖ N/A | Application/configuration-level, no schema change. Actuator runs on its own `management.server.port`; `/actuator/*` on the application port is a 404. Because `SecurityConfig` defines its own filter chain, Spring Boot's default actuator security backs off, so a second chain scoped with `EndpointRequest` owns actuator — no customer or staff token grants access. `health` is anonymous and status-only (`show-details: never`); `loggers`/`env`/`configprops`/`beans`/`heapdump`/`threaddump` stay off — people use the staff API (`FR-41`, `FR-42`), never the management port. An integration test checks no exposed endpoint returns a known secret value (`NFR-23`). Adds `ARCHITECTURE.md` §6.6. **Not sourced from `task.pdf`** — adapted from an external observability breakdown; see `actuator_observability.md`. |
| NFR-37 | Observability / Reliability | Health model: liveness and readiness probes; readiness covers Postgres and Vault, never mail | ➖ N/A | Application/configuration-level. Readiness reflects what no request can be served without (Postgres, Vault); liveness only the application itself. Mail is excluded from both because `FR-30` deliberately succeeds when the verification email can't be sent — a mail outage must not take an instance out of service. Stripe/cbu.uz follow the same rule when they arrive: measured, not readiness. Probe and graceful-shutdown settings also matter to scheduling: a liveness restart mid-job leaves the stale `shedlock` rows §11 warns about. Depends on `NFR-36`. **Not sourced from `task.pdf`.** |
| NFR-38 | Observability / Deployability | Build identity (version, git branch/commit, build time) in `/actuator/info` | ➖ N/A | Build-level: `spring-boot-maven-plugin`'s `build-info` goal plus the git-commit-id plugin with `failOnNoGitDirectory=false`, so a future Docker build without `.git` still builds. Lets anyone confirm which commit an environment is actually running. Depends on `NFR-36`. **Not sourced from `task.pdf`.** |
| NFR-39 | Observability | Application metrics contract: Prometheus registry, common tags (`application`, `environment`, `version`), `subscribemaster.*` naming, bounded tag values | ➖ N/A | Application/configuration-level. **Standing convention** once established: every metric uses fixed-set tag values only (a `MeterFilter` denies identifier tags and caps `uri`), and each domain module records its own metrics through `MeterRegistry` — no shared observability package, to keep Wave 4.5's boundaries. **Amends `NFR-32`:** Prometheus becomes Grafana's second data source alongside PostgreSQL (§6.5's "no separate metrics store" no longer holds). Answers item 3 of `load_testing_open_questions.md`. Depends on `NFR-36`. **Not sourced from `task.pdf`.** |
| NFR-40 | Observability | Initial business metrics: login outcomes, verification email send outcomes, idempotency replays/mismatches, optimistic-lock conflicts | ➖ N/A | Application-level. Each metric reads a condition already on `ARCHITECTURE.md` §11's monitoring list or the dashboard metric tabled under `NFR-34`; see `actuator_observability.md` for names and tags. No orphan metrics: each gets a panel or alert in `NFR-32`'s dashboards, or a recorded reason why not. Payment metrics come with Waves 6–7 under `NFR-39`'s convention. Depends on `NFR-39`; pairs with `NFR-32`. **Not sourced from `task.pdf`.** |
| NFR-41 | Security / Compliance | `audit_logs.details`: a nullable JSON column recording what an audited action changed | ➖ N/A | Migration adding `details JSONB NULL` to `audit_logs`, plus the entity field and a `recordEvent` overload; existing callers unchanged. Closes the gap `NFR-34` hit (no place for the SMTP cause): `resource_id` is a UUID and there was no free column, so an audit row could say *who* changed *what kind of thing* but not *what changed*. Convention: a small object with only what a reviewer needs (`{"from","to"}` plus the name of the thing changed); never secrets or personal data (`NFR-23`). **Amends `NFR-17`.** Needed by `FR-42`–`FR-44` and `NFR-44`. **Not sourced from `task.pdf`.** |
| NFR-42 | Observability | Request tracing via Micrometer Tracing (OpenTelemetry bridge, OTLP export to Tempo), trace ids in log lines; `trace_spans` dropped | ➖ N/A | Application/infrastructure-level plus a forward migration dropping `trace_spans`. **Re-implements `NFR-18`:** `trace_spans` (`V6`) was only ever schema — no code writes or reads it — and Micrometer Tracing exports spans to a tracing backend, never to a relational table, so with it in place nothing would populate the table. Tempo joins `compose.yaml` and becomes a Grafana data source (amends `NFR-32`). Includes log correlation (trace/span ids on every log line and in `app_logs` rows), a sampling setting per environment, and context propagation into async and scheduled work. `V6` stays as written; the drop is a new migration. Depends on `NFR-36`. **Not sourced from `task.pdf`.** |
| NFR-43 | Observability / Performance | Application logs to console only, or console + `app_logs`, via buffered batch writes that never slow a request | ➖ N/A | Console logging is always on; the table is an optional second destination (it can't replace the console — database outages and pre-startup lines still need somewhere to go). A Logback appender queues lines at or above a minimum level (default WARN) in a bounded, non-blocking buffer; a background task batch-inserts them (plain JDBC) on batch size or interval, and on shutdown. When full, the oldest lines are dropped and counted (metric + one console WARN per interval). Database/pool/Hibernate/JDBC loggers are excluded to prevent feedback loops. New `log_settings` table (one row per target, defaults seeded, `@Version`, `updated_by`) holds destination, minimum level and `NFR-44`'s archive settings. `app_logs` migrated in place (empty): Logback level names, plus `thread`, `trace_id`, `exception`. Audit writes are deliberately **not** buffered. **Amends `NFR-23`.** **Not sourced from `task.pdf`.** |
| NFR-44 | Security / Compliance | Log archive and retention job for `app_logs` and `audit_logs`: export old rows to dated `.jsonl.gz` files, delete archived rows, delete expired archives | ➖ N/A | One daily job under ShedLock (`FR-21`), reading each target's `log_settings` row. Rows older than "archive after" are written to `<base-dir>/<subfolder>/<pattern>.jsonl.gz` and deleted only after the file is closed; archives older than "delete after" are removed. The base directory is config-only; the subfolder is one plain folder name; the file name pattern must contain `{date}` and `{seq}` and no slashes. Audit retention has config floors the job enforces. Each run is audited by a new reserved system actor `log_archiver` with details. The only sanctioned deletion of `audit_logs` rows — **amends `NFR-17`**. A crash between file write and row delete can duplicate rows across files (accepted, never lost). Depends on `NFR-41`, `NFR-43` and `FR-21`. **Not sourced from `task.pdf`.** |
```

## Appendix B — Draft backlog entries for `IMPLEMENTATION_BACKLOG.md`

Grouped by the wave each entry goes into.

**Wave 2 — `NFR-41` as the first item; `NFR-36`, `NFR-38` after `FR-35`:**

```markdown
- `[NFR-41] audit_logs.details column (what changed, old → new)` — **[S]** — no dependencies; amends `NFR-17`.
- `[NFR-36] Actuator baseline (management port, health + info only, separate security chain)` — **[M]** — no dependencies. See `actuator_observability.md`.
- `[NFR-38] Build identity in /actuator/info` — **[S]** — depends on `NFR-36`.
```

**Wave 4 — after the existing items:**

```markdown
- `[NFR-42] Request tracing via Micrometer Tracing + Tempo; drop trace_spans` — **[M]** — depends on `NFR-36`; re-implements `NFR-18`; amends `NFR-32` (Tempo data source).
```

**Wave 5:**

```markdown
- `[NFR-43] Application logs to console, or console + app_logs (buffered, non-blocking)` — **[M]** — no dependencies; amends `NFR-23`.
- `[NFR-37] Health model (liveness/readiness; Postgres + Vault, never mail)` — **[S]** — depends on `NFR-36`.
```

**Wave 7:**

```markdown
- `[FR-41] Staff system status endpoint (read-only, RBAC)` — **[S]** — depends on `NFR-36`, `NFR-38` and `FR-06`; uses the existing `view_system_health` permission.
- `[FR-42] Staff log-level management (persisted, audited)` — **[M]** — depends on `FR-06` and `NFR-41`.
```

**Wave 9:**

```markdown
- `[NFR-44] Log archive and retention job (app_logs, audit_logs)` — **[L]** — depends on `NFR-41`, `NFR-43` and `FR-21` (ShedLock); amends `NFR-17`.
- `[FR-44] Staff audit-log archive settings (bounded by config floors)` — **[S]** — depends on `FR-06`, `NFR-41` and `NFR-44`.
```

**Wave 10:**

```markdown
- `[NFR-39] Metrics contract (Prometheus registry, common tags, naming, bounded cardinality)` — **[M]** **(standing)** — depends on `NFR-36`; amends `NFR-32`'s "no separate metrics store" boundary.
- `[NFR-40] Initial business metrics` — **[M]** — depends on `NFR-39`; pairs with `NFR-32` (no orphan metrics).
- `[FR-43] Staff application-log settings (destination, minimum level, archive settings)` — **[M]** — depends on `FR-06`, `NFR-41`, `NFR-43` and `NFR-44`.
```

`NFR-18`'s row in `subscribe_master_requirements.md` also changes: status from
"✅ Met (v6)" to reflect that it is re-implemented by `NFR-42`, and its Wave 10
backlog entry is removed (the issue closes when `NFR-42` merges).
