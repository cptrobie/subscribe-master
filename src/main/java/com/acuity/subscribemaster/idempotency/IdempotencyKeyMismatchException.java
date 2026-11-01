package com.acuity.subscribemaster.idempotency;

/**
 * An {@code Idempotency-Key} was reused with a request whose hash differs from the one stored for
 * it -- a client bug (one key for two different requests), not a retry, so the stored response is
 * not replayed. Mapped to a 422 {@code IDEMPOTENCY_KEY_MISMATCH} by {@link
 * com.acuity.subscribemaster.error.GlobalExceptionHandler}, following the IETF Idempotency-Key
 * draft. The message is returned to the client and tells them to use a new key.
 */
public class IdempotencyKeyMismatchException extends RuntimeException {
  public IdempotencyKeyMismatchException() {
    super(
        "This Idempotency-Key was already used with a different request. Use a new key for a new request.");
  }
}
