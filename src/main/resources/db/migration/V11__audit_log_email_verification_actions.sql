-- =====================================================================
-- V11 — Email verification audit actions (FR-30)
-- Widens audit_logs_action_check (V10) to allow the two new AuditAction
-- values FR-30 records: the verification email being sent, and the
-- customer confirming it.
--
-- A new migration rather than an edit to V10: V10 is already applied
-- wherever main has run, and changing it would fail Flyway's checksum
-- validation on startup for every existing database.
-- =====================================================================

ALTER TABLE audit_logs
    DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_action_check
        CHECK (action IN ('registered', 'account_locked', 'registration_rejected',
    'subscription_created', 'subscription_cancelled', 'subscription_paused',
    'subscription_resumed', 'subscription_updated',
    'email_verification_sent', 'email_verified'));
