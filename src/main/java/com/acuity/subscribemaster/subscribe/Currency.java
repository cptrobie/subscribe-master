package com.acuity.subscribemaster.subscribe;

/**
 * A subscription's billing currency ({@code customer_subscriptions.currency}, {@code CHAR(3)}). The
 * seven ISO 4217 codes mirror the curated list that {@code V2}'s {@code CHECK} constraint allows.
 */
public enum Currency {
  CAD,
  CNY,
  EUR,
  GBP,
  JPY,
  USD,
  UZS
}
