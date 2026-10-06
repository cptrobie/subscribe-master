package com.acuity.subscribemaster.subscribe.dto;

import com.acuity.subscribemaster.subscribe.BillingFrequency;
import com.acuity.subscribemaster.subscribe.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request payload for creating a new customer subscription.
 *
 * <p>{@code providerId} and {@code customName} are each individually optional, but at least one
 * must be supplied — a subscription must be identifiable either by a known provider from the seeded
 * {@code subscription_providers} catalog, by a customer-supplied name (for providers not in the
 * catalog), or both (e.g. providerId=Netflix, customName="Family entertainment"). This invariant is
 * enforced here via {@code @AssertTrue} and backed by DB CHECK constraints ({@code
 * chk_subscription_has_name}) as a defense-in-depth layer.
 *
 * <p>{@code customerId} is intentionally absent — it is derived server-side from the authenticated
 * JWT, never accepted from the client.
 */
@Schema(name = "SubscriptionRequest")
public record SubscriptionRequest(
    UUID providerId,
    @Size(max = 254) String customName,
    String accountIdentifier,
    UUID paymentMethodId,
    @NotNull Currency currency,
    @NotNull @Positive BigDecimal amount,
    @NotNull BillingFrequency billingFrequency,
    @Positive Integer billingIntervalDays,
    @NotNull @FutureOrPresent LocalDate nextPaymentDate) {

  @AssertTrue(message = "Either providerId or customName must be provided")
  public boolean isProviderOrCustomNamePresent() {
    return providerId != null || customName != null;
  }

  @AssertTrue(message = "billingIntervalDays is required when billingFrequency is CUSTOM")
  public boolean isCustomIntervalPresent() {
    return billingFrequency != BillingFrequency.CUSTOM || billingIntervalDays != null;
  }
}
