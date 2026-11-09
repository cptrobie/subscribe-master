-- =====================================================================
-- V12 — audit_logs.details: what an audited action changed (NFR-41)
-- Adds a nullable JSONB column so an audit row can record *what*
-- changed, not just who changed which resource. resource_id is a UUID
-- and there was no free column for this (the gap NFR-34 hit with the
-- SMTP cause).
--
-- Convention: a small JSON object holding only what a reviewer needs.
-- For a change, that's {"from": ..., "to": ...} plus the name of the
-- thing changed (e.g. "logger", "setting"). Never secrets or personal
-- data (NFR-23). Amends NFR-17; see ARCHITECTURE.md §6.1.
--
-- Nullable with no default: existing rows, and new rows that have
-- nothing to add, stay NULL.
-- =====================================================================

ALTER TABLE audit_logs ADD COLUMN details JSONB NULL;
