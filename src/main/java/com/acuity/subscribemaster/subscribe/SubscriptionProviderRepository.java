package com.acuity.subscribemaster.subscribe;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the {@link SubscriptionProvider} catalog, which is seeded by {@code
 * R__seed_subscription_provider_catalog.sql}. Currently used only by {@link SubscriptionService} to
 * resolve a subscription's provider display name.
 */
interface SubscriptionProviderRepository extends JpaRepository<SubscriptionProvider, UUID> {}
