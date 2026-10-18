package com.acuity.subscribemaster.payment;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Auto-applied JPA converter mapping {@link PaymentAttemptStatus} to {@code
 * payment_attempts.status}, stored as the lowercase enum name ({@code succeeded}/{@code failed}).
 */
@Converter(autoApply = true)
public class PaymentAttemptStatusConverter
    implements AttributeConverter<PaymentAttemptStatus, String> {

  @Override
  public String convertToDatabaseColumn(PaymentAttemptStatus status) {
    return status == null ? null : status.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public PaymentAttemptStatus convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : PaymentAttemptStatus.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
