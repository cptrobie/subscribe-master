-- =====================================================================
-- V13 — Staff login lockout and staff audit actions (FR-06)
-- Adds failed_login_attempts / locked_until to staff_users, matching
-- the columns customers already has (FR-02). Staff login gets the same
-- per-account lockout as customer login, applied by
-- StaffCredentialChecker using the same app.login-lockout.* settings.
-- The most privileged accounts in the system shouldn't have weaker
-- brute-force protection than customers.
--
-- Also widens audit_logs_action_check (V10, widened in V11) with two
-- staff actions:
--   staff_logged_in     a successful staff login. Customer logins are
--                       not audited, but staff access is privileged and
--                       rare, so the record is useful rather than noise.
--   staff_user_created  a staff account being created. The first is the
--                       bootstrap admin, created from config at startup;
--                       its actor is the new admin's own id, with
--                       {"source": "bootstrap"} in details (NFR-41).
-- A staff lockout reuses the existing account_locked action;
-- actor_type = 'staff' is what tells it apart from a customer's.
--
-- The CHECK is replaced as a whole, so the list below must repeat every
-- value V11 allowed. A new migration rather than an edit to V1 or V11,
-- because both are already applied wherever main has run, and changing
-- them would fail Flyway's checksum validation on startup.
-- See design-docs/staff_authorization_design.md.
-- =====================================================================

ALTER TABLE staff_users ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE staff_users ADD COLUMN locked_until TIMESTAMPTZ;

ALTER TABLE audit_logs
    DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_action_check
        CHECK (action IN ('registered', 'account_locked', 'registration_rejected',
    'subscription_created', 'subscription_cancelled', 'subscription_paused',
    'subscription_resumed', 'subscription_updated',
    'email_verification_sent', 'email_verification_send_failed', 'email_verified',
    'email_changed', 'staff_logged_in', 'staff_user_created'));
