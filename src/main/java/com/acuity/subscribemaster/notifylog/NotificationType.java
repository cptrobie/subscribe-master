package com.acuity.subscribemaster.notifylog;

/**
 * What kind of notification a {@link NotificationLog} entry records ({@code
 * notification_log.notification_type}). Grows as the payment and reminder notifications (FR-19,
 * FR-29) are built.
 */
public enum NotificationType {
  PAYMENT_RETRIES_EXHAUSTED
}
