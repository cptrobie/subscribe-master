package com.acuity.subscribemaster.payment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link PaymentHistory}. No custom query methods yet; {@link PaymentService} uses
 * only the inherited {@code findById}/{@code save}.
 */
public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, UUID> {}
