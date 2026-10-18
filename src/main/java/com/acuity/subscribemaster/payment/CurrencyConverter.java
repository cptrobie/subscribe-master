package com.acuity.subscribemaster.payment;

import com.acuity.subscribemaster.subscribe.Currency;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Auto-applied JPA converter for {@link Currency}: stores the enum's name as-is (the uppercase ISO
 * 4217 code, e.g. {@code USD}), matching the uppercase values in the {@code CHECK} constraints on
 * the {@code CHAR(3)} currency columns created in {@code V2}. Unlike this package's status
 * converters, it does not lowercase.
 */
@Converter(autoApply = true)
public class CurrencyConverter implements AttributeConverter<Currency, String> {

  @Override
  public String convertToDatabaseColumn(Currency currency) {
    return currency == null ? null : currency.name();
  }

  @Override
  public Currency convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : Currency.valueOf(dbValue);
  }
}
