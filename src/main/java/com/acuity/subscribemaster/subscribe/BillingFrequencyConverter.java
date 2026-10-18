package com.acuity.subscribemaster.subscribe;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Maps {@link BillingFrequency} to the {@code customer_subscriptions.billing_frequency} column,
 * stored as the lowercase name (e.g. {@code semi_annual}) to match {@code V2}'s {@code CHECK}
 * constraint. Applied automatically to every {@code BillingFrequency} attribute.
 */
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
