package com.acuity.subscribemaster.notifylog;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@link NotificationLog} entries. Runs in its own transaction ({@code REQUIRES_NEW}), like
 * {@code AuditLogService}, so the record survives a rollback of the caller's transaction.
 */
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
