package com.acuity.subscribemaster.support;

/**
 * Thrown when a requested status change is not allowed from the entity's current state (e.g.
 * pausing a subscription that is not {@code ACTIVE}). Mapped to {@code 409
 * INVALID_STATE_TRANSITION} by {@code GlobalExceptionHandler}.
 */
public class InvalidStateTransitionException extends RuntimeException {
  public InvalidStateTransitionException(String message) {
    super(message);
  }
}
