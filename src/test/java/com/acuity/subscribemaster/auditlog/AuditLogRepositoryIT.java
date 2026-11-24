package com.acuity.subscribemaster.auditlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * Verifies AuditLog actually round-trips through real Postgres for the columns that use native
 * Postgres types: {@code @JdbcTypeCode(SqlTypes.INET)} persists and reads back a String against
 * {@code inet}, and {@code @JdbcTypeCode(SqlTypes.JSON)} does the same for a {@code Map} against
 * {@code jsonb} ({@code details}, NFR-41).
 *
 * <p>It also proves that every {@link AuditAction} value is accepted by {@code
 * audit_logs_action_check} and reads back unchanged through {@link AuditActionConverter}. The enum
 * and the constraint are maintained separately (a new value needs a migration, as in V11 and V13),
 * and a value missing from the constraint would otherwise only fail at runtime, the first time that
 * action is audited. Values are taken from the enum itself, so new ones are covered automatically.
 *
 * <p>Uses @DataJpaTest rather than the full SubscribeMasterApplicationIT pattern, since this only
 * needs the JPA layer, not the whole application context or Vault.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class AuditLogRepositoryIT {
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
  @Autowired private AuditLogRepository repository;

  @Test
  void ipAddressRoundTripsThroughRealPostgresInetColumn() {
    AuditLog saved =
        repository.save(
            new AuditLog(
                ActorType.CUSTOMER,
                UUID.randomUUID(),
                AuditAction.REGISTERED,
                "customers",
                UUID.randomUUID(),
                null,
                "192.168.1.1"));

    AuditLog fetched = repository.findById(saved.getId()).orElseThrow();

    assertThat(fetched.getIpAddress()).isEqualTo("192.168.1.1");
  }

  @Test
  void detailsRoundTripsThroughRealPostgresJsonbColumn() {
    Map<String, Object> details = new HashMap<>();
    details.put("logger", "com.acuity");
    details.put("from", null);
    details.put("to", "DEBUG");

    var logEntry =
        new AuditLog(
            ActorType.CUSTOMER,
            UUID.randomUUID(),
            AuditAction.SUBSCRIPTION_CREATED,
            "customer_subscriptions",
            UUID.randomUUID(),
            details,
            "192.168.0.1");

    AuditLog saved = entityManager.persistAndFlush(logEntry); // INSERT runs now; id assigned
    entityManager.clear(); // forget the cached object

    AuditLog fetched = repository.findById(saved.getId()).orElseThrow(); // real SELECT

    assertThat(fetched.getDetails()).isEqualTo(details);
    assertThat(fetched.getDetails()).containsEntry("from", null);
  }

  @Test
  void detailsIsNullWhenNotGiven() {
    var logEntry =
        new AuditLog(
            ActorType.CUSTOMER,
            UUID.randomUUID(),
            AuditAction.EMAIL_VERIFICATION_SENT,
            "auth",
            UUID.randomUUID(),
            "192.168.0.1");

    AuditLog saved = entityManager.persistAndFlush(logEntry); // INSERT runs now; id assigned
    entityManager.clear(); // forget the cached object

    AuditLog fetched = repository.findById(saved.getId()).orElseThrow(); // real SELECT

    assertThat(fetched.getDetails()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(AuditAction.class)
  void everyAuditActionIsAcceptedByTheActionCheckConstraint(AuditAction action) {
    var logEntry =
        new AuditLog(
            ActorType.CUSTOMER,
            UUID.randomUUID(),
            action,
            "subscription",
            UUID.randomUUID(),
            "192.168.0.1");

    AuditLog saved = entityManager.persistAndFlush(logEntry); // INSERT runs now; id assigned
    entityManager.clear(); // forget the cached object

    AuditLog fetched = repository.findById(saved.getId()).orElseThrow(); // real SELECT

    assertThat(fetched.getAction()).isEqualTo(action);
  }
}
