-- =====================================================================
-- V9 — Allow 'retry_scheduler' as an audit_logs actor_type
-- Widens audit_logs.actor_type's CHECK constraint so payment-retry
-- exhaustion (system-triggered subscription cancellation) can be
-- recorded in the audit log with a real, non-human actor identity,
-- distinct from 'customer'/'staff'. See payment_retry_and_dunning.md
-- for the design context.
-- =====================================================================

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_actor_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_actor_type_check
    CHECK (actor_type IN ('customer', 'staff', 'retry_scheduler'));