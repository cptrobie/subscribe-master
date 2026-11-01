package com.acuity.subscribemaster.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository for {@link IdempotencyKey} -- no {@code @Query}, no native SQL (NFR-16).
 * Lookups are by the full {@link IdempotencyPk}, never the key value alone, so a key only ever
 * matches within its own endpoint.
 */
public interface IdempotencyRepository extends JpaRepository<IdempotencyKey, IdempotencyPk> {}
