# Requirement Gap Closures — Design Notes

**Project:** Subscription management system (retail), owned/maintained/managed by a single company
**Scope of this document:** The four requirement gaps identified when the ERD was checked against the `task.pdf` requirements document, and how each was closed. *(Note: the specific files these were originally closed in — `full_schema_v2.sql` and a versioned ERD file — have since been superseded; the current, single source of truth is `migrations/` for schema and `subscribe_master_erd.drawio` for the diagram. The reasoning in this document still applies to the current files. See also the note on `task.pdf` at the top of `subscribe_master_requirements.md` — it's referenced here for provenance only and isn't included in this repository or still authoritative.)*
**Companion documents:**
- `erd_design_notes.md` — auth/authz portion
- `subscription_erd_design_notes.md` — subscription tracking, Stripe integration, currency conversion
- `billing_retry_refund_design_notes.md` — billing gap analysis, retry/dunning, partial refunds
- `subscribe_master_requirements.md` — the extracted functional/non-functional requirements these gaps were checked against

---

## 1. Background

After the subscription and billing ERD was extended with retry/refund handling, it was cross-checked against a separate requirements document (`task.pdf`) that hadn't been part of the original conversation. That comparison found the schema already satisfied most functional and non-functional requirements — but four items were genuine gaps, not just differences in wording. This document covers those four and nothing else; unrelated mismatches (e.g. status value casing, refresh-token modeling, Liquibase formatting) were flagged separately and are intentionally out of scope here.

## 2. Gap 1 — Optimistic locking (NFR-04)

**Requirement:** prevent data corruption from concurrent writes to the same record, via an `@Version`-style mechanism.

**Why it was a real gap:** the schema had no `version` column anywhere. Both `customer_subscriptions` and `payment_history` are written to from more than one path — customer/staff edits, a scheduled retry job, and a Stripe webhook can all touch the same row. Without a version check, whichever write lands last silently overwrites the other, with no error and no corrupted-looking data — the classic "lost update" problem.

**Fix:** added `version INTEGER NOT NULL DEFAULT 0` to both `customer_subscriptions` and `payment_history`. In application code (e.g. JPA/Hibernate `@Version`), every update includes the currently-held version number in its `WHERE` clause and increments it; if another process already changed the row, the update matches zero rows and the application can detect the conflict and retry rather than silently losing one of the two writes.

**Why only these two tables:** they're the two tables most exposed to concurrent writes from multiple actors. Tables like `roles` or `subscription_providers` change rarely and typically only by a single admin action at a time, so the overhead of version tracking wasn't added there.

## 3. Gap 2 — Category grouping for custom subscriptions (FR-27)

**Requirement:** subscriptions groupable by category (Entertainment, Productivity, AI Tools, etc.).

**Why it was a real gap:** `category` already existed, but only on `subscription_providers` — the catalog table. A **custom** subscription (where `provider_id` is null and `custom_name` is used instead, per the earlier catalog-with-fallback decision) had no path to a category at all, since it isn't linked to a catalog row.

**Fix:** added a `category` column directly to `customer_subscriptions`, independent of `subscription_providers.category`. This means:
- A catalog subscription (e.g. Netflix) can either inherit its category from `subscription_providers` or have it overridden per-customer.
- A custom subscription (e.g. "my kid's karate class") can be categorized even with no catalog link at all.

**Not resolved:** whether a catalog subscription's category should be copied onto `customer_subscriptions.category` at creation time (denormalized for query simplicity) or always resolved via the `provider_id` join. The column exists either way; which pattern the application layer uses is an implementation decision, not a schema one.

## 4. Gap 3 — Notification tracking (FR-19 / FR-20)

**Requirement:** warn users when a payment is due soon (daily scheduled check), with the warning simulated via log or email.

**Why it was a real gap:** nothing recorded *that* a warning had already been sent for a given subscription and cycle. Without this, a scheduler restart, a retry, or simply the job running twice in one day would risk sending the same customer the same reminder multiple times.

**Fix:** added a `notification_log` table:
- `subscription_id` — required; every notification is tied to a subscription.
- `payment_history_id` — nullable, because a payment-due reminder fires *before* that cycle's charge — and therefore before that cycle's `payment_history` row may exist.
- `notification_type` — free text (e.g. `payment_due_reminder`), left open rather than a fixed enum so new notification types don't require a schema change.
- `channel` — constrained to `log` / `email`, matching the two channels named in the requirements doc (`LogNotifier`, `EmailNotifier`).
- A partial unique index — `(subscription_id, notification_type, sent_at::date)` where `sent_at IS NOT NULL` — directly enforces "don't send the same reminder type to the same subscription twice in one day," rather than just logging that sends happened and leaving de-duplication entirely to application logic.

## 5. Gap 4 — Scheduler concurrency safety (FR-21)

**Requirement:** if the system runs on multiple instances in the future, the daily scheduled job must not run twice.

**Why it was a real gap:** the requirements doc names a specific mechanism for this — ShedLock — which requires its own lock-coordination table. Nothing like it existed in the schema.

**Fix:** added a `shedlock` table matching the exact schema the ShedLock library expects (`name` as primary key, `lock_until`, `locked_at`, `locked_by`). Deliberately given **no foreign keys** to any other table in the schema — it's a pure scheduler-coordination primitive (one process acquires the lock by inserting/updating a row for a given job name; others see it's held and skip), not domain data, so it doesn't participate in the subscription/payment relationship graph at all.

## 6. Summary of changes

| Gap | Table(s) affected | Change |
|---|---|---|
| Optimistic locking | `customer_subscriptions`, `payment_history` | Added `version INTEGER NOT NULL DEFAULT 0` |
| Category grouping | `customer_subscriptions` | Added `category TEXT` (independent of the catalog's category) |
| Notification tracking | *(new)* `notification_log` | New table + partial unique index for same-day de-duplication |
| Scheduler concurrency | *(new)* `shedlock` | New table, standard ShedLock schema, no foreign keys |

## 7. Open items not covered by this change

These were identified in the same gap analysis but are intentionally **not** part of this update:

- Status value casing mismatch (`'Active'` vs. the doc's `ACTIVE`) — cosmetic, but matters if a Java enum maps directly to the column value.
- Refresh token (FR-05) not modeled as a distinct concept from `customer_sessions`.
- Schema delivered as plain `CREATE TABLE` statements rather than Liquibase changesets (NFR-13).
- The `customers` / `staff_users` split vs. the requirements doc's single `users` table with a `USER`/`ADMIN` role — a deliberate earlier architectural decision, not something this change revisits.
