-- =====================================================================
-- V11 — Email verification audit actions (FR-30, NFR-34)
-- Widens audit_logs_action_check (V10) to allow the three new AuditAction
-- values for email verification: the verification email being sent
-- (FR-30), its send failing (NFR-34), and the customer confirming it
-- (FR-30).
--
-- NFR-34's value was added here in place rather than in a V12, because
-- V11 had not yet been applied anywhere beyond local development. Local
-- databases were rebuilt (docker compose down -v), not repaired.
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
    'email_verification_sent', 'email_verification_send_failed', 'email_verified'));
