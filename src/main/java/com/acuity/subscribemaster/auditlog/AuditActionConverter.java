package com.acuity.subscribemaster.auditlog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Maps {@link AuditAction} to {@code audit_logs.action}, stored as the enum's lowercase name (e.g.
 * {@code ACCOUNT_LOCKED} as {@code account_locked}) to match the values allowed by {@code
 * audit_logs_action_check} ({@code V10}, widened in {@code V11}). Applied automatically to every
 * {@link AuditAction} attribute.
 */
@Converter(autoApply = true)
public class AuditActionConverter implements AttributeConverter<AuditAction, String> {

  @Override
  public String convertToDatabaseColumn(AuditAction action) {
    return action == null ? null : action.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public AuditAction convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : AuditAction.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
