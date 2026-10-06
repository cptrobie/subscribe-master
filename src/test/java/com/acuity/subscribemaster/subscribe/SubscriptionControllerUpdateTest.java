package com.acuity.subscribemaster.subscribe;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionUpdateRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

public class SubscriptionControllerUpdateTest extends AbstractSubscriptionControllerTest {

  @Test
  void acceptsExistingSubscription_returnsOkWithResponseBody() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var providerId = UUID.randomUUID();
    var providerName = "Netflix";
    var accountIdentifier = "accountIdentifier";
    var nextPayBillDate = LocalDate.now();
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var currency = Currency.GBP;
    var amount = new BigDecimal("1.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.ACTIVE;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, currency, amount, billFreq, null);

    var validPutResponse =
        SubscriptionResponse.accepted(
            subscriptionId,
            customerId,
            providerId,
            providerName,
            null,
            null, // category stays null until FR-35 implements provider-category snapshotting
            currency,
            amount,
            billFreq,
            nextPayBillDate,
            status,
            null,
            startedAt);

    when(subscriptionSvc.update(customerId, subscriptionId, validPutRequest, ipAddress))
        .thenReturn(validPutResponse);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPutRequest)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerId").value(providerId.toString()))
        .andExpect(jsonPath("$.providerName").value(providerName));
  }

  @Test
  void rejected_missingProviderAndCustomName_returnsInvalidSubscriptionIdentityException()
      throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("1.99");
    var currency = Currency.GBP;
    var billFreq = BillingFrequency.MONTHLY;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, currency, amount, billFreq, null);

    when(subscriptionSvc.update(customerId, subscriptionId, validPutRequest, ipAddress))
        .thenThrow(
            new InvalidSubscriptionIdentityException(
                "Subscription "
                    + subscriptionId
                    + " has no provider on file — customName cannot be cleared"));
    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_SUBSCRIPTION_IDENTITY.name()));
  }

  @Test
  void rejected_missingRequiredFieldAmount_returnsFailedValidation() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;

    var invalidPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, currency, null, billFreq, null);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_missingRequiredFieldCurrency_returnsFailedValidation() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("9.99");
    var billFreq = BillingFrequency.MONTHLY;

    var invalidPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, null, amount, billFreq, null);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_missingRequiredFieldBillingFrequency_returnsFailedValidation() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("9.99");
    var currency = Currency.CNY;

    var invalidPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, currency, amount, null, null);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_AmountOfZero_returnsFailedValidation() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("0.00");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;

    var invalidPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, currency, amount, billFreq, null);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void rejected_customNameLengthExceedsLimit_returnsFailedValidation() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var customName =
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstu";
    var amount = new BigDecimal("10.99");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;

    var invalidPutRequest =
        new SubscriptionUpdateRequest(
            customName, accountIdentifier, currency, amount, billFreq, null);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void malformedJsonBody_returnsMalformedRequest() throws Exception {
    var subscriptionId = "not a valid UUID";
    var accountIdentifier = "accountIdentifier";
    var customName = " some test custom name";
    var amount = new BigDecimal("9.99");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.ANNUAL;

    var invalidPutRequest =
        new SubscriptionUpdateRequest(
            customName, accountIdentifier, currency, amount, billFreq, null);

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPutRequest)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }

  @Test
  void malformedPathVariable_returnsMalformedRequest() throws Exception {
    var subscriptionId = "not a uuid";

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }

  @Test
  void rejectedMissingSubscriptionId_returnsMalformedRequest() throws Exception {

    mvc.perform(put("/api/v1/subscribe/{id}", "").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()))
        .andExpect(jsonPath("$.message").value("No matching route for this request."));
  }

  /*
   * A controller-level test here never touches real optimistic locking — it only proves that when
   * the (mocked) service throws ObjectOptimisticLockingFailureException, GlobalExceptionHandler
   *  correctly maps it to 409/CONCURRENT_MODIFICATION. The real "does locking actually work"
   * Test can be found in the IT, still pending. This one's just closing the controller-to-handler
   *  wiring gap for update():
   */

  @Test
  void rejected_concurrentModification_returnsConflict() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var accountIdentifier = "accountIdentifier";
    var amount = new BigDecimal("9.99");
    var currency = Currency.EUR;
    var billFreq = BillingFrequency.MONTHLY;
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    var validPutRequest =
        new SubscriptionUpdateRequest(null, accountIdentifier, currency, amount, billFreq, null);

    when(subscriptionSvc.update(customerId, subscriptionId, validPutRequest, ipAddress))
        .thenThrow(
            new ObjectOptimisticLockingFailureException(
                CustomerSubscription.class, subscriptionId));

    mvc.perform(
            put("/api/v1/subscribe/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPutRequest)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.CONCURRENT_MODIFICATION.name()))
        .andExpect(
            jsonPath("$.message")
                .value("This record was modified by another request. Refresh and try again."));
  }
}
