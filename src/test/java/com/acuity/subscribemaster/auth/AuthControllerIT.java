package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.acuity.subscribemaster.auditlog.AuditLogRepository;
import com.acuity.subscribemaster.auth.dto.LoginRequest;
import com.acuity.subscribemaster.auth.dto.LoginResponse;
import com.acuity.subscribemaster.auth.dto.RegistrationRequest;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.customer.CustomerSessionRepository;
import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * End-to-end registration coverage against a real Postgres + Vault, following the
 * Testcontainers/Vault bootstrap pattern established in SubscribeMasterApplicationIT. Exercises
 * what AuthControllerTest/AuthServiceTest structurally cannot: real BCrypt hash persistence and the
 * real (not hand-built) Jackson/Bean Validation wiring. Does NOT verify citext's case-insensitive
 * matching -- email is normalized to lowercase before reaching AuthService (see
 * duplicateEmailDifferentCase_isRejectedByExistsByEmailCheck), so this suite genuinely cannot
 * confirm whether citext's DB-level behavior is correct.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
public class AuthControllerIT {

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
  @Autowired private CustomerRepository customerRepo;
  @Autowired private CustomerSessionRepository sessionRepo;
  @Autowired private AuditLogRepository auditLogRepo;
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

    var putResult =
        vault.execInContainer(
            "vault",
            "kv",
            "put",
            "-address=http://127.0.0.1:8200",
            "secret/subscribe-master",
            "spring.datasource.username=subscribe_master",
            "spring.datasource.password=test_only_not_a_real_secret");
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
  void registerNewCustomer_persistsHashedPasswordAndAuditEvent() {
    var email = "new.customer@example.com";
    var rawPassword = "easyPassword!#123";
    var request = new RegistrationRequest(email, rawPassword);

    ResponseEntity<RegistrationResponse> response =
        restTemplate.postForEntity("/api/v1/auth/register", request, RegistrationResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.email()).isEqualTo(email);
    assertThat(body.emailVerified()).isFalse();
    assertThat(body.id()).isNotNull();
    assertThat(body.createdAt()).isNotNull();

    var savedCustomer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(savedCustomer.getPasswordHash()).isNotEqualTo(rawPassword);
    assertThat(passwordEncoder.matches(rawPassword, savedCustomer.getPasswordHash())).isTrue();

    var auditEvents = auditLogRepo.findByActorIdAndAction(savedCustomer.getId(), "REGISTERED");
    assertThat(auditEvents).hasSize(1);
    assertThat(auditEvents.get(0).getResource()).isEqualTo("customers");
    assertThat(auditEvents.get(0).getResourceId()).isEqualTo(savedCustomer.getId());
  }

  @Test
  void duplicateEmail_returnsConflictAndDoesNotDuplicateRow() {
    var email = "duplicate@example.com";
    var request = new RegistrationRequest(email, "easyPassword!#123");

    restTemplate.postForEntity("/api/v1/auth/register", request, RegistrationResponse.class);

    ResponseEntity<ApiError> secondAttempt =
        restTemplate.postForEntity("/api/v1/auth/register", request, ApiError.class);

    assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(secondAttempt.getBody()).isNotNull();
    assertThat(secondAttempt.getBody().code()).isEqualTo("ACCOUNT_ALREADY_EXISTS");
    assertThat(customerRepo.existsByEmail(email)).isTrue();
  }

  @Test
  void duplicateEmailDifferentCase_isRejectedByExistsByEmailCheck() {
    var request = new RegistrationRequest("Case.Test@Example.com", "easyPassword!#123");
    restTemplate.postForEntity("/api/v1/auth/register", request, RegistrationResponse.class);

    var sameEmailDifferentCase =
        new RegistrationRequest("case.test@example.com", "anotherPassword!#456");

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/v1/auth/register", sameEmailDifferentCase, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("ACCOUNT_ALREADY_EXISTS");

    // RegistrationRequest normalizes email to lowercase before it ever reaches AuthService,
    // so both requests above resolve to the identical string "case.test@example.com" --
    // existsByEmail() gets a plain exact match. This was a deliberate choice to sidestep
    // diagnosing whether citext's case-insensitive matching is actually working correctly
    // at the database level (CustomerRepository's Javadoc claims it should be) -- that's
    // genuinely unverified, not confirmed broken, and worth a real investigation later if
    // it matters (e.g. once a client that can't normalize email itself needs to rely on it).
    var savedCustomer = customerRepo.findByEmail("case.test@example.com").orElseThrow();
    assertThat(savedCustomer.getEmail()).isEqualTo("case.test@example.com");

    var rejections =
        auditLogRepo.findByActorIdAndAction(savedCustomer.getId(), "REGISTRATION_REJECTED");
    assertThat(rejections).hasSize(1);
    assertThat(rejections.get(0).getActorType()).isEqualTo("customer");
  }

  @Test
  void invalidBody_returnsValidationFailedWithFieldErrors() {
    var request = new RegistrationRequest("not-an-email", "short");

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/v1/auth/register", request, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
    assertThat(response.getBody().fieldErrors())
        .extracting(ApiError.FieldError::field)
        .contains("email", "password");
  }

  @Test
  void malformedJsonBody_returnsBadRequest() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var entity = new HttpEntity<>("{not valid json", headers);

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/v1/auth/register", entity, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("MALFORMED_REQUEST");
  }

  @Test
  void registerThenLogin_persistsHashedSessionTokenAndReturnsRawToken() {
    var email = "login.roundtrip@example.com";
    var rawPassword = "easyPassword!#123";

    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, rawPassword),
        RegistrationResponse.class);

    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, rawPassword), LoginResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.token()).isNotBlank();
    assertThat(body.expiresAt()).isNotNull().isAfter(Instant.now().plus(12, ChronoUnit.MINUTES));

    var savedCustomer = customerRepo.findByEmail(email).orElseThrow();
    var sessions = sessionRepo.findByCustomerId(savedCustomer.getId());
    assertThat(sessions).hasSize(1);

    // The stored token is a HASH, never the raw bearer token -- same discipline as
    // password_hash. Prove they're the SAME underlying token by hashing the raw
    // one returned and comparing, rather than trusting the response alone.
    assertThat(sessions.get(0).getSessionToken()).isNotEqualTo(body.token());
    assertThat(sessions.get(0).getSessionToken()).isEqualTo(Tokens.hash(body.token()));
  }

  @Test
  void loginWithWrongPassword_returnsUnauthorizedAndCreatesNoSession() {
    var email = "addme@example.com";
    var rawPassword = "easyPassword!#123";
    var wrongPassword = "wrongPassword!#123";

    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, rawPassword),
        RegistrationResponse.class);

    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, wrongPassword), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");

    var savedCustomer = customerRepo.findByEmail(email).orElseThrow();
    var sessions = sessionRepo.findByCustomerId(savedCustomer.getId());

    assertThat(sessionRepo.findByCustomerId(savedCustomer.getId())).isEmpty();
  }

  @Test
  void loginWithNonexistentEmail_returnsIdenticalResponseToWrongPassword() {
    // No registration here -- this email was never created. The whole point of
    // this test is that the response is INDISTINGUISHABLE from a wrong password
    // against a real account (see AuthService's Javadoc on enumeration protection).
    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("never.registered@example.com", "anyPassword!#123"),
            ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
  }
}
