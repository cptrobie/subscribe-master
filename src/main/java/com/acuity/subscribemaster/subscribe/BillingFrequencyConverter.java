package com.acuity.subscribemaster.subscribe;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter(autoApply = true)
public class BillingFrequencyConverter implements AttributeConverter<BillingFrequency, String> {

  @Override
  public String convertToDatabaseColumn(BillingFrequency frequency) {
    return frequency == null ? null : frequency.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public BillingFrequency convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : BillingFrequency.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
