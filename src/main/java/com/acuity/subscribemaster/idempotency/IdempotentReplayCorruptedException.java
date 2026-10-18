package com.acuity.subscribemaster.idempotency;

/**
 * A stored idempotent response (NFR-24) could not be re-read for replay; the underlying failure is
 * kept as the cause. Mapped to a 500 {@code INTERNAL_ERROR} by {@link
 * com.acuity.subscribemaster.error.GlobalExceptionHandler}. Nothing throws it yet -- the
 * idempotency store it belongs to arrives with NFR-33.
 */
public class IdempotentReplayCorruptedException extends RuntimeException {
  public IdempotentReplayCorruptedException(Throwable cause) {
    super("stored idempotent response could not be re-read", cause);
  }
}
