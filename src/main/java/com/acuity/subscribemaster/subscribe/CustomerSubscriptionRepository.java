package com.acuity.subscribemaster.subscribe;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSubscriptionRepository extends JpaRepository<CustomerSubscription, UUID> {

  Optional<CustomerSubscription> findByIdAndCustomerId(UUID id, UUID customerId);

  List<CustomerSubscription> findByCustomerId(UUID customerId);
}
