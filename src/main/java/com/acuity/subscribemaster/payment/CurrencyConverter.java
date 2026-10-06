package com.acuity.subscribemaster.payment;

import com.acuity.subscribemaster.subscribe.Currency;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

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
