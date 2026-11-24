package com.acuity.subscribemaster.staff;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.AccountLockedException;
import com.acuity.subscribemaster.auth.InvalidCredentialsException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks a staff email and password with the staff lockout applied (FR-06). It's the staff
 * counterpart of {@link com.acuity.subscribemaster.auth.CredentialChecker} and works the same way,
 * except for the three differences below. That class's Javadoc gives the full reasoning behind the
 * shared behaviour.
 *
 * <p><b>Stricter, separate lockout settings.</b> {@code app.staff.login-lockout.max-attempts}
 * consecutive failures lock the account for {@code app.staff.login-lockout.duration-minutes}. These
 * are separate from the customer's {@code app.login-lockout.*} and lower, because staff accounts
 * can reach every customer's data. See {@code design-docs/staff_authorization_design.md}.
 *
 * <p><b>Inactive accounts are refused</b> ({@code staff_users.is_active = false}), before the lock
 * and the password are checked, with the same generic {@link InvalidCredentialsException} as an
 * unknown email or a wrong password. An inactive account therefore looks exactly like a wrong
 * password, whatever password is sent, and its counters are never touched, so it can't be locked or
 * reveal anything through a lock.
 *
 * <p><b>No email-verified check.</b> Staff accounts are created by an administrator or the
 * bootstrap runner, not by self-registration, so there's no verification step to wait for.
 *
 * <p>The rest is shared with the customer version:
 *
 * <ul>
 *   <li>"Email not found" and "wrong password" both throw {@link InvalidCredentialsException}, so
 *       the endpoint can't be used to find out which staff emails exist.
 *   <li>A locked account gets a distinct {@link AccountLockedException}, so a real user knows why
 *       their login stopped working.
 *   <li>The lock expires on its own: {@code locked_until} is checked on each attempt, with no
 *       scheduled job. An expired lock is cleared and the counter reset before the password is
 *       checked, so the first wrong password after a lock counts as attempt one.
 *   <li>Only the lock itself is audited ({@link AuditAction#ACCOUNT_LOCKED} with {@link
 *       ActorType#STAFF} and resource {@code "auth"}), not every failed attempt. The actor type is
 *       what tells it apart from a customer lockout.
 *   <li>A missing {@code password_hash} simply fails the match, like any wrong password.
 * </ul>
 *
 * <p><b>Failure state survives the exception, in its own transaction.</b> {@link #check} updates
 * the counter (and, on the last allowed failure, the lock) and then throws. It's marked {@code
 * noRollbackFor} both exceptions, so Spring doesn't roll that write back, and it runs in a new
 * transaction ({@code REQUIRES_NEW}), so the write commits whatever the caller does next. It's a
 * separate bean rather than a method on {@link StaffAuthService} because {@code @Transactional}
 * only applies to calls made through Spring's proxy, and a call from inside the same class bypasses
 * it.
 *
 * <p><b>Known, deliberately deferred gap:</b> attempts with an unknown email aren't rate-limited or
 * audited, because there's no staff row to attach a counter to. Fixing that needs IP-based rate
 * limiting, a separate mechanism from this per-account lockout, and it's out of scope here, as it
 * is for customers. {@link #invalidCredentials} is where it would go.
 */
@Service
public class StaffCredentialChecker {
  private final StaffUserRepository staffRepo;
  private final PasswordEncoder pwdEncoder;
  private final AuditLogService auditLogService;
  private final int maxLoginAttempts;
  private final int lockoutDurationMinutes;

  private static final Logger logger = LoggerFactory.getLogger(StaffCredentialChecker.class);

  public StaffCredentialChecker(
      StaffUserRepository staffUserRepository,
      PasswordEncoder pwdEncoder,
      AuditLogService auditLogService,
      @Value("${app.staff.login-lockout.max-attempts}") int maxLoginAttempts,
      @Value("${app.staff.login-lockout.duration-minutes}") int lockoutDurationMinutes) {
    this.staffRepo = staffUserRepository;
    this.pwdEncoder = pwdEncoder;
    this.auditLogService = auditLogService;
    this.maxLoginAttempts = maxLoginAttempts;
    this.lockoutDurationMinutes = lockoutDurationMinutes;
  }

  /**
   * Checks the credentials and returns the staff user's id if they're valid, updating the lockout
   * state either way.
   *
   * @param email the already-normalized email (stripped and lowercased by {@link
   *     com.acuity.subscribemaster.staff.dto.StaffLoginRequest}); the lookup is case-sensitive
   * @param password the raw password, compared against the stored BCrypt hash
   * @param ipAddress the caller's address, recorded on the ACCOUNT_LOCKED audit entry
   * @return the staff user's id
   * @throws InvalidCredentialsException if the email is unknown, the account is inactive, or the
   *     password is wrong but not yet the last allowed attempt
   * @throws AccountLockedException if the account is already locked, or this failure locks it
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
  public UUID check(String email, String password, String ipAddress) {

    // 1. Check login credential's validity
    var staffUser = staffRepo.findByEmail(email).orElseThrow(this::invalidCredentials);
    var staffUserId = staffUser.getId();

    // 2. Reject if not active
    if (!staffUser.isActive()) {
      logger.warn("Staff {}: login refused, account inactive", staffUserId);
      throw invalidCredentials();
    }

    // 3. Reject if locked; reset an expired lock
    var now = Instant.now();
    var lockedUntil = staffUser.getLockedUntil();

    if (lockedUntil != null) {
      if (lockedUntil.isAfter(now)) {
        logger.warn("Staff {}: account is locked", staffUserId);
        throw new AccountLockedException();
      }
      staffUser.setFailedLoginCount(0);
      staffUser.setLockedUntil(null);
      logger.info("Staff {}: expired lock cleared", staffUserId);
    }

    // 4. Verify the password & set the failedLoginCounter if not verified
    if (!pwdEncoder.matches(password, staffUser.getPasswordHash())) {

      staffUser.setFailedLoginCount(staffUser.getFailedLoginCount() + 1);

      if (staffUser.getFailedLoginCount() < maxLoginAttempts) {
        throw invalidCredentials();
      }
      staffUser.setLockedUntil(now.plus(lockoutDurationMinutes, ChronoUnit.MINUTES));
      throw lockAccount(staffUserId, ipAddress);
    }

    // 5. If successful, clear brute-force counters
    staffUser.setFailedLoginCount(0);
    staffUser.setLockedUntil(null);

    return staffUserId;
  }

  /**
   * The one generic failure for an unknown email, an inactive account and a wrong password. Also
   * where IP-based rate limiting would go; see the class Javadoc ("Known, deliberately deferred
   * gap").
   */
  private InvalidCredentialsException invalidCredentials() {
    return new InvalidCredentialsException();
  }

  /**
   * Audits the lock, attributed to the staff user being locked, and returns the exception to throw.
   * The audit entry is written in {@link AuditLogService}'s own transaction.
   */
  private AccountLockedException lockAccount(UUID staffUserId, String ipAddress) {
    auditLogService.recordEvent(
        ActorType.STAFF, staffUserId, AuditAction.ACCOUNT_LOCKED, "auth", staffUserId, ipAddress);
    return new AccountLockedException();
  }
}
