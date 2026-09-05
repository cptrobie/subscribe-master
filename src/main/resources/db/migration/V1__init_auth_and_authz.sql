-- =====================================================================
-- V1 — Auth & Authz foundation
-- Customer auth (self-service, social login), staff auth & RBAC,
-- and audit/app logging. No subscription or payment concepts yet —
-- those are introduced in V2.
--
-- Schema only — seed data for roles/permissions lives in the
-- companion migration V1.1__seed_roles_and_permissions.sql, kept
-- separate so schema and seed data version independently.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto"; -- for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";   -- for case-insensitive email columns


-- ---------------------------------------------------------------------
-- Customer auth
-- ---------------------------------------------------------------------
CREATE TABLE customers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           CITEXT NOT NULL UNIQUE,
    password_hash   TEXT,               -- nullable: social-only accounts may have no password
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customers_email ON customers (email);

CREATE TABLE customer_oauth_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    provider            TEXT NOT NULL CHECK (provider IN ('google', 'facebook', 'x')),
    provider_user_id    TEXT NOT NULL,
    access_token        TEXT,
    refresh_token       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_customer_oauth_accounts_customer_id ON customer_oauth_accounts (customer_id);

CREATE TABLE customer_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    session_token   TEXT NOT NULL UNIQUE,   -- store a hash, not the raw token, in application code
    ip_address      INET,
    user_agent      TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_sessions_customer_id ON customer_sessions (customer_id);
CREATE INDEX idx_customer_sessions_expires_at ON customer_sessions (expires_at);

CREATE TABLE customer_password_reset_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,   -- store a hash of the token, never the raw value
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,            -- null until consumed; prevents token reuse
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_pwd_reset_customer_id ON customer_password_reset_tokens (customer_id);

CREATE TABLE customer_email_verification_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_email_verif_customer_id ON customer_email_verification_tokens (customer_id);

CREATE TABLE customer_refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,   -- store a hash of the token, never the raw value
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,            -- null while valid; set on logout, rotation, or manual revocation
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_refresh_tokens_customer_id ON customer_refresh_tokens (customer_id);


-- ---------------------------------------------------------------------
-- Staff auth & RBAC
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    resource        TEXT NOT NULL,
    action          TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role_permissions (
    role_id         UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id   UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,

    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE staff_users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           CITEXT NOT NULL UNIQUE,
    password_hash   TEXT,               -- nullable to accommodate future SSO-only accounts
    role_id         UUID NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_users_email ON staff_users (email);
CREATE INDEX idx_staff_users_role_id ON staff_users (role_id);

CREATE TABLE staff_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id   UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    session_token   TEXT NOT NULL UNIQUE,
    ip_address      INET,
    user_agent      TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_sessions_staff_user_id ON staff_sessions (staff_user_id);
CREATE INDEX idx_staff_sessions_expires_at ON staff_sessions (expires_at);

CREATE TABLE staff_password_reset_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id   UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_pwd_reset_staff_user_id ON staff_password_reset_tokens (staff_user_id);


-- ---------------------------------------------------------------------
-- Logs
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type      TEXT NOT NULL CHECK (actor_type IN ('customer', 'staff')),
    actor_id        UUID NOT NULL,
    action          TEXT NOT NULL,
    resource        TEXT NOT NULL,
    resource_id     UUID,
    ip_address      INET,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_type, actor_id);
CREATE INDEX idx_audit_logs_resource ON audit_logs (resource, resource_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);

CREATE TABLE app_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    level           TEXT NOT NULL CHECK (level IN ('debug', 'info', 'warning', 'error', 'critical')),
    source          TEXT NOT NULL,
    message         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_logs_level ON app_logs (level);
CREATE INDEX idx_app_logs_created_at ON app_logs (created_at);
