-- =====================================================================
-- V8 — Payment retry backoff tracking
-- Adds last_attempted_at to payment_history so the retry scheduler can
-- determine backoff eligibility (2h after attempt 1, 4h after attempt
-- 2) without deriving MAX(attempted_at) from payment_attempts on every
-- run — mirrors the existing attempt_count denormalization pattern.
-- =====================================================================

ALTER TABLE payment_history
    ADD COLUMN last_attempted_at TIMESTAMPTZ;