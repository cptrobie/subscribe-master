package com.acuity.subscribemaster.subscribe;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SubscriptionProviderRepository extends JpaRepository<SubscriptionProvider, UUID> {}
