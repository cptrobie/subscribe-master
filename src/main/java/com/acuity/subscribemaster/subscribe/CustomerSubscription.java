package com.acuity.subscribemaster.subscribe;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "customer_subscriptions")
public class CustomerSubscription {

  public CustomerSubscription() {}

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private UUID customerId;

  @Column(name = "provider_id", updatable = false)
  private UUID providerId;

  @Column(name = "custom_name")
  private String customName;

  private String category;

  @Column(name = "account_identifier")
  private String accountIdentifier;

  @Column(name = "payment_method_id", updatable = false)
  private UUID paymentMethodId;

  @Enumerated(EnumType.STRING)
  private Currency currency;

  private BigDecimal amount;

  @Column(name = "billing_frequency")
  private BillingFrequency billingFrequency;

  @Column(name = "billing_interval_days")
  private Integer billingIntervalDays;

  @Column(name = "next_payment_date")
  private LocalDate nextPaymentDate;

  @Enumerated(EnumType.STRING)
  private SubscriptionStatus status;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version private int version;

  @Column(name = "cancellation_reason")
  private CancellationReason cancellationReason;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  // --- Status-transition helpers -------------------------------------
  // Keep status changes and their dependent fields atomic from the
  // caller's point of view, rather than exposing setStatus() alone and
  // relying on every call site to remember what else has to change.
  /**
   * Suspends billing. Clears nextPaymentDate so a paused subscription can never be picked up by
   * date-driven billing checks — resuming always requires a freshly computed date rather than
   * resurrecting a stale one.
   */
  public void pause() {
    this.status = SubscriptionStatus.PAUSED;
    this.nextPaymentDate = null;
  }

  /**
   * Reactivates billing as of the given date. nextPaymentDate is required (never inferred here)
   * because pause() always clears it — the caller (service layer) owns the "how do we recompute
   * this" logic.
   *
   * @throws IllegalArgumentException if nextPaymentDate is null
   */
  public void resume(LocalDate nextPaymentDate) {
    if (nextPaymentDate == null) {
      throw new IllegalArgumentException("nextPaymentDate is required to resume a subscription");
    }
    this.status = SubscriptionStatus.ACTIVE;
    this.nextPaymentDate = nextPaymentDate;
  }

  /**
   * Cancels the subscription. Clears nextPaymentDate for the same reason
   * pause() does — a cancelled row should never look "due" to a date-driven
   * billing check that only inspects nextPaymentDate without also checking
   * status.
   */
  public void cancel(Instant cancelledAt, CancellationReason reason) {
    this.status = SubscriptionStatus.CANCELLED;
    this.cancelledAt = cancelledAt;
    this.nextPaymentDate = null;
    this.cancellationReason = reason;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public void setCustomerId(UUID customerId) {
    this.customerId = customerId;
  }

  public UUID getProviderId() {
    return providerId;
  }

  public void setProviderId(UUID providerId) {
    this.providerId = providerId;
  }

  public String getCustomName() {
    return customName;
  }

  public void setCustomName(String customName) {
    this.customName = customName;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getAccountIdentifier() {
    return accountIdentifier;
  }

  public void setAccountIdentifier(String accountIdentifier) {
    this.accountIdentifier = accountIdentifier;
  }

  public UUID getPaymentMethodId() {
    return paymentMethodId;
  }

  public void setPaymentMethodId(UUID paymentMethodId) {
    this.paymentMethodId = paymentMethodId;
  }

  public Currency getCurrency() {
    return currency;
  }

  public void setCurrency(Currency currency) {
    this.currency = currency;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public BillingFrequency getBillingFrequency() {
    return billingFrequency;
  }

  public void setBillingFrequency(BillingFrequency billingFrequency) {
    this.billingFrequency = billingFrequency;
  }

  public Integer getBillingIntervalDays() {
    return billingIntervalDays;
  }

  public void setBillingIntervalDays(Integer billingIntervalDays) {
    this.billingIntervalDays = billingIntervalDays;
  }

  public LocalDate getNextPaymentDate() {
    return nextPaymentDate;
  }

  public void setNextPaymentDate(LocalDate nextPaymentDate) {
    this.nextPaymentDate = nextPaymentDate;
  }

  public SubscriptionStatus getStatus() {
    return status;
  }

  public void setStatus(SubscriptionStatus status) {
    this.status = status;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public int getVersion() {
    return version;
  }

  public CancellationReason getCancellationReason() {
    return cancellationReason;
  }

  public void setCancellationReason(CancellationReason cancellationReason) {
    this.cancellationReason = cancellationReason;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public void setCancelledAt(Instant cancelledAt) {
    this.cancelledAt = cancelledAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
