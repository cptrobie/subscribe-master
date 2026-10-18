package com.acuity.subscribemaster.idempotency;

// TODO(NFR-33): stub for the stored idempotent response. Once the idempotency store is
// implemented, this should be populated by looking up the Idempotency-Key header against
// persisted (status, body) pairs from a prior request, instead of being constructed by hand.
/**
 * A stored idempotent response -- HTTP {@code status} and raw {@code body} -- to return for a
 * retried request (NFR-24). Currently a placeholder only: nothing populates or uses it until the
 * idempotency store lands with NFR-33.
 */
public record Replay(int status, String body) {}
