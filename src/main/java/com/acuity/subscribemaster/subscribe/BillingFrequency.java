package com.acuity.subscribemaster.subscribe;

/**
 * How often a subscription bills (FR-07). Stored in {@code
 * customer_subscriptions.billing_frequency} as the lowercase name via {@link
 * BillingFrequencyConverter}. {@link #CUSTOM} bills every {@code billing_interval_days} days
 * instead of a fixed period; {@code V2}'s {@code chk_custom_interval} requires that column for it.
 */
public enum BillingFrequency {
  WEEKLY,
  MONTHLY,
  QUARTERLY,
  SEMI_ANNUAL,
  ANNUAL,
  CUSTOM
}
