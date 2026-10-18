package com.acuity.subscribemaster.subscribe;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository for {@link CustomerSubscription} -- no {@code @Query}, no native SQL
 * (NFR-16). Lookups take a {@code customerId}, so a customer can only reach their own
 * subscriptions.
 */
public interface CustomerSubscriptionRepository extends JpaRepository<CustomerSubscription, UUID> {

  Optional<CustomerSubscription> findByIdAndCustomerId(UUID id, UUID customerId);

  List<CustomerSubscription> findByCustomerId(UUID customerId);
}
