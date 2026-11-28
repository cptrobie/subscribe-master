package com.acuity.subscribemaster.subscribe;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the {@link SubscriptionProvider} catalog. The catalog starts from {@code
 * R__seed_subscription_provider_catalog.sql} and, since FR-35, is maintained by staff.
 *
 * <p>Providers are deactivated rather than deleted, so a row can exist while it is no longer
 * offered. The inherited {@code findById}/{@code findAll} return every row, active or not, which is
 * what staff and existing subscriptions need. Anything that should only see providers customers can
 * still choose uses the {@code ...DeletedAtIsNull} methods below.
 */
interface SubscriptionProviderRepository extends JpaRepository<SubscriptionProvider, UUID> {

  /**
   * Finds an active (not deactivated) provider by id. Used when a customer creates a subscription,
   * so a deactivated provider is treated the same as one that doesn't exist.
   *
   * @param id the provider id
   * @return the provider, or empty if no active provider has this id
   */
  Optional<SubscriptionProvider> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * Checks whether an active provider already uses this name, so a create or rename can be rejected
   * with a 409 before the partial unique index ({@code WHERE deleted_at IS NULL}) rejects the
   * insert. Deactivated providers don't count, since deactivating a provider frees its name.
   *
   * @param name the provider name, compared exactly (case-sensitive, like the index)
   * @return {@code true} if an active provider has this name
   */
  boolean existsByNameAndDeletedAtIsNull(String name);
}
