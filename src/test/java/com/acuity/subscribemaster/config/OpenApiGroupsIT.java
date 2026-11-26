package com.acuity.subscribemaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Tests for the customer and staff OpenAPI groups (NFR-46). They read the specs springdoc actually
 * serves, from a full application context against Postgres and Vault (Testcontainers).
 *
 * <p><b>What these tests show.</b>
 *
 * <ul>
 *   <li>Each group holds only its own paths: no staff path in the customer spec, and only staff
 *       paths in the staff spec.
 *   <li>Each spec has its own title and its own {@code bearerAuth} scheme, whose description names
 *       the login that issues the token.
 *   <li>Every operation outside the unauthenticated paths carries the {@code bearerAuth}
 *       requirement, and the unauthenticated ones carry none. This is the guard for new
 *       controllers: one without {@code @SecurityRequirement} fails here.
 *   <li>Swagger UI's config lists both groups, with customer as the default.
 *   <li>The combined {@code /v3/api-docs} isn't served. It returns 400 {@code MALFORMED_REQUEST}
 *       rather than 404, because {@code GlobalExceptionHandler} maps an unmatched route that way.
 * </ul>
 *
 * <p>No test logs in: the specs are public, and whether a token works on an endpoint is tested by
 * the controller ITs.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
public class OpenApiGroupsIT {
  private static final String VAULT_TOKEN = "test-root-token";

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine")
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

  @Autowired private TestRestTemplate restTemplate;

  @BeforeAll
  static void configureAndSeedVault() throws Exception {
    System.setProperty("spring.cloud.vault.host", vault.getHost());
    System.setProperty("spring.cloud.vault.port", String.valueOf(vault.getMappedPort(8200)));
    System.setProperty("spring.cloud.vault.scheme", "http");
    System.setProperty("spring.cloud.vault.authentication", "TOKEN");
    System.setProperty("spring.cloud.vault.token", VAULT_TOKEN);

    var loginResult =
        vault.execInContainer("vault", "login", "-address=http://127.0.0.1:8200", VAULT_TOKEN);
    if (loginResult.getExitCode() != 0) {
      throw new IllegalStateException("vault login failed: " + loginResult.getStderr());
    }

    String testPrivateKeyPem = readTestResource("test-jwt-keys/jwt-private.pem");
    String testPublicKeyPem = readTestResource("test-jwt-keys/jwt-public.pem");

    var putResult =
        vault.execInContainer(
            "vault",
            "kv",
            "put",
            "-address=http://127.0.0.1:8200",
            "secret/subscribe-master",
            "spring.datasource.username=subscribe_master",
            "spring.datasource.password=test_only_not_a_real_secret",
            "jwt.private-key=" + testPrivateKeyPem,
            "jwt.public-key=" + testPublicKeyPem,
            "app.idempotency.hmac-secret=test_only_not_a_real_secret");
    if (putResult.getExitCode() != 0) {
      throw new IllegalStateException("vault kv put failed: " + putResult.getStderr());
    }
  }

  /** Reads a classpath resource as UTF-8 text; the same helper as {@code AuthControllerIT}'s. */
  private static String readTestResource(String classpathLocation) throws IOException {
    var resource = new ClassPathResource(classpathLocation);
    try (var inputStream = resource.getInputStream()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
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

  private JsonNode spec(String group) {
    var response = restTemplate.getForEntity("/v3/api-docs/" + group, JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private List<String> paths(JsonNode spec) {
    return List.copyOf(spec.path("paths").propertyNames());
  }

  /**
   * The unauthenticated API paths, as permitted in {@code SecurityConfig}; keep the two in sync.
   */
  private static boolean isPublic(String path) {
    return path.startsWith("/api/v1/auth/") || path.equals("/api/v1/staff/auth/login");
  }

  @Test
  void customerSpec_containsOnlyCustomerPaths() {
    var paths = paths(spec("customer"));
    assertThat(paths).allMatch(p -> p.startsWith("/api/v1/"));
    assertThat(paths).noneMatch(p -> p.startsWith("/api/v1/staff/"));
    assertThat(paths).contains("/api/v1/auth/login", "/api/v1/subscribe");
  }

  @Test
  void staffSpec_containsOnlyStaffPaths() {
    var paths = paths(spec("staff"));
    assertThat(paths).allMatch(p -> p.startsWith("/api/v1/staff/"));
    assertThat(paths).contains("/api/v1/staff/auth/login", "/api/v1/staff/me");
  }

  @Test
  void eachSpec_hasItsOwnTitleAndBearerScheme() {
    var customerNode = spec("customer");
    var customerScheme = customerNode.path("components").path("securitySchemes").path("bearerAuth");

    assertThat(customerNode.path("info").path("title").asString())
        .isEqualTo("Subscribe Master Customer API");
    assertThat(customerScheme.path("type").asString()).isEqualTo("http");
    assertThat(customerScheme.path("scheme").asString()).isEqualTo("bearer");
    assertThat(customerScheme.path("description").asString()).contains("/api/v1/auth/login");

    var staffNode = spec("staff");
    var staffScheme = staffNode.path("components").path("securitySchemes").path("bearerAuth");

    assertThat(staffNode.path("info").path("title").asString())
        .isEqualTo("Subscribe Master Staff API");
    assertThat(staffScheme.path("type").asString()).isEqualTo("http");
    assertThat(staffScheme.path("scheme").asString()).isEqualTo("bearer");
    assertThat(staffScheme.path("description").asString()).contains("/api/v1/staff/auth/login");
  }

  @ParameterizedTest
  @ValueSource(strings = {"customer", "staff"})
  void everyProtectedOperation_requiresBearerAuth(String group) {
    for (var pathEntry : spec(group).path("paths").properties()) {
      for (var op : pathEntry.getValue().properties()) {
        var label = group + " " + op.getKey() + " " + pathEntry.getKey();
        if (isPublic(pathEntry.getKey())) {
          assertThat(op.getValue().has("security")).as(label).isFalse();
        } else {
          assertThat(op.getValue().path("security").toString()).as(label).contains("bearerAuth");
        }
      }
    }
  }

  @Test
  void swaggerConfig_listsBothGroups_customerFirst() {
    var response = restTemplate.getForEntity("/v3/api-docs/swagger-config", JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    var body = response.getBody();
    assertThat(body).isNotNull();

    var groupUrls = body.path("urls").valueStream().map(u -> u.path("url").asString()).toList();
    assertThat(groupUrls).containsExactly("/v3/api-docs/customer", "/v3/api-docs/staff");
    assertThat(body.path("urls.primaryName").asString()).isEqualTo("customer");
  }

  @Test
  void combinedSpec_isNotServed() {
    var response = restTemplate.getForEntity("/v3/api-docs", JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    var body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.path("code").asString()).isEqualTo("MALFORMED_REQUEST");
  }
}
