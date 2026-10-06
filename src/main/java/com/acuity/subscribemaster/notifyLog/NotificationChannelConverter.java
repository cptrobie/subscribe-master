package com.acuity.subscribemaster.notifyLog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

@Converter(autoApply = true)
public class NotificationChannelConverter
    implements AttributeConverter<NotificationChannel, String> {

  @Override
  public String convertToDatabaseColumn(NotificationChannel channel) {
    return channel == null ? null : channel.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public NotificationChannel convertToEntityAttribute(String dbValue) {
    return dbValue == null ? null : NotificationChannel.valueOf(dbValue.toUpperCase(Locale.ROOT));
  }
}
