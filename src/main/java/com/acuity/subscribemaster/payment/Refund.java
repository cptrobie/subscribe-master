package com.acuity.subscribemaster.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A full or partial refund of a {@link PaymentHistory} payment (table {@code refunds}, {@code V3})
 * -- FR-28, ARCHITECTURE.md &sect;4.4.
 *
 * <p>{@code amount} need not equal the original payment, and {@code reason} is mandatory free text.
 * {@code stripe_refund_id} is the reconciliation reference to Stripe's refund (&sect;4.1). {@code
 * payment_history_id} is a plain UUID, not a {@code @ManyToOne}.
 */
@Entity
@Table(name = "refunds")
public class Refund {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payment_history_id", nullable = false)
  private UUID paymentHistoryId;

  @Column(name = "amount", nullable = false)
  private BigDecimal amount;

  @Column(name = "reason", nullable = false)
  private String reason;

  @Column(name = "stripe_refund_id", unique = true)
  private String stripeRefundId;

  @Column(name = "refunded_at")
  private Instant refundedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public Refund() {}

  public UUID getId() {
    return id;
  }

  public UUID getPaymentHistoryId() {
    return paymentHistoryId;
  }

  public void setPaymentHistoryId(UUID paymentHistoryId) {
    this.paymentHistoryId = paymentHistoryId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getStripeRefundId() {
    return stripeRefundId;
  }

  public void setStripeRefundId(String stripeRefundId) {
    this.stripeRefundId = stripeRefundId;
  }

  public Instant getRefundedAt() {
    return refundedAt;
  }

  public void setRefundedAt(Instant refundedAt) {
    this.refundedAt = refundedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
