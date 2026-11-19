package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogRepository;
import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.staff.dto.StaffLoginRequest;
import com.acuity.subscribemaster.staff.dto.StaffLoginResponse;
import com.acuity.subscribemaster.support.JwtAudiences;
import com.acuity.subscribemaster.support.JwtIssuer;
import com.acuity.subscribemaster.support.PemKeys;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * End-to-end tests for staff login, {@code POST /api/v1/staff/auth/login} (FR-06), through the real
 * HTTP stack, security chain, Postgres (Testcontainers) and Vault, as {@code AuthControllerIT} does
 * for customers.
 *
 * <p><b>What only this test can show.</b> The unit tests mock the database, so these tests cover
 * what needs a real one:
 *
 * <ul>
 *   <li>the issued token verifies against the real public key and carries {@code aud = staff-api}
 *       and the staff id as {@code sub};
 *   <li>a mixed-case or padded email still logs in, which depends on {@code StaffLoginRequest}
 *       normalizing it, because JPA lookups on the citext column are case-sensitive;
 *   <li>the failure counter and the lock really are committed when the checker throws ({@code
 *       REQUIRES_NEW} with {@code noRollbackFor}), checked by reloading the row;
 *   <li>unknown email, wrong password and deactivated account return identical 401 responses, and a
 *       lock returns 429 even with the correct password;
 *   <li>a successful login resets the failure counter, and an expired lock is cleared on the next
 *       attempt rather than needing a scheduled job;
 *   <li>the audit rows ({@code STAFF_LOGGED_IN}, {@code ACCOUNT_LOCKED}) are written with actor
 *       type {@code STAFF} and the caller's address, and a plain failed attempt writes none;
 *   <li>the two security chains tell tokens apart by {@code aud}: a staff token is rejected on a
 *       customer path and a customer token on a staff path, both as an invalid token.
 * </ul>
 *
 * <p>The lockout settings are read from the real {@code app.staff.login-lockout.*} configuration,
 * not hard-coded, so these tests follow the values the application actually runs with. Staff users
 * are saved with the seeded {@code auditor} role because {@code role_id} is NOT NULL; the role
 * doesn't affect login.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
public class StaffAuthControllerIT {
  private static final String VAULT_TOKEN = "test-root-token";
  private static final String PASSWORD = "easyPassword!#123";

  @Value("${jwt.public-key}")
  private String jwtPublicKeyPem;

  private RSAPublicKey jwtPublicKey;

  @Value("${app.staff.login-lockout.max-attempts}")
  private int maxLoginAttempts;

  @Value("${app.staff.login-lockout.duration-minutes}")
  private int lockoutDurationMinutes;

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
  @Autowired private AuditLogRepository auditLogRepo;
  @Autowired private JwtIssuer jwtIssuer;

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

  @BeforeEach
  void parseJwtPublicKey() throws Exception {
    jwtPublicKey = PemKeys.parsePublicKey(jwtPublicKeyPem);
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

  /**
   * POSTs a login and reads the body as the given type (StaffLoginResponse on success, ApiError on
   * failure).
   */
  private <T> ResponseEntity<T> login(String email, String password, Class<T> type) {
    return restTemplate.postForEntity(
        "/api/v1/staff/auth/login", new StaffLoginRequest(email, password), type);
  }

  @Test
  void staffLoginWithValidCredentials_issuesSignedStaffTokenAndRecordsStaffLoggedIn()
      throws Exception {
    var staffUser = saveStaffUser();

    var response = login(staffUser.getEmail(), PASSWORD, StaffLoginResponse.class);
    var body = response.getBody();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body).isNotNull();
    assertThat(body.id()).isEqualTo(staffUser.getId());
    assertThat(body.email()).isEqualTo(staffUser.getEmail());

    // Prove the token is a genuinely valid, correctly-signed JWT -- not just a
    // non-blank string -- by parsing it and verifying its signature against the
    // real public key, then checking its claims match the staff user who logged in.
    SignedJWT signedJwt = SignedJWT.parse(body.token());
    RSASSAVerifier verifier = new RSASSAVerifier(jwtPublicKey);
    assertThat(signedJwt.verify(verifier)).isTrue();
    assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo(staffUser.getId().toString());
    assertThat(signedJwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isCloseTo(body.expiresAt(), within(2, ChronoUnit.SECONDS));
    assertThat(signedJwt.getJWTClaimsSet().getAudience()).containsExactly(JwtAudiences.STAFF);

    var audits =
        auditLogRepo.findByActorIdAndAction(staffUser.getId(), AuditAction.STAFF_LOGGED_IN);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).getActorType()).isEqualTo(ActorType.STAFF);
    assertThat(audits.get(0).getIpAddress()).isNotBlank();
  }

  @Test
  void staffLoginWithWrongPassword_returnsUnauthorized() {
    var staffUser = saveStaffUser();

    var response = login(staffUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
    var body = response.getBody();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(body).isNotNull();
    assertThat(body.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());

    var reloaded = staffRepo.findById(staffUser.getId()).orElseThrow();
    assertThat(reloaded.getFailedLoginCount()).isEqualTo(1);
    assertThat(reloaded.getLockedUntil()).isNull();

    // Only the lock itself is audited (see StaffCredentialChecker); a plain miss is not.
    assertThat(auditLogRepo.findByActorIdAndAction(staffUser.getId(), AuditAction.ACCOUNT_LOCKED))
        .isEmpty();
  }

  @Test
  void staffLoginAfterFailedAttempt_resetsFailureCounterOnSuccess() {
    var staffUser = saveStaffUser();

    var miss = login(staffUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
    assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(staffRepo.findById(staffUser.getId()).orElseThrow().getFailedLoginCount())
        .isEqualTo(1);

    var success = login(staffUser.getEmail(), PASSWORD, StaffLoginResponse.class);
    assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);

    var reloaded = staffRepo.findById(staffUser.getId()).orElseThrow();
    assertThat(reloaded.getFailedLoginCount()).isZero();
    assertThat(reloaded.getLockedUntil()).isNull();
  }

  @Test
  void staffLoginWithNonexistentEmail_returnsIdenticalResponseToWrongPassword() {
    var staffUser = saveStaffUser();

    var wrongPassword = login(staffUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
    var unknownEmail =
        login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD, ApiError.class);

    assertThat(unknownEmail.getBody()).isNotNull();
    assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(wrongPassword.getBody()).isNotNull();
    assertThat(unknownEmail.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
    assertThat(unknownEmail.getBody().code()).isEqualTo(wrongPassword.getBody().code());
    assertThat(unknownEmail.getBody().message()).isEqualTo(wrongPassword.getBody().message());
  }

  @Test
  void staffLoginForInactiveAccount_returnsIdenticalResponseToWrongPassword() {
    var activeUser = saveStaffUser();
    var inactiveUser = saveStaffUser();
    inactiveUser.setActive(false);
    staffRepo.saveAndFlush(inactiveUser);

    var wrongPassword = login(activeUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
    var inactive = login(inactiveUser.getEmail(), PASSWORD, ApiError.class);

    var reloaded = staffRepo.findById(inactiveUser.getId()).orElseThrow();
    assertThat(reloaded.getFailedLoginCount()).isZero();
    assertThat(reloaded.getLockedUntil()).isNull();

    assertThat(inactive.getBody()).isNotNull();
    assertThat(inactive.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(wrongPassword.getBody()).isNotNull();
    assertThat(inactive.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
    assertThat(inactive.getBody().code()).isEqualTo(wrongPassword.getBody().code());
    assertThat(inactive.getBody().message()).isEqualTo(wrongPassword.getBody().message());
  }

  @Test
  void staffLoginWithMaxWrongPasswords_locksAccountAndPersistsLock() {
    var staffUser = saveStaffUser();

    for (int i = 1; i < maxLoginAttempts; i++) {
      var miss = login(staffUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
      assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    var lockingAttempt = login(staffUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
    assertThat(lockingAttempt.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(lockingAttempt.getBody()).isNotNull();
    assertThat(lockingAttempt.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.name());

    var reloaded = staffRepo.findById(staffUser.getId()).orElseThrow();
    assertThat(reloaded.getFailedLoginCount()).isEqualTo(maxLoginAttempts);
    assertThat(reloaded.getLockedUntil())
        .isCloseTo(
            Instant.now().plus(lockoutDurationMinutes, ChronoUnit.MINUTES),
            within(5, ChronoUnit.SECONDS));

    var lockedAttempt = login(staffUser.getEmail(), PASSWORD, ApiError.class);
    assertThat(lockedAttempt.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(lockedAttempt.getBody()).isNotNull();
    assertThat(lockedAttempt.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.name());

    // A wrong password while locked is still 429, doesn't advance the counter, and
    // doesn't audit the lock a second time.
    var wrongWhileLocked = login(staffUser.getEmail(), "wrong" + PASSWORD, ApiError.class);
    assertThat(wrongWhileLocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(staffRepo.findById(staffUser.getId()).orElseThrow().getFailedLoginCount())
        .isEqualTo(maxLoginAttempts);

    var lockAudits =
        auditLogRepo.findByActorIdAndAction(staffUser.getId(), AuditAction.ACCOUNT_LOCKED);
    assertThat(lockAudits).hasSize(1);
    assertThat(lockAudits.get(0).getActorType()).isEqualTo(ActorType.STAFF);
    assertThat(lockAudits.get(0).getIpAddress()).isNotBlank();
  }

  @Test
  void staffLoginAfterLockExpires_clearsLockAndSucceeds() {
    var staffUser = saveStaffUser();
    staffUser.setFailedLoginCount(maxLoginAttempts);
    staffUser.setLockedUntil(Instant.now().minus(1, ChronoUnit.MINUTES));
    staffRepo.saveAndFlush(staffUser);

    var response = login(staffUser.getEmail(), PASSWORD, StaffLoginResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var reloaded = staffRepo.findById(staffUser.getId()).orElseThrow();
    assertThat(reloaded.getFailedLoginCount()).isZero();
    assertThat(reloaded.getLockedUntil()).isNull();
  }

  @Test
  void staffLoginWithMixedCaseEmail_succeeds() {
    var staffUser = saveStaffUser();
    var mixedCaseEmail = "  " + staffUser.getEmail().toUpperCase() + " ";

    var response =
        restTemplate.postForEntity(
            "/api/v1/staff/auth/login",
            Map.of("email", mixedCaseEmail, "password", PASSWORD),
            StaffLoginResponse.class);
    var body = response.getBody();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body).isNotNull();
    assertThat(body.id()).isEqualTo(staffUser.getId());
    assertThat(body.email()).isEqualTo(staffUser.getEmail());
  }

  @Test
  void staffLoginWithInvalidBody_returnsBadRequest() {
    var badResponse = login("not-an-email", "short", ApiError.class);
    var body = badResponse.getBody();

    assertThat(badResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body).isNotNull();
    assertThat(body.code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    assertThat(body.fieldErrors())
        .extracting(ApiError.FieldError::field)
        .contains("email", "password");
  }

  /** GETs a path with the given bearer token; the body is irrelevant to these tests. */
  private ResponseEntity<String> getWithBearer(String path, String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  @Test
  void staffToken_onCustomerEndpoint_returnsUnauthorized() {
    var staffUser = saveStaffUser();
    var loginBody = login(staffUser.getEmail(), PASSWORD, StaffLoginResponse.class).getBody();
    assertThat(loginBody).isNotNull();

    var response = getWithBearer("/api/v1/subscribe", loginBody.token());

    // invalid_token, not a bare challenge: the token was read and refused for its audience,
    // which a request with no token at all would not show.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .contains("invalid_token");
  }

  @Test
  void customerToken_onStaffPath_returnsUnauthorized() {
    var token = jwtIssuer.issueCustomer(UUID.randomUUID()).token();

    // Any path under the staff chain will do: authentication fails before routing.
    var response = getWithBearer("/api/v1/staff/anything", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .contains("invalid_token");
  }
}
