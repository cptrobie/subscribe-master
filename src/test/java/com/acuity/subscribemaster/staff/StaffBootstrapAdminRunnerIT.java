package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogRepository;
import com.acuity.subscribemaster.staff.dto.StaffLoginRequest;
import com.acuity.subscribemaster.staff.dto.StaffLoginResponse;
import com.acuity.subscribemaster.staff.dto.StaffMeResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for {@link StaffBootstrapAdminRunner} (FR-06): the first admin created at
 * startup, against a real Postgres (Testcontainers) and Vault, through the real HTTP stack and
 * security chains.
 *
 * <p><b>Why a class of its own.</b> The runner acts only once, at startup, and only when {@code
 * staff_users} is empty. This class starts its own database and is the only IT that puts {@code
 * app.staff.bootstrap-admin.*} in Vault, so the runner has already created the admin by the time
 * the first test runs. Every other IT leaves the keys unset, so the runner skips there. The email
 * is stored in Vault in mixed case ({@code BOOTSTRAP_EMAIL_AS_CONFIGURED}), so these tests also
 * prove it is normalized.
 *
 * <p><b>What only this test can show.</b> The unit tests cover each path with mocks; these show the
 * result is real:
 *
 * <ul>
 *   <li>exactly one admin exists, found by the lowercased email, with a BCrypt hash rather than the
 *       password;
 *   <li>that admin can log in through the real staff login and gets the {@code admin} role from
 *       {@code GET /api/v1/staff/me}, so the hash, the role and the email all work together;
 *   <li>one {@code STAFF_USER_CREATED} row was written, with the admin as actor, {@code {"source":
 *       "bootstrap"}} surviving the JSONB column and a null IP address;
 *   <li>running the runner again, as a restart would, creates nothing more.
 * </ul>
 *
 * <p>All tests share the one admin and none of them changes it: no wrong passwords, lockouts or
 * deactivation. So they can run in any order. Startup failing when only one key is set, and two
 * instances racing to create the admin, are covered by {@code StaffBootstrapAdminRunnerTest}.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
public class StaffBootstrapAdminRunnerIT {
  private static final String VAULT_TOKEN = "test-root-token";
  private static final String PASSWORD = "easyPassword!#123";
  private static final String BOOTSTRAP_EMAIL_AS_CONFIGURED = "Bootstrap.Admin@Example.COM";
  private static final String BOOTSTRAP_EMAIL = "bootstrap.admin@example.com";

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
  @Autowired private StaffBootstrapAdminRunner runner;
  @Autowired private StaffUserRepository staffRepo;
  @Autowired private AuditLogRepository auditLogRepo;

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
            "app.idempotency.hmac-secret=test_only_not_a_real_secret",
            "app.staff.bootstrap-admin.email=" + BOOTSTRAP_EMAIL_AS_CONFIGURED,
            "app.staff.bootstrap-admin.password=" + PASSWORD);
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

  private <T> ResponseEntity<T> getWithBearer(String path, String token, Class<T> type) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
  }

  /** Logs in through the real staff login endpoint and returns the token; asserts the 200. */
  private String loginForToken(String email, String password) {
    var response =
        restTemplate.postForEntity(
            "/api/v1/staff/auth/login",
            new StaffLoginRequest(email, password),
            StaffLoginResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().token();
  }

  @Test
  void startup_withBothKeysAndNoStaff_createsOneNormalizedAdmin() {
    // the runner ran at startup.
    assertThat(staffRepo.count()).isEqualTo(1);

    var admin = staffRepo.findByEmail(BOOTSTRAP_EMAIL);
    assertThat(admin).isPresent();

    var hash = admin.orElseThrow().getPasswordHash();
    assertThat(hash).isNotEqualTo(PASSWORD);
    assertThat(hash).startsWith("$2");
  }

  @Test
  void bootstrapAdmin_canLogInAndSeesAdminRole() {
    var token = loginForToken(BOOTSTRAP_EMAIL, PASSWORD);
    var response = getWithBearer("/api/v1/staff/me", token, StaffMeResponse.class);
    var responseBody = response.getBody();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(responseBody).isNotNull();
    assertThat(responseBody.role()).isEqualTo("admin");
    assertThat(responseBody.email()).isEqualTo(BOOTSTRAP_EMAIL);
  }

  @Test
  void startup_recordsOneStaffUserCreatedAuditRow() {
    var adminId = staffRepo.findByEmail(BOOTSTRAP_EMAIL).orElseThrow().getId();
    var rows = auditLogRepo.findByActorIdAndAction(adminId, AuditAction.STAFF_USER_CREATED);
    assertThat(rows).hasSize(1);

    var row = rows.get(0);
    assertThat(row.getActorType()).isEqualTo(ActorType.STAFF);
    assertThat(row.getResource()).isEqualTo("staff_user");
    assertThat(row.getResourceId()).isEqualTo(adminId);
    assertThat(row.getDetails()).containsEntry("source", "bootstrap");
    assertThat(row.getIpAddress()).isNull();
  }

  @Test
  void rerun_withAdminPresent_createsNothing() {
    runner.run(null); // This simulates a restart, now that an admin exists.

    assertThat(staffRepo.count()).isEqualTo(1);
    var adminId = staffRepo.findByEmail(BOOTSTRAP_EMAIL).orElseThrow().getId();

    var rows = auditLogRepo.findByActorIdAndAction(adminId, AuditAction.STAFF_USER_CREATED);
    assertThat(rows).hasSize(1);
  }
}
