-- =====================================================================
-- V10 — CHECK constraint on audit_logs.action
-- Constrains audit_logs.action to the known AuditAction enum values,
-- matching the same lowercase-string bridge pattern already used for
-- actor_type (see V1/V9) and cancellation_reason (see V4).
-- =====================================================================

ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_action_check
        CHECK (action IN ('registered', 'account_locked', 'registration_rejected', 'subscription_cancelled'));