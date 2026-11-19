package com.acuity.subscribemaster.auditlog;

/**
 * What an {@link AuditLog} entry records. Stored lowercase by {@link AuditActionConverter}, and
 * constrained in the database by {@code audit_logs_action_check} ({@code V10}, widened in {@code
 * V11} and {@code V13}) -- adding a value here needs a matching migration, or inserting it fails at
 * runtime.
 *
 * <p>An action names what happened, not who it happened to: {@link #ACCOUNT_LOCKED} covers both a
 * customer and a staff lockout (FR-06), with the entry's {@link ActorType} telling them apart. Only
 * actions that exist solely for staff carry a {@code STAFF_} prefix.
 */
public enum AuditAction {
  REGISTERED,
  ACCOUNT_LOCKED,
  REGISTRATION_REJECTED,
  SUBSCRIPTION_CREATED,
  SUBSCRIPTION_CANCELLED,
  SUBSCRIPTION_PAUSED,
  SUBSCRIPTION_RESUMED,
  SUBSCRIPTION_UPDATED,
  EMAIL_VERIFICATION_SENT,
  EMAIL_VERIFICATION_SEND_FAILED,
  EMAIL_VERIFIED,
  EMAIL_CHANGED,
  STAFF_LOGGED_IN,
  STAFF_USER_CREATED
}
