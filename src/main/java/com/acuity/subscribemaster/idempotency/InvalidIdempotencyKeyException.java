package com.acuity.subscribemaster.idempotency;

/**
 * The {@code Idempotency-Key} header was sent but is unusable -- blank, or longer than the service
 * accepts. Its message is returned to the client, so it says which rule failed. Mapped to a 400
 * {@code MALFORMED_REQUEST} by {@link com.acuity.subscribemaster.error.GlobalExceptionHandler}.
 */
public class InvalidIdempotencyKeyException extends RuntimeException {
  public InvalidIdempotencyKeyException(String message) {
    super(message);
  }
}
