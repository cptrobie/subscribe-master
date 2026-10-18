package com.acuity.subscribemaster.subscribe;

import com.acuity.subscribemaster.support.EntityNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a subscription with {@link CancellationReason#PAYMENT_FAILURE} once its payment retries
 * are exhausted -- the direct-call entry point for the payment side to change subscription status
 * (see {@code design-docs/payment_retry_and_dunning.md}). Runs in its own transaction ({@code
 * REQUIRES_NEW}), so the cancellation commits independently of the caller's.
 */
@Service
public class SubscriptionStatusUpdater {

  private final CustomerSubscriptionRepository custSubRepo;

  public SubscriptionStatusUpdater(CustomerSubscriptionRepository repository) {
    this.custSubRepo = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void handlePaymentExhausted(UUID subscriptionId) {
    CustomerSubscription subscription =
        custSubRepo
            .findById(subscriptionId)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        "Customer Subscription not found: " + subscriptionId));
    subscription.cancel(Instant.now(), CancellationReason.PAYMENT_FAILURE);
    custSubRepo.save(subscription);
  }
}
