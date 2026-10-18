package com.acuity.subscribemaster.payment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One individual charge attempt against a {@link PaymentHistory} obligation (table {@code
 * payment_attempts}, {@code V3}) -- FR-29, ARCHITECTURE.md &sect;4.3.
 *
 * <p>Each try gets its own row with its own {@code attempt_number} (1-3, unique per {@code
 * payment_history_id}), Stripe payment intent id, outcome and {@code failure_reason}; the parent's
 * {@code attempt_count} and {@code status} hold the cumulative state. {@code payment_history_id} is
 * a plain UUID, not a {@code @ManyToOne}.
 */
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

  @Column(name = "attempt_number", nullable = false)
  private short attemptNumber = 1;

  @Column(name = "stripe_payment_intent_id")
  private String stripePaymentIntentId;

  @Column(name = "status", nullable = false)
  private PaymentAttemptStatus status;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "attempted_at", nullable = false)
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

  public short getAttemptNumber() {
    return attemptNumber;
  }

  public void setAttemptNumber(short attemptNumber) {
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
