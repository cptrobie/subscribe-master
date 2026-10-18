package com.acuity.subscribemaster.auditlog;

/**
 * Who performed an {@link AuditLog} entry's action -- a customer, a staff user, or the
 * payment-retry scheduler (a non-human system actor). Stored lowercase by {@link
 * ActorTypeConverter}, and constrained in the database by {@code audit_logs_actor_type_check}
 * ({@code V1}, widened in {@code V9}) -- adding a value here needs a matching migration, or
 * inserting it fails at runtime.
 */
public enum ActorType {
  CUSTOMER,
  STAFF,
  RETRY_SCHEDULER
}
