package com.acuity.subscribemaster.payment;

/**
 * Overall status of a {@link PaymentHistory} row, stored in {@code payment_history.status}. The
 * {@code CHECK} constraint started with {@code pending}/{@code succeeded}/{@code failed} in {@code
 * V2}; {@code V3} added the two refund states (FR-28).
 */
public enum PaymentStatus {
  PENDING,
  SUCCEEDED,
  FAILED,
  REFUNDED,
  PARTIALLY_REFUNDED
}
