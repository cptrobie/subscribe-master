package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.AccountLockedException;
import com.acuity.subscribemaster.auth.InvalidCredentialsException;
import com.acuity.subscribemaster.support.JwtIssuer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link StaffAuthService} (FR-06): staff login. It's a plain Mockito test, with no
 * Spring context or database, so it runs in milliseconds and counts towards the JaCoCo unit-test
 * gate.
 *
 * <p>The service only coordinates its dependencies, so every one of them is mocked. The credential
 * check, the inactive-account rule and the lockout belong to {@link StaffCredentialChecker} and are
 * tested in {@code StaffCredentialCheckerTest}. Here the checker just returns an id or throws.
 *
 * <p>What the tests check:
 *
 * <ul>
 *   <li>A successful login returns the issuer's token and expiry with the staff user's id and
 *       email, records {@link AuditAction#STAFF_LOGGED_IN} with {@link ActorType#STAFF}, and issues
 *       a staff token, never a customer one. The {@code never()} check on {@link
 *       JwtIssuer#issueCustomer} matters because the mocked issuer returns the same fake token
 *       either way, so the response assertions alone wouldn't catch the wrong method being called.
 *   <li>When the checker throws {@link InvalidCredentialsException} or {@link
 *       AccountLockedException}, the same exception reaches the caller unchanged, so the controller
 *       maps it to the right status. Nothing is looked up, issued or audited; the checker has
 *       already recorded the failure.
 *   <li>If the staff row disappears between the check and the lookup, login fails with the generic
 *       {@link InvalidCredentialsException} and still issues and audits nothing.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class StaffAuthServiceTest {
  @Mock private StaffUserRepository staffRepo;
  @Mock private JwtIssuer jwtIssuer;
  @Mock private StaffCredentialChecker staffCredChecker;
  @Mock private AuditLogService auditLogService;

  private StaffAuthService staffAuthService;

  @BeforeEach
  void setUp() {
    staffAuthService =
        new StaffAuthService(staffRepo, jwtIssuer, staffCredChecker, auditLogService);
  }

  /**
   * A staff user as the repository would return it. The service only reads the email, so the role
   * is null.”.
   */
  private static StaffUser staffUser(UUID id, String email, String passwordHash) {
    var user = new StaffUser(email, passwordHash, null);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  @Test
  void successfulLogin_returnsStaffTokenAndRecordsStaffLoggedIn() {
    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var hashedPassword = "a hashed password";
    var staffUserId = UUID.randomUUID();
    var ipAddress = "192.168.0.1";
    var issuedExpiresAt = Instant.now().plus(5L, ChronoUnit.MINUTES);

    var staffUser = staffUser(staffUserId, email, hashedPassword);
    var issuedToken = new JwtIssuer.IssuedToken("fake.jwt.token", issuedExpiresAt);

    when(staffCredChecker.check(email, password, ipAddress)).thenReturn(staffUserId);
    when(staffRepo.findById(staffUserId)).thenReturn(Optional.of(staffUser));
    when(jwtIssuer.issueStaff(staffUserId)).thenReturn(issuedToken);

    // Act
    var response = staffAuthService.login(email, password, ipAddress);

    // Assert: response carries the issuer's token expiry and the staff user's id is accurate.
    assertThat(response.id()).isEqualTo(staffUserId);
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.token()).isEqualTo(issuedToken.token());
    assertThat(response.expiresAt()).isEqualTo(issuedToken.expiresAt());
    assertThat(response.message()).isEqualTo("Login successful.");

    // Assert: a staff token, never a customer one, and the login is auditLog recorded
    verify(jwtIssuer, never()).issueCustomer(any());
    verify(jwtIssuer).issueStaff(staffUserId);
    verify(auditLogService)
        .recordEvent(
            ActorType.STAFF,
            staffUserId,
            AuditAction.STAFF_LOGGED_IN,
            "auth",
            staffUserId,
            ipAddress);
  }

  @Test
  void checkerRejectsCredentials_propagatesInvalidCredentialsAndIssuesNothing() {
    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(staffCredChecker.check(email, password, ipAddress))
        .thenThrow(new InvalidCredentialsException());

    // Act
    assertThatThrownBy(() -> staffAuthService.login(email, password, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert
    verifyNoInteractions(staffRepo);
    verifyNoInteractions(jwtIssuer);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void checkerReportsLockedAccount_propagatesAccountLockedAndIssuesNothing() {
    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(staffCredChecker.check(email, password, ipAddress))
        .thenThrow(new AccountLockedException());

    // Act
    assertThatThrownBy(() -> staffAuthService.login(email, password, ipAddress))
        .isInstanceOf(AccountLockedException.class);

    // Assert
    verifyNoInteractions(staffRepo);
    verifyNoInteractions(jwtIssuer);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void staffUserMissingAfterSuccessfulCheck_throwsInvalidCredentialsAndIssuesNothing() {
    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var staffUserId = UUID.randomUUID();
    var ipAddress = "192.168.0.1";

    when(staffCredChecker.check(email, password, ipAddress)).thenReturn(staffUserId);
    when(staffRepo.findById(staffUserId)).thenReturn(Optional.empty());

    // Act
    assertThatThrownBy(() -> staffAuthService.login(email, password, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert
    verifyNoInteractions(jwtIssuer);
    verifyNoInteractions(auditLogService);
  }
}
