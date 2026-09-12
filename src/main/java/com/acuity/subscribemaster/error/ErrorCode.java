package com.acuity.subscribemaster.error;

/** Stable, machine-readable error identifiers returned in {@link ApiError#code()}. */
public enum ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    USER_ALREADY_EXISTS,
    INTERNAL_ERROR
}
