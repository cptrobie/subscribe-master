package com.acuity.subscribemaster;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference pattern for Testcontainers-backed integration tests in this
 * project — copy this shape for future integration tests rather than
 * inventing a new one per test class.
 *
 * <p>Uses webEnvironment = RANDOM_PORT (rather than the default MOCK)
 * so a real embedded web server actually starts, letting this test make
 * a genuine HTTP call rather than only confirming the context boots.
 * This requires spring-boot-starter-webmvc on the classpath (added in
 * pom.xml alongside springdoc-openapi-starter-webmvc-ui).
 *
 * <p>Spring Boot 4 moved TestRestTemplate from
 * org.springframework.boot.test.web.client to
 * org.springframework.boot.resttestclient, requires the separate
 * spring-boot-resttestclient (test) and spring-boot-restclient
 * dependencies, and no longer auto-configures the bean under
 * @SpringBootTest by default — @AutoConfigureTestRestTemplate is now
 * required explicitly. See the Spring Boot 4.0 migration guide.
 *
 * <p>Uses a plain GenericContainer for Vault rather than a dedicated
 * Testcontainers Vault module — the dedicated `org.testcontainers:vault`
 * module's version numbering has drifted out of sync with Testcontainers
 * core, causing repeated dependency resolution failures. GenericContainer
 * is part of core itself, so it doesn't have that problem.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The Vault import lives in application-{profile}.yaml, not application.yaml
        // (see the note in application.yaml for why). This test runs with no
        // profile, so it declares the import itself; the connection config it
        // refers to is supplied as system properties in configureAndSeedVault().
        properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
class SubscribeMasterApplicationIT {

  private static final String VAULT_TOKEN = "test-root-token";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("subscribe_master")
          .withUsername("subscribe_master")
          .withPassword("test_only_not_a_real_secret");

  @Container
  static final GenericContainer<?> vault = new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
          .withExposedPorts(8200)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", VAULT_TOKEN)
          .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
          .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200));

  @Autowired
  private TestRestTemplate restTemplate;

  @BeforeAll
  static void configureAndSeedVault() throws Exception {
    System.setProperty("spring.cloud.vault.host", vault.getHost());
    System.setProperty("spring.cloud.vault.port", String.valueOf(vault.getMappedPort(8200)));
    System.setProperty("spring.cloud.vault.scheme", "http");
    System.setProperty("spring.cloud.vault.authentication", "TOKEN");
    System.setProperty("spring.cloud.vault.token", VAULT_TOKEN);

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
    System.clearProperty("spring.cloud.vault.host");
    System.clearProperty("spring.cloud.vault.port");
    System.clearProperty("spring.cloud.vault.scheme");
    System.clearProperty("spring.cloud.vault.authentication");
    System.clearProperty("spring.cloud.vault.token");
  }

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
  }

  @Test
  void contextLoadsMigratesAndResolvesSecretsFromVault() {
    assertThat(postgres.isRunning()).isTrue();
    assertThat(vault.isRunning()).isTrue();
  }

  @Test
  void openApiSpecIsServed() {
    ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}