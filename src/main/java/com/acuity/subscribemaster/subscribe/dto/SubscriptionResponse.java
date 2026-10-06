package com.acuity.subscribemaster.subscribe.dto;

import com.acuity.subscribemaster.subscribe.BillingFrequency;
import com.acuity.subscribemaster.subscribe.CancellationReason;
import com.acuity.subscribemaster.subscribe.Currency;
import com.acuity.subscribemaster.subscribe.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Login response body. On success, the token is provided in the Response body. */
@Schema(name = "SubscriptionResponse")
public record SubscriptionResponse(
    UUID id,
    UUID customerId,
    UUID providerId,
    String providerName,
    String customName,
    String category,
    Currency currency,
    BigDecimal amount,
    BillingFrequency billingFrequency,
    LocalDate nextPaymentDate,
    SubscriptionStatus status,
    CancellationReason cancellationReason,
    Instant startedAt) {

  public static SubscriptionResponse accepted(
      UUID id,
      UUID customerId,
      UUID providerId,
      String providerName,
      String customName,
      String category,
      Currency currency,
      BigDecimal amount,
      BillingFrequency billingFrequency,
      LocalDate nextPaymentDate,
      SubscriptionStatus status,
      CancellationReason cancellationReason,
      Instant startedAt) {
    return new SubscriptionResponse(
        id,
        customerId,
        providerId,
        providerName,
        customName,
        category,
        currency,
        amount,
        billingFrequency,
        nextPaymentDate,
        status,
        cancellationReason,
        startedAt);
  }
}
