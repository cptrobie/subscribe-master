package com.acuity.subscribemaster.subscribe;

import com.acuity.subscribemaster.support.InvalidStateTransitionException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A customer's tracked subscription (table {@code customer_subscriptions}, created in {@code
 * V2__subscriptions_and_payments.sql}; {@code version}, {@code category} and {@code
 * cancellation_reason} added in {@code V4}) -- FR-07. It is identified either by {@code
 * provider_id} (the {@link SubscriptionProvider} catalog) or by {@code custom_name}
 * (ARCHITECTURE.md &sect;3.2).
 *
 * <p>Status changes go through {@link #pause()}, {@link #resume(LocalDate)} and {@link
 * #cancel(Instant, CancellationReason)} rather than a status setter, enforcing FR-09's transitions
 * (see {@code design-docs/subscription_status_transitions.md}): only {@code ACTIVE} can pause, only
 * {@code PAUSED} can resume, and {@code CANCELLED} is terminal; anything else throws {@link
 * InvalidStateTransitionException}.
 *
 * <p>{@code @Version} provides optimistic locking (NFR-04, ARCHITECTURE.md &sect;5.1). {@code
 * deleted_at} is the soft-delete marker (FR-11, ARCHITECTURE.md &sect;3.1); nothing here sets it
 * yet.
 */
@Entity
@Table(name = "customer_subscriptions")
public class CustomerSubscription {

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

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", columnDefinition = "char(3)", nullable = false)
  private Currency currency;

  @Column(name = "amount", nullable = false)
  private BigDecimal amount;

  @Column(name = "billing_frequency", nullable = false)
  private BillingFrequency billingFrequency;

  @Column(name = "billing_interval_days")
  private Integer billingIntervalDays;

  @Column(name = "next_payment_date", nullable = false)
  private LocalDate nextPaymentDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private SubscriptionStatus status;

  @Column(name = "started_at", nullable = false)
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

  public CustomerSubscription() {}

  public CustomerSubscription(
      UUID customerId,
      UUID providerId,
      String customName,
      String accountIdentifier,
      UUID paymentMethodId,
      Currency currency,
      BigDecimal amount,
      BillingFrequency billingFrequency,
      Integer billingIntervalDays,
      LocalDate nextPaymentDate,
      SubscriptionStatus status,
      Instant startedAt) {
    this.customerId = customerId;
    this.providerId = providerId;
    this.customName = customName;
    this.accountIdentifier = accountIdentifier;
    this.paymentMethodId = paymentMethodId;
    this.currency = currency;
    this.amount = amount;
    this.billingFrequency = billingFrequency;
    this.billingIntervalDays = billingIntervalDays;
    this.nextPaymentDate = nextPaymentDate;
    this.status = status;
    this.startedAt = startedAt;
  }

  // --- Status-transition helpers -------------------------------------
  // Keep status changes and their dependent fields atomic from the
  // caller's point of view, rather than exposing setStatus() alone and
  // relying on every call site to remember what else has to change.
  /**
   * Suspends billing. Leaves nextPaymentDate unchanged — the original billing cadence and
   * day-of-cycle are preserved, not reset. This is the anchor resume() depends on to determine
   * whether a payment was actually skipped during the pause.
   *
   * @throws InvalidStateTransitionException if state is not equal to active
   */
  public void pause() {
    if (status != SubscriptionStatus.ACTIVE) {
      throw new InvalidStateTransitionException("Only an Active Status can change to pause");
    }
    this.status = SubscriptionStatus.PAUSED;
  }

  /**
   * Cancels the subscription. Clears nextPaymentDate -- unlike pause(), which deliberately keeps it
   * as the resume anchor -- so a cancelled row never looks "due" to a date-driven billing check
   * that only inspects nextPaymentDate without also checking status. (V4's migration comment says
   * pause() clears it too; that comment is out of date, but V4 can't be edited once applied.)
   *
   * @throws InvalidStateTransitionException if state already equal to cancel
   */
  public void cancel(Instant cancelledAt, CancellationReason reason) {
    if (status == SubscriptionStatus.CANCELLED) {
      throw new InvalidStateTransitionException("This subscription has already been cancelled");
    }
    this.status = SubscriptionStatus.CANCELLED;
    this.cancelledAt = cancelledAt;
    this.nextPaymentDate = null;
    this.cancellationReason = reason;
  }

  /**
   * Reactivates billing as of the given date. nextPaymentDate is required (never inferred here)
   * because pause() leaves it untouched. — the caller (service layer) owns the "how do we recompute
   * this" logic.
   *
   * @throws InvalidStateTransitionException if state is not equal to paused
   * @throws IllegalArgumentException if nextPaymentDate is null
   */
  public void resume(LocalDate nextPaymentDate) {
    if (status != SubscriptionStatus.PAUSED) {
      throw new InvalidStateTransitionException("Only a paused subscription can be resumed");
    }
    if (nextPaymentDate == null) {
      throw new IllegalArgumentException("nextPaymentDate is required to resume a subscription");
    }
    this.status = SubscriptionStatus.ACTIVE;
    this.nextPaymentDate = nextPaymentDate;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getProviderId() {
    return providerId;
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

  public String getAccountIdentifier() {
    return accountIdentifier;
  }

  public void setAccountIdentifier(String accountIdentifier) {
    this.accountIdentifier = accountIdentifier;
  }

  public UUID getPaymentMethodId() {
    return paymentMethodId;
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

  public SubscriptionStatus getStatus() {
    return status;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public int getVersion() {
    return version;
  }

  public CancellationReason getCancellationReason() {
    return cancellationReason;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
