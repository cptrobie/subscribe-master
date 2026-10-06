package com.acuity.subscribemaster.subscribe;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionRequest;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionUpdateRequest;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import com.acuity.subscribemaster.support.InvalidStateTransitionException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

  private final CustomerSubscriptionRepository subscriptionRepo;
  private final SubscriptionProviderRepository providerRepo;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);

  public SubscriptionService(
      CustomerSubscriptionRepository subscriptionRepo,
      SubscriptionProviderRepository subscriptionProviderRepository,
      AuditLogService auditLogService) {
    this.subscriptionRepo = subscriptionRepo;
    this.providerRepo = subscriptionProviderRepository;
    this.auditLogService = auditLogService;
  }

  /**
   * Persists a new subscription for the given customer.
   *
   * <p>When {@code request.providerId()} is present, the referenced {@link SubscriptionProvider} is
   * looked up to resolve its display name for the response; if no provider matches, an {@link
   * EntityNotFoundException} is thrown. When {@code providerId} is absent, the subscription relies
   * solely on {@code customName} (validated as required in that case by {@link
   * SubscriptionRequest}), and no provider lookup is performed.
   *
   * @param customerId the authenticated customer's ID, derived from the JWT — never client-supplied
   * @param request the validated subscription creation request
   * @return the created subscription as a response DTO
   * @throws EntityNotFoundException if {@code request.providerId()} is present but does not match a
   *     known provider
   */
  @Transactional
  public SubscriptionResponse create(
      UUID customerId, SubscriptionRequest request, String ipAddress) {
    var providerName = resolveProviderName(request.providerId());

    var subscription =
        new CustomerSubscription(
            customerId,
            request.providerId(),
            request.customName(),
            request.accountIdentifier(),
            request.paymentMethodId(),
            request.currency(),
            request.amount(),
            request.billingFrequency(),
            request.billingIntervalDays(),
            request.nextPaymentDate(),
            SubscriptionStatus.ACTIVE,
            Instant.now());
    CustomerSubscription saved = subscriptionRepo.save(subscription);

    logger.info("Subscription {} created for customer {}", saved.getId(), customerId);

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        customerId,
        AuditAction.SUBSCRIPTION_CREATED,
        "subscription",
        saved.getId(),
        ipAddress);

    return toResponse(saved, providerName);
  }

  @Transactional
  public SubscriptionResponse pause(UUID customerId, UUID subscriptionId, String ipAddress) {
    var subscription =
        subscriptionRepo
            .findByIdAndCustomerId(subscriptionId, customerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Subscription not found: " + subscriptionId));

    if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
      throw new InvalidStateTransitionException("Only an Active Status can change to pause");
    }
    subscription.pause();

    var providerName = resolveProviderName(subscription.getProviderId());
    CustomerSubscription saved = subscriptionRepo.save(subscription);

    logger.info("Subscription {} paused", subscription.getId());

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        customerId,
        AuditAction.SUBSCRIPTION_PAUSED,
        "subscription",
        subscriptionId,
        ipAddress);

    return toResponse(saved, providerName);
  }

  @Transactional
  public SubscriptionResponse cancel(UUID customerId, UUID subscriptionId, String ipAddress) {
    var subscription =
        subscriptionRepo
            .findByIdAndCustomerId(subscriptionId, customerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Subscription not found: " + subscriptionId));

    if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
      throw new InvalidStateTransitionException(
          "Subscription " + subscriptionId + " is already cancelled");
    }
    subscription.cancel(Instant.now(), CancellationReason.CUSTOMER_REQUESTED);

    var providerName = resolveProviderName(subscription.getProviderId());
    CustomerSubscription saved = subscriptionRepo.save(subscription);

    logger.info("Subscription {} cancelled", subscription.getId());

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        customerId,
        AuditAction.SUBSCRIPTION_CANCELLED,
        "subscription",
        subscriptionId,
        ipAddress);

    return toResponse(saved, providerName);
  }

  @Transactional
  public SubscriptionResponse resume(UUID customerId, UUID subscriptionId, String ipAddress) {
    var subscription =
        subscriptionRepo
            .findByIdAndCustomerId(subscriptionId, customerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Subscription not found: " + subscriptionId));

    if (subscription.getStatus() != SubscriptionStatus.PAUSED) {
      throw new InvalidStateTransitionException(
          "Subscription " + subscriptionId + " can only be resumed from a paused state");
    }

    subscription.resume(calculateNextPaymentDate(subscription));

    var providerName = resolveProviderName(subscription.getProviderId());
    CustomerSubscription saved = subscriptionRepo.save(subscription);

    logger.info("Subscription {} resumed", subscription.getId());

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        customerId,
        AuditAction.SUBSCRIPTION_RESUMED,
        "subscription",
        subscriptionId,
        ipAddress);

    return toResponse(saved, providerName);
  }

  @Transactional
  public SubscriptionResponse update(
      UUID customerId, UUID subscriptionId, SubscriptionUpdateRequest request, String ipAddress) {
    var subscription =
        subscriptionRepo
            .findByIdAndCustomerId(subscriptionId, customerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Subscription not found: " + subscriptionId));

    if (request.customName() == null && subscription.getProviderId() == null) {
      throw new InvalidSubscriptionIdentityException(
          "Subscription "
              + subscriptionId
              + " has no provider on file — customName cannot be cleared");
    }

    subscription.setCustomName(request.customName());
    subscription.setAccountIdentifier(request.accountIdentifier());
    subscription.setAmount(request.amount());
    subscription.setCurrency(request.currency());
    subscription.setBillingFrequency(request.billingFrequency());
    subscription.setBillingIntervalDays(request.billingIntervalDays());

    CustomerSubscription updated = subscriptionRepo.save(subscription);

    logger.info("Subscription {} updated for customer {}", updated.getId(), customerId);

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        customerId,
        AuditAction.SUBSCRIPTION_UPDATED,
        "subscription",
        updated.getId(),
        ipAddress);

    return toResponse(updated, resolveProviderName(subscription.getProviderId()));
  }

  @Transactional(readOnly = true)
  public SubscriptionResponse getOne(UUID customerId, UUID subscriptionId) {
    CustomerSubscription subscription =
        subscriptionRepo
            .findByIdAndCustomerId(subscriptionId, customerId)
            .orElseThrow(
                () -> new EntityNotFoundException("Subscription not found: " + subscriptionId));

    return toResponse(subscription, resolveProviderName(subscription.getProviderId()));
  }

  @Transactional(readOnly = true)
  public List<SubscriptionResponse> getAll(UUID customerId) {
    return subscriptionRepo.findByCustomerId(customerId).stream()
        .map(s -> toResponse(s, resolveProviderName(s.getProviderId())))
        .toList();
  }

  private String resolveProviderName(UUID providerId) {
    if (providerId == null) {
      return null;
    }
    return providerRepo
        .findById(providerId)
        .map(SubscriptionProvider::getName)
        .orElseThrow(() -> new EntityNotFoundException("Provider not found: " + providerId));
  }

  private LocalDate calculateNextPaymentDate(CustomerSubscription subscription) {
    var nxtPayDte = subscription.getNextPaymentDate();

    while (nxtPayDte.isBefore(LocalDate.now())) {
      nxtPayDte =
          BillingFrequencyDateMapper.advanceBillingDateByFrequency(
              nxtPayDte, subscription.getBillingFrequency(), subscription.getBillingIntervalDays());
    }
    return nxtPayDte;
  }

  SubscriptionResponse toResponse(CustomerSubscription subscription, String providerName) {
    return new SubscriptionResponse(
        subscription.getId(),
        subscription.getCustomerId(),
        subscription.getProviderId(),
        providerName,
        subscription.getCustomName(),
        subscription.getCategory(),
        subscription.getCurrency(),
        subscription.getAmount(),
        subscription.getBillingFrequency(),
        subscription.getNextPaymentDate(),
        subscription.getStatus(),
        subscription.getCancellationReason(),
        subscription.getStartedAt());
  }
}
