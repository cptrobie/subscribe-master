package com.acuity.subscribemaster.auditlog;

/**
 * What an {@link AuditLog} entry records. Stored lowercase by {@link AuditActionConverter}, and
 * constrained in the database by {@code audit_logs_action_check} ({@code V10}, widened in {@code
 * V11}) -- adding a value here needs a matching migration, or inserting it fails at runtime.
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
  EMAIL_VERIFIED
}
