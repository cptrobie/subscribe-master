package com.acuity.subscribemaster.payment;

import com.acuity.subscribemaster.subscribe.Currency;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One billing-cycle payment obligation for a subscription (table {@code payment_history}, {@code
 * V2}) -- the parent of its {@link PaymentAttempt}s and {@link Refund}s (FR-28, FR-29).
 *
 * <p>An immutable snapshot, not a live reference: {@code amount}, {@code base_currency_amount} and
 * {@code exchange_rate_applied} record what was charged at payment time and must not be re-derived
 * from the subscription or {@code exchange_rates} (ARCHITECTURE.md &sect;4.2). {@code
 * attempt_count} (capped at 3, {@code V3}) and {@code last_attempted_at} ({@code V8}) are
 * denormalized from {@code payment_attempts} for the retry logic (&sect;4.3). {@code version}
 * ({@code V4}) is the optimistic-locking column (NFR-04, &sect;5.1). {@code subscription_id} and
 * {@code payment_method_id} are plain UUIDs, not entity associations.
 */
@Entity
@Table(name = "payment_history")
public class PaymentHistory {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "subscription_id", nullable = false)
  private UUID subscriptionId;

  @Column(name = "payment_method_id")
  private UUID paymentMethodId;

  @Column(name = "version")
  @Version
  private int version;

  @Column(name = "attempt_count", nullable = false)
  private short attemptCount = 0;

  @Column(name = "amount", nullable = false)
  private BigDecimal amount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", columnDefinition = "char(3)", nullable = false)
  private Currency currency;

  @Column(name = "base_currency_amount")
  private BigDecimal baseCurrencyAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "base_currency", columnDefinition = "char(3)")
  private Currency baseCurrency;

  @Column(name = "exchange_rate_applied")
  private BigDecimal exchangeRateApplied;

  @Column(name = "status", nullable = false)
  private PaymentStatus status;

  @Column(name = "scheduled_at", nullable = false)
  private Instant scheduledAt;

  @Column(name = "last_attempted_at")
  private Instant lastAttemptedAt;

  @Column(name = "paid_at")
  private Instant paidAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public PaymentHistory() {}

  public UUID getId() {
    return id;
  }

  public UUID getSubscriptionId() {
    return subscriptionId;
  }

  public void setSubscriptionId(UUID subscriptionId) {
    this.subscriptionId = subscriptionId;
  }

  public UUID getPaymentMethodId() {
    return paymentMethodId;
  }

  public void setPaymentMethodId(UUID paymentMethodId) {
    this.paymentMethodId = paymentMethodId;
  }

  public int getVersion() {
    return version;
  }

  public short getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(short paymentCount) {
    this.attemptCount = paymentCount;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public Currency getCurrency() {
    return currency;
  }

  public void setCurrency(Currency currency) {
    this.currency = currency;
  }

  public BigDecimal getBaseCurrencyAmount() {
    return baseCurrencyAmount;
  }

  public void setBaseCurrencyAmount(BigDecimal baseCurrencyAmount) {
    this.baseCurrencyAmount = baseCurrencyAmount;
  }

  public Currency getBaseCurrency() {
    return baseCurrency;
  }

  public void setBaseCurrency(Currency baseCurrency) {
    this.baseCurrency = baseCurrency;
  }

  public BigDecimal getExchangeRateApplied() {
    return exchangeRateApplied;
  }

  public void setExchangeRateApplied(BigDecimal exchangeRateApplied) {
    this.exchangeRateApplied = exchangeRateApplied;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public void setStatus(PaymentStatus status) {
    this.status = status;
  }

  public Instant getScheduledAt() {
    return scheduledAt;
  }

  public void setScheduledAt(Instant scheduledAtAt) {
    this.scheduledAt = scheduledAtAt;
  }

  public Instant getLastAttemptedAt() {
    return lastAttemptedAt;
  }

  public void setLastAttemptedAt(Instant lastAttemptedAt) {
    this.lastAttemptedAt = lastAttemptedAt;
  }

  public Instant getPaidAt() {
    return paidAt;
  }

  public void setPaidAt(Instant paidAt) {
    this.paidAt = paidAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
