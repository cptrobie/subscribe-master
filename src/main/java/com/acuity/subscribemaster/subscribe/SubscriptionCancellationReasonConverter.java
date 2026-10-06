package com.acuity.subscribemaster.subscribe;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter(autoApply = true)
public class SubscriptionCancellationReasonConverter
    implements AttributeConverter<CancellationReason, String> {

  @Override
  public String convertToDatabaseColumn(CancellationReason reason) {
    return reason == null ? null : reason.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public CancellationReason convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : CancellationReason.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
