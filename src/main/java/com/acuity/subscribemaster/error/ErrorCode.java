package com.acuity.subscribemaster.error;

/** Stable, machine-readable error identifiers returned in {@link ApiError#code()}. */
public enum ErrorCode {
  VALIDATION_FAILED,
  MALFORMED_REQUEST,
  MISSING_HEADER,
  USER_ALREADY_EXISTS,
  IDEMPOTENCY_KEY_MISMATCH,
  IDEMPOTENCY_KEY_PROCESSING,
  INTERNAL_ERROR
}
