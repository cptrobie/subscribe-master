package com.acuity.subscribemaster.payment;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

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
