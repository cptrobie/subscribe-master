package com.acuity.subscribemaster.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository for {@link CustomerEmailVerificationToken} -- no {@code @Query}, no
 * native SQL (NFR-16). Lookups are always by the token's hash, never the raw value.
 */
public interface CustomerEmailVerificationTokenRepository
    extends JpaRepository<CustomerEmailVerificationToken, UUID> {

  Optional<CustomerEmailVerificationToken> findByTokenHash(String tokenHash);
}
