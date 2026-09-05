-- =====================================================================
-- V6 — Distributed tracing
-- Closes NFR-18: request tracing was not previously supported —
-- app_logs (V1) covers discrete log messages but has no trace
-- correlation. This follows the same separation-of-concerns reasoning
-- already applied to audit_logs vs. app_logs: a dedicated table for
-- request-path timing, kept separate from point-in-time log messages.
--
-- Uses TEXT rather than UUID for trace_id/span_id, since common
-- tracing standards (e.g. OpenTelemetry) generate hex-encoded IDs
-- that aren't valid UUIDs — forcing UUID here would fight whatever
-- tracing library actually generates these values.
--
-- Note: like app_logs, span data is typically high-volume and often
-- lives in a dedicated tracing backend (Jaeger, Zipkin, Tempo, or a
-- hosted APM) rather than the primary database in production. This
-- table satisfies the requirement at the schema level; whether it's
-- actually where trace data should live long-term is a separate
-- infrastructure decision.
-- =====================================================================

CREATE TABLE trace_spans (
    span_id         TEXT PRIMARY KEY,               -- e.g. OpenTelemetry-style hex span ID
    trace_id        TEXT NOT NULL,                   -- groups all spans belonging to one request/trace
    parent_span_id  TEXT REFERENCES trace_spans (span_id) ON DELETE SET NULL,  -- null for the root span
    operation_name  TEXT NOT NULL,                   -- e.g. 'POST /subscriptions', 'ExchangeRateService.fetchRate'
    service_name    TEXT,                            -- which service/module emitted this span
    status          TEXT NOT NULL DEFAULT 'ok' CHECK (status IN ('ok', 'error')),
    started_at      TIMESTAMPTZ NOT NULL,
    duration_ms     INTEGER,                         -- null while the span is in-flight; set on completion
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trace_spans_trace_id ON trace_spans (trace_id);
CREATE INDEX idx_trace_spans_parent_span_id ON trace_spans (parent_span_id);
CREATE INDEX idx_trace_spans_started_at ON trace_spans (started_at);
CREATE INDEX idx_trace_spans_service_operation ON trace_spans (service_name, operation_name);
