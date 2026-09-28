package com.acuity.subscribemaster.auditlog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

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
