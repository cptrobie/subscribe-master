package com.acuity.subscribemaster.message;

/**
 * A message could not be delivered (the provider rejected it, couldn't be reached, or the message
 * couldn't be built). The single failure type the {@code message} module exposes, so callers never
 * handle mail-library (or, later, Twilio) exceptions directly.
 *
 * <p>Treated as a tolerable, potentially temporary failure -- e.g. registration still succeeds
 * (FR-30). Its message is deliberately generic, since provider error text can echo the recipient's
 * address (NFR-23); the original exception is kept as the cause. Misconfiguration (no sender for a
 * channel) is a bug and throws {@link IllegalStateException} instead, so it can't be silently
 * absorbed by callers that catch this.
 */
public class MessageDeliveryException extends RuntimeException {
  public MessageDeliveryException(String message, Throwable cause) {
    super(message, cause);
  }
}
