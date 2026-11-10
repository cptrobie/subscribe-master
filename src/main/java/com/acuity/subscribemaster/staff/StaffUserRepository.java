package com.acuity.subscribemaster.staff;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository for {@link StaffUser} -- no {@code @Query}, no native SQL (NFR-16).
 *
 * <p><b>Email lookups are case-sensitive, despite {@code citext}.</b> The JDBC driver sends a
 * String parameter typed as {@code varchar}, so Postgres compares the column as {@code text}, and
 * {@code findByEmail("Admin@x.test")} doesn't find {@code admin@x.test}. Confirmed against Postgres
 * 16: a typed {@code varchar} or {@code text} parameter matches case-sensitively; only a literal or
 * an explicit {@code ::citext} cast matches case-insensitively. So callers must pass an email
 * that's already stripped and lowercased, as the customer request DTOs do. {@code citext} still
 * makes the UNIQUE constraint case-insensitive, which is the backstop against duplicate accounts.
 */
public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {

  /**
   * The account with this email, for staff login. The role is not loaded.
   *
   * @param email an already-normalized (stripped, lowercased) email; matched exactly
   */
  Optional<StaffUser> findByEmail(String email);

  /**
   * The account with this id, with its role and the role's permissions loaded in the same query --
   * what the staff security chain calls on every request to turn a token's {@code sub} into
   * authorities (FR-06). Still a derived query: Spring Data ignores the words between {@code find}
   * and {@code By}, and the {@code @EntityGraph} only changes what's fetched, so this stays within
   * NFR-15 (lazy mappings, eager only where a query asks for it).
   *
   * <p>Returns the whole user rather than just permission names, so the caller can tell the cases
   * apart: no row or {@code isActive() == false} means the token is no longer valid (401), while an
   * active user whose role has no permissions is authenticated but refused any permission-gated
   * endpoint (403).
   *
   * @param id the staff user's id, from the token's {@code sub} claim
   */
  @EntityGraph(attributePaths = {"role", "role.permissions"})
  Optional<StaffUser> findWithRoleAndPermissionsById(UUID id);
}
