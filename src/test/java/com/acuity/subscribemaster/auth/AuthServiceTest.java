package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.BDDAssertions.within;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit test -- no Spring context, no infrastructure. Mocks every AuthService dependency to
 * isolate its actual logic.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private CustomerRepository customerRepo;
  @Mock private CustomerSessionRepository sessionRepo;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AuditLogService auditLogService;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            customerRepo,
            sessionRepo,
            passwordEncoder,
            auditLogService,
            12L, // sessionDurationHours
            5, // maxLoginAttempts
            15); // lockoutDurationMinutes
  }

  @Test
  void successfulRegistration_savesHashedPasswordAndRecordsAuditEvent() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var createdAt = Instant.now();

    // Customer has no constructor/setter for createdAt -- it's Hibernate-managed
    // via @CreationTimestamp -- so reflection is the only way to simulate a
    // persisted row's real timestamp here.
    var savedCustomer = new Customer(customerId, email, encodedPassword, false, true);
    ReflectionTestUtils.setField(savedCustomer, "createdAt", createdAt);

    when(customerRepo.existsByEmail(email)).thenReturn(false);
    when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
    when(customerRepo.saveAndFlush(any(Customer.class))).thenReturn(savedCustomer);

    // Act
    var response = authService.register(email, rawPassword, ipAddress);

    // Assert: the Customer passed to saveAndFlush carried the HASH, not the raw password
    var customerCaptor = ArgumentCaptor.forClass(Customer.class);
    verify(customerRepo).saveAndFlush(customerCaptor.capture());
    assertThat(customerCaptor.getValue().getPasswordHash()).isEqualTo(encodedPassword);

    // Assert: audit event recorded with the correct actor/resource
    verify(auditLogService, times(1))
        .recordEvent("customer", customerId, "REGISTERED", "customers", customerId, ipAddress);

    // Assert: response reflects the saved customer
    assertThat(response.id()).isEqualTo(customerId);
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.emailVerified()).isFalse();
    assertThat(response.createdAt()).isEqualTo(createdAt);

    verify(customerRepo, times(1)).existsByEmail(email);
  }

  // Covers AuthService.duplicateEmailDetected -- the existsByEmail() check catching the
  // duplicate before any insert is attempted.
  @Test
  void existsByEmailCheck_throwsAccountAlreadyExistsAndRecordsRejectionAudit() {

    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var existingId = UUID.randomUUID();
    var existingCustomer = new Customer(existingId, email, "someHash", false, true);

    when(customerRepo.existsByEmail(email)).thenReturn(true);
    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(existingCustomer));

    // Act & Assert
    assertThatThrownBy(() -> authService.register(email, password, ipAddress))
        .isInstanceOf(AccountAlreadyExistsException.class)
        .hasMessage("An account with this email address already exists."); // taken from the actual
    // exception

    // Verify repository interaction
    verify(customerRepo, times(1)).existsByEmail(email);
    verify(customerRepo, never()).save(any());
    verify(customerRepo, never()).saveAndFlush(any());

    // Assert: rejection is audited against the existing account, not a new one
    verify(auditLogService, times(1))
        .recordEvent(
            "customer", existingId, "REGISTRATION_REJECTED", "customers", existingId, ipAddress);
  }

  // TODO: Commenting out now because its not in scope however when we add idempotency it may
  // resurface. If at that time its still not needed we can make decision to remove or retain
  //
  // Covers AuthService.duplicateEmailRaceLost -- existsByEmail() misses it (lost the race with
  // a concurrent registration), and the DB's citext unique constraint catches it at insert time
  // instead, surfacing as DataIntegrityViolationException from saveAndFlush(). Deliberately does
  // NOT stub customerRepo.findByEmail(...): the real AuthService no longer calls it here, since
  // that query would run inside the transaction saveAndFlush's failure already poisoned against
  // a real Postgres connection (see the javadoc on duplicateEmailRaceLost). If this test ever
  // needed findByEmail stubbed to pass, that itself would be a regression back to the bug.
  /*
  @Test
  void saveAndFlushConstraintViolation_throwsUserAlreadyExistsAndRecordsRejectionAudit() {

    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(customerRepo.existsByEmail(email)).thenReturn(false);
    when(passwordEncoder.encode(password)).thenReturn("bCrypted_password");
    when(customerRepo.saveAndFlush(any(Customer.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    // Act & Assert
    assertThatThrownBy(() -> authService.register(email, password, ipAddress))
        .isInstanceOf(UserAlreadyExistsException.class);

    // Assert: rejection is audited against the "system" sentinel with no resourceId -- the
    // existing customer's id is deliberately not looked up here (see AuthService javadoc).
    verify(auditLogService, times(1))
        .recordEvent(
            "system", AuthService.SYSTEM_ACTOR_ID, "REGISTRATION_REJECTED", "customers", null, ipAddress);
  }
   */

  @Test
  void successfulLogin_savesHashedTokenResponseCarriesSessionExpiresAtCountersReset() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var rawToken = Tokens.generate();
    var hashedToken = Tokens.hash(rawToken);
    var userAgent = "dummy_agent";
    var createdAt = Instant.now();
    var expiresAt = Instant.now().plus(3L, ChronoUnit.MINUTES);

    // Customer has no constructor/setter for createdAt -- it's Hibernate-managed
    // via @CreationTimestamp -- so reflection is the only way to simulate a
    // persisted row's real timestamp here.
    var loggedInCustomer = new Customer(customerId, email, encodedPassword, false, true);
    ReflectionTestUtils.setField(loggedInCustomer, "createdAt", createdAt);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(loggedInCustomer));
    when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

    // Act
    var response = authService.login(email, rawPassword, userAgent, ipAddress);

    // Assert: response reflects a real, freshly-generated token and the session's expiry
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.token()).isNotBlank();

    // Assert: the session actually saved to the repository carries the HASH, not the raw
    // token -- capture what was passed to save(), don't just trust the response
    var sessionCaptor = ArgumentCaptor.forClass(CustomerSession.class);
    verify(sessionRepo).save(sessionCaptor.capture());
    var savedSession = sessionCaptor.getValue();

    assertThat(savedSession.getCustomerId()).isEqualTo(customerId);
    assertThat(savedSession.getIpAddress()).isEqualTo(ipAddress);
    assertThat(savedSession.getUserAgent()).isEqualTo(userAgent);

    // The response's raw token, hashed, must match what actually got persisted --
    // proves the SAME token is both saved (hashed) and returned (raw), not two
    // different values.
    assertThat(Tokens.hash(response.token())).isEqualTo(savedSession.getSessionToken());

    // Assert: response's expiresAt matches the session's real expiry, not the
    // account's createdAt (the exact bug this test exists to guard against)
    assertThat(response.expiresAt()).isEqualTo(savedSession.getExpiresAt());

    // Assert: successful login resets both lockout-tracking fields
    assertThat(loggedInCustomer.getFailedLoginCount()).isEqualTo(0);
    assertThat(loggedInCustomer.getLockedUntil()).isNull();
  }

  @Test
  void unregisteredEmailLoginFailure_respondsWithInvalidCredentialsExceptionThrown() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var userAgent = "dummy_agent";

    when(customerRepo.findByEmail(email)).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> authService.login(email, rawPassword, userAgent, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: the rejection short-circuits cleanly -- no session created, no password
    // comparison attempted (there's no real customer/password hash to compare against)
    verify(sessionRepo, never()).save(any());
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void
      registeredEmailWrongPasswordBelowFailureCountThreshold_respondsWithInvalidCredentialsExceptionThrown() {
    // Arrange
    var email = "test@example.com";
    var wrongPassword = "guessedAtPassword";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var userAgent = "dummy_agent";

    var registeredCustomer = new Customer(customerId, email, encodedPassword, false, true);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(registeredCustomer));
    when(passwordEncoder.matches(wrongPassword, encodedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> authService.login(email, wrongPassword, userAgent, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: that the failedloginCount gets incremented, but since it has not met the
    // lockout threshold, the lockedUntil remain Null
    assertThat(registeredCustomer.getFailedLoginCount()).isEqualTo(1);
    assertThat(registeredCustomer.getLockedUntil()).isNull();

    // Assert: a single below-threshold failure is NOT audited -- only the lockout
    // trigger itself is (see the class Javadoc's reasoning on audit-log noise)
    verify(auditLogService, never()).recordEvent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void
      registeredEmailWrongPasswordHitsFailureCountThreshold_respondsWithAccountLockedExceptionThrown() {
    // Arrange
    var email = "test@example.com";
    var wrongPassword = "guessedAtPassword";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var userAgent = "dummy_agent";
    var failedLoginCountThresholdValue = 5;
    var lockedUntil = Instant.now().plus(15L, ChronoUnit.MINUTES);

    var registeredCustomer = new Customer(customerId, email, encodedPassword, false, true);
    registeredCustomer.setFailedLoginCount(failedLoginCountThresholdValue - 1);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(registeredCustomer));
    when(passwordEncoder.matches(wrongPassword, encodedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> authService.login(email, wrongPassword, userAgent, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    // Assert: that the failedloginCount matches the threshold value and sets the lockedUntil value
    assertThat(registeredCustomer.getFailedLoginCount()).isEqualTo(failedLoginCountThresholdValue);
    assertThat(registeredCustomer.getLockedUntil())
        .isCloseTo(lockedUntil, within(2, ChronoUnit.SECONDS));

    // Assert: that the lockout trigger has been met which justifies an audit record
    verify(auditLogService, times(1))
        .recordEvent(
            "customer",
            registeredCustomer.getId(),
            "ACCOUNT_LOCKED",
            "customers",
            registeredCustomer.getId(),
            ipAddress);
  }

  @Test
  void loginAttemptOnLockedAccount_respondsWithLockedAccountRegardlessIfCredentialsAreValidOrNot() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var userAgent = "dummy_agent";
    var lockedUntil =
        Instant.now()
            .plus(14L, ChronoUnit.MINUTES); // assume one minute has passed since originally set

    var lockedCustomerAccount = new Customer(customerId, email, encodedPassword, false, true);
    lockedCustomerAccount.setLockedUntil(lockedUntil);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(lockedCustomerAccount));

    // Act & Assert
    assertThatThrownBy(() -> authService.login(email, rawPassword, userAgent, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    assertThat(lockedCustomerAccount.getLockedUntil())
        .isCloseTo(lockedUntil, within(2, ChronoUnit.SECONDS));

    // According to Service Logic the account is checked to determine if it is
    // already locked even before the password is checked so this should NEVER occur
    verify(passwordEncoder, never()).matches(anyString(), anyString());

    // Assert: a single above-threshold failure is NOT audited -- only the lockout
    // trigger itself is (see the class Javadoc's reasoning on audit-log noise)
    verify(auditLogService, never()).recordEvent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void
      previouslyLockedAccountLockTimeHasExpired_respondsByResettingFailedLoginCountToZeroAndLockedUnitToNull() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var userAgent = "dummy_agent";
    var lockedExpireTime = Instant.now().minus(30L, ChronoUnit.SECONDS); // "some" time in the past

    var retryCustomer = new Customer(customerId, email, encodedPassword, false, true);
    ReflectionTestUtils.setField(retryCustomer, "lockedUntil", lockedExpireTime);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(retryCustomer));
    when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

    // Act
    var response = authService.login(email, rawPassword, userAgent, ipAddress);

    // Assert: successful login resets both lockout-tracking fields
    assertThat(retryCustomer.getFailedLoginCount()).isEqualTo(0);
    assertThat(retryCustomer.getLockedUntil()).isNull();
  }
}
