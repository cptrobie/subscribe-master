package com.acuity.subscribemaster.idempotency;

// TODO(FR-30/FR-31): stub for the stored idempotent response. Once the idempotency store is
// implemented, this should be populated by looking up the Idempotency-Key header against
// persisted (status, body) pairs from a prior request, instead of being constructed by hand.
public record Replay(int status, String body) {}
