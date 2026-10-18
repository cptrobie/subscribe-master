package com.acuity.subscribemaster.subscribe;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Maps {@link CancellationReason} to the {@code customer_subscriptions.cancellation_reason} column
 * ({@code V4}), stored as the lowercase name (e.g. {@code payment_failure}) to match that column's
 * {@code CHECK} constraint. Applied automatically to every {@code CancellationReason} attribute.
 */
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
