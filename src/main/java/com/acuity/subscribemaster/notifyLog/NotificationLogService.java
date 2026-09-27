package com.acuity.subscribemaster.notifyLog;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationLogService {

  private final NotificationRepository notifyRepo;

  public NotificationLogService(NotificationRepository notifyRepo) {
    this.notifyRepo = notifyRepo;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordNotification(
      UUID subscriptionId,
      UUID paymentHistoryId,
      NotificationType notificationType,
      NotificationChannel channel,
      Instant sentAt) {
    notifyRepo.save(
        new NotificationLog(subscriptionId, paymentHistoryId, notificationType, channel, sentAt));
  }
}
