package com.acuity.subscribemaster.auditlog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Maps {@link ActorType} to {@code audit_logs.actor_type}, stored as the enum's lowercase name
 * (e.g. {@code RETRY_SCHEDULER} as {@code retry_scheduler}) to match the values allowed by {@code
 * audit_logs_actor_type_check} ({@code V1}, widened in {@code V9}). Applied automatically to every
 * {@link ActorType} attribute.
 */
@Converter(autoApply = true)
public class ActorTypeConverter implements AttributeConverter<ActorType, String> {

  @Override
  public String convertToDatabaseColumn(ActorType actorType) {
    return actorType == null ? null : actorType.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public ActorType convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : ActorType.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
