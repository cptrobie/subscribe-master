package com.acuity.subscribemaster.error;

/** Stable, machine-readable error identifiers returned in {@link ApiError#code()}. */
public enum ErrorCode {
  VALIDATION_FAILED,
  MALFORMED_REQUEST,
  MISSING_HEADER,
  ACCOUNT_ALREADY_EXISTS,
  ACCOUNT_LOCKED,
  INVALID_CREDENTIALS,
  IDEMPOTENCY_KEY_MISMATCH,
  IDEMPOTENCY_KEY_PROCESSING,
  INTERNAL_ERROR
}
