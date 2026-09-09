-- =====================================================================
-- Idempotency support for POST endpoints with side effects (NFR-24).
--
-- Standard pattern (see Stripe, Square, and similar API design):
-- a client sends an Idempotency-Key header on a POST request. On first
-- receipt, the server processes normally and stores the outcome here.
-- On a retry with the same key, the server returns the stored response
-- instead of re-running the operation -- so a network-drop-and-retry
-- doesn't create a second customer, send a second email, or (once
-- Wave 6 lands) charge a card twice.
--
-- Composite primary key (idempotency_key, endpoint): the same key value
-- is only meaningful within one endpoint's context -- reusing a key
-- across two different endpoints should not collide.
--
-- caller_id is nullable and intentionally NOT foreign-keyed to a single
-- table, mirroring audit_logs.actor_id's reasoning (V4) -- it may
-- reference a customer, a staff user, or nothing at all (unauthenticated
-- endpoints like registration have no caller identity yet, since
-- creating that identity is the whole point of the call).
--
-- request_hash guards against a real client bug or misuse: the same
-- idempotency key reused with a genuinely different request body should
-- be rejected as a mismatch, not silently served a stale cached
-- response for the wrong payload.
--
-- Append-only, like audit_logs/notification_log/payment_attempts/
-- refunds/exchange_rates (NFR-07) -- only created_at, no updated_at,
-- since a row is never legitimately modified after being written.
-- =====================================================================

CREATE TABLE idempotency_keys (
                                  idempotency_key TEXT NOT NULL,
                                  endpoint        TEXT NOT NULL,
                                  caller_id       UUID,
                                  request_hash    TEXT NOT NULL,
                                  response_status INTEGER NOT NULL,
                                  response_body   JSONB,
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  expires_at      TIMESTAMPTZ NOT NULL,
                                  PRIMARY KEY (idempotency_key, endpoint)
);

-- Supports an eventual cleanup job (expired rows have no further value
-- and can be purged) without a full table scan.
CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
