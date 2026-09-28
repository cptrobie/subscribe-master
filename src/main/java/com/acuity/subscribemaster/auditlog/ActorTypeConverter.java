package com.acuity.subscribemaster.auditlog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

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
