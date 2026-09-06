package com.acuity.subscribemaster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Reference pattern for Testcontainers-backed integration tests in this project — copy this shape
 * for future integration tests rather than inventing a new one per test class.
 *
 * <p>NOTE: this file was written without the ability to compile or run it in the environment that
 * produced it. Verify it actually compiles and passes locally (`mvn test`) before relying on it.
 *
 * <p>Uses a plain GenericContainer for Vault rather than a dedicated Testcontainers Vault module —
 * the dedicated `org.testcontainers:vault` module's version numbering has drifted out of sync with
 * Testcontainers core (stuck at 1.21.x while core is on the 2.x line), causing repeated dependency
 * resolution failures. GenericContainer is part of core itself, so it doesn't have that problem —
 * this trades away a couple of Vault-specific convenience methods for using only the most stable
 * part of the API.
 *
 * <p>What this test proves, if it passes: Flyway applied every migration in
 * src/main/resources/db/migration/ against a completely fresh Postgres instance; Hibernate's
 * ddl-auto=validate confirmed JPA entities (once they exist) match that migrated schema; and the
 * datasource credentials were resolved from Vault — not hardcoded — exactly like in the dev/prod
 * profiles.
 */
@Testcontainers
@SpringBootTest
class SubscribeMasterApplicationIT {

  private static final String VAULT_TOKEN = "test-root-token";

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("subscribe_master")
          .withUsername("subscribe_master")
          .withPassword("test_only_not_a_real_secret");

  @Container
  static final GenericContainer<?> vault =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
          .withExposedPorts(8200)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", VAULT_TOKEN)
          .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
          .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200));

  @BeforeAll
  static void configureAndSeedVault() throws Exception {
    System.setProperty("spring.cloud.vault.host", vault.getHost());
    System.setProperty("spring.cloud.vault.port", String.valueOf(vault.getMappedPort(8200)));
    System.setProperty("spring.cloud.vault.scheme", "http");
    System.setProperty("spring.cloud.vault.authentication", "TOKEN");
    System.setProperty("spring.cloud.vault.token", VAULT_TOKEN);

    // -address is required explicitly: the vault CLI defaults to HTTPS,
    // but dev-mode Vault only serves plain HTTP. Without this flag,
    // both commands below fail silently against the wrong protocol —
    // which is exactly what happened before this fix, and why the
    // failure only surfaced three layers downstream as a confusing
    // Postgres authentication error instead of a clear Vault error.
    var loginResult = vault.execInContainer(
            "vault", "login", "-address=http://127.0.0.1:8200", VAULT_TOKEN
    );
    if (loginResult.getExitCode() != 0) {
      throw new IllegalStateException("vault login failed: " + loginResult.getStderr());
    }

    var putResult = vault.execInContainer(
            "vault", "kv", "put", "-address=http://127.0.0.1:8200", "secret/subscribe-master",
            "spring.datasource.username=subscribe_master",
            "spring.datasource.password=test_only_not_a_real_secret"
    );
    if (putResult.getExitCode() != 0) {
      throw new IllegalStateException("vault kv put failed: " + putResult.getStderr());
    }
  }

  @AfterAll
  static void clearVaultSystemProperties() {
    // Cleanup, in case other *IT classes ever share this JVM fork —
    // without this, these System properties would leak into any test
    // that runs afterward in the same fork.
    System.clearProperty("spring.cloud.vault.host");
    System.clearProperty("spring.cloud.vault.port");
    System.clearProperty("spring.cloud.vault.scheme");
    System.clearProperty("spring.cloud.vault.authentication");
    System.clearProperty("spring.cloud.vault.token");
  }

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    // spring.datasource.username/password intentionally NOT set here —
    // they're expected to resolve from Vault, exactly as in dev/prod.
  }

  @Test
  void contextLoadsMigratesAndResolvesSecretsFromVault() {
    // No further assertions needed beyond the context successfully
    // starting — a failed migration, a schema/entity mismatch, or a
    // broken Vault credential lookup would each throw during context
    // startup, before this line is ever reached.
    assertThat(postgres.isRunning()).isTrue();
    assertThat(vault.isRunning()).isTrue();
  }
}
