-- =====================================================================
-- V3 — Payment retry & refunds
-- Adds per-attempt retry tracking (up to 3 tries per payment) and
-- refund support (full or partial, with a reason). Widens
-- payment_history.status to include the refund states these
-- entities need.
-- =====================================================================

ALTER TABLE payment_history
    ADD COLUMN attempt_count SMALLINT NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3);

ALTER TABLE payment_history
    DROP CONSTRAINT chk_payment_history_status;

ALTER TABLE payment_history
    ADD CONSTRAINT chk_payment_history_status
    CHECK (status IN ('pending', 'succeeded', 'failed', 'refunded', 'partially_refunded'));


CREATE TABLE payment_attempts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_history_id         UUID NOT NULL REFERENCES payment_history (id) ON DELETE CASCADE,
    attempt_number              SMALLINT NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    stripe_payment_intent_id   TEXT,
    status                      TEXT NOT NULL CHECK (status IN ('succeeded', 'failed')),
    failure_reason               TEXT,                    -- e.g. 'card_declined', 'insufficient_funds'
    attempted_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (payment_history_id, attempt_number)
);

CREATE INDEX idx_payment_attempts_payment_history_id ON payment_attempts (payment_history_id);


CREATE TABLE refunds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_history_id      UUID NOT NULL REFERENCES payment_history (id) ON DELETE RESTRICT,
    amount                  NUMERIC(12, 2) NOT NULL,      -- full or partial; must not exceed original payment amount
    reason                  TEXT NOT NULL,                -- e.g. 'prorated_cancellation', 'duplicate_charge'
    stripe_refund_id        TEXT UNIQUE,
    refunded_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refunds_payment_history_id ON refunds (payment_history_id);
