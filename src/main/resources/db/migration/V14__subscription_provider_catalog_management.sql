-- =====================================================================
-- V14 — Staff-managed subscription provider catalog (FR-35)
-- Until now subscription_providers was insert-only: seeded by
-- R__seed_subscription_provider_catalog.sql and never edited. FR-35
-- lets staff create, update and deactivate providers, so the table
-- becomes mutable. Four changes:
--
--   1. updated_at and deleted_at on subscription_providers.
--      updated_at because the table is no longer insert-only, which
--      takes it out of NFR-07's "append-only tables only get
--      created_at" exception. deleted_at is the soft-delete flag:
--      providers are deactivated, never deleted, so a provider that
--      subscriptions reference can never be orphaned.
--   2. name's UNIQUE constraint replaced with a partial unique index
--      (WHERE deleted_at IS NULL). A plain UNIQUE would reserve a
--      deactivated provider's name forever; the partial index frees
--      it, while still stopping two active providers sharing a name.
--   3. Four permissions, one per staff action (create, update,
--      deactivate, read), granted to admin only. Endpoints check the
--      permission name, never the role (FR-06), so another role can be
--      given one later without a code change.
--   4. audit_logs_action_check widened with provider_created,
--      provider_updated and provider_deactivated. Reading the catalog
--      changes nothing, so view_providers has no audit action.
--
-- R__seed_subscription_provider_catalog.sql changes in the same commit:
-- its ON CONFLICT (name) must now name the index's predicate
-- (WHERE deleted_at IS NULL), or Postgres finds no matching unique
-- index and the seed fails on every fresh database.
--
-- A new migration rather than an edit to V13, which is already applied
-- wherever main has run; changing it would fail Flyway's checksum
-- validation on startup.
-- See design-docs/provider_catalog_management.md.
-- =====================================================================

-- 1. Timestamps. Existing rows get updated_at = now() from the default;
--    deleted_at stays NULL, so every seeded provider starts active.
ALTER TABLE subscription_providers ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE subscription_providers ADD COLUMN deleted_at TIMESTAMPTZ;

-- 2. Name uniqueness among active providers only. V2 declared UNIQUE
--    inline, so Postgres named the constraint <table>_<column>_key.
--    The index must come after deleted_at exists, since its WHERE
--    clause refers to it.
ALTER TABLE subscription_providers DROP CONSTRAINT subscription_providers_name_key;

CREATE UNIQUE INDEX subscription_providers_active_name_uq ON subscription_providers (name)
    WHERE deleted_at IS NULL;

-- 3. Permissions, named verb_noun like V1.1's (process_refunds,
--    view_customers). V1.1's CROSS JOIN grant to admin ran once, so
--    these new rows need their own explicit grant.
INSERT INTO permissions (name, resource, action) VALUES
    ('create_providers',     'subscription_provider', 'create'),
    ('update_providers',     'subscription_provider', 'update'),
    ('deactivate_providers',     'subscription_provider', 'deactivate'),
    ('view_providers',     'subscription_provider', 'read');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name
IN ('create_providers', 'update_providers', 'deactivate_providers', 'view_providers')
WHERE r.name = 'admin';

-- 4. Audit actions. The CHECK is replaced as a whole, so the list below
--    must repeat every value V13 allowed.
ALTER TABLE audit_logs
    DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs
    ADD CONSTRAINT audit_logs_action_check
        CHECK (action IN ('registered', 'account_locked', 'registration_rejected',
    'subscription_created', 'subscription_cancelled', 'subscription_paused',
    'subscription_resumed', 'subscription_updated',
    'email_verification_sent', 'email_verification_send_failed', 'email_verified',
    'email_changed', 'staff_logged_in', 'staff_user_created', 'provider_created',
    'provider_updated', 'provider_deactivated'));
