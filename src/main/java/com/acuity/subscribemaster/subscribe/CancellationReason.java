package com.acuity.subscribemaster.subscribe;

/**
 * Why a subscription was cancelled, stored in {@code customer_subscriptions.cancellation_reason}
 * ({@code V4}) as the lowercase name via {@link SubscriptionCancellationReasonConverter}.
 *
 * <p>A reason, not a status: a payment-failure cancellation is still {@link
 * SubscriptionStatus#CANCELLED}, rather than a new status value that every {@code status} check
 * would have to know about (see {@code design-docs/payment_retry_and_dunning.md}).
 */
public enum CancellationReason {
  CUSTOMER_REQUESTED,
  PAYMENT_FAILURE,
  STAFF_ACTION
}
