package com.acuity.subscribemaster.subscribe;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionRequest;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

public class SubscriptionControllerCreateTest extends AbstractSubscriptionControllerTest {

  @Test
  void acceptsValidProviderOnlyAndLowerBoundaryAmount_returnsCreatedWithResponseBody()
      throws Exception {
    var id = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "Netflix"; // from the subscription_providers table
    var category = " streaming_video"; // from the subscription_providers table
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var currency = Currency.EUR;
    var amount = new BigDecimal("0.01");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.ACTIVE; // creates default value
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
    var validResponse =
        SubscriptionResponse.accepted(
            id,
            customerId,
            providerId,
            providerName,
            null,
            category,
            currency,
            amount,
            billFreq,
            nextPayBillDate,
            status,
            null,
            startedAt);

    when(subscriptionSvc.create(customerId, validRequest, ipAddress)).thenReturn(validResponse);

    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.providerName").value(providerName))
        .andExpect(jsonPath("$.category").value(category))
        .andExpect(jsonPath("$.customerId").value(customerId.toString()));
  }

  @Test
  void acceptsValidCustomNameOnlyAndNextPaymentDateAsToday_returnsCreatedWithResponseBody()
      throws Exception {
    var id = UUID.randomUUID();
    var customName = "Test Name";
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now();
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.CAD;
    var amount = new BigDecimal("101.01");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.ACTIVE; // creates default value

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
    var validResponse =
        SubscriptionResponse.accepted(
            id,
            customerId,
            null,
            null,
            customName,
            null,
            currency,
            amount,
            billFreq,
            nextPayBillDate,
            status,
            null,
            startedAt);

    when(subscriptionSvc.create(customerId, validRequest, ipAddress)).thenReturn(validResponse);

    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.customerId").value(customerId.toString()));
  }

  @Test
  void
      acceptsValidProviderAndCustomNameWithCustomNameBoundaryLength_returnsCreatedWithResponseBody()
          throws Exception {
    var id = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "Netflix";
    var category = " streaming_video"; // from the subscription_providers table
    var customName =
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrst";
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.EUR;
    var amount = new BigDecimal("0.01");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.ACTIVE; // creates default value
    var validRequest =
        new SubscriptionRequest(
            providerId,
            customName,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate);
    var validResponse =
        SubscriptionResponse.accepted(
            id,
            customerId,
            providerId,
            providerName,
            customName,
            category,
            currency,
            amount,
            billFreq,
            nextPayBillDate,
            status,
            null,
            startedAt);
    when(subscriptionSvc.create(customerId, validRequest, ipAddress)).thenReturn(validResponse);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.customerId").value(customerId.toString()));
  }

  @Test
  void acceptsValidCustomBillingFreqAndBillingIntervalDays_returnsCreatedWithResponseBody()
      throws Exception {
    var id = UUID.randomUUID();
    var customName = " valid test name";
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.EUR;
    var amount = new BigDecimal("101.01");
    var billFreq = BillingFrequency.CUSTOM;
    var billIntDays = 10;
    var status = SubscriptionStatus.ACTIVE; // creates default value
    var validRequest =
        new SubscriptionRequest(
            null,
            customName,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            billIntDays,
            nextPayBillDate);
    var validResponse =
        SubscriptionResponse.accepted(
            id,
            customerId,
            null,
            null,
            customName,
            null,
            currency,
            amount,
            billFreq,
            nextPayBillDate,
            status,
            null,
            startedAt);
    when(subscriptionSvc.create(customerId, validRequest, ipAddress)).thenReturn(validResponse);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.customerId").value(customerId.toString()));
  }

  @Test
  void rejected_missingProviderAndCustomName_returnsFailedValidation() throws Exception {
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var currency = Currency.EUR;
    var amount = new BigDecimal("0.01");
    var billFreq = BillingFrequency.MONTHLY;

    var invalidRequest =
        new SubscriptionRequest(
            null,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billFreq,
            null,
            nextPayBillDate);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_customNameLengthExceedsLimit_returnsFailedValidation() throws Exception {
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var customName =
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstu";
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var currency = Currency.EUR;
    var amount = new BigDecimal("10.99");
    var billFreq = BillingFrequency.MONTHLY;

    var invalidRequest =
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
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_AmountOfZero_returnsFailedValidation() throws Exception {
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var customName = "valid custom name test";
    var nextPayBillDate = LocalDate.now().plusDays(1);
    var currency = Currency.EUR;
    var amount = new BigDecimal("0.00");
    var billFreq = BillingFrequency.MONTHLY;

    var invalidRequest =
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
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_nextBillingDateYesterday_returnsFailedValidation() throws Exception {
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var customName = "valid custom name test";
    var nextPayBillDate = LocalDate.now().minusDays(1);
    var currency = Currency.EUR;
    var amount = new BigDecimal("10.01");
    var billFreq = BillingFrequency.MONTHLY;

    var invalidRequest =
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
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_missingRequiredFieldAmount_returnsFailedValidation() throws Exception {
    var providerId = UUID.randomUUID();
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now().plusDays(1);
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;

    var invalidRequest =
        new SubscriptionRequest(
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            null,
            billFreq,
            null,
            nextPayBillDate);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_missingRequiredFieldCurrency_returnsFailedValidation() throws Exception {
    var providerId = UUID.randomUUID();
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("9.99");
    var nextPayBillDate = LocalDate.now().plusDays(1);
    var billFreq = BillingFrequency.MONTHLY;

    var invalidRequest =
        new SubscriptionRequest(
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            null,
            amount,
            billFreq,
            null,
            nextPayBillDate);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_missingRequiredFieldBillingFrequency_returnsFailedValidation() throws Exception {
    var providerId = UUID.randomUUID();
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("9.99");
    var currency = Currency.JPY;
    var nextPayBillDate = LocalDate.now().plusDays(1);

    var invalidRequest =
        new SubscriptionRequest(
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            null,
            null,
            nextPayBillDate);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_missingRequiredFieldNxtPaymentBillDte_returnsFailedValidation() throws Exception {
    var providerId = UUID.randomUUID();
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("9.99");
    var currency = Currency.JPY;
    var billingFreq = BillingFrequency.WEEKLY;

    var invalidRequest =
        new SubscriptionRequest(
            providerId,
            null,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billingFreq,
            null,
            null);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_customBillingFrequencyRequiresFieldIntervalDaysButMissing_returnsFailedValidation()
      throws Exception {
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var customName = "valid custom name";
    var amount = new BigDecimal("9.99");
    var currency = Currency.JPY;
    var billingFreq = BillingFrequency.CUSTOM;
    var nextPayBillDate = LocalDate.now().plusDays(1);

    var invalidRequest =
        new SubscriptionRequest(
            null,
            customName,
            accountIdentifier,
            paymentMethodId,
            currency,
            amount,
            billingFreq,
            null,
            nextPayBillDate);
    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_invalidProviderId_returnsEntityNotFoundException() throws Exception {
    var providerId = UUID.randomUUID();
    var paymentMethodId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now().plusDays(7);
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.EUR;
    var amount = new BigDecimal("9.99");
    var billFreq = BillingFrequency.WEEKLY;

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

    when(subscriptionSvc.create(customerId, invalidRequest, ipAddress))
        .thenThrow(new EntityNotFoundException("Provider not found: " + providerId));

    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
  }

  @Test
  void malformedJsonBody_returnsMalformedRequest() throws Exception {

    mvc.perform(
            post("/api/v1/subscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not valid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }
}
