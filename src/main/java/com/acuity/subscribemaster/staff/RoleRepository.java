package com.acuity.subscribemaster.staff;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Derived-query repository for {@link Role} -- no {@code @Query}, no native SQL (NFR-16). Roles are
 * reference data seeded by migrations, so this is for looking them up, not creating them.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

  /**
   * The role with this name, for example {@code admin} when the first admin account is created at
   * startup. Its permissions aren't loaded.
   *
   * @param name a seeded role name, matched exactly ({@code roles.name} is {@code text}, not {@code
   *     citext})
   */
  Optional<Role> findByName(String name);
}
