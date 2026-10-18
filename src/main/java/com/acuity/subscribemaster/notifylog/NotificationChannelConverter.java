package com.acuity.subscribemaster.notifylog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Maps {@link NotificationChannel} to and from the lowercase string stored in {@code
 * notification_log.channel}.
 */
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
