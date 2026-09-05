-- =====================================================================
-- V1.1 — Seed data: staff roles & permissions
-- Split out from V1 so schema and seed data version independently:
-- roles/permissions are foundational reference data tightly coupled to
-- the tables V1 creates, so this stays a versioned migration (runs
-- once) rather than a repeatable one — unlike the subscription
-- provider catalog (see R__seed_subscription_provider_catalog.sql),
-- this data isn't expected to be re-applied or grow independently of
-- schema changes to roles/permissions themselves.
-- =====================================================================

INSERT INTO roles (name, description) VALUES
    ('admin',          'Full access to all system settings, users, and data'),
    ('support_agent',  'Views and manages customer accounts and subscriptions for troubleshooting'),
    ('billing_admin',  'Manages billing configuration, payment processing, and refunds'),
    ('auditor',        'Read-only access to audit logs, app logs, and system health for troubleshooting and compliance review');

INSERT INTO permissions (name, resource, action) VALUES
    ('view_customers',     'customer',     'read'),
    ('edit_customers',     'customer',     'update'),
    ('view_subscriptions', 'subscription', 'read'),
    ('edit_subscriptions', 'subscription', 'update'),
    ('process_refunds',    'payment',      'refund'),
    ('manage_billing',     'billing',      'update'),
    ('manage_staff',       'staff_user',   'update'),
    ('manage_roles',       'role',         'update'),
    ('view_audit_logs',    'audit_log',    'read'),
    ('view_app_logs',      'app_log',      'read'),
    ('view_system_health', 'system',       'read');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'admin';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('view_customers', 'edit_customers', 'view_subscriptions')
WHERE r.name = 'support_agent';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('view_subscriptions', 'edit_subscriptions', 'process_refunds', 'manage_billing')
WHERE r.name = 'billing_admin';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('view_audit_logs', 'view_app_logs', 'view_system_health')
WHERE r.name = 'auditor';
