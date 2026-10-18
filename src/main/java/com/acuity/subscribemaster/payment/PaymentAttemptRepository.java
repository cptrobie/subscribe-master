package com.acuity.subscribemaster.payment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link PaymentAttempt}. No custom query methods yet; {@link PaymentService}
 * currently only saves new attempts through it.
 */
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {}
