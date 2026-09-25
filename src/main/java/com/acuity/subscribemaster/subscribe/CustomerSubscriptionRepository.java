package com.acuity.subscribemaster.subscribe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<CustomerSubscription, UUID> {}
