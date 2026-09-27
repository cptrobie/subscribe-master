# Payment Retry & Dunning — Design Decisions

Captures the design decisions made for payment retry, cross-domain
coordination with `Subscription`, and cancellation-reason tracking, ahead
of implementing `PaymentService`, the two schedulers, and
`SubscriptionStatusUpdater`. Supersedes prior in-chat discussion — this
is the single source of truth going forward.

## Retry attempts & backoff

- Up to 3 attempts per payment (`payment_attempts.attempt_number BETWEEN 1
  AND 3`, `payment_history.attempt_count BETWEEN 0 AND 3`).
- Attempt 1 happens immediately when the billing scheduler creates the
  `payment_history` row — it does not go through the retry path.
- Attempts 2 and 3 are spaced out with a multiplicative backoff:

  ```java
  private static final Duration RETRY_BASE_BACKOFF = Duration.ofHours(2);
  private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

  private static Duration backoffFor(int attemptCount) {
      long millis = (long) (RETRY_BASE_BACKOFF.toMillis()
          * Math.pow(RETRY_BACKOFF_MULTIPLIER, attemptCount - 1));
      return Duration.ofMillis(millis);
  }
  ```

  `attemptCount = 1` → 2h wait before attempt 2. `attemptCount = 2` → 4h
  wait before attempt 3. Total window from first failure to exhaustion:
  ~6 hours.
- The formula generalizes if the attempt limit ever changes, but the DB
  CHECK constraints (`attempt_number`, `attempt_count`) do not — raising
  the limit still requires a migration.
- Rejected: a flat interval (1h) — too tight, risks issuer-side fraud
  detection on rapid repeated charge attempts against the same card.
  Considered but not adopted: a multi-day dunning schedule with a
  distinct `PAST_DUE` subscription state — rejected because a ~6-hour
  window gives no realistic opportunity for the customer to act, so the
  state would add complexity without functional benefit. Revisit if the
  backoff schedule is later stretched to span days.

## Determining payment outcome — DECIDED: synchronous for v1

The scheduler confirms a Stripe `PaymentIntent` and reads
success/failure directly off the synchronous API response. Matches
`payment_attempts.status`'s current CHECK constraint
(`'succeeded'`/`'failed'` only) with no migration needed.

**Known, accepted gap:** the supported currency list includes EUR and
GBP, so SCA (Strong Customer Authentication) may require a 3D Secure
challenge on an off-session charge depending on issuer risk scoring,
even against a stored payment method. When that happens, Stripe's
synchronous response is `requires_action`, not `succeeded`/`failed`.
Handling: treat `requires_action` as a failure for retry-scheduling
purposes — record the `PaymentAttempt` as `FAILED` with
`failure_reason = 'requires_authentication'`, and let it flow through
the normal 2h/4h backoff like any other decline. A customer who hits
this has no path to actually complete the challenge in this design and
will exhaust all 3 attempts and get cancelled, even though the card
might otherwise have worked. Accepted as narrow (most saved-card
off-session charges don't trigger 3DS) and worth monitoring via
`payment_attempts.failure_reason`, not worth building around pre-emptively.

**Why not webhooks (Stripe's own recommended source of truth):** two
reasons, not one.

- Webhooks alone don't actually close the 3DS gap. Even with a webhook
  correctly recording that a charge needs authentication, the customer
  still can't complete that authentication without also building a
  customer-facing completion step (a Stripe Elements/Checkout page they
  get emailed a link to). Webhooks fix *recording accuracy* — no longer
  misfiling "needs 3DS" as "failed" — they don't by themselves let the
  payment succeed. Closing the gap for real is "webhooks + a completion
  UI," a meaningfully bigger scope than "add a webhook handler."
- **Webhooks done properly are a downstream consequence of a future
  architecture change, not a standalone task.** A webhook handler
  bolted onto the current single-deployable modulith can just write
  directly to the DB in-process — no broker needed, since there's
  nothing to hand off to. A broker (RabbitMQ/Kafka) only becomes
  *necessary* once Payment (or whichever module receives the webhook)
  isn't the same deployable as everything that needs to react to it —
  the receiver then needs to durably publish the event so a
  separately-deployed consumer can pick it up without blocking on, or
  losing the event to, that consumer being down, mid-deploy, or scaled
  differently. That's exactly the workload a message broker is for, and
  exactly the kind of workload webhook events are (must-not-lose,
  at-least-once, idempotent consumer). Standing up a broker today,
  purely to support a webhook handler still talking to itself inside
  one process, would be premature infrastructure for a solo/part-time
  project with no payoff yet.

**Revisit trigger:** the modulith → separate-services split, if and when
that happens — not a fixed version number or a calendar date. Full
webhook support (with the completion UI) belongs to that milestone, not
before it.

## Exhausted retries → cancellation

- No new `SubscriptionStatus` value for payment-failure cancellation.
  `status` describes *what state* the subscription is in; a new value
  duplicating `CANCELLED`'s behavior everywhere `status` is checked was
  rejected as unnecessary complexity for a fact that's really a *reason*,
  not a state.
- Instead: `CancellationReason` enum (`CUSTOMER_REQUESTED`,
  `PAYMENT_FAILURE`, `STAFF_ACTION`), stored on
  `customer_subscriptions.cancellation_reason` (added in V4), lowercase
  on the wire via `SubscriptionCancellationReasonConverter`.
- `CustomerSubscription.cancel(Instant, CancellationReason)` sets
  `status = CANCELLED`, `cancelledAt`, nulls `nextPaymentDate`, and
  records the reason.
- When `attempt_count` reaches 3 on a failed payment:
  `subscription.cancel(Instant.now(), CancellationReason.PAYMENT_FAILURE)`.

## Cross-domain coordination: Payment → Subscription

- One-directional only. Payment failure needs to affect subscription
  status; a paused/cancelled subscription does *not* need a live call
  into Payment — whatever schedules new payments simply checks
  `status == ACTIVE` before creating a `payment_history` row.
- Pattern: **direct call, not events** — consistent with the existing
  `AuditLogService` precedent (direct-call chosen over event/listener
  architecture "until multiple reactions necessary"). Rejected Spring
  application events / outbox pattern / polling reconciliation for this
  interaction; may revisit if reactions to payment failure multiply
  beyond subscription-cancellation + audit + notification.
- Shape: `SubscriptionStatusUpdater`, a small service living in
  `subscribe`, injected directly into `PaymentService`:

  ```java
  @Service
  public class SubscriptionStatusUpdater {

      private final CustomerSubscriptionRepository repository;

      public SubscriptionStatusUpdater(CustomerSubscriptionRepository repository) {
          this.repository = repository;
      }

      @Transactional(propagation = Propagation.REQUIRES_NEW)
      public void handlePaymentExhausted(UUID subscriptionId) {
          CustomerSubscription subscription = repository.findById(subscriptionId)
              .orElseThrow(/* ... */);
          subscription.cancel(Instant.now(), CancellationReason.PAYMENT_FAILURE);
          repository.save(subscription);
      }
  }
  ```

- `REQUIRES_NEW` isolates this commit from the payment-attempt
  transaction in both directions — a later failure in the payment side
  doesn't roll back the subscription cancellation, and vice versa.

## Logging & notification split

Two separate writes on exhaustion, not one:

- **`audit_logs`** (via `AuditLogService`, same `REQUIRES_NEW` pattern) —
  the compliance/security record that the payment failed.
- **`notification_log`** (V4) — the record that the *customer was told*.
  Already has `payment_history_id` (nullable FK), so a failure
  notification ties directly to the `PaymentHistory`/`PaymentAttempt`
  that triggered it, not just the subscription. New
  `notification_type` value, e.g. `'payment_attempt_failed'` (free text,
  no CHECK constraint on this column). The actual email send is a
  separate concern from writing this row.
- Rationale: audit answers "did we record the failure," notification
  answers "did we tell the customer" — support/compliance may need
  these answered independently.

## Schema changes

- **V4** (`requirement_gap_closures.sql`, folded in rather than a new
  file — confirmed no other environment had V2–V4 applied at the time):
  `version` (optimistic locking, both `customer_subscriptions` and
  `payment_history`), `cancellation_reason` (`customer_subscriptions`).
- **V8** (`V8__payment_retry_backoff_tracking.sql`):
  `payment_history.last_attempted_at TIMESTAMPTZ` — denormalized,
  mirrors the existing `attempt_count` pattern of keeping a summary
  field on `payment_history` rather than deriving `MAX(attempted_at)`
  from `payment_attempts` on every retry scheduler run. Numbered V8, not
  V5/V6, despite V5 being a skipped/unused version number — a version
  number that was ever part of the project's real history isn't reused,
  even when it ended up empty (see V4/V6/V7, which weren't renumbered
  down either).

## Schedulers (not yet written)

Both `@SchedulerLock` (ShedLock, schema already in V4):

- **Billing scheduler** — daily. Finds `ACTIVE` subscriptions with
  `next_payment_date <= today`, creates the `payment_history` row, makes
  attempt 1 immediately.
- **Retry scheduler** — every 15 minutes. Finds `payment_history` where
  `status = FAILED AND attempt_count IN (1, 2) AND last_attempted_at <=
  now() - backoffFor(attempt_count)`, makes the next attempt.