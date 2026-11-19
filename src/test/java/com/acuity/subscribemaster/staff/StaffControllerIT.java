package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.acuity.subscribemaster.staff.dto.StaffLoginRequest;
import com.acuity.subscribemaster.staff.dto.StaffLoginResponse;
import com.acuity.subscribemaster.staff.dto.StaffMeResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for {@code GET /api/v1/staff/me} (FR-06), the first staff endpoint that needs a
 * token, through the real HTTP stack, security chains, Postgres (Testcontainers) and Vault. Tokens
 * come from the real staff login endpoint, never from {@code JwtIssuer} directly.
 *
 * <p><b>What only this test can show.</b> The unit tests cover each piece on its own; these tests
 * prove the pieces work together:
 *
 * <ul>
 *   <li>a staff token passes the staff chain's audience check and {@code
 *       StaffJwtAuthenticationConverter}, and reaches {@code StaffController}, which returns the
 *       caller's own profile with the seeded {@code auditor} permissions, sorted;
 *   <li>{@code /me} is protected: with no token it returns 401, so it isn't caught by the staff
 *       chain's {@code permitAll()} for {@code /api/v1/staff/auth/**};
 *   <li>deactivating a staff user takes effect on their very next request with the <em>same</em>
 *       token, returning 401 {@code invalid_token}, without waiting for the token to expire. This
 *       is why permissions are looked up per request rather than carried in the token.
 * </ul>
 *
 * <p>Token separation between the staff and customer chains is tested in {@code
 * StaffAuthControllerIT}. Staff users are saved with the seeded {@code auditor} role, which fixes
 * the expected permissions.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
public class StaffControllerIT {
  private static final String VAULT_TOKEN = "test-root-token";
  private static final String PASSWORD = "easyPassword!#123";

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
  @Autowired private StaffUserRepository staffRepo;
  @Autowired private RoleRepository roleRepo;
  @Autowired private PasswordEncoder passwordEncoder;

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

  /** Saves an active staff user with a unique email and PASSWORD, using the seeded auditor role. */
  private StaffUser saveStaffUser() {
    var email = "staff-" + UUID.randomUUID() + "@example.com";
    var role = roleRepo.findByName("auditor").orElseThrow();
    return staffRepo.saveAndFlush(new StaffUser(email, passwordEncoder.encode(PASSWORD), role));
  }

  private <T> ResponseEntity<T> getWithBearer(String path, String token, Class<T> type) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
  }

  private String loginForToken(StaffUser user) {
    var response =
        restTemplate.postForEntity(
            "/api/v1/staff/auth/login",
            new StaffLoginRequest(user.getEmail(), PASSWORD),
            StaffLoginResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().token();
  }

  @Test
  void staffMe_withStaffToken_returnsOwnProfileAndPermissions() {
    var staffUser = saveStaffUser();
    var token = loginForToken(staffUser);

    var response = getWithBearer("/api/v1/staff/me", token, StaffMeResponse.class);
    var body = response.getBody();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body).isNotNull();
    assertThat(body.id()).isEqualTo(staffUser.getId());
    assertThat(body.email()).isEqualTo(staffUser.getEmail());
    assertThat(body.role()).isEqualTo(staffUser.getRole().getName());
    assertThat(body.permissions())
        .containsExactly("view_app_logs", "view_audit_logs", "view_system_health");
  }

  @Test
  void staffMe_withoutToken_returnsUnauthorized() {
    var response = restTemplate.getForEntity("/api/v1/staff/me", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void staffMe_afterDeactivation_returnsUnauthorizedWithSameToken() {
    var staffUser = saveStaffUser();
    var token = loginForToken(staffUser);

    var response = getWithBearer("/api/v1/staff/me", token, StaffMeResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    var reloaded = staffRepo.findById(staffUser.getId()).orElseThrow();
    reloaded.setActive(false);
    staffRepo.saveAndFlush(reloaded);
    var afterDeactivation = getWithBearer("/api/v1/staff/me", token, String.class);

    assertThat(afterDeactivation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(afterDeactivation.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .contains("invalid_token");
  }
}
