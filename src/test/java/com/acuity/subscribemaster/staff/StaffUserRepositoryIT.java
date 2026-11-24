package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the staff entities ({@link StaffUser}, {@link Role}, {@link Permission}) and their
 * repositories against real Postgres with every migration applied (FR-06). The context starting at
 * all proves the mappings pass {@code ddl-auto: validate} against {@code V1} and {@code V13}. The
 * tests then prove:
 *
 * <ul>
 *   <li>the {@code role_permissions} join table mapping reads {@code V1.1}'s seeded roles and
 *       permissions correctly
 *   <li>a staff user round-trips, including the {@code V13} lockout columns, looked up by its
 *       normalized email
 *   <li>{@link StaffUserRepository#findWithRoleAndPermissionsById} really loads the role and its
 *       permissions in the same query
 * </ul>
 *
 * <p><b>Why the entity-graph test checks what's loaded, not just the values.</b>
 * {@code @DataJpaTest} wraps each test in a transaction, so lazy loading works inside a test even
 * when nothing was fetched eagerly. Reading the role's permissions there would pass whether or not
 * the {@code @EntityGraph} works. In production the staff security chain runs with {@code
 * open-in-view: false} and no transaction, where the same call throws {@code
 * LazyInitializationException}. So that test asks {@code PersistenceUtil.isLoaded} first, after
 * {@code clear()}, before touching either association.
 *
 * <p>Seeded roles are read, never created: roles and permissions are reference data that only
 * migrations change. Uses @DataJpaTest rather than the full SubscribeMasterApplicationIT pattern,
 * since this only needs the JPA layer, not the whole application context or Vault -- the same
 * reasoning as {@code AuditLogRepositoryIT}.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class StaffUserRepositoryIT {
  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("subscribe_master")
          .withUsername("subscribe_master")
          .withPassword("test_only_not_a_real_secret");

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private TestEntityManager entityManager;
  @Autowired private StaffUserRepository staffUserRepo;
  @Autowired private RoleRepository roleRepo;

  @Test
  void seededAuditorRoleHasExactlyItsThreePermissions() {
    var role = roleRepo.findByName("auditor").orElseThrow();

    assertThat(role.getPermissionNames())
        .containsExactlyInAnyOrder("view_audit_logs", "view_app_logs", "view_system_health");
  }

  @Test
  void staffUserRoundTripsWithRoleAndLockoutColumns() {
    var roleName = "support_agent";
    var email = "support@example.com";
    var lockedUntil = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

    var staffUser =
        new StaffUser(email, "a password hash", roleRepo.findByName(roleName).orElseThrow());
    staffUser.setLockedUntil(lockedUntil);

    entityManager.persistAndFlush(staffUser); // INSERT runs now; id assigned
    entityManager.clear(); // forget the cached object

    var fetched = staffUserRepo.findByEmail(email).orElseThrow(); // real SELECT

    assertThat(fetched.getFailedLoginCount()).isZero();
    assertThat(fetched.getRole().getName()).isEqualTo(roleName);
    assertThat(fetched.getLockedUntil()).isEqualTo(lockedUntil);
  }

  @Test
  void findWithRoleAndPermissionsByIdLoadsRoleAndPermissionsEagerly() {
    var email = "auditor@example.com";
    var staffUser =
        new StaffUser(email, "a password hash", roleRepo.findByName("auditor").orElseThrow());

    var saved = entityManager.persistAndFlush(staffUser); // INSERT runs now; id assigned
    entityManager.clear(); // forget the cached object

    var fetched =
        staffUserRepo.findWithRoleAndPermissionsById(saved.getId()).orElseThrow(); // real SELECT

    var persistenceUtil =
        entityManager.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil();

    assertThat(persistenceUtil.isLoaded(fetched, "role")).isTrue();
    assertThat(persistenceUtil.isLoaded(fetched.getRole(), "permissions")).isTrue();
    assertThat(fetched.getRole().getPermissionNames())
        .containsExactlyInAnyOrder("view_audit_logs", "view_app_logs", "view_system_health");
  }

  @Test
  void findWithRoleAndPermissionsByIdIsEmptyForUnknownId() {
    var fetched = staffUserRepo.findWithRoleAndPermissionsById(UUID.randomUUID());

    assertThat(fetched).isEmpty();
  }
}
