# Implementation Backlog & Prioritization

**Purpose:** a ready-to-open GitHub Issues backlog, one issue per FR/NFR from `subscribe_master_requirements.md`, sequenced into waves. This document captures *why* the sequencing is what it is, so the ordering itself is a documented decision, not an arbitrary list.

---

## How to use this

1. Open one GitHub Issue per row below, titled exactly as shown (`[FR-01] User registration endpoint`) — this keeps every issue traceable back to its requirement ID in `subscribe_master_requirements.md`.
2. Work waves roughly in order — later waves generally depend on earlier ones (see rationale per wave).
3. Within a wave, issues can usually be worked in parallel or in any order unless a note says otherwise.
4. Some issues will decompose into implementation subtasks once picked up (e.g. `FR-12` likely splits into "create Stripe payment intent," "handle webhook," "persist `payment_history` row") — use the issue's checklist feature for that; it doesn't need a new top-level requirement ID.
5. Apply a size label (`S`/`M`/`L`) to each issue when you open it, using the rubric below.
6. Copy the relevant work-type checklist(s) from **"Issue checklists by work type"** below into the issue as a task list, based on what kind of work it actually involves — not every checklist applies to every issue, and most issues will pull from more than one (e.g. a new endpoint usually touches both the "REST endpoint" and "service" checklists).

---

## Sizing labels (S / M / L)

For a solo project, a full story-point/planning-poker system is overhead with no one to calibrate against — but a rough size still helps with pacing and knowing what you're committing to before you start. This is time-boxed effort estimation, anchored to concrete criteria rather than gut feel:

| Label | Rough effort | Criteria (if any one applies, round up to the next size) |
|---|---|---|
| **S** — Small | Half a day or less | Touches one class/file, or a small addition to an existing one. No new external integration. No new database interaction beyond a simple query. |
| **M** — Medium | 1–2 days | Touches multiple layers (controller + service + repository). Moderate new business logic. May involve a new but well-understood integration pattern (e.g. a new endpoint following an existing convention). |
| **L** — Large | 3+ days / spans multiple sessions | New external integration from scratch (Stripe, cbu.uz, Vault). Complex business logic with several edge cases (retry/dunning, refund proration). Crosses many tables or services. Anything you can't clearly picture the full implementation of yet. |

**Two calibration rules:**
- **If you're unsure between two sizes, pick the larger one.** Underestimating causes more friction (a Small that becomes a two-day slog feels like failure; a Medium that takes half a day feels like a win) than overestimating does.
- **`(standing)` convention items get sized as "establish the convention," not "guarantee it forever."** E.g. `NFR-01` (layered architecture) is realistically an `S` to write down and start following — its actual cost is paid continuously across every future PR, which sizing can't capture and shouldn't try to.

The size labels below are a starting estimate made with full context of the schema and design decisions — expect to revise a few once you're actually inside the code and the real shape of the work becomes clearer. That's normal; update the label on the issue itself rather than here, since this document is the initial estimate, not a live tracker (same principle as the frozen-snapshot framing in `subscribe_master_requirements.md`).

---

## Two kinds of NFRs — tracked differently

Not all NFRs are "features" that get built once and marked done. Splitting them matters for how you actually track them:

- **Discrete, buildable items** — optimistic locking, exception handling, auditing, tracing, caching, CI/CD, Docker Compose, migrations tooling. These get an issue and close normally.
- **Standing conventions, enforced continuously** — layered architecture (NFR-01), code style (NFR-06), Clean Code principles (NFR-10), constructor injection (NFR-14), no eager fetching (NFR-15), no native SQL (NFR-16). These aren't things you build once; they're a definition-of-done applied to *every* PR. Opening an issue for these is still useful (as a place to document the standard and link violations), but "closing" it doesn't mean "compliance is now guaranteed forever" — it means the standard is documented and enforced going forward (e.g. via PR review checklist or a linter/static analysis rule, not a one-time task).

The backlog below still lists one issue per NFR either way, but issues in the second category are marked **(standing)** so nobody expects them to behave like a normal feature ticket.

---

## Issue checklists by work type

Most issues involve one or more of these categories. Pick whichever apply and paste that checklist into the issue when you start it — this is how the "standing convention" NFRs (§ above) actually get enforced per-PR, rather than only existing as an abstract policy nobody checks against.

**Always applies, every issue, no exceptions:**
- [ ] Code style follows the Google Java Style Guide (NFR-06)
- [ ] Reads as Clean Code — clear naming, no dead code, no commented-out blocks left in (NFR-10)

**New REST endpoint / controller:**
- [ ] Swagger/OpenAPI annotations present (NFR-09)
- [ ] Controller contains no business logic — delegates to a service, stays thin (NFR-01)
- [ ] Constructor injection only, no field injection (NFR-14)
- [ ] Errors bubble up to the global exception handler rather than being caught/formatted locally (NFR-02)
- [ ] Request/response uses DTOs — entities are never returned directly

**New JPA entity / mapping:**
- [ ] Every association explicitly marked `LAZY` (NFR-15)
- [ ] `created_at`/`updated_at` present if the row is mutable, matching the pattern already established across the schema (NFR-07)
- [ ] `@Version` mapped if this entity needs optimistic locking (already required on `customer_subscriptions`/`payment_history` — consider whether a new entity needs it too) (NFR-04)
- [ ] Entity lives in the correct package per layered architecture (NFR-01)

**New repository / query method:**
- [ ] Uses JPQL or the Specification API — no native SQL (NFR-16)
- [ ] Paginated (`Pageable`) if the result set could grow unbounded
- [ ] Constructor injection only (NFR-14)

**New service / business logic:**
- [ ] Business logic lives here, not leaked into the controller (NFR-01)
- [ ] Constructor injection only (NFR-14)
- [ ] Wrapped in `@Transactional` if it touches money or spans multiple table writes (NFR-03)
- [ ] Optimistic-lock conflicts are caught and retried where relevant, not left to bubble as a raw 500 (NFR-04)
- [ ] Writes an `audit_logs` entry if this is the kind of action that should be auditable (NFR-17)

**New scheduled job:**
- [ ] Uses ShedLock so it can't run concurrently across multiple instances (FR-21)
- [ ] Outcomes are logged appropriately — `notification_log` if it's a customer-facing notification
- [ ] Constructor injection only (NFR-14)

**New Flyway migration:**
- [ ] Placed in `src/main/resources/db/migration/`, correctly named (`Vx__description.sql`, or `R__` for repeatable)
- [ ] Status/enum casing follows the existing convention — lowercase, except `customer_subscriptions.status` (see `COMMON_QUERIES.md`)
- [ ] Any index expression using a function is checked for the `IMMUTABLE` requirement before assuming it'll apply cleanly (see `V4`'s history for exactly why)
- [ ] Actually run — via `flyway migrate` or `mvn verify` (Testcontainers) — not just reviewed by eye

**New external integration (Stripe, cbu.uz, additional Vault usage, etc.):**
- [ ] Secrets/credentials sourced from Vault — never hardcoded, never in `.env` (NFR-08/NFR-20)
- [ ] Resilience/fallback considered (Circuit Breaker pattern) if this is a live call in the request path, following the `FR-16` precedent
- [ ] Errors from the external service are translated, not leaked as raw stack traces to the client (NFR-02)

**New test:**
- [ ] Follows the `SubscribeMasterApplicationIT` Testcontainers pattern if it needs a real Postgres/Vault, rather than a hand-rolled setup
- [ ] Named `*Test`/`*Tests` for unit tests (runs via Surefire) or `*IT` for integration tests (runs via Failsafe) — this suffix is mechanically load-bearing, not just a style choice

---

## Wave 0 — Project bootstrap ✅ Complete (verified via CI)

**Rationale:** nothing else can be built, tested, or deployed without this. Do this before writing feature code, not alongside it. This wave also absorbs the non-code setup tasks that used to sit in a separate "Environment & Third-Party Setup" bucket in the Overview graph — they're one-time bootstrap work with no real ordering dependency relative to the code-focused items below, so keeping them in a separate wave/node implied a sequencing that didn't actually exist.

**Code / tooling issues:**
- `[NFR-13] Set up Flyway and wire migrations into the build` — **[M]**
- `[NFR-12] Docker Compose single-command startup` — **[S]**
- `[NFR-08] Environment-based configuration (dev/prod profiles, no hardcoded secrets)` — **[S]**
- `[NFR-20] Vault integration for secrets management (all environments, including local dev)` — **[L]**
- `[NFR-19] CI/CD pipeline for build, test, and deploy` — **[M]**
- `[NFR-09] Set up API documentation (Swagger/OpenAPI)` — **[S]** — start this early so it grows with the API instead of being reconstructed at the end
- `[NFR-01] Establish layered architecture (Controller → Service → Repository → Entity)` — **[S]** **(standing)**
- `[NFR-14] Establish constructor-injection-only convention` — **[S]** **(standing)**
- `[NFR-15] Establish LAZY-fetching convention` — **[S]** **(standing)**
- `[NFR-16] Establish JPQL/Specification API convention (no native SQL)` — **[S]** **(standing)**

**Non-code bootstrap tasks** (not FR/NFR-numbered, but belong in this wave — worth their own checklist items or issues even without a requirement ID):
- Create the Stripe account and obtain API keys (store in Vault, not in code or `.env`)
- Obtain cbu.uz API access
- Provision hosting for each environment
- Provision Vault itself for each environment (dev, staging, prod) and configure the auth method (e.g. AppRole) the application will use
- Create the GitHub Issues for every item in this backlog, titled per the `[ID] Title` convention used throughout this document

## Wave 1 — Authentication core

**Rationale:** every other feature is scoped to "the logged-in customer" or "the authorized staff member" — nothing downstream can be meaningfully built or tested without login working first.

- `[FR-01] User registration endpoint (/auth/register)` — **[S]**
- `[FR-02] User login endpoint (/auth/login)` — **[S]**
- `[FR-03] Password hashing (BCrypt)` — **[S]**
- `[FR-04] Per-user data isolation / authorization enforcement` — **[M]**
- `[NFR-02] Global exception handling` — **[S]** — introduce as soon as the first real endpoints exist, not later

## Wave 2 — Authorization extensions

**Rationale:** depends on Wave 1 existing. Needed before Wave 5 (statistics), since admin-only statistics require role checks to exist first.

- `[FR-06] Role-based access control (staff roles/permissions)` — **[M]**
- `[FR-05] Refresh token mechanism` — **[M]**

## Wave 3 — Subscription management core

**Rationale:** the core domain object everything else (payments, statistics, reports) hangs off of. Optimistic locking is introduced here specifically because this is the first mutable, multi-writer entity in the system.

- `[FR-07] Subscription CRUD (name, price, currency, start date, frequency)` — **[M]**
- `[FR-08] Automatic next-payment-date calculation` — **[S]**
- `[FR-09] Subscription status enum (ACTIVE/PAUSED/CANCELLED)` — **[S]**
- `[NFR-04] Optimistic locking on customer_subscriptions and payment_history` — **[M]**

## Wave 4 — Subscription management extensions

- `[FR-10] Pagination & filtering (Specification API, status/currency/price range)` — **[M]**
- `[FR-11] Soft delete for subscriptions` — **[S]**
- `[FR-27] Category grouping` — **[S]**

## Wave 5 — Currency conversion

**Rationale:** needed before payments can display/report meaningfully in the customer's base currency, but doesn't block subscription CRUD itself — can run in parallel with Wave 3/4 if you have the capacity.

- `[FR-13] Base currency tracking per customer` — **[S]**
- `[FR-14] Real-time exchange rate integration (cbu.uz)` — **[M]**
- `[FR-15] Exchange rate caching` — **[S]**
- `[NFR-05] Exchange rate caching (performance angle — same implementation as FR-15, tracked separately since it's evaluated as its own criterion)` — **[S]**
- `[FR-17] Rate date transparency` — **[S]**
- `[FR-16] Resilience / fallback (Circuit Breaker) for the rate API` — **[M]**

## Wave 6 — Payments core

**Rationale:** depends on Wave 3 (subscriptions must exist to attach payments to) and Wave 5 (currency conversion needed to populate `base_currency_amount`).

- `[FR-12] Payment history persistence, including after subscription cancellation` — **[L]**
- `[NFR-03] Transactional integrity on money-related operations` — **[M]**

## Wave 7 — Payment resilience

**Rationale:** depends on Wave 6 existing — you need a working payment flow before you can retry or refund it.

- `[FR-29] Payment retry / dunning (up to 3 attempts)` — **[L]**
- `[FR-28] Partial refund support` — **[M]**

## Wave 8 — Scheduling & notifications

**Rationale:** depends on Wave 3 (needs `next_payment_date` to check against) and benefits from Wave 6 existing (a real payment flow to warn about).

- `[FR-18] Daily scheduled payment-due check` — **[S]**
- `[FR-19] Payment-due warning notification (log/email)` — **[M]**
- `[FR-20] Notification strategy abstraction (Strategy pattern)` — **[S]**
- `[FR-21] Scheduler concurrency safety (ShedLock)` — **[M]**

## Wave 9 — Reporting & statistics

**Rationale:** deliberately last among the feature waves — every one of these reads data produced by Waves 3–8, so building this earlier just means building against fake/incomplete data.

- `[FR-22] Annual cost report export (Excel/CSV)` — **[M]**
- `[FR-23] Report contents (name, monthly price, base equivalent, annual total)` — **[S]**
- `[FR-24] Most expensive subscription statistic` — **[S]**
- `[FR-25] Total monthly spend statistic` — **[S]**
- `[FR-26] Monthly cost trend (6/12 months, JSON)` — **[M]**

## Wave 10 — Observability & audit

**Rationale:** genuinely can run in parallel with earlier waves once Wave 1 exists (auditable actions start as soon as auth does), but listed last because it's not on the critical path to a working demo — it's important, but nothing else depends on it.

- `[NFR-07] Auditing timestamps` — **[S]** **(standing — apply as each table is built, not as a separate pass)**
- `[NFR-17] Audit logging of actor actions` — **[M]**
- `[NFR-18] Distributed / request tracing` — **[M]**

## Wave 11 — Quality gate (release readiness)

**Rationale:** these aren't "built" so much as *verified* — they should be continuously true throughout, and this wave is really a final compliance check before calling the project done, not a block of net-new work.

- `[NFR-06] Code style compliance (Google Java Style Guide)` — **[S]** **(standing — verify)**
- `[NFR-10] Clean Code principles` — **[S]** **(standing — verify)**
- `[NFR-11] Minimum test coverage (30%, JUnit 5 + AssertJ)` — **[L]** *(tooling set up early — JaCoCo reporting and Testcontainers dependencies were added during Wave 0 bootstrap, per the reasoning that test infrastructure is cheap to establish early and expensive to retrofit; see `SubscribeMasterApplicationIT` for the reference pattern, now verified working both locally and in CI. What remains for this wave is the actual work: writing enough tests to hit 30% coverage, then flipping `jacoco.check.skip` to `false` in `pom.xml` to enforce it going forward.)*
- `[NFR-09] API documentation completeness (Swagger)` — **[S]** **(standing — verify)**

---

## Suggested milestones

Since the goal here is to actually build out and practice each domain area properly — not race a deadline — the waves above double as natural checkpoints rather than "must-have vs. cuttable" scope:

- **Waves 0–6** form a legitimate first milestone: a working end-to-end flow (register/login → manage subscriptions → convert currency → record a payment). Reaching this is a good point to pause, verify the foundational patterns (layered architecture, RBAC, optimistic locking, the snapshot pattern) actually feel right in practice, and adjust before building further on top of them.
- **Waves 7–9** (retry/refunds, scheduling/notifications, reporting/statistics) are where the more distinctive enterprise patterns live — dunning logic, distributed-lock-safe scheduling, financial reporting. These are worth their own deliberate pass rather than being treated as optional extras, since they're likely a big part of what made this worth building as a multi-domain exercise in the first place.
- **Waves 10–11** (observability, audit, and the quality-gate NFRs) are the kind of thing that's easy to bolt on convincingly at a small scale but genuinely hard to get right — that gap is itself worth experiencing deliberately rather than skipping.

If you do decide to reorder or skip something as you go, that's still worth writing down somewhere (even just a short note in this file or the relevant issue) — not because anyone's grading it, but because "why did we build it in this order" is exactly the kind of context that's easy to lose track of on a project spanning multiple sessions.
