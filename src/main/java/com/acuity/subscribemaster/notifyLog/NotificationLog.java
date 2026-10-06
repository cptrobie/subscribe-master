package com.acuity.subscribemaster.notifyLog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "notification_log")
public class NotificationLog {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "subscription_id", nullable = false, updatable = false)
  private UUID subscriptionId;

  @Column(name = "payment_history_id", updatable = false)
  private UUID paymentHistoryId;

  @Column(name = "notification_type", nullable = false, updatable = false)
  private NotificationType notificationType;

  @Column(nullable = false, updatable = false)
  private NotificationChannel channel;

  @Column(name = "sent_at")
  private Instant sentAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public NotificationLog(
      UUID subscriptionId,
      UUID paymentHistoryId,
      NotificationType notificationType,
      NotificationChannel channel,
      Instant sentAt) {
    this.subscriptionId = subscriptionId;
    this.paymentHistoryId = paymentHistoryId;
    this.notificationType = notificationType;
    this.channel = channel;
    this.sentAt = sentAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSubscriptionId() {
    return subscriptionId;
  }

  public UUID getPaymentHistoryId() {
    return paymentHistoryId;
  }

  public NotificationType getNotificationType() {
    return notificationType;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
