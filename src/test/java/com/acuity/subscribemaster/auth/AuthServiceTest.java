package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.BDDAssertions.within;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.JwtIssuer;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Duration;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit test -- no Spring context, no infrastructure. Mocks every AuthService dependency to
 * isolate its actual logic.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private CustomerRepository customerRepo;
  @Mock private JwtIssuer jwtIssuer;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private CredentialChecker credChecker;
  @Mock private CustomerEmailVerificationTokenRepository emailVerifyTokenRepo;
  @Mock private AuditLogService auditLogService;
  @Mock private ApplicationEventPublisher events;

  private final EmailVerificationProperties emailVerifyProps =
      new EmailVerificationProperties(
          "http://localhost:8080/api/v1/auth/verify-email", Duration.ofHours(24));

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            customerRepo,
            jwtIssuer,
            passwordEncoder,
            credChecker,
            emailVerifyTokenRepo,
            emailVerifyProps,
            events,
            auditLogService);
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
        .recordEvent(
            ActorType.CUSTOMER, customerId, AuditAction.REGISTERED, "auth", customerId, ipAddress);

    // Assert: response reflects the saved customer
    assertThat(response.id()).isEqualTo(customerId);
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.emailVerified()).isFalse();
    assertThat(response.createdAt()).isEqualTo(createdAt);

    verify(customerRepo, times(1)).existsByEmail(email);
  }

  @Test
  void successfulRegistration_savesHashedTokenAndPublishesEventWithRawToken() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var createdAt = Instant.now();

    var savedCustomer = new Customer(customerId, email, encodedPassword, false, true);
    ReflectionTestUtils.setField(savedCustomer, "createdAt", createdAt);

    when(customerRepo.existsByEmail(email)).thenReturn(false);
    when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
    when(customerRepo.saveAndFlush(any(Customer.class))).thenReturn(savedCustomer);

    // Act
    authService.register(email, rawPassword, ipAddress);

    // Assert -- capture what register() actually saved and published
    var tokenCaptor = ArgumentCaptor.forClass(CustomerEmailVerificationToken.class);
    verify(emailVerifyTokenRepo).save(tokenCaptor.capture());
    var savedToken = tokenCaptor.getValue();

    var eventCaptor = ArgumentCaptor.forClass(CustomerRegisteredEvent.class);
    verify(events).publishEvent(eventCaptor.capture());
    var event = eventCaptor.getValue();

    // The event carries the real address and the raw token; only its hash was stored
    assertThat(event.customerId()).isEqualTo(customerId);
    assertThat(event.email()).isEqualTo(email);
    assertThat(event.ipAddress()).isEqualTo(ipAddress);
    assertThat(savedToken.getTokenHash())
        .isEqualTo(Tokens.hash(event.rawToken()))
        .isNotEqualTo(event.rawToken());

    // The token belongs to the new customer and expires after the configured TTL
    assertThat(savedToken.getCustomerId()).isEqualTo(customerId);
    assertThat(savedToken.getExpiresAt())
        .isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(5, ChronoUnit.SECONDS));
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
    verifyNoInteractions(emailVerifyTokenRepo, events);

    // Assert: rejection is audited against the existing account, not a new one
    verify(auditLogService, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            existingId,
            AuditAction.REGISTRATION_REJECTED,
            "auth",
            existingId,
            ipAddress);
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
  void rejectedCredentials_propagatesCheckerExceptionAndIssuesNoJwt() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(credChecker.check(email, rawPassword, ipAddress))
        .thenThrow(new InvalidCredentialsException());

    // Act
    assertThatThrownBy(() -> authService.login(email, rawPassword, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert
    verify(customerRepo, never()).findById(any());
    verifyNoInteractions(jwtIssuer);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void successfulLogin_issuesJwtForCheckedCustomer() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var issuedExpiresAt = Instant.now().plus(5L, ChronoUnit.MINUTES);
    var issuedToken = new JwtIssuer.IssuedToken("fake.jwt.token", issuedExpiresAt);
    var loggedInCustomer = new Customer(customerId, email, encodedPassword, true, true);

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(loggedInCustomer));
    when(jwtIssuer.issue(customerId)).thenReturn(issuedToken);

    // Act
    var response = authService.login(email, rawPassword, ipAddress);

    // Assert: response reflects a real, freshly-generated token and the session's expiry
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.token()).isNotBlank();
    assertThat(response.expiresAt()).isEqualTo(issuedToken.expiresAt());

    // Assert: the customer's own ID is what gets passed to the issuer
    verify(jwtIssuer).issue(customerId);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void unverifiedCustomer_throwsEmailNotVerifiedAndIssuesNoJwt() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var rawPassword = "easyPassword123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var registeredCustomer = new Customer(customerId, email, encodedPassword, false, true);

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(registeredCustomer));

    // Act & Assert
    assertThatThrownBy(() -> authService.login(email, rawPassword, ipAddress))
        .isInstanceOf(EmailNotVerifiedException.class);

    verifyNoInteractions(jwtIssuer);
    verifyNoInteractions(auditLogService);
  }
}
