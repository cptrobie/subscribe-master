# Staff-Managed Subscription Provider Catalog — Design Decisions

Captures the design decisions made for `FR-35` (staff management of the
`subscription_providers` catalog), ahead of implementation. **Not sourced
from `task.pdf`** — added deliberately, surfaced while auditing
`SubscriptionControllerCreateTest`'s fixture data for realism against
`ARCHITECTURE.md` §3.3's previously-undecided category-population question.
Depends entirely on `FR-06` — see `staff_authorization_design.md` for the
staff authentication/permission mechanism this feature is the first real
consumer of. Supersedes prior in-chat discussion — this is the single
source of truth going forward.

## Why this feature, and why now

Chosen deliberately as the first thing built on top of `FR-06`'s new
staff-permission infrastructure, ahead of `FR-05`/`FR-33`/`FR-34` (Wave 2's
other items) and well ahead of `FR-28` (partial refunds, Wave 7). Rationale:
this is catalog metadata, not money — a much safer place to find bugs in
brand-new permission-enforcement wiring than making financial refunds the
first real test of it. **Scheduling: built immediately after `FR-06`**, not
placed in a later wave.

## Scope: create, update, deactivate — no hard delete

- **Create** and **update**: name, category, `logo_url`, `website_url`.
- **Deactivate**, not delete: adds `subscription_providers.deleted_at`.
  Deliberately rejected a hard delete gated by a "have any subscriptions
  ever referenced this" check — soft delete is strictly safer (nothing is
  ever destroyed, no check can have a gap) and matches the pattern already
  used for `customer_subscriptions`/`customer_payment_methods`.
- **Out of scope for now, explicitly deferred:**
  - Can a deactivated provider ever be reactivated, or must a replacement
    row be created? Not decided.
  - Should `update()` re-validate `name` uniqueness against other *active*
    providers when renaming? Not decided — low-stakes edge case.
  - Exact endpoint paths and request/response DTO shapes — implementation
    detail, left open like `subscription_status_transitions.md` leaves
    exact code out of scope in favor of the behavioral rules.

## Access control: three permissions, all assigned to `admin` for now

Per `FR-06`'s data-driven principle, this is **three separate permissions**
— one per action (`create`, `update`, `deactivate`) — not one combined
"manage" permission. The `permissions` table's own schema (`resource` +
`action` columns) is built for exactly this granularity, matching how
`ARCHITECTURE.md` already describes `billing_admin`/`support_agent` as
having distinct, non-overlapping permission sets rather than one blanket
flag. All three are seeded and assigned only to `admin` today — nothing
stops a later decision to also grant one of them to a different role
without any code change, which is the entire point of building it this way.

**Per-admin restriction considered and rejected.** Checked the actual schema
(`staff_users` → single `role_id` → `role_permissions` → `permissions`):
there is no granularity finer than role. Every staff member holding `admin`
gets identical permissions to every other admin. Building per-user
overrides would be a genuinely new RBAC capability, not justified for this
one feature — accepted as the correct scope boundary, not a gap.

## Schema changes

One migration, three changes to `subscription_providers`:

1. **Add `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.** The table was
   insert-only until now (seeded once, never edited), correctly excluded
   from `updated_at` under NFR-07's own stated logic ("append-only tables...
   only ever get `created_at`"). This feature makes the table genuinely
   mutable, which moves it out of that exception — per the standing
   convention, it needs `updated_at` too, not just `deleted_at`.
2. **Add `deleted_at TIMESTAMPTZ`** (nullable) — the soft-delete flag.
3. **Replace `name TEXT NOT NULL UNIQUE` with a partial unique index**:
   `UNIQUE (name) WHERE deleted_at IS NULL`. A plain `UNIQUE` constraint
   would permanently reserve a deactivated provider's name — "Netflix"
   could never be re-added if the original row was ever deactivated, even
   by mistake. The partial index frees the name once its row is
   deactivated, while still preventing two *active* providers from
   colliding.

## Category snapshot semantics — resolves `ARCHITECTURE.md` §3.3

This decision retroactively answers the question `ARCHITECTURE.md` §3.3
flagged as deliberately left open ("the *behavior* is an application
decision not yet made"): **yes, `create()` auto-populates
`customer_subscriptions.category` from the provider's category at creation
time — but only as a one-time snapshot, not a live link.**

- If a provider's category is later changed (or the provider is
  deactivated), **existing subscriptions that already reference it keep
  their original `category` value unchanged.**
- Only subscriptions **created after** the provider change pick up the new
  category.
- This matches a pattern already established elsewhere in this schema —
  `payment_history.exchange_rate_applied` freezes the rate used at payment
  time rather than tracking the live rate. Category-at-creation is the same
  idea applied to a different field.
- `ARCHITECTURE.md` §3.3 is updated alongside this doc to record the
  decision and stop describing it as open.

## Backend enforcement of deactivated providers

The (out-of-scope) customer-facing UI is expected to only list active
providers in its create dialog, but **the backend must not rely on that
alone.** `SubscriptionService.create()` must treat a deactivated
`providerId` (`deleted_at IS NOT NULL`) as `404`/`EntityNotFoundException`
— identical to a `providerId` that doesn't exist at all — defense in depth
against a stale page, a direct API call, or a race between page-load and
submission.

**`update()` does not need this check, and it's worth being explicit about
why not.** Investigating this exact question surfaced that a subscription's
`providerId` can never change after creation at all — no setter exists on
`CustomerSubscription`, and the column is `updatable = false` by design.
Wanting to "switch providers" on an existing subscription doesn't map to a
real operation this system should support — a Netflix subscription can't
become a Spotify subscription; the correct action is cancelling the
existing one and creating a new one against the desired provider. That's
simpler, not a limitation: `update()` never resolves a provider from client
input at all, so there is no "new provider" for it to validate.

(This same investigation surfaced a separate, pre-existing bug — `update()`
was resolving `providerName` from the client-supplied `request.providerId()`
instead of the subscription's real, stored `providerId` — and a related API
consistency gap, `subscriptionId` living in the request body instead of the
URL path like every sibling endpoint. Both are being fixed as their own
change, unrelated to this feature, tracked separately.)

## Audit logging

Three new `AuditAction` values: `PROVIDER_CREATED`, `PROVIDER_UPDATED`,
`PROVIDER_DEACTIVATED` (not `PROVIDER_DELETED` — nothing is destroyed).
Per the pattern already established when subscription create/update audit
logging was added, this is a **two-part cost, not just a Java enum
change**: `audit_logs.action`'s CHECK constraint (`audit_logs_action_check`)
also needs a migration to widen it to include the three new values, or
every write will be rejected at the database level regardless of what the
application code does. Console (SLF4J) logging of who/what/when, matching
the existing service-layer pattern (`logger.info("Subscription {} created
for customer {}", ...)`), applies here the same way.
