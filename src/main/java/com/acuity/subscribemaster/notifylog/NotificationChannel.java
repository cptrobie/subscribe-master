package com.acuity.subscribemaster.notifylog;

/**
 * The channel a {@link NotificationLog} entry was sent on ({@code notification_log.channel}).
 *
 * <p>Known gap: {@code V4}'s CHECK constraint only allows {@code 'log'} and {@code 'email'}, so
 * recording {@code SMS} fails at runtime until a migration widens it. FR-20 folds this package into
 * {@code message}, where {@code MessageChannel} replaces this enum (see FR-20's notes).
 */
public enum NotificationChannel {
  LOG,
  EMAIL,
  SMS
}
