package com.acuity.subscribemaster.payment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"payment_history_id", "attempt_number"}))
public class PaymentAttempt {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payment_history_id", nullable = false)
  private UUID paymentHistoryId;

  @Column(name = "attempt_number")
  private int attemptNumber;

  @Column(name = "stripe_payment_intent_id")
  private String stripePaymentIntentId;

  private PaymentAttemptStatus status;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "attempted_at")
  private Instant attemptedAt;

  public PaymentAttempt() {}

  public UUID getId() {
    return id;
  }

  public UUID getPaymentHistoryId() {
    return paymentHistoryId;
  }

  public void setPaymentHistoryId(UUID paymentHistoryId) {
    this.paymentHistoryId = paymentHistoryId;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public void setAttemptNumber(int attemptNumber) {
    this.attemptNumber = attemptNumber;
  }

  public String getStripePaymentIntentId() {
    return stripePaymentIntentId;
  }

  public void setStripePaymentIntentId(String stripePaymentIntentId) {
    this.stripePaymentIntentId = stripePaymentIntentId;
  }

  public PaymentAttemptStatus getStatus() {
    return status;
  }

  public void setStatus(PaymentAttemptStatus status) {
    this.status = status;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public Instant getAttemptedAt() {
    return attemptedAt;
  }

  public void setAttemptedAt(Instant attemptedAt) {
    this.attemptedAt = attemptedAt;
  }
}
