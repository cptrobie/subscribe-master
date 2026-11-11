package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.BDDAssertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.AccountLockedException;
import com.acuity.subscribemaster.auth.InvalidCredentialsException;
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

/**
 * Unit tests for {@link StaffCredentialChecker} (FR-06): the staff credential check, the
 * inactive-account rule and the stricter staff lockout. It's a plain Mockito test, with no Spring
 * context or database, so it runs in milliseconds and counts towards the JaCoCo unit-test gate.
 *
 * <p>The checker is built directly with 3 attempts and 10 minutes, rather than reading {@code
 * app.staff.login-lockout.*}, so the thresholds here don't change when the configuration does. The
 * tests mirror {@code CredentialCheckerTest} case for case, plus one the customer version doesn't
 * need: an inactive account is refused with the generic error even with the correct password, the
 * password is never compared and its counters are left alone.
 *
 * <p><b>Building a staff user.</b> {@link StaffUser} has no constructor that takes an id (it's
 * generated when the row is saved), so {@link #staffUser} sets it with {@code ReflectionTestUtils},
 * as the inactive test does for {@code isActive}. The role is {@code null} because the checker
 * never reads it, so no {@code RoleRepository} is needed.
 *
 * <p>What's checked across the tests: unknown email, inactive account and wrong password all give
 * the same {@link InvalidCredentialsException}; only the lock itself is audited, with {@link
 * ActorType#STAFF}; and a rejection that comes before the password check never calls {@link
 * PasswordEncoder#matches}.
 */
@ExtendWith(MockitoExtension.class)
public class StaffCredentialCheckerTest {

  @Mock private StaffUserRepository staffRepo;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AuditLogService auditLogService;

  private StaffCredentialChecker staffCredChecker;

  @BeforeEach
  void setUp() {
    staffCredChecker =
        new StaffCredentialChecker(
            staffRepo,
            passwordEncoder,
            auditLogService,
            3, // maxLoginAttempts
            10 // lockoutDurationMinutes
            );
  }

  /**
   * A staff user as the repository would return it. The checker never reads the role, so it's null.
   */
  private static StaffUser staffUser(UUID id, String email, String passwordHash) {
    var user = new StaffUser(email, passwordHash, null);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  @Test
  void successfulCheck_returnsStaffUserIdAndResetsFailedCount() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var hashedPassword = "bCrypted_password";
    var staffUserId = UUID.randomUUID();

    var registeredStaffUser = staffUser(staffUserId, email, hashedPassword);
    registeredStaffUser.setFailedLoginCount(3);

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(registeredStaffUser));
    when(passwordEncoder.matches(rawPassword, hashedPassword)).thenReturn(true);

    // Act
    var checkedStaffId = staffCredChecker.check(email, rawPassword, "192.168.0.1");

    // Assert
    assertThat(checkedStaffId).isEqualTo(staffUserId);
    assertThat(registeredStaffUser.getFailedLoginCount()).isEqualTo(0);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void unregisteredEmailCheckFailure_respondsWithInvalidCredentialsExceptionThrown() {
    // Arrange
    var email = "test@example.com";
    ;

    when(staffRepo.findByEmail(email)).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> staffCredChecker.check(email, "easyPassword!#123", "192.168.0.1"))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: the rejection short-circuits cleanly -- no password
    // comparison attempted (there's no real staffUser/password hash to compare against)
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void
      registeredEmailWrongPasswordBelowFailureCountThreshold_respondsWithInvalidCredentialsExceptionThrown() {
    // Arrange
    var email = "test@example.com";
    var wrongPassword = "guessedAtPassword";
    var hashedPassword = "bCrypted_password";
    var staffUserId = UUID.randomUUID();

    var registeredStaffUser = staffUser(staffUserId, email, hashedPassword);

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(registeredStaffUser));
    when(passwordEncoder.matches(wrongPassword, hashedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> staffCredChecker.check(email, wrongPassword, "192.168.0.1"))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: that the failedloginCount gets incremented, but since it has not met the
    // lockout threshold, the lockedUntil remain Null
    assertThat(registeredStaffUser.getFailedLoginCount()).isEqualTo(1);
    assertThat(registeredStaffUser.getLockedUntil()).isNull();

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
    var hashedPassword = "bCrypted_password";
    var staffUserId = UUID.randomUUID();
    var failedLoginCountThresholdValue = 3;
    var lockedUntil = Instant.now().plus(10L, ChronoUnit.MINUTES);
    var ipAddress = "192.168.0.1";

    var registeredStaffUser = staffUser(staffUserId, email, hashedPassword);
    registeredStaffUser.setFailedLoginCount(failedLoginCountThresholdValue - 1);

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(registeredStaffUser));
    when(passwordEncoder.matches(wrongPassword, hashedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> staffCredChecker.check(email, wrongPassword, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    // Assert: that the failedLoginCount matches the threshold value and sets the lockedUntil value
    assertThat(registeredStaffUser.getFailedLoginCount()).isEqualTo(failedLoginCountThresholdValue);
    assertThat(registeredStaffUser.getLockedUntil())
        .isCloseTo(lockedUntil, within(2, ChronoUnit.SECONDS));

    // Assert: that the lockout trigger has been met which justifies an audit record
    verify(auditLogService, times(1))
        .recordEvent(
            ActorType.STAFF,
            registeredStaffUser.getId(),
            AuditAction.ACCOUNT_LOCKED,
            "auth",
            registeredStaffUser.getId(),
            ipAddress);
  }

  @Test
  void checkAttemptOnLockedAccount_respondsWithLockedAccountRegardlessIfCredentialsAreValidOrNot() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var hashedPassword = "bCrypted_password";
    var lockedUntil =
        Instant.now()
            .plus(9L, ChronoUnit.MINUTES); // assume one minute has passed since originally set
    var staffUserId = UUID.randomUUID();
    var ipAddress = "192.168.0.1";

    var lockedStaffUserAccount = staffUser(staffUserId, email, hashedPassword);
    lockedStaffUserAccount.setLockedUntil(lockedUntil);

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(lockedStaffUserAccount));

    // Act & Assert
    assertThatThrownBy(() -> staffCredChecker.check(email, rawPassword, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    assertThat(lockedStaffUserAccount.getLockedUntil())
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
    var hashedPassword = "bCrypted_password";
    var staffUserId = UUID.randomUUID();
    var lockedExpireTime = Instant.now().minus(30L, ChronoUnit.SECONDS); // "some" time in the past
    var ipAddress = "192.168.0.1";

    var retryStaffUser = staffUser(staffUserId, email, hashedPassword);
    ReflectionTestUtils.setField(retryStaffUser, "lockedUntil", lockedExpireTime);

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(retryStaffUser));
    when(passwordEncoder.matches(rawPassword, hashedPassword)).thenReturn(true);

    // Act
    var checkedStaffId = staffCredChecker.check(email, rawPassword, ipAddress);

    assertThat(checkedStaffId).isEqualTo(staffUserId);
    // Assert: successful check() resets both lockout-tracking fields
    assertThat(retryStaffUser.getFailedLoginCount()).isEqualTo(0);
    assertThat(retryStaffUser.getLockedUntil()).isNull();
  }

  @Test
  void lockExpiredThenWrongPassword_restartsFailedCountAtOneAndClearsLock() {
    // Arrange
    var email = "test@example.com";
    var wrongPassword = "guessedAtPassword";
    var hashedPassword = "bCrypted_password";
    var staffUserId = UUID.randomUUID();
    var ipAddress = "192.168.0.1";

    var registeredStaffUser = staffUser(staffUserId, email, hashedPassword);
    registeredStaffUser.setFailedLoginCount(3); // the max-attempts value from setUp
    registeredStaffUser.setLockedUntil(Instant.now().minus(30L, ChronoUnit.SECONDS));

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(registeredStaffUser));
    when(passwordEncoder.matches(wrongPassword, hashedPassword)).thenReturn(false);

    // Act & Assert
    assertThatThrownBy(() -> staffCredChecker.check(email, wrongPassword, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert: the expired lock was cleared, so this failure counts as attempt one, not a re-lock
    assertThat(registeredStaffUser.getFailedLoginCount()).isEqualTo(1);
    assertThat(registeredStaffUser.getLockedUntil()).isNull();

    verifyNoInteractions(auditLogService);
  }

  @Test
  void
      inactiveAccountWithCorrectPassword_respondsWithInvalidCredentialsAndLeavesCountersUntouched() {
    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var hashedPassword = "bCrypted_password";
    var failedLoginCount = 2;
    var staffUserId = UUID.randomUUID();
    var ipAddress = "192.168.0.1";

    var inactiveStaffUser = staffUser(staffUserId, email, hashedPassword);
    inactiveStaffUser.setFailedLoginCount(failedLoginCount);
    ReflectionTestUtils.setField(inactiveStaffUser, "isActive", false);

    when(staffRepo.findByEmail(email)).thenReturn(Optional.of(inactiveStaffUser));

    // Act & Assert
    assertThatThrownBy(() -> staffCredChecker.check(email, password, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert
    assertThat(inactiveStaffUser.getFailedLoginCount()).isEqualTo(failedLoginCount);
    assertThat(inactiveStaffUser.getLockedUntil()).isNull();
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verifyNoInteractions(auditLogService);
  }
}
