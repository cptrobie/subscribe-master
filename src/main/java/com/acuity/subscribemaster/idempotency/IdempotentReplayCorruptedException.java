package com.acuity.subscribemaster.idempotency;

/**
 * A stored idempotent response (NFR-24) could not be re-read for replay; the underlying failure is
 * kept as the cause. Mapped to a 500 {@code INTERNAL_ERROR} by {@link
 * com.acuity.subscribemaster.error.GlobalExceptionHandler}. Thrown by {@link IdempotencyService}
 * when a stored {@code response_body} can't be read back into the endpoint's response type.
 */
public class IdempotentReplayCorruptedException extends RuntimeException {
  public IdempotentReplayCorruptedException(Throwable cause) {
    super("stored idempotent response could not be re-read", cause);
  }
}
