# ARCHITECTURE.md — Subscribe Master

**Audience:** mid-level backend engineers joining this project, plus support/ops/audit staff who need to know what to watch once this is running in production.

**Purpose:** this document explains the *why* behind the database design — not just what tables exist, but the reasoning that shaped them, what you need to keep in mind while building on top of them, and what to monitor once the system is live. It assumes familiarity with relational databases, JPA/Hibernate-style ORMs, and standard backend patterns — it does not re-explain what a foreign key or an index is.

Companion documents (design history, if you want the blow-by-blow): `erd_design_notes.md`, `subscription_erd_design_notes.md`, `billing_retry_refund_design_notes.md`, `gap_closure_design_notes.md`, `subscribe_master_requirements.md`.

---

## 1. System overview and boundaries

Subscribe Master consolidates a customer's paid subscriptions (Netflix, Spotify, etc.), tracks spending in a customer-chosen base currency, and pays those subscriptions on the customer's behalf via Stripe. Two boundaries shape a lot of the schema and are worth internalizing early:

- **This system is not the merchant of record for the tracked subscriptions.** It doesn't issue invoices or calculate tax — the customer enters one tax-inclusive total per subscription, and that's what gets charged. Don't build tax-calculation logic on top of this schema; there's nowhere for it to live, and it wasn't asked for.
- **There is no platform fee.** The system doesn't monetize itself. If that ever changes, it's a new domain (fee schedules, revenue tables) layered on top — not a modification to the existing payment tables.

---

## 2. Authentication & authorization

### 2.1 Customers and staff are separate tables, not one `users` table with a role flag

`customers` and `staff_users` are two distinct tables, each with their own sessions, password reset flow, and (for customers) social login. This was a deliberate choice over a single `users` table with a `USER`/`ADMIN` discriminator, because the two populations have fundamentally different auth flows: customers self-register and can use Google/Facebook/X login; staff are invite-provisioned and never touch social login. Splitting the tables means a bug in a customer-facing endpoint can't physically expose staff data — there's no shared table for a missing `WHERE` clause to leak across.

**For developers:** don't be tempted to unify these later "for simplicity." If you need a person to be both a customer and staff member, that's an unsolved case in the current design — there's no link between the two tables. Raise it explicitly rather than improvising a workaround (e.g. don't reuse a customer's UUID as a staff UUID).

**For ops/support:** account lookups differ by population. A "can't log in" ticket needs you to know *which* table to check — a customer complaint means `customers`/`customer_sessions`, a staff complaint means `staff_users`/`staff_sessions`. They will never be in the same table.

### 2.2 Staff authorization is RBAC (roles → permissions), not a hardcoded role check

`staff_users.role_id → roles → role_permissions → permissions` means permission checks should be data-driven (`does this role have the 'process_refunds' permission?`), not `if (role == "ADMIN")` conditionals scattered through the codebase. Four roles are seeded: `admin`, `support_agent`, `billing_admin`, `auditor` — each with a distinct, non-overlapping set of permissions by design (e.g. `support_agent` can edit customer records but never touch refunds; `billing_admin` can issue refunds but never edit customer profile data).

**For developers:** when adding a new staff-facing feature, ask "what permission gates this?" before writing the endpoint. If the right permission doesn't exist yet, add a row to `permissions` and wire it into the relevant role(s) — don't check `role.name` directly in application code, since that defeats the entire point of the permission layer.

**For ops/audit:** if a staff member reports they can't do something they think they should be able to, the fix is a `role_permissions` row change, not a code deploy. Audit review of *who has what access* is a query against `role_permissions` joined to `staff_users`, and should be a routine periodic check, not just an incident-response tool.

### 2.3 Token lifecycle: four distinct token tables, all hash-stored

`customer_sessions`, `customer_password_reset_tokens`, `customer_email_verification_tokens`, and `customer_refresh_tokens` (plus staff equivalents for sessions and password reset) all follow the same pattern: store a **hash** of the token, never the raw value, the same way passwords are hashed. `password_reset_tokens`/`email_verification_tokens` are single-use (`used_at` marks consumption); `refresh_tokens` are reusable until `revoked_at` or `expires_at` — that distinction is deliberate, not an oversight, so don't "fix" refresh tokens to look like the single-use tables.

**For developers:** never log a raw token, even at debug level. If you need to look up a token record, you're hashing the incoming token with the same algorithm the app uses to generate them and querying by the hash — not searching for a raw value anywhere.

**For ops/support:** "user says their reset link doesn't work" is answered by checking `used_at`/`expires_at` on the relevant token row, not by asking the customer to read the token back to you over the phone (you can't reverse the hash to verify it anyway).

---

## 3. Subscription domain

### 3.1 Soft delete: `deleted_at`, not row removal

`customer_subscriptions.deleted_at` and `customer_payment_methods.deleted_at` mean "hidden from the customer's active view," not "gone." This exists specifically so `payment_history` never has a dangling foreign key — a customer removing a subscription must never risk losing the payment record trail attached to it.

**For developers:** every query that lists "active" subscriptions or payment methods must filter `WHERE deleted_at IS NULL` explicitly — there's no database-level mechanism (like a view) enforcing this for you in the current schema. Forgetting this filter is the single easiest way to leak "deleted" data back into the UI. Partial indexes (`idx_customer_subscriptions_active`, `idx_customer_payment_methods_active`) already assume this filter is applied — use them, don't work around them.

**For ops/support:** a customer asking "why can I still see a charge from a subscription I deleted" is expected behavior, not a bug — that's the soft delete doing its job. Reversing an accidental soft-delete is a targeted `UPDATE ... SET deleted_at = NULL`, not a restore-from-backup operation.

### 3.2 Provider catalog with a custom fallback

`subscription_providers` is the catalog (Netflix, Spotify, etc.); `customer_subscriptions.provider_id` is nullable with `custom_name` as the fallback for anything not in the catalog. A `CHECK` constraint (`chk_subscription_has_name`) enforces that one of the two is always populated.

**For developers:** never assume `provider_id` is non-null when building subscription display logic — always branch on "is this a catalog subscription or a custom one," and resolve the display name accordingly (`subscription_providers.name` vs. `custom_name`).

**For ops:** if a customer requests a provider be added to the catalog (e.g. a newly popular streaming service), that's a `subscription_providers` insert, not a schema change — see `R__seed_subscription_provider_catalog.sql` for the pattern.

### 3.3 Category is on both the catalog and the subscription itself

`subscription_providers.category` and `customer_subscriptions.category` are independent columns. This was added specifically because a custom (non-catalog) subscription has no `provider_id` to inherit a category from.

**For developers:** decide up front (and document it, since the schema doesn't enforce either direction) whether creating a catalog subscription auto-populates `customer_subscriptions.category` from the provider's category, or whether the two are always independent. The column exists either way, but the *behavior* is an application decision not yet made.

---

## 4. Payments & billing

### 4.1 Stripe owns the money movement; this schema owns the record of it

`customer_payment_methods.stripe_payment_method_id`, `payment_attempts.stripe_payment_intent_id`, and `refunds.stripe_refund_id` are all reconciliation references back to Stripe — Stripe is the source of truth for whether money actually moved. This schema never stores raw card numbers; only Stripe's tokens and display-safe metadata (brand, last 4 digits, expiry).

**For developers:** never build logic that treats `payment_history.status = 'succeeded'` as authoritative without it having been set *from* a Stripe webhook or API response. If you're ever tempted to mark a payment succeeded optimistically before Stripe confirms it, don't — that's exactly the kind of write that optimistic locking (§5) exists to protect against being silently wrong.

**For ops:** discrepancies between what this database says and what Stripe's dashboard says should always be resolved in Stripe's favor, then reconciled here — never the other way around. Build reconciliation jobs (comparing `payment_history`/`payment_attempts`/`refunds` against Stripe's records) into your operational tooling early; don't wait for a customer complaint to discover drift.

### 4.2 Payment history is an immutable snapshot, not a live reference

This is probably the single most important pattern in the schema, and it shows up three times:

- **Price changes:** `payment_history.amount` is independent of `customer_subscriptions.amount`. Changing a subscription's price only affects *future* payments; past `payment_history` rows keep whatever was actually charged.
- **Currency conversion:** `payment_history.exchange_rate_applied` and `base_currency_amount` are snapshotted at payment time, independent of the live `exchange_rates` cache. A payment from six months ago will always show the rate that applied then, even if the cache has since refreshed or been pruned.
- **Tax-inclusive amounts:** since tax is baked into the customer-entered `amount` (no separate tax calculation), that snapshot is also the full, final amount — nothing needs to be recomputed later.

**For developers:** if you ever find yourself joining `payment_history` back to `customer_subscriptions.amount` or `exchange_rates` to "get the real number," stop — you're about to reintroduce the exact bug this design prevents. The snapshot columns *are* the real number, permanently.

**For audit:** this is precisely what makes `payment_history` reliable for financial audit and reporting — every row is self-contained and doesn't drift as other tables change around it.

### 4.3 Retry: `payment_history` (the obligation) vs. `payment_attempts` (each try)

A failed charge gets up to 3 tries, each recorded as its own row in `payment_attempts` (own Stripe intent ID, own failure reason), while `payment_history.attempt_count` and `.status` reflect the cumulative/final state. **The retry schedule itself (how long to wait between attempts) is not in the schema** — that's application/scheduler logic.

**For developers:** when building the retry job, write the *timing* rule (e.g. "retry after 2 days") in code/config, not the database — but every attempt, successful or not, must still get a `payment_attempts` row. Don't skip writing a row for a failed attempt just because "the final one succeeded anyway" — the failure history has value for fraud/dunning analysis.

**For ops:** a subscription stuck at `attempt_count = 3` with `status = 'failed'` and no resolution is exactly the kind of thing that needs an alert — that's a customer who's about to have their subscription lapse involuntarily. Build a monitoring query for this pattern before launch, not after the first complaint.

### 4.4 Partial refunds have their own table, with a mandatory reason

`refunds.amount` doesn't have to equal the original payment, and `reason` is `NOT NULL` — every refund must be explainable. This exists specifically to support prorated refunds on mid-cycle subscription cancellations.

**For developers:** the proration *calculation itself* (how much to refund for a partial period) is not in the schema — it's a business rule you'll implement in application code. The table just stores whatever that calculation produces. Get the proration rule reviewed and signed off before writing the code; the schema won't catch a wrong formula.

**For audit:** `refunds.reason` being free text (not a fixed enum) means it can drift into inconsistent values over time (`"prorated"` vs. `"prorated_cancellation"` vs. `"Prorated Cancellation"`) if application code isn't disciplined about it. Consider enforcing a fixed set of reason codes at the application layer even though the schema allows free text.

### 4.5 Currency: cache for display, snapshot for history

`exchange_rates` is a refreshable cache (rates get inserted periodically); it is never the source of truth for a historical payment — see §4.2. It's fine, and expected, for this table to be pruned or to have gaps.

**For ops:** don't treat a missing/old `exchange_rates` row as an incident on its own — check whether the rate-fetching job is actually running before assuming data loss. It's expected that only recent rates are kept around.

---

## 5. Data integrity & concurrency

### 5.1 Optimistic locking on `customer_subscriptions` and `payment_history`

Both tables have a `version` column. Every update must check-and-increment the version (`UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`), and the application must treat a zero-row-affected result as a conflict to retry, not a silent no-op. This exists because both tables are written to from multiple independent paths (customer/staff edits, the retry scheduler, Stripe webhooks) — without it, whichever write lands last silently overwrites the other with no error raised.

**For developers:** if you're using JPA/Hibernate, this is `@Version` and mostly automatic — but you still need to handle `OptimisticLockException` explicitly (retry the operation after re-reading the row, don't just let the request 500). If you're writing raw SQL anywhere against these two tables, you must include the version check manually; it's not enforced by a database trigger.

**For ops:** a spike in optimistic-lock conflict/retry rate on these two tables is a genuine signal — it usually means either a scheduler is running more often than expected (possible ShedLock misconfiguration, see §6.3) or a webhook is being delivered/processed more than once. Treat repeated conflicts on the *same* row as worth investigating, not just noise to retry through.

---

## 6. Observability: three logging concerns, kept deliberately separate

`audit_logs`, `app_logs`, and `trace_spans` look similar (all "logging") but answer different questions, and mixing them up is a design mistake worth actively avoiding:

| Table | Answers | Volume | Retention expectation |
|---|---|---|---|
| `audit_logs` | *Who did what?* (compliance/security) | Low-moderate | Long — this is the compliance record |
| `app_logs` | *What did the system report?* (errors/warnings) | High | Short |
| `trace_spans` | *Why was this request slow / where did it fail?* | Very high | Very short, or externalized entirely |

### 6.1 `audit_logs`: actor-attributed, polymorphic

`actor_type` + `actor_id` can point to either a `customer` or a `staff_user` — there's no real foreign key here (Postgres can't FK to "one of two tables"), so **this integrity is enforced entirely at the application layer.**

**For developers:** every write to `audit_logs` must set `actor_type` correctly and populate `actor_id` from the right table. There is nothing in the database preventing an `actor_id` that doesn't actually exist in either table — get this wrong and you'll have orphaned, unverifiable audit entries, which defeats the entire purpose of an audit log.

**For audit:** because there's no FK, periodic validation queries (does every `audit_logs.actor_id` actually resolve to a real customer or staff row, given its `actor_type`?) are worth running on a schedule — this is the one place in the schema where referential integrity is a process, not a database guarantee.

### 6.2 `app_logs` and `trace_spans`: expect them to outgrow the primary database

Both are flagged, deliberately, as candidates for living in dedicated external tooling (a log aggregator like Datadog/CloudWatch for `app_logs`; a tracing backend like Jaeger/Zipkin/Tempo for `trace_spans`) rather than the primary database in a real production deployment. They're modeled here to satisfy the requirement at the schema level, not as a strong recommendation to actually run high-volume logging through your transactional Postgres instance.

**For developers:** don't build features that depend on `app_logs`/`trace_spans` being complete or long-retained in this database — if/when these move to external tooling, that dependency breaks.

**For ops:** monitor table growth on these two specifically. If either is growing unexpectedly fast, that's your signal to prioritize migrating them out rather than just adding more disk.

### 6.3 `notification_log`: prevents duplicate customer-facing messages

A partial unique index (`uq_notification_log_daily_dedup`) enforces "don't send the same reminder type to the same subscription twice in one day" at the database level, not just in application logic.

**For developers:** if a notification *should* legitimately be sent twice in one day (an edge case you didn't anticipate), the insert will fail — don't catch and silently swallow that failure; treat it as the dedup working as intended, and reconsider whether you actually meant to send it again.

**For ops/support:** "customer says they got the same reminder email twice" is worth investigating as a real bug (this constraint should prevent it) rather than dismissing as expected noise.

---

## 7. Scheduling & operational safety

### 7.1 `shedlock`: prevents the daily job from running twice

If this application is ever horizontally scaled (multiple instances), the daily payment-due check must only run on one instance at a time. `shedlock` is the standard table the ShedLock library expects, and it has **no foreign keys to any business table** — it's a pure coordination primitive.

**For developers:** don't repurpose this table for anything else, and don't add foreign keys to it — it needs to stay generic so ShedLock's own library code can manage it without knowledge of the rest of the schema.

**For ops:** a stuck lock (a row in `shedlock` with `lock_until` in the past that never gets cleared) is a known failure mode of distributed locking if an instance crashes mid-job — build alerting for "lock held past its `lock_until` with no corresponding job completion," since ShedLock itself won't page you about this.

---

## 8. Migration strategy: Flyway, versioned and incremental

Schema changes are delivered as versioned Flyway migrations (`V1`, `V1.1`, `V2`–`V4`, `V6` at the time of writing) plus one repeatable migration (`R__seed_subscription_provider_catalog`), not a single monolithic script. Each migration is scoped to one coherent unit of change (auth foundation, subscriptions, retry/refunds, gap closures, tracing) rather than grouped by when the request happened to arrive. Seed data is deliberately split from schema: foundational reference data tightly coupled to the tables that need it (roles/permissions) lives in its own versioned migration (`V1.1`) right after the schema that creates those tables; catalog data expected to grow independently (the subscription provider list) lives in a repeatable (`R__`) migration instead, which re-runs automatically whenever the file changes — see `V1__init_auth_and_authz.sql` and `R__seed_subscription_provider_catalog.sql` for the reasoning in each file's header comment.

**For developers:** once a migration has actually run in any shared environment (staging or production), **never edit it** — write a new `V{n+1}` migration instead, even for a one-line fix. Editing an already-applied migration breaks Flyway's checksum validation and will fail deployments for anyone who already ran it. (Everything up through `V6` in this project has *not* yet been deployed anywhere, which is why earlier fixes in this project's history were made by editing existing migrations rather than adding new ones — that's a one-time luxury of a pre-deployment project, not a pattern to continue once this ships.)

**For ops:** `flyway_schema_history` (created automatically by Flyway, not something we modeled) is your source of truth for what's actually been applied to a given environment — check it before assuming an environment is up to date, especially after a failed or interrupted deployment.

---

## 9. Secrets management: Vault, in every environment

Application secrets — database credentials, the Stripe API key, the JWT signing key, and anything else sensitive — are retrieved from HashiCorp Vault at runtime, not read from `.env` files or hardcoded config. This applies uniformly across every environment, including local development, rather than having local dev diverge from how staging/prod actually retrieve secrets. That consistency is the whole point: a secrets-handling bug that only shows up in production because local dev never exercised the real path is exactly the kind of gap this choice is meant to close.

**For developers:** never commit a Vault token, never read a secret from a `.env` file "just for local convenience," and never log a value that came from Vault. If a new secret is needed (a new third-party API key, for instance), the process is: add it to Vault for each environment, then wire the application to read it from there — not add it to a config file and plan to "migrate to Vault later." Local dev friction here is a feature, not a bug — it's what catches secrets-handling mistakes before they reach staging or production.

**For ops:** Vault's own operational state needs monitoring independently of the application:
- **Seal status** — a sealed Vault means the application can't retrieve any secrets and will fail to start or fail to refresh a credential mid-run. This should page immediately, not wait for a downstream symptom.
- **Token/lease expiry** — if the application authenticates via a method with a limited-lifetime token or lease (e.g. AppRole), track renewal failures separately from generic connection errors, since the failure mode looks identical to Vault being unreachable but the fix is different (renew/re-authenticate vs. Vault itself being down).
- **Audit log** — Vault's own audit logging (distinct from this application's `audit_logs` table) records every secret access; review it as part of standard security posture, not just incident response.

**Not modeled in the schema, deliberately:** as with `shedlock`, there's nothing to add to the database for this — secrets are never persisted in any table, by design. This is a pure infrastructure/configuration concern (see `subscribe_master_requirements.md`, NFR-20).

## 10. Consolidated developer checklist

- [ ] Filter `deleted_at IS NULL` explicitly on every subscription/payment-method query — it's not automatic.
- [ ] Never join `payment_history` back to `customer_subscriptions.amount` or live `exchange_rates` to "recompute" a historical value — the snapshot columns are already final.
- [ ] Handle `OptimisticLockException` (or equivalent) on `customer_subscriptions` and `payment_history` writes — retry, don't fail silently or 500.
- [ ] Never log or persist a raw session/reset/verification/refresh token — only hashes.
- [ ] Gate staff features by permission (`role_permissions` lookup), never by hardcoded `role.name` checks.
- [ ] Write every payment attempt to `payment_attempts`, even failed ones — don't skip failure rows.
- [ ] Get the proration formula for partial refunds reviewed before implementing — the schema doesn't validate it.
- [ ] Don't build long-term features on `app_logs`/`trace_spans` persistence — they're expected to move to external tooling.
- [ ] Never edit an already-deployed Flyway migration — add a new one.
- [ ] When adding a new subscription provider, insert into `subscription_providers` — don't hardcode provider names in application code.
- [ ] Never commit a Vault token or read a secret from `.env` "just for local dev convenience" — local dev uses Vault the same way staging/prod do.

## 11. Consolidated ops/support/audit monitoring guide

- **Payment retries stuck at max attempts (`attempt_count = 3`, `status = 'failed'`):** alert-worthy — a customer's subscription is about to lapse involuntarily.
- **Discrepancies between this database and Stripe's dashboard:** resolve in Stripe's favor, then reconcile here; build a scheduled reconciliation job rather than relying on customer reports.
- **Spike in optimistic-lock conflicts on `customer_subscriptions`/`payment_history`:** investigate — possible duplicate webhook delivery or a scheduler running more often than intended.
- **Rapid growth of `app_logs` or `trace_spans`:** expected to be high-volume, but sustained unexpected growth is your cue to prioritize moving them to external tooling.
- **`shedlock` row with `lock_until` in the past and no completed job:** a crashed instance likely left a stale lock — needs manual clearing and investigation into why the instance died mid-job.
- **Duplicate customer-facing notifications reported:** should be prevented by `uq_notification_log_daily_dedup` — treat a genuine duplicate as a bug, not expected behavior.
- **`audit_logs` integrity:** periodically verify every `actor_id` resolves to a real row in the table implied by `actor_type` — this isn't enforced by a foreign key, only by application discipline.
- **Role/permission access reviews:** query `role_permissions` joined to `staff_users` on a routine schedule (not just during incidents) as part of standard access governance.
- **Refund reason consistency:** `refunds.reason` is free text; watch for drift into inconsistent values over time if application code isn't disciplined about using a fixed set of reason strings.
- **Flyway deployment state:** check `flyway_schema_history` after any failed or interrupted deployment before assuming an environment is current.
- **Vault sealed, or a token/lease renewal failing:** page immediately for a sealed Vault (the application can't retrieve secrets at all); track renewal failures separately from generic connectivity errors, since the fix differs from "Vault is unreachable."

---

## 12. Known trade-offs (not gaps — deliberate decisions worth knowing about)

- **RBAC (roles/permissions) instead of a simple `USER`/`ADMIN` flag.** More granular and extensible than what the original spec described, but it's a structural departure — worth flagging if this project is ever compared line-by-line against a simpler reference implementation.
- **Transactional boundaries are entirely application-level.** The schema supports data integrity through constraints (`ON DELETE RESTRICT` on financial records, `CHECK` constraints, optimistic locking), but `@Transactional` demarcation itself is code, not something the database enforces on its own. Get code review specifically focused on transaction boundaries around any multi-table write involving `payment_history`.
- **`app_logs`/`trace_spans` living in the primary database is a starting point, not an endorsement.** Both are explicitly flagged as likely candidates to externalize once volume becomes a real concern — build with that migration in mind rather than assuming they'll stay here indefinitely.
