package com.acuity.subscribemaster.subscribe;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class SubscriptionControllerQueryTest extends AbstractSubscriptionControllerTest {

  @Test
  void acceptsExistingSubscription_returnsOkWithResponseBody() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var customName = "Test Name";
    var nextPayBillDate = LocalDate.now();
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var currency = Currency.GBP;
    var amount = new BigDecimal("1.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.ACTIVE; // creates default value

    var getOneResponse =
        SubscriptionResponse.accepted(
            subscriptionId,
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

    when(subscriptionSvc.getOne(customerId, subscriptionId)).thenReturn(getOneResponse);

    mvc.perform(get("/api/v1/subscribe/{id}", subscriptionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(subscriptionId.toString()));
  }

  @Test
  void acceptsCustomerWithSubscriptions_returnsOkWithPopulatedList() throws Exception {
    var subscriptionId1 = UUID.randomUUID();
    var subscriptionId2 = UUID.randomUUID();
    var customName1 = "Test Name";
    var customName2 = "Other Test Name";
    var nextPayBillDate = LocalDate.now();
    var startedAt1 = Instant.parse("2026-01-01T00:00:00Z");
    var startedAt2 = Instant.parse("2024-06-01T00:00:00Z");
    var currency = Currency.GBP;
    var amount1 = new BigDecimal("1.99");
    var amount2 = new BigDecimal("10.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status1 = SubscriptionStatus.ACTIVE;
    var status2 = SubscriptionStatus.PAUSED;

    var getAllResponse1 =
        SubscriptionResponse.accepted(
            subscriptionId1,
            customerId,
            null,
            null,
            customName1,
            null,
            currency,
            amount1,
            billFreq,
            nextPayBillDate,
            status1,
            null,
            startedAt1);
    var getAllResponse2 =
        SubscriptionResponse.accepted(
            subscriptionId2,
            customerId,
            null,
            null,
            customName2,
            null,
            currency,
            amount2,
            billFreq,
            nextPayBillDate,
            status2,
            null,
            startedAt2);
    List<SubscriptionResponse> getAllResponses = new ArrayList<>();
    getAllResponses.add(getAllResponse1);
    getAllResponses.add(getAllResponse2);

    when(subscriptionSvc.getAll(customerId)).thenReturn(getAllResponses);

    mvc.perform(get("/api/v1/subscribe"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)));
  }

  @Test
  void acceptsCustomerWithNoSubscriptions_returnsOkWithEmptyList() throws Exception {
    List<SubscriptionResponse> getEmptyAllResponses = new ArrayList<>();

    when(subscriptionSvc.getAll(customerId)).thenReturn(getEmptyAllResponses);

    mvc.perform(get("/api/v1/subscribe"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void rejected_subscriptionNotFound_returnsEntityNotFoundException() throws Exception {
    var subscriptionId = UUID.randomUUID();

    when(subscriptionSvc.getOne(customerId, subscriptionId))
        .thenThrow(new EntityNotFoundException("Subscription not found: " + subscriptionId));

    mvc.perform(get("/api/v1/subscribe/{id}", subscriptionId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
  }

  // getOne
  @Test
  void malformedSubscriptionIdOnGetOne_returnsBadRequest() throws Exception {
    var subscriptionId = "not a uuid";

    mvc.perform(get("/api/v1/subscribe/{id}", subscriptionId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }
}
