# Common Queries Reference — Subscribe Master

Practical SQL for the questions that come up repeatedly against this schema. Grouped by domain, with a note on which requirement (if any) each query backs. All examples assume PostgreSQL and the table/column names from `migrations/` (`V1`, `V1.1`, `V2`–`V4`, `R__seed_subscription_provider_catalog`, `V6`) — the migration set is the sole source of truth for the schema; there's no longer a separate monolithic schema file.

**A note on status/enum value casing:** every `CHECK`-constrained status/type column in this schema is lowercase (`'succeeded'`, `'failed'`, `'customer'`, `'log'`, `'ok'`, etc.) with **one deliberate exception** — `customer_subscriptions.status` uses uppercase (`'ACTIVE'`, `'CANCELLED'`, `'PAUSED'`), to match `task.pdf`'s literal spec wording so it maps directly to a Java enum. Getting this wrong doesn't error — `WHERE status = 'active'` against `customer_subscriptions` just silently returns zero rows, which is a much harder bug to spot than a query failing outright. Every example query below already uses the correct casing for its table; when writing a new one, double-check this column specifically.

---

## 1. Subscriptions

**A customer's active subscriptions** (always filter soft-deleted rows explicitly — see `ARCHITECTURE.md` §3.1):

```sql
SELECT cs.id, COALESCE(sp.name, cs.custom_name) AS subscription_name,
       cs.amount, cs.currency, cs.status, cs.next_payment_date
FROM customer_subscriptions cs
LEFT JOIN subscription_providers sp ON sp.id = cs.provider_id
WHERE cs.customer_id = $1
  AND cs.deleted_at IS NULL
ORDER BY cs.next_payment_date;
```

**Subscriptions due for a payment-reminder check** (backs FR-18/FR-19 — the daily scheduled job):

```sql
SELECT cs.id, cs.customer_id, cs.next_payment_date
FROM customer_subscriptions cs
WHERE cs.deleted_at IS NULL
  AND cs.status = 'ACTIVE'
  AND cs.next_payment_date = CURRENT_DATE + INTERVAL '2 days';
```

**Filter by status, currency, and price range** (backs FR-10 — pagination/filtering):

```sql
SELECT id, COALESCE(custom_name, provider_id::text) AS name, amount, currency, status
FROM customer_subscriptions
WHERE customer_id = $1
  AND deleted_at IS NULL
  AND status = COALESCE($2, status)          -- pass NULL to skip this filter
  AND currency = COALESCE($3, currency)
  AND amount BETWEEN COALESCE($4, 0) AND COALESCE($5, 999999999)
ORDER BY amount DESC
LIMIT $6 OFFSET $7;                          -- pagination
```

**Spending grouped by category** (backs FR-27):

```sql
SELECT COALESCE(cs.category, sp.category, 'Uncategorized') AS category,
       COUNT(*) AS subscription_count,
       SUM(cs.amount) AS total_amount
FROM customer_subscriptions cs
LEFT JOIN subscription_providers sp ON sp.id = cs.provider_id
WHERE cs.customer_id = $1
  AND cs.deleted_at IS NULL
  AND cs.status = 'ACTIVE'
GROUP BY COALESCE(cs.category, sp.category, 'Uncategorized')
ORDER BY total_amount DESC;
```

---

## 2. Payments & statistics

**Most expensive subscription, current month** (backs FR-24):

```sql
SELECT cs.id, COALESCE(sp.name, cs.custom_name) AS subscription_name,
       ph.base_currency_amount, ph.base_currency
FROM payment_history ph
JOIN customer_subscriptions cs ON cs.id = ph.subscription_id
LEFT JOIN subscription_providers sp ON sp.id = cs.provider_id
WHERE cs.customer_id = $1
  AND ph.status = 'succeeded'
  AND date_trunc('month', ph.paid_at) = date_trunc('month', CURRENT_DATE)
ORDER BY ph.base_currency_amount DESC
LIMIT 1;
```

**Total spend for the current month, in base currency** (backs FR-25):

```sql
SELECT SUM(ph.base_currency_amount) AS total_this_month
FROM payment_history ph
JOIN customer_subscriptions cs ON cs.id = ph.subscription_id
WHERE cs.customer_id = $1
  AND ph.status = 'succeeded'
  AND date_trunc('month', ph.paid_at) = date_trunc('month', CURRENT_DATE);
```

**Monthly cost trend, last 12 months** (backs FR-26 — JSON-ready for frontend charting):

```sql
SELECT date_trunc('month', ph.paid_at) AS month,
       SUM(ph.base_currency_amount) AS total_spent
FROM payment_history ph
JOIN customer_subscriptions cs ON cs.id = ph.subscription_id
WHERE cs.customer_id = $1
  AND ph.status = 'succeeded'
  AND ph.paid_at >= CURRENT_DATE - INTERVAL '12 months'
GROUP BY date_trunc('month', ph.paid_at)
ORDER BY month;
```

**Annual cost report data** (backs FR-22/FR-23 — feeds the Excel/CSV export):

```sql
SELECT COALESCE(sp.name, cs.custom_name) AS subscription_name,
       cs.amount AS monthly_price,
       cs.currency,
       ph.base_currency_amount,
       ph.base_currency,
       SUM(ph.base_currency_amount) OVER (PARTITION BY cs.id) AS total_annual_cost
FROM customer_subscriptions cs
LEFT JOIN subscription_providers sp ON sp.id = cs.provider_id
JOIN payment_history ph ON ph.subscription_id = cs.id
WHERE cs.customer_id = $1
  AND ph.status = 'succeeded'
  AND ph.paid_at >= CURRENT_DATE - INTERVAL '1 year'
ORDER BY subscription_name, ph.paid_at;
```

---

## 3. Retry & refunds

**Payments stuck at max retry attempts** (ops alert candidate — see `ARCHITECTURE.md` §10):

```sql
SELECT ph.id, ph.subscription_id, ph.attempt_count, ph.amount, ph.currency
FROM payment_history ph
WHERE ph.status = 'failed'
  AND ph.attempt_count >= 3;
```

**Full attempt history for a given payment** (debugging a specific failed charge):

```sql
SELECT attempt_number, status, failure_reason, attempted_at, stripe_payment_intent_id
FROM payment_attempts
WHERE payment_history_id = $1
ORDER BY attempt_number;
```

**Refunds issued in a date range, with reason breakdown** (audit/finance reporting):

```sql
SELECT reason, COUNT(*) AS refund_count, SUM(amount) AS total_refunded
FROM refunds
WHERE refunded_at BETWEEN $1 AND $2
GROUP BY reason
ORDER BY total_refunded DESC;
```

**Partial refunds only** (amount less than the original payment):

```sql
SELECT r.id, r.amount AS refund_amount, ph.amount AS original_amount, r.reason
FROM refunds r
JOIN payment_history ph ON ph.id = r.payment_history_id
WHERE r.amount < ph.amount;
```

---

## 4. Auth & RBAC

**Check whether a staff user has a specific permission** (the pattern every permission-gated endpoint should use — see `ARCHITECTURE.md` §2.2):

```sql
SELECT EXISTS (
    SELECT 1
    FROM staff_users su
    JOIN role_permissions rp ON rp.role_id = su.role_id
    JOIN permissions p ON p.id = rp.permission_id
    WHERE su.id = $1
      AND p.name = $2          -- e.g. 'process_refunds'
) AS has_permission;
```

**Full permission list for a role** (access review):

```sql
SELECT p.name, p.resource, p.action
FROM role_permissions rp
JOIN permissions p ON p.id = rp.permission_id
WHERE rp.role_id = (SELECT id FROM roles WHERE name = $1)
ORDER BY p.resource, p.action;
```

**Active (non-expired) sessions for a customer:**

```sql
SELECT id, ip_address, user_agent, created_at, expires_at
FROM customer_sessions
WHERE customer_id = $1
  AND expires_at > now();
```

**Expired tokens ready for cleanup** (housekeeping job — run periodically, not ad hoc):

```sql
SELECT 'password_reset' AS token_type, id FROM customer_password_reset_tokens WHERE expires_at < now()
UNION ALL
SELECT 'email_verification', id FROM customer_email_verification_tokens WHERE expires_at < now()
UNION ALL
SELECT 'refresh_token', id FROM customer_refresh_tokens WHERE expires_at < now() AND revoked_at IS NULL;
```

---

## 5. Operational

**Pending notifications not yet sent** (queue-style check for the notification worker):

```sql
SELECT id, subscription_id, notification_type, channel
FROM notification_log
WHERE sent_at IS NULL
ORDER BY created_at
LIMIT 100;
```

**Check whether a reminder was already sent today** (what `uq_notification_log_daily_dedup` enforces — useful for the scheduler to check before attempting an insert):

```sql
SELECT EXISTS (
    SELECT 1 FROM notification_log
    WHERE subscription_id = $1
      AND notification_type = $2
      AND sent_at::date = CURRENT_DATE
) AS already_sent_today;
```

**Stuck ShedLock entries** (a lock held past its expiry with no completed job — see `ARCHITECTURE.md` §7.1):

```sql
SELECT name, lock_until, locked_at, locked_by
FROM shedlock
WHERE lock_until < now();
```

**Recent actions by a specific actor, from `audit_logs`:**

```sql
SELECT action, resource, resource_id, ip_address, created_at
FROM audit_logs
WHERE actor_type = $1        -- 'customer' or 'staff'
  AND actor_id = $2
ORDER BY created_at DESC
LIMIT 50;
```

**Validate `audit_logs` referential integrity** (periodic check — there's no real FK enforcing this; see `ARCHITECTURE.md` §6.1):

```sql
SELECT al.id, al.actor_type, al.actor_id
FROM audit_logs al
WHERE (al.actor_type = 'customer' AND NOT EXISTS (
          SELECT 1 FROM customers c WHERE c.id = al.actor_id))
   OR (al.actor_type = 'staff' AND NOT EXISTS (
          SELECT 1 FROM staff_users su WHERE su.id = al.actor_id));
```

---

## 6. Currency

**Most recent cached exchange rate for a currency pair:**

```sql
SELECT rate, fetched_at
FROM exchange_rates
WHERE base_currency = $1 AND target_currency = $2
ORDER BY fetched_at DESC
LIMIT 1;
```

**Fallback pattern for the Circuit Breaker (FR-16)** — same query as above; the "fallback" is simply using this cached row when the live API call fails, rather than a different query.
