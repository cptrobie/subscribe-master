package com.acuity.subscribemaster.subscribe;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import com.acuity.subscribemaster.support.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class SubscriptionControllerStatusTransitionTest extends AbstractSubscriptionControllerTest {

  @Test
  void acceptsActiveSubscription_returnsOkWithPausedStatus() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var customName = "Test Name";
    var nextPayBillDate = LocalDate.now();
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.GBP;
    var amount = new BigDecimal("1.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.PAUSED;

    var pauseResponse =
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

    when(subscriptionSvc.pause(customerId, subscriptionId, ipAddress)).thenReturn(pauseResponse);

    mvc.perform(post("/api/v1/subscribe/{id}/pause", subscriptionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(SubscriptionStatus.PAUSED.name()));
  }

  @Test
  void acceptsActiveOrPausedSubscription_returnsOkWithCancelledStatus() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var customName = "Test Name";
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.GBP;
    var amount = new BigDecimal("1.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.CANCELLED;
    var cancelReason = CancellationReason.CUSTOMER_REQUESTED;

    var cancelResponse =
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
            null,
            status,
            cancelReason,
            startedAt);

    when(subscriptionSvc.cancel(customerId, subscriptionId, ipAddress)).thenReturn(cancelResponse);

    mvc.perform(post("/api/v1/subscribe/{id}/cancel", subscriptionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextPaymentDate").isEmpty())
        .andExpect(
            jsonPath("$.cancellationReason").value(CancellationReason.CUSTOMER_REQUESTED.name()))
        .andExpect(jsonPath("$.status").value(SubscriptionStatus.CANCELLED.name()));
  }

  @Test
  void acceptsPausedSubscription_returnsOkWithActiveStatus() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var customName = "Test Name";
    var nextPayBillDate = LocalDate.now();
    var startedAt = Instant.parse("2026-06-01T00:00:00Z");
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value
    var currency = Currency.GBP;
    var amount = new BigDecimal("1.99");
    var billFreq = BillingFrequency.MONTHLY;
    var status = SubscriptionStatus.ACTIVE; // return value

    var resumeResponse =
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

    when(subscriptionSvc.resume(customerId, subscriptionId, ipAddress)).thenReturn(resumeResponse);

    mvc.perform(post("/api/v1/subscribe/{id}/resume", subscriptionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(SubscriptionStatus.ACTIVE.name()));
  }

  @Test
  void rejected_alreadyPausedSubscription_returnsInvalidStateTransitionException()
      throws Exception {
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionSvc.pause(customerId, subscriptionId, ipAddress))
        .thenThrow(
            new InvalidStateTransitionException("Only an Active Status can change to pause"));

    mvc.perform(post("/api/v1/subscribe/{id}/pause", subscriptionId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE_TRANSITION.name()));
  }

  @Test
  void rejected_alreadyCancelledSubscription_returnsInvalidStateTransitionException()
      throws Exception {
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionSvc.cancel(customerId, subscriptionId, ipAddress))
        .thenThrow(
            new InvalidStateTransitionException(
                "Subscription " + subscriptionId + " is already cancelled"));

    mvc.perform(post("/api/v1/subscribe/{id}/cancel", subscriptionId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE_TRANSITION.name()));
  }

  @Test
  void rejected_alreadyActiveSubscription_returnsInvalidStateTransitionException()
      throws Exception {
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionSvc.resume(customerId, subscriptionId, ipAddress))
        .thenThrow(
            new InvalidStateTransitionException(
                "Subscription " + subscriptionId + " can only be resumed from a paused state"));

    mvc.perform(post("/api/v1/subscribe/{id}/resume", subscriptionId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE_TRANSITION.name()));
  }

  @Test
  void rejected_pausedSubscriptionNotFound_returnsEntityNotFoundException() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionSvc.pause(customerId, subscriptionId, ipAddress))
        .thenThrow(new EntityNotFoundException("Subscription not found: " + subscriptionId));

    mvc.perform(post("/api/v1/subscribe/{id}/pause", subscriptionId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
  }

  @Test
  void rejected_cancelledSubscriptionNotFound_returnsEntityNotFoundException() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionSvc.cancel(customerId, subscriptionId, ipAddress))
        .thenThrow(new EntityNotFoundException("Subscription not found: " + subscriptionId));

    mvc.perform(post("/api/v1/subscribe/{id}/cancel", subscriptionId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
  }

  @Test
  void rejected_resumedSubscriptionNotFound_returnsEntityNotFoundException() throws Exception {
    var subscriptionId = UUID.randomUUID();
    var ipAddress = "127.0.0.1"; // this is MockHttpServletRequest's default value

    when(subscriptionSvc.resume(customerId, subscriptionId, ipAddress))
        .thenThrow(new EntityNotFoundException("Subscription not found: " + subscriptionId));

    mvc.perform(post("/api/v1/subscribe/{id}/resume", subscriptionId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
  }

  @Test
  void malformedSubscriptionIdOnPause_returnsBadRequest() throws Exception {
    var subscriptionId = "not a uuid";

    mvc.perform(post("/api/v1/subscribe/{id}/pause", subscriptionId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }

  @Test
  void malformedSubscriptionIdOnCancel_returnsBadRequest() throws Exception {
    var subscriptionId = "not a uuid";

    mvc.perform(post("/api/v1/subscribe/{id}/cancel", subscriptionId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }

  @Test
  void malformedSubscriptionIdOnResume_returnsBadRequest() throws Exception {
    var subscriptionId = "not a uuid";

    mvc.perform(post("/api/v1/subscribe/{id}/resume", subscriptionId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }
}
