package com.acuity.subscribemaster.payment;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Auto-applied JPA converter mapping {@link PaymentStatus} to {@code payment_history.status},
 * stored as the lowercase enum name (e.g. {@code partially_refunded}).
 */
@Converter(autoApply = true)
public class PaymentStatusConverter implements AttributeConverter<PaymentStatus, String> {
  @Override
  public String convertToDatabaseColumn(PaymentStatus status) {
    return status == null ? null : status.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public PaymentStatus convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : PaymentStatus.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
