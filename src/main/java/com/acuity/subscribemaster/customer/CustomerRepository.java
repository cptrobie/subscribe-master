package com.acuity.subscribemaster.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository only — no {@code @Query}, no native SQL (NFR-16). {@code
 * customers.email} is {@code citext}, so equality here is already case-insensitive at the database.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

  boolean existsByEmail(String email);

  Optional<Customer> findByEmail(String email);
}
