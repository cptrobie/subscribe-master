package com.acuity.subscribemaster.payment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link Refund}. No custom query methods, and not yet used by any service. */
public interface RefundRepository extends JpaRepository<Refund, UUID> {}
