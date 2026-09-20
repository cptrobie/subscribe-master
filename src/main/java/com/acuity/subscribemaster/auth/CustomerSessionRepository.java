package com.acuity.subscribemaster.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSessionRepository extends JpaRepository<CustomerSession, UUID> {

  /** Derived-query repository only -- no {@code @Query}, no native SQL (NFR-16). */
  List<CustomerSession> findByCustomerId(UUID customerId);
}
