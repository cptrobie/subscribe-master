-- =====================================================================
-- Failed-login lockout support for the customer login endpoint (FR-02).
--
-- Mirrors the design already worked out for FR-33's 2FA lockout: a
-- live-expiry-checked timestamp rather than a scheduled cleanup job --
-- the login endpoint checks locked_until against now() on every
-- attempt, rather than any background process actively clearing it.
-- No ShedLock/scheduling infrastructure needed for this reason alone.
--
-- Policy (application-level, not enforced here): 5 failed attempts
-- triggers a 15-minute lockout. A correct password, or the lockout
-- window naturally expiring, resets failed_login_attempts back to 0.
-- A locked account returns the same generic failure regardless of
-- whether the submitted password happens to be correct -- same
-- reasoning as the 2FA lockout: a locked-out attacker should learn
-- nothing about whether their guess was right.
--
-- These are mutable, single-current-value properties of the account
-- itself (like email_verified), not an append-only history -- correctly
-- live on customers directly, not a separate table.
-- =====================================================================

ALTER TABLE customers
    ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          TIMESTAMPTZ;
