package com.acuity.subscribemaster.subscribe.dto;

import com.acuity.subscribemaster.subscribe.BillingFrequency;
import com.acuity.subscribemaster.subscribe.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Request payload for updating an existing customer subscription. */
@Schema(name = "SubscriptionUpdateRequest")
public record SubscriptionUpdateRequest(
    @Size(max = 254) String customName,
    String accountIdentifier,
    @NotNull Currency currency,
    @NotNull @Positive BigDecimal amount,
    @NotNull BillingFrequency billingFrequency,
    @Positive Integer billingIntervalDays) {

  @AssertTrue(message = "billingIntervalDays is required when billingFrequency is CUSTOM")
  public boolean isCustomIntervalPresent() {
    return billingFrequency != BillingFrequency.CUSTOM || billingIntervalDays != null;
  }
}
