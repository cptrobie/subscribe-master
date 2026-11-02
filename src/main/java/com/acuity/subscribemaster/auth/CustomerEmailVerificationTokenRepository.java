package com.acuity.subscribemaster.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository for {@link CustomerEmailVerificationToken} -- no {@code @Query}, no
 * native SQL (NFR-16). Lookups are always by the token's hash, never the raw value. The
 * per-customer queries serve the FR-40 resend: its rate limit, and invalidating earlier tokens.
 */
public interface CustomerEmailVerificationTokenRepository
    extends JpaRepository<CustomerEmailVerificationToken, UUID> {

  Optional<CustomerEmailVerificationToken> findByTokenHash(String tokenHash);

  /**
   * Counts the tokens issued to a customer after {@code since}, used or not -- the FR-40 resend
   * limit (three in the last hour, the registration token included). This is why superseded tokens
   * are marked used rather than deleted: deleting them would reset the count.
   *
   * @param customerId the customer whose tokens to count
   * @param since exclusive lower bound on {@code created_at}
   * @return the number of tokens created strictly after {@code since}
   */
  long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant since);

  /**
   * Finds a customer's tokens that haven't been used yet, expired ones included -- the tokens a
   * FR-40 resend invalidates (marks used) before issuing a new one, so a link sent to an old
   * address can't verify the account. Used tokens are excluded because {@link
   * CustomerEmailVerificationToken#markUsed} refuses to mark one twice.
   *
   * @param customerId the customer whose unused tokens to find
   * @return the unused tokens, possibly empty
   */
  List<CustomerEmailVerificationToken> findByCustomerIdAndUsedAtIsNull(UUID customerId);
}
