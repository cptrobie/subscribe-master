package com.acuity.subscribemaster.subscribe;

/**
 * A subscription's lifecycle state (FR-09), stored in {@code customer_subscriptions.status} as the
 * uppercase name -- a deliberate exception to the schema's lowercase convention, so it maps with no
 * converter ({@code V2}). Allowed transitions are enforced by {@link CustomerSubscription}.
 */
public enum SubscriptionStatus {
  ACTIVE,
  PAUSED,
  CANCELLED
}
