package com.acuity.subscribemaster.payment;

/**
 * Outcome of a single {@link PaymentAttempt}, stored in {@code payment_attempts.status} ({@code
 * V3}), whose {@code CHECK} allows only these two values.
 */
public enum PaymentAttemptStatus {
  SUCCEEDED,
  FAILED
}
