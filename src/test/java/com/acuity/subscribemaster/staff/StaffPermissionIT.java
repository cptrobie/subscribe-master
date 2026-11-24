package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.staff.dto.StaffLoginRequest;
import com.acuity.subscribemaster.staff.dto.StaffLoginResponse;
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
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for staff permission checks (FR-06): {@code @PreAuthorize} on a staff endpoint,
 * through the real HTTP stack, staff security chain, {@code StaffJwtAuthenticationConverter},
 * Postgres (Testcontainers) and Vault. Tokens come from the real staff login endpoint.
 *
 * <p><b>The probe endpoint.</b> No production endpoint checks a permission yet, so {@link
 * PermissionProbeController} adds one, guarded by {@code hasAuthority('view_audit_logs')}. It is a
 * class nested in this test, which Spring Boot's test scanning skips, so it exists only in this
 * test's context through {@code @Import}: never in the application and never in other ITs. Its path
 * is under {@code /api/v1/staff/**}, so requests go through the staff chain.
 *
 * <p><b>What these tests show.</b>
 *
 * <ul>
 *   <li>a staff user whose role has the permission gets a 200;
 *   <li>one whose role lacks it gets a 403 with {@code ACCESS_DENIED}, not a 500. A denied {@code
 *       PreAuthorize} reaches {@code GlobalExceptionHandler}, whose catch-all would otherwise turn
 *       it into a 500;
 *   <li>revoking or granting a permission in {@code role_permissions} takes effect on the very next
 *       request with the <em>same</em> token. This is why permissions are looked up per request
 *       rather than carried in the token.
 * </ul>
 *
 * <p><b>Changing permissions.</b> {@code Role} deliberately has no setters, since roles change only
 * through migrations until {@code manage_roles} exists, so the tests change {@code
 * role_permissions} with SQL through {@link JdbcTemplate}. They never change a seeded role: tests
 * that grant or revoke create their own role with a unique name, so other tests in this class keep
 * the seeded permissions.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
@Import(StaffPermissionIT.PermissionProbeController.class)
public class StaffPermissionIT {
  private static final String VAULT_TOKEN = "test-root-token";
  private static final String PASSWORD = "easyPassword!#123";
  private static final String PROBE_PATH = "/api/v1/staff/test/audit-probe";
  private static final String PROBE_PERMISSION = "view_audit_logs";

  /** A test-only staff endpoint that requires {@code view_audit_logs}; see the class Javadoc. */
  @RestController
  static class PermissionProbeController {
    @GetMapping(PROBE_PATH)
    @PreAuthorize("hasAuthority('view_audit_logs')")
    String probe() {
      return "ok";
    }
  }

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
  @Autowired private JdbcTemplate jdbc;

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

  /** Saves an active staff user with a unique email and PASSWORD, holding the named role. */
  private StaffUser saveStaffUser(String roleName) {
    var email = "staff-" + UUID.randomUUID() + "@example.com";
    var role = roleRepo.findByName(roleName).orElseThrow();
    return staffRepo.saveAndFlush(new StaffUser(email, passwordEncoder.encode(PASSWORD), role));
  }

  /** Logs in through the real staff login endpoint and returns the token; asserts the 200. */
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

  private <T> ResponseEntity<T> getWithBearer(String path, String token, Class<T> type) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
  }

  /** Creates a test-only role with no permissions and returns its unique name. */
  private String createRole() {
    var name = "test_role_" + UUID.randomUUID();
    jdbc.update("INSERT INTO roles (name, description) VALUES (?, 'test only')", name);
    return name;
  }

  private void grant(String roleName, String permissionName) {
    jdbc.update(
        "INSERT INTO role_permissions (role_id, permission_id)"
            + " SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = ? AND p.name = ?",
        roleName,
        permissionName);
  }

  private void revoke(String roleName, String permissionName) {
    jdbc.update(
        "DELETE FROM role_permissions"
            + " WHERE role_id = (SELECT id FROM roles WHERE name = ?)"
            + " AND permission_id = (SELECT id FROM permissions WHERE name = ?)",
        roleName,
        permissionName);
  }

  @Test
  void withPermission_returnsOk() {
    var token = loginForToken(saveStaffUser("auditor"));

    var response = getWithBearer(PROBE_PATH, token, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("ok");
  }

  @Test
  void withoutPermission_returnsForbidden() {
    var token = loginForToken(saveStaffUser("support_agent"));

    var response = getWithBearer(PROBE_PATH, token, ApiError.class);
    var body = response.getBody();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(body).isNotNull();
    assertThat(body.code()).isEqualTo("ACCESS_DENIED");
    assertThat(body.message()).doesNotContain(PROBE_PERMISSION);
  }

  @Test
  void permissionRevoked_sameToken_returnsForbidden() {
    var roleName = createRole();
    grant(roleName, PROBE_PERMISSION);
    var token = loginForToken(saveStaffUser(roleName));
    assertThat(getWithBearer(PROBE_PATH, token, String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    revoke(roleName, PROBE_PERMISSION);
    var afterRevoke = getWithBearer(PROBE_PATH, token, String.class);

    assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void permissionGranted_sameToken_returnsOk() {
    var roleName = createRole();
    var token = loginForToken(saveStaffUser(roleName));
    assertThat(getWithBearer(PROBE_PATH, token, String.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    grant(roleName, PROBE_PERMISSION);
    var afterGrant = getWithBearer(PROBE_PATH, token, String.class);

    assertThat(afterGrant.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(afterGrant.getBody()).isEqualTo("ok");
  }
}
