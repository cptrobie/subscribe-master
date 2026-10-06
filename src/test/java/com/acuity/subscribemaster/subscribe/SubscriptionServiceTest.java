package com.acuity.subscribemaster.subscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionRequest;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionUpdateRequest;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import com.acuity.subscribemaster.support.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit test -- no Spring context, no infrastructure. Mocks every SubscriptionService
 * dependency to isolate its actual logic.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

  @Mock private CustomerSubscriptionRepository subscriptionRepo;
  @Mock private SubscriptionProviderRepository providerRepo;
  @Mock private AuditLogService auditLogSvc;

  private SubscriptionService subscriptionSvc;

  @BeforeEach
  void setUp() {
    subscriptionSvc = new SubscriptionService(subscriptionRepo, providerRepo, auditLogSvc);
  }

  @Test
  void successfulCreateSubscriptionViaValidProviderLookup_savesSubscriptionAndRecordsAuditEvent() {
    // Arrange
    var customerId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("0.01");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validRequest =
        new SubscriptionRequest(
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate);
    var validProvider =
        new SubscriptionProvider(
            providerId, "Netflix", "streaming service", null, null, Instant.now());

    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.create(customerId, validRequest, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("Netflix");
    assertThat(response.category()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getCustomerId()).isEqualTo(customerId);
    assertThat(saved.getProviderId()).isEqualTo(providerId);
    assertThat(saved.getCustomName()).isNull();
    assertThat(saved.getAccountIdentifier()).isEqualTo(accountIdentifier);
    assertThat(saved.getAmount()).isEqualTo(amount);
    assertThat(saved.getCurrency()).isEqualTo(currency);
    assertThat(saved.getBillingFrequency()).isEqualTo(billFreq);
    assertThat(saved.getNextPaymentDate()).isEqualTo(nextPayBillDate);
    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

    // then, that several service calls occurred
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_CREATED,
            "subscription",
            saved.getId(),
            ipAddress);
  }

  @Test
  void successfulCreateSubscriptionViaCustomName_savesSubscriptionAndRecordsAuditEvent() {
    // Arrange
    var customerId = UUID.randomUUID();
    var customName = "a new provider";
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("0.01");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validRequest =
        new SubscriptionRequest(
            null,
            customName,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate);

    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.create(customerId, validRequest, ipAddress);

    // Assert starts here...
    assertThat(response.providerName()).isNull();
    assertThat(response.customName()).isEqualTo(customName);
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getCustomerId()).isEqualTo(customerId);
    assertThat(saved.getProviderId()).isNull();
    assertThat(saved.getCustomName()).isEqualTo(customName);
    assertThat(saved.getAmount()).isEqualTo(amount);
    assertThat(saved.getCurrency()).isEqualTo(currency);
    assertThat(saved.getBillingFrequency()).isEqualTo(billFreq);
    assertThat(saved.getNextPaymentDate()).isEqualTo(nextPayBillDate);
    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

    // then, that several service calls occurred
    verify(providerRepo, never()).findById(any());
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_CREATED,
            "subscription",
            saved.getId(),
            ipAddress);
  }

  @Test
  void successfulPauseFromActiveSubscription_setsPausedStatusAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "AppleTv";
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.ACTIVE;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validProvider =
        new SubscriptionProvider(
            providerId, providerName, "streaming service", null, null, Instant.now());
    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.pause(customerId, subscriptionId, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("AppleTv");
    assertThat(response.category()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results -- pause() only ever touches status, and per its documented
    // contract must leave nextPaymentDate untouched; the other fields are unrelated to pause's
    // own behavior and were already proven correct by the create() tests.
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
    assertThat(saved.getNextPaymentDate()).isEqualTo(nextPayBillDate);

    // then, that several service calls occurred
    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_PAUSED,
            "subscription",
            subscriptionId,
            ipAddress);
  }

  @Test
  void successfulCancelFromPauseSubscription_setsCancelledStatusAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "AppleTv";
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.PAUSED;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validProvider =
        new SubscriptionProvider(
            providerId, providerName, "streaming service", null, null, Instant.now());
    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.cancel(customerId, subscriptionId, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("AppleTv");
    assertThat(response.category()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(saved.getNextPaymentDate()).isNull();
    assertThat(saved.getCancellationReason()).isEqualTo(CancellationReason.CUSTOMER_REQUESTED);

    // then, that several service calls occurred
    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_CANCELLED,
            "subscription",
            subscriptionId,
            ipAddress);
  }

  @Test
  void successfulResumeFromPauseSubscription_setsActiveStatusAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "AppleTv";
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.PAUSED;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validProvider =
        new SubscriptionProvider(
            providerId, providerName, "streaming service", null, null, Instant.now());
    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.resume(customerId, subscriptionId, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("AppleTv");
    assertThat(response.category()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(saved.getNextPaymentDate()).isEqualTo(nextPayBillDate);
    assertThat(saved.getCancellationReason()).isNull();

    // then, that several service calls occurred
    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_RESUMED,
            "subscription",
            subscriptionId,
            ipAddress);
  }

  @Test
  void successfulResumeFromPauseSubscription_withMissedCycle_advancesNextPaymentDate() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "AppleTv";
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now().minusMonths(9);
    var status = SubscriptionStatus.PAUSED;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validProvider =
        new SubscriptionProvider(
            providerId, providerName, "streaming service", null, null, Instant.now());
    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.resume(customerId, subscriptionId, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("AppleTv");
    assertThat(response.category()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(saved.getNextPaymentDate()).isAfterOrEqualTo(LocalDate.now());
    assertThat(saved.getCancellationReason()).isNull();

    // then, that several service calls occurred
    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_RESUMED,
            "subscription",
            subscriptionId,
            ipAddress);
  }

  @Test
  void successfulGetOneUsingProvider_resolvesProviderName() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "AmazonPrime";
    var accountIdentifier = "some random acct Id";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.ACTIVE;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var validProvider =
        new SubscriptionProvider(
            providerId, providerName, "streaming service", null, null, Instant.now());
    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));

    // Act
    var response = subscriptionSvc.getOne(customerId, subscriptionId);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo(providerName);
    assertThat(response.category()).isNull();
    assertThat(response.customName()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    // then, that several service calls occurred
    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verify(providerRepo, times(1)).findById(providerId);
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void successfulGetOneUsingCustomName_skipsProviderLookup() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var customName = "local paper delivery subscription";
    var accountIdentifier = "some random acct Id";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.ACTIVE;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");

    var validSubscription =
        new CustomerSubscription(
            customerId,
            null,
            customName,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));

    // Act
    var response = subscriptionSvc.getOne(customerId, subscriptionId);

    // Assert Starts here...
    assertThat(response.providerName()).isNull();
    assertThat(response.category()).isNull();
    assertThat(response.customName()).isEqualTo(customName);
    assertThat(response.customerId()).isEqualTo(customerId);

    // then, that several service calls occurred
    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verifyNoInteractions(providerRepo);
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void successfulGetAll_returnsEmptySubscriptionList() {
    var customerId = UUID.randomUUID();

    when(subscriptionRepo.findByCustomerId(customerId)).thenReturn(List.of());

    // Act
    var responses = subscriptionSvc.getAll(customerId);

    // Assert Starts here...
    assertThat(responses.size()).isEqualTo(0);

    verifyNoInteractions(providerRepo);
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void successfulGetAll_returnsProviderAndCustomNameSubscriptions() {
    var customerId = UUID.randomUUID();
    var providerId1 = UUID.randomUUID();
    var providerName1 = "hulu";
    var customName2 = "Other Test Name";
    var accountIdentifier = "some random acct Id";
    var paymentMethodId = UUID.randomUUID();
    var nextPayBillDate = LocalDate.now();
    var startedAt = Instant.parse("2026-01-01T00:00:00Z");
    var currency = Currency.GBP;
    var amount1 = new BigDecimal("1.99");
    var amount2 = new BigDecimal("10.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status1 = SubscriptionStatus.ACTIVE;
    var status2 = SubscriptionStatus.PAUSED;

    var validProvider =
        new SubscriptionProvider(
            providerId1, providerName1, "streaming service", null, null, Instant.now());

    var subscription1 =
        new CustomerSubscription(
            customerId,
            providerId1,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount1,
            billFreq,
            null,
            nextPayBillDate,
            status1,
            startedAt);
    var subscription2 =
        new CustomerSubscription(
            customerId,
            null,
            customName2,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount2,
            billFreq,
            null,
            nextPayBillDate,
            status2,
            startedAt);

    when(subscriptionRepo.findByCustomerId(customerId))
        .thenReturn(List.of(subscription1, subscription2));
    when(providerRepo.findById(providerId1)).thenReturn(Optional.of(validProvider));

    // Act
    var responses = subscriptionSvc.getAll(customerId);

    // Assert
    assertThat(responses).hasSize(2);

    assertThat(responses.get(0).providerName()).isEqualTo("hulu");
    assertThat(responses.get(0).customName()).isNull();
    assertThat(responses.get(0).amount()).isEqualTo(amount1);
    assertThat(responses.get(0).status()).isEqualTo(SubscriptionStatus.ACTIVE);

    assertThat(responses.get(1).providerName()).isNull();
    assertThat(responses.get(1).customName()).isEqualTo(customName2);
    assertThat(responses.get(1).amount()).isEqualTo(amount2);
    assertThat(responses.get(1).status()).isEqualTo(SubscriptionStatus.PAUSED);

    verify(providerRepo, times(1)).findById(providerId1);
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void successfulUpdate_appliesFieldsAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var customName = "a new custom subscription name";
    var updateCustomName = "The updated custom subscription name";
    var accountIdentifier = "accountIdentifier";
    var updatedAccountIdentifier = "updated accountIdentifier";
    var amount = new BigDecimal("0.01");
    var updatedAmount = new BigDecimal("9.99");
    var currency = Currency.EUR;
    var updatedCurrency = Currency.USD;
    var billFreq = BillingFrequency.MONTHLY;
    var updatedBillFreq = BillingFrequency.WEEKLY;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            customName,
            accountIdentifier,
            UUID.randomUUID(),
            currency,
            amount,
            billFreq,
            null,
            LocalDate.now().plusDays(7),
            SubscriptionStatus.PAUSED,
            Instant.parse("2026-01-01T00:00:00Z"));
    var validProvider =
        new SubscriptionProvider(
            providerId, "Hulu", "streaming service", null, null, Instant.now());
    var subscriptionUpdateRequest =
        new SubscriptionUpdateRequest(
            updateCustomName,
            updatedAccountIdentifier,
            updatedCurrency,
            updatedAmount,
            updatedBillFreq,
            null);

    ReflectionTestUtils.setField(validSubscription, "id", subscriptionId);
    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var response =
        subscriptionSvc.update(customerId, subscriptionId, subscriptionUpdateRequest, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("Hulu");
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getCustomName()).isEqualTo(updateCustomName);
    assertThat(saved.getAccountIdentifier()).isEqualTo(updatedAccountIdentifier);
    assertThat(saved.getAmount()).isEqualTo(updatedAmount);
    assertThat(saved.getCurrency()).isEqualTo(updatedCurrency);
    assertThat(saved.getBillingFrequency()).isEqualTo(updatedBillFreq);

    // then, that several service calls occurred
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_UPDATED,
            "subscription",
            saved.getId(),
            ipAddress);
  }

  @Test
  void successfulUpdate_customFrequencyAppliesBillingIntervalFieldAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("0.01");
    var currency = Currency.CNY;
    var billFreq = BillingFrequency.CUSTOM;
    var billIntervalDays = 7;
    var updatedBillIntervalDays = 14;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            UUID.randomUUID(),
            currency,
            amount,
            billFreq,
            billIntervalDays,
            LocalDate.now().plusDays(7),
            SubscriptionStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"));
    var validProvider =
        new SubscriptionProvider(
            providerId, "Hulu", "streaming service", null, null, Instant.now());
    var subscriptionUpdateRequest =
        new SubscriptionUpdateRequest(
            null, accountIdentifier, currency, amount, billFreq, updatedBillIntervalDays);

    ReflectionTestUtils.setField(validSubscription, "id", subscriptionId);
    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    var response =
        subscriptionSvc.update(customerId, subscriptionId, subscriptionUpdateRequest, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("Hulu");
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getBillingFrequency()).isEqualTo(BillingFrequency.CUSTOM);
    assertThat(saved.getBillingIntervalDays()).isEqualTo(updatedBillIntervalDays);

    // then, that several service calls occurred
    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_UPDATED,
            "subscription",
            saved.getId(),
            ipAddress);
  }

  @Test
  void successfulUpdate_clearingCustomNameWithProviderOnFile_clearsNameAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "account id field";
    var amount = new BigDecimal("9.99");
    var currency = Currency.USD;
    var billFreq = BillingFrequency.ANNUAL;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    // provider-based subscription with a custom label
    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            "my custom subscription",
            accountIdentifier,
            UUID.randomUUID(),
            currency,
            amount,
            billFreq,
            null,
            LocalDate.now().plusDays(7),
            SubscriptionStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"));
    ReflectionTestUtils.setField(validSubscription, "id", subscriptionId);
    var validProvider =
        new SubscriptionProvider(
            providerId, "Hulu", "streaming service", null, null, Instant.now());
    var clearingRequest =
        new SubscriptionUpdateRequest(
            null, // This is the updated field
            accountIdentifier, // Unchanged
            currency, // Unchanged
            amount, // Unchanged
            billFreq, // Unchanged
            null);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(providerRepo.findById(providerId)).thenReturn(Optional.of(validProvider));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response = subscriptionSvc.update(customerId, subscriptionId, clearingRequest, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isEqualTo("Hulu");
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getCustomName()).isNull();

    verify(providerRepo, times(1)).findById(providerId);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_UPDATED,
            "subscription",
            saved.getId(),
            ipAddress);
  }

  @Test
  void
      successfulUpdate_renamingCustomNameWithNullProviderAppliesCustomNameChangeAndRecordsAuditEvent() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var customName = "initial Custom Name";
    var updatedCustomName = "a new custom name";
    var accountIdentifier = "account id field";
    var amount = new BigDecimal("9.99");
    var currency = Currency.USD;
    var billFreq = BillingFrequency.ANNUAL;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    //  No provider but has custom name
    var validSubscription =
        new CustomerSubscription(
            customerId,
            null,
            customName,
            accountIdentifier,
            UUID.randomUUID(),
            currency,
            amount,
            billFreq,
            null,
            LocalDate.now().plusDays(7),
            SubscriptionStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"));
    ReflectionTestUtils.setField(validSubscription, "id", subscriptionId);

    var validUpdateRequest =
        new SubscriptionUpdateRequest(
            updatedCustomName, // This is the updated field
            accountIdentifier, // Unchanged
            currency, // Unchanged
            amount, // Unchanged
            billFreq, // Unchanged
            null);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));
    when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Act
    var response =
        subscriptionSvc.update(customerId, subscriptionId, validUpdateRequest, ipAddress);

    // Assert Starts here...
    assertThat(response.providerName()).isNull();
    assertThat(response.customerId()).isEqualTo(customerId);

    // Assert/verify save results
    var captor = ArgumentCaptor.forClass(CustomerSubscription.class);
    verify(subscriptionRepo, times(1)).save(captor.capture());
    var saved = captor.getValue();

    assertThat(saved.getCustomName()).isEqualTo(updatedCustomName);

    verify(subscriptionRepo, times(1)).findByIdAndCustomerId(subscriptionId, customerId);
    verifyNoInteractions(providerRepo);
    verify(auditLogSvc, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.SUBSCRIPTION_UPDATED,
            "subscription",
            saved.getId(),
            ipAddress);
  }

  @Test
  void rejected_createProviderIdPresentButNotFound_respondsWithEntityNotFoundException() {
    // Arrange
    var customerId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("0.01");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var invalidRequest =
        new SubscriptionRequest(
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate);

    when(providerRepo.findById(providerId)).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.create(customerId, invalidRequest, ipAddress))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Provider not found: " + providerId);

    // then verify that these service calls didn't happen
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_pauseAlreadyPausedSubscription_returnsInvalidStateTransitionException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.PAUSED;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.pause(customerId, subscriptionId, ipAddress))
        .isInstanceOf(InvalidStateTransitionException.class)
        .hasMessage("Only an Active Status can change to pause");

    // then verify that these service calls didn't happen
    verify(providerRepo, never()).findById(any());
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_cancelAlreadyCancelledSubscription_returnsInvalidStateTransitionException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.CANCELLED;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.cancel(customerId, subscriptionId, ipAddress))
        .isInstanceOf(InvalidStateTransitionException.class)
        .hasMessage("Subscription " + subscriptionId + " is already cancelled");

    // then verify that these service calls didn't happen
    verify(providerRepo, never()).findById(any());
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_resumeAlreadyActiveSubscription_returnsInvalidStateTransitionException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var paymentMethodId = UUID.randomUUID();
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var nextPayBillDate = LocalDate.now();
    var status = SubscriptionStatus.ACTIVE;
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validSubscription =
        new CustomerSubscription(
            customerId,
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate,
            status,
            startedAt);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(validSubscription));

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.resume(customerId, subscriptionId, ipAddress))
        .isInstanceOf(InvalidStateTransitionException.class)
        .hasMessage("Subscription " + subscriptionId + " can only be resumed from a paused state");

    // then verify that these service calls didn't happen
    verify(providerRepo, never()).findById(any());
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_pauseInvalidSubscriptionId_returnsEntityNotFoundException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID(); // invalid for this test case
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.pause(customerId, subscriptionId, ipAddress))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Subscription not found: " + subscriptionId);

    // then verify that these service calls didn't happen
    verify(providerRepo, never()).findById(any());
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_cancelInvalidSubscriptionId_returnsEntityNotFoundException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID(); // invalid for this test case
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.cancel(customerId, subscriptionId, ipAddress))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Subscription not found: " + subscriptionId);

    // then verify that these service calls didn't happen
    verify(providerRepo, never()).findById(any());
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_resumeInvalidSubscriptionId_returnsEntityNotFoundException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID(); // invalid for this test case
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.resume(customerId, subscriptionId, ipAddress))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Subscription not found: " + subscriptionId);

    // then verify that these service calls didn't happen
    verify(providerRepo, never()).findById(any());
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_getOneUsingInvalidSubscriptionId_returnsEntityNotFoundException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> subscriptionSvc.getOne(customerId, subscriptionId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Subscription not found: " + subscriptionId);

    // then verify that these service calls didn't happen
    verifyNoInteractions(providerRepo);
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void rejected_updateInvalidSubscriptionId_returnsEntityNotFoundException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID(); // invalid for this test case
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var updateRequest =
        new SubscriptionUpdateRequest(
            "a custom name",
            "accountIdentifier",
            Currency.USD,
            new BigDecimal("9.99"),
            BillingFrequency.MONTHLY,
            null);

    // Optional.empty() exercises the service's own orElseThrow, not a stubbed exception
    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(
            () -> subscriptionSvc.update(customerId, subscriptionId, updateRequest, ipAddress))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessage("Subscription not found: " + subscriptionId);

    // then verify that these service calls didn't happen
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(providerRepo);
    verifyNoInteractions(auditLogSvc);
  }

  @Test
  void
      rejected_updateClearingCustomNameWithNoProvider_returnsInvalidSubscriptionIdentityException() {
    var customerId = UUID.randomUUID();
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    // custom-name-only subscription: no provider on file, so customName is its only identity
    var customOnlySubscription =
        new CustomerSubscription(
            customerId,
            null,
            "my custom subscription",
            "accountIdentifier",
            UUID.randomUUID(),
            Currency.USD,
            new BigDecimal("9.99"),
            BillingFrequency.MONTHLY,
            null,
            LocalDate.now().plusDays(7),
            SubscriptionStatus.ACTIVE,
            Instant.parse("2026-01-01T00:00:00Z"));
    ReflectionTestUtils.setField(customOnlySubscription, "id", subscriptionId);

    // the update would wipe the only identity the subscription has
    var clearingRequest =
        new SubscriptionUpdateRequest(
            null,
            "accountIdentifier",
            Currency.USD,
            new BigDecimal("9.99"),
            BillingFrequency.MONTHLY,
            null);

    when(subscriptionRepo.findByIdAndCustomerId(subscriptionId, customerId))
        .thenReturn(Optional.of(customOnlySubscription));

    // Act & Assert
    assertThatThrownBy(
            () -> subscriptionSvc.update(customerId, subscriptionId, clearingRequest, ipAddress))
        .isInstanceOf(InvalidSubscriptionIdentityException.class)
        .hasMessage(
            "Subscription "
                + subscriptionId
                + " has no provider on file — customName cannot be cleared");

    // the guard must fire before any mutation is persisted or audited
    assertThat(customOnlySubscription.getCustomName()).isEqualTo("my custom subscription");
    verify(subscriptionRepo, never()).save(any());
    verifyNoInteractions(providerRepo);
    verifyNoInteractions(auditLogSvc);
  }
}
