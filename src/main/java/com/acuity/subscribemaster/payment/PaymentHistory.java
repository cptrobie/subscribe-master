package com.acuity.subscribemaster.payment;

import com.acuity.subscribemaster.subscribe.Currency;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "payment_history")
public class PaymentHistory {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "subscription_id")
  private UUID subscriptionId;

  @Column(name = "payment_method_id")
  private UUID paymentMethodId;

  @Version private int version;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;

  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  private Currency currency;

  @Column(name = "base_currency_amount")
  private BigDecimal baseCurrencyAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "base_currency")
  private Currency baseCurrency;

  @Column(name = "exchange_rate_applied")
  private BigDecimal exchangeRateApplied;

  private PaymentStatus status;

  @Column(name = "scheduled_at", nullable = false)
  private Instant scheduledAt;

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

  public int getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(int paymentCount) {
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
