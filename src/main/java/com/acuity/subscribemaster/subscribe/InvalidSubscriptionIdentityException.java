package com.acuity.subscribemaster.subscribe;

/**
 * Thrown when an update would leave a subscription with neither a catalog provider nor a {@code
 * customName} -- i.e. clearing {@code customName} on a subscription that has no {@code providerId}.
 * Mapped to {@code 400 INVALID_SUBSCRIPTION_IDENTITY} by {@code GlobalExceptionHandler}.
 */
public class InvalidSubscriptionIdentityException extends RuntimeException {
  public InvalidSubscriptionIdentityException(String message) {
    super(message);
  }
}
