-- =====================================================================
-- V4 — Requirement gap closures
-- Closes four gaps found when cross-checking the schema against the
-- Subscribe Master requirements doc:
--   1. Optimistic locking (NFR-04) — version column on the two tables
--      most exposed to concurrent writes.
--   2. Category grouping for custom subscriptions (FR-27).
--   3. Notification tracking, to avoid duplicate payment-due reminders
--      (FR-19/FR-20).
--   4. Scheduler concurrency safety via ShedLock (FR-21).
-- =====================================================================

-- --- 1. Optimistic locking -----------------------------------------------

ALTER TABLE customer_subscriptions
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE payment_history
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;


-- --- 2. Category grouping -------------------------------------------------

ALTER TABLE customer_subscriptions
    ADD COLUMN category TEXT;  -- independent of subscription_providers.category,
                                -- so custom (non-catalog) subscriptions can be grouped too

CREATE INDEX idx_customer_subscriptions_category ON customer_subscriptions (category);


-- --- 3. Notification tracking ----------------------------------------------

CREATE TABLE notification_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID NOT NULL REFERENCES customer_subscriptions (id) ON DELETE CASCADE,
    payment_history_id  UUID REFERENCES payment_history (id) ON DELETE SET NULL,
    notification_type   TEXT NOT NULL,           -- e.g. 'payment_due_reminder'
    channel              TEXT NOT NULL CHECK (channel IN ('log', 'email')),
    sent_at              TIMESTAMPTZ,             -- null if send is pending/queued
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_log_subscription_id ON notification_log (subscription_id);
CREATE INDEX idx_notification_log_type_sent ON notification_log (notification_type, sent_at);

-- Prevents sending the same reminder type twice for the same subscription
-- on the same day the scheduler runs.
CREATE UNIQUE INDEX uq_notification_log_daily_dedup
    ON notification_log (subscription_id, notification_type, (sent_at::date))
    WHERE sent_at IS NOT NULL;


-- --- 4. Scheduler concurrency safety -----------------------------------

-- Standard schema expected by the ShedLock library. No foreign keys to
-- any business table — it's a pure scheduler coordination primitive,
-- not domain data.
CREATE TABLE shedlock (
    name        VARCHAR(64) PRIMARY KEY,
    lock_until  TIMESTAMP NOT NULL,
    locked_at   TIMESTAMP NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
