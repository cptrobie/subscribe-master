-- =====================================================================
-- V2 — Subscriptions & payments
-- Extends `customers` with billing-related columns, and introduces
-- subscription tracking, Stripe-tokenized payment methods, payment
-- history, and exchange rate caching.
--
-- Note: payment_history.status intentionally starts narrower here
-- (pending/succeeded/failed) — refunded/partially_refunded are added
-- in V3 alongside the retry and refund entities that need them.
--
-- Note: customer_subscriptions.status uses ACTIVE/CANCELLED/PAUSED
-- (uppercase) from the outset, matching the Subscribe Master spec's
-- enum convention directly — no later casing fix required.
-- =====================================================================

ALTER TABLE customers
    ADD COLUMN base_currency       CHAR(3) NOT NULL DEFAULT 'USD',  -- customer-selected reporting currency
    ADD COLUMN stripe_customer_id  TEXT UNIQUE;                     -- Stripe Customer object reference


-- ---------------------------------------------------------------------
-- Subscription tracking
-- ---------------------------------------------------------------------
CREATE TABLE subscription_providers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,   -- e.g. 'Netflix', 'Spotify', 'Apple TV+'
    category        TEXT,                   -- e.g. 'streaming_video', 'music', 'cloud_storage'
    logo_url        TEXT,
    website_url     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE customer_payment_methods (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id                 UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    stripe_payment_method_id    TEXT NOT NULL UNIQUE,
    brand                       TEXT,
    last4                       CHAR(4),
    exp_month                   SMALLINT CHECK (exp_month BETWEEN 1 AND 12),
    exp_year                    SMALLINT,
    is_default                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ            -- soft delete: preserves payment_history integrity
);

CREATE INDEX idx_customer_payment_methods_customer_id ON customer_payment_methods (customer_id);
CREATE INDEX idx_customer_payment_methods_active
    ON customer_payment_methods (customer_id)
    WHERE deleted_at IS NULL;

CREATE TABLE customer_subscriptions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id             UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    provider_id             UUID REFERENCES subscription_providers (id) ON DELETE SET NULL,
    custom_name             TEXT,                        -- used when provider_id is null
    account_identifier      TEXT,                        -- e.g. email/username used with the provider (not credentials)
    payment_method_id       UUID REFERENCES customer_payment_methods (id) ON DELETE SET NULL,
    currency                CHAR(3) NOT NULL,             -- billing currency of the subscription
    amount                  NUMERIC(12, 2) NOT NULL,      -- tax-inclusive total, as entered by the customer
    billing_frequency       TEXT NOT NULL CHECK (
                                billing_frequency IN ('weekly', 'monthly', 'quarterly', 'semi_annual', 'annual', 'custom')
                             ),
    billing_interval_days   INTEGER,                      -- used when billing_frequency = 'custom'
    next_payment_date       DATE NOT NULL,
    -- NOTE: uppercase is a deliberate exception to this schema's otherwise
    -- consistent lowercase convention for enum-like CHECK values (see every
    -- other status/type column in this file and V1/V3/V4/V6). This matches
    -- task.pdf's literal ACTIVE/PAUSED/CANCELLED spec wording (FR-09), so it
    -- maps directly to a Java enum without a casing-translation layer.
    status                  TEXT NOT NULL DEFAULT 'ACTIVE'
                             CONSTRAINT chk_customer_subscriptions_status
                             CHECK (status IN ('ACTIVE', 'CANCELLED', 'PAUSED')),
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at             TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,                  -- soft delete: preserves payment_history integrity
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_subscription_has_name CHECK (provider_id IS NOT NULL OR custom_name IS NOT NULL),
    CONSTRAINT chk_custom_interval CHECK (billing_frequency <> 'custom' OR billing_interval_days IS NOT NULL)
);

CREATE INDEX idx_customer_subscriptions_customer_id ON customer_subscriptions (customer_id);
CREATE INDEX idx_customer_subscriptions_provider_id ON customer_subscriptions (provider_id);
CREATE INDEX idx_customer_subscriptions_next_payment_date ON customer_subscriptions (next_payment_date);
CREATE INDEX idx_customer_subscriptions_active
    ON customer_subscriptions (customer_id, status)
    WHERE deleted_at IS NULL;


-- ---------------------------------------------------------------------
-- Payment history (core — retry/refund states arrive in V3)
-- ---------------------------------------------------------------------
CREATE TABLE payment_history (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id         UUID NOT NULL REFERENCES customer_subscriptions (id) ON DELETE RESTRICT,
    payment_method_id       UUID REFERENCES customer_payment_methods (id) ON DELETE SET NULL,
    amount                  NUMERIC(12, 2) NOT NULL,      -- tax-inclusive, in subscription currency
    currency                CHAR(3) NOT NULL,
    base_currency_amount    NUMERIC(12, 2),                -- converted amount, snapshotted at payment time
    base_currency           CHAR(3),
    exchange_rate_applied   NUMERIC(18, 8),                -- snapshot of the rate used; immutable after the fact
    status                  TEXT NOT NULL DEFAULT 'pending'
                             CONSTRAINT chk_payment_history_status
                             CHECK (status IN ('pending', 'succeeded', 'failed')),
    scheduled_at            TIMESTAMPTZ NOT NULL,
    paid_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_history_subscription_id ON payment_history (subscription_id);
CREATE INDEX idx_payment_history_status ON payment_history (status);
CREATE INDEX idx_payment_history_scheduled_at ON payment_history (scheduled_at);


-- ---------------------------------------------------------------------
-- Currency
-- ---------------------------------------------------------------------
CREATE TABLE exchange_rates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency       CHAR(3) NOT NULL,
    target_currency     CHAR(3) NOT NULL,
    rate                NUMERIC(18, 8) NOT NULL,
    source              TEXT,                    -- e.g. name of the forex API used
    fetched_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (base_currency, target_currency, fetched_at)
);

CREATE INDEX idx_exchange_rates_pair ON exchange_rates (base_currency, target_currency, fetched_at DESC);
