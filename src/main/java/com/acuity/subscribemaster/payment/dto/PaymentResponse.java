package com.acuity.subscribemaster.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Login response body. On success, the token is provided in the Response body. */
@Schema(name = "PaymentResponse")
public record PaymentResponse(
    UUID id,
    UUID subscriptionId,
    UUID paymentMethodId,
    String status,
    BigDecimal amount,
    String currency,
    BigDecimal baseCurrencyAmount,
    String baseCurrency,
    BigDecimal exchangeRateApplied,
    Instant scheduledAt,
    Instant paidAt,
    Instant createdAt) {

  public static PaymentResponse paymentMade(
      UUID id,
      UUID subscriptionId,
      UUID paymentMethodId,
      String status,
      BigDecimal amount,
      String currency,
      BigDecimal baseCurrencyAmount,
      String baseCurrency,
      BigDecimal exchangeRateApplied,
      Instant scheduledAt,
      Instant paidAt,
      Instant createdAt) {
    return new PaymentResponse(
        id,
        subscriptionId,
        paymentMethodId,
        status,
        amount,
        currency,
        baseCurrencyAmount,
        baseCurrency,
        exchangeRateApplied,
        scheduledAt,
        paidAt,
        createdAt);
  }
}
