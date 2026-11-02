package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.BDDAssertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CredentialCheckerTest {

  @Mock private CustomerRepository customerRepo;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AuditLogService auditLogService;

  private CredentialChecker credChecker;

  @BeforeEach
  void setUp() {
    credChecker =
        new CredentialChecker(
            customerRepo,
            passwordEncoder,
            auditLogService,
            5, // maxLoginAttempts
            15 // lockoutDurationMinutes
            );
  }

  @Test
  void successfulCheck_returnsCustomerIdAndResetsFailedCount() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var loggedInCustomer = new Customer(customerId, email, encodedPassword, true, true);

    loggedInCustomer.setFailedLoginCount(3);
    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(loggedInCustomer));
    when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

    // Act
    var checkedCustomerId = credChecker.check(email, rawPassword, ipAddress);

    // Assert
    assertThat(checkedCustomerId).isEqualTo(customerId);
    assertThat(loggedInCustomer.getFailedLoginCount()).isEqualTo(0);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void unregisteredEmailCheckFailure_respondsWithInvalidCredentialsExceptionThrown() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(customerRepo.findByEmail(email)).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> credChecker.check(email, rawPassword, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: the rejection short-circuits cleanly -- no password
    // comparison attempted (there's no real customer/password hash to compare against)
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verifyNoInteractions(auditLogService);
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
    var registeredCustomer = new Customer(customerId, email, encodedPassword, false, true);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(registeredCustomer));
    when(passwordEncoder.matches(wrongPassword, encodedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> credChecker.check(email, wrongPassword, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: that the failedloginCount gets incremented, but since it has not met the
    // lockout threshold, the lockedUntil remain Null
    assertThat(registeredCustomer.getFailedLoginCount()).isEqualTo(1);
    assertThat(registeredCustomer.getLockedUntil()).isNull();

    // Assert: a single below-threshold failure is NOT audited -- only the lockout
    // trigger itself is (see the class Javadoc's reasoning on audit-log noise)
    verifyNoInteractions(auditLogService);
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
    var failedLoginCountThresholdValue = 5;
    var lockedUntil = Instant.now().plus(15L, ChronoUnit.MINUTES);

    var registeredCustomer = new Customer(customerId, email, encodedPassword, false, true);
    registeredCustomer.setFailedLoginCount(failedLoginCountThresholdValue - 1);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(registeredCustomer));
    when(passwordEncoder.matches(wrongPassword, encodedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> credChecker.check(email, wrongPassword, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    // Assert: that the failedloginCount matches the threshold value and sets the lockedUntil value
    assertThat(registeredCustomer.getFailedLoginCount()).isEqualTo(failedLoginCountThresholdValue);
    assertThat(registeredCustomer.getLockedUntil())
        .isCloseTo(lockedUntil, within(2, ChronoUnit.SECONDS));

    // Assert: that the lockout trigger has been met which justifies an audit record
    verify(auditLogService, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            registeredCustomer.getId(),
            AuditAction.ACCOUNT_LOCKED,
            "auth",
            registeredCustomer.getId(),
            ipAddress);
  }

  @Test
  void checkAttemptOnLockedAccount_respondsWithLockedAccountRegardlessIfCredentialsAreValidOrNot() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var lockedUntil =
        Instant.now()
            .plus(14L, ChronoUnit.MINUTES); // assume one minute has passed since originally set

    var lockedCustomerAccount = new Customer(customerId, email, encodedPassword, false, true);
    lockedCustomerAccount.setLockedUntil(lockedUntil);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(lockedCustomerAccount));

    // Act & Assert
    assertThatThrownBy(() -> credChecker.check(email, rawPassword, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    assertThat(lockedCustomerAccount.getLockedUntil())
        .isCloseTo(lockedUntil, within(2, ChronoUnit.SECONDS));

    // According to Service Logic the account is checked to determine if it is
    // already locked even before the password is checked so this should NEVER occur
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void
      previouslyLockedAccountLockTimeHasExpired_respondsByResettingFailedLoginCountToZeroAndLockedUntilToNull() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var lockedExpireTime = Instant.now().minus(30L, ChronoUnit.SECONDS); // "some" time in the past

    var retryCustomer = new Customer(customerId, email, encodedPassword, true, true);
    ReflectionTestUtils.setField(retryCustomer, "lockedUntil", lockedExpireTime);

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(retryCustomer));
    when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

    // Act
    var checkedCustomerId = credChecker.check(email, rawPassword, ipAddress);

    assertThat(checkedCustomerId).isEqualTo(customerId);
    // Assert: successful check() resets both lockout-tracking fields
    assertThat(retryCustomer.getFailedLoginCount()).isEqualTo(0);
    assertThat(retryCustomer.getLockedUntil()).isNull();
  }

  @Test
  void lockExpiredThenWrongPassword_restartsFailedCountAtOneAndClearsLock() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var wrongPassword = "guessedAtPassword";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";

    // Deliberately unverified: a wrong password must still be a plain 401, never the
    // FR-31 "email not verified" 403 -- otherwise the 403 would reveal the account exists
    // and is unverified to someone who doesn't know the password.
    var registeredCustomer = new Customer(customerId, email, encodedPassword, false, true);
    registeredCustomer.setFailedLoginCount(5); // the max-attempts value from setUp
    registeredCustomer.setLockedUntil(Instant.now().minus(30L, ChronoUnit.SECONDS));

    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(registeredCustomer));
    when(passwordEncoder.matches(wrongPassword, encodedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> credChecker.check(email, wrongPassword, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: the expired lock was cleared, so this failure counts as attempt one, not a re-lock
    assertThat(registeredCustomer.getFailedLoginCount()).isEqualTo(1);
    assertThat(registeredCustomer.getLockedUntil()).isNull();

    verifyNoInteractions(auditLogService);
  }
}
