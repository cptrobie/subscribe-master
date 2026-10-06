package com.acuity.subscribemaster.subscribe;

import com.acuity.subscribemaster.support.EntityNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
