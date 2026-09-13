package com.acuity.subscribemaster.auditlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies AuditLog actually round-trips through real Postgres -- specifically
 * that @JdbcTypeCode(SqlTypes.INET) correctly persists and reads back a String
 * value against the native `inet` column type. Uses @DataJpaTest rather than
 * the full SubscribeMasterApplicationIT pattern, since this only needs the JPA
 * layer, not the whole application context or Vault.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class AuditLogRepositoryIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("subscribe_master")
            .withUsername("subscribe_master")
            .withPassword("test_only_not_a_real_secret");

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuditLogRepository repository;

    @Test
    void ipAddressRoundTripsThroughRealPostgresInetColumn() {
        AuditLog saved = repository.save(new AuditLog(
                "customer",
                UUID.randomUUID(),
                "REGISTERED",
                "customers",
                UUID.randomUUID(),
                "192.168.1.1"));

        AuditLog fetched = repository.findById(saved.getId()).orElseThrow();

        assertThat(fetched.getIpAddress()).isEqualTo("192.168.1.1");
    }
}
