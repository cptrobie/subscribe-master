package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.customer.CustomerRepository;
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
 * Checks an email and password with the per-account lockout applied (FR-02) -- shared by {@link
 * AuthService#login} and the unauthenticated verification resend (FR-40), so a wrong password on
 * either endpoint counts toward the same lockout. Without that, the resend endpoint would be an
 * unthrottled password-guessing oracle beside login.
 *
 * <p><b>Credential errors are unified deliberately.</b> "Email not found" and "wrong password" both
 * throw the same {@link InvalidCredentialsException}, with the same message and HTTP status --
 * distinguishing them would let an attacker enumerate registered emails through these endpoints,
 * the same vulnerability {@link AccountAlreadyExistsException}'s generic message already guards
 * against on registration.
 *
 * <p><b>Lockout</b> ({@code app.login-lockout.max-attempts} consecutive failed attempts lock the
 * account for {@code app.login-lockout.duration-minutes}) is live-expiry-checked on {@code
 * customers.locked_until}, not a scheduled job -- same reasoning as FR-33's 2FA lockout design.
 * Unlike the credential-unification above, a locked account returns a distinct {@link
 * AccountLockedException}, not the generic {@link InvalidCredentialsException} -- deliberately,
 * after weighing the trade-off: a genuine user locked out by a handful of mistyped attempts has no
 * way to know why their presumably-correct password stopped working, or when to retry, under a
 * fully generic response. The information this reveals to an attacker (that the account exists)
 * costs them {@code app.login-lockout.max-attempts} full attempts to obtain, versus a single
 * request via registration enumeration -- a meaningfully higher attack cost, and lockout itself
 * already blocks sustained automated probing regardless of what the response reveals. Only the
 * lockout trigger itself (the 5th failure) is audited, attributed to the real customer being locked
 * -- not every failed attempt, which would make audit_logs unusably noisy for what's often just a
 * routine mistyped password. An expired lock is cleared, with the counter reset to zero, before the
 * password is checked, so the first wrong password after a lock runs out counts as attempt one
 * rather than re-locking the account immediately.
 *
 * <p><b>Failure state survives the exception, in its own transaction.</b> {@link #check} records a
 * failed attempt (and, on the threshold failure, the lock itself) and then throws. It's marked
 * {@code noRollbackFor} both exceptions, so Spring's default rollback on unchecked exceptions
 * doesn't discard the counter and lock -- and it runs in a <em>new</em> transaction ({@code
 * REQUIRES_NEW}), so that write commits no matter what the caller's transaction does next. {@code
 * noRollbackFor} alone isn't enough once a caller has its own transaction: FR-40 runs inside {@link
 * com.acuity.subscribemaster.idempotency.IdempotencyService}'s {@code TransactionTemplate}, which
 * rolls back on any exception, so a caller sending a fresh {@code Idempotency-Key} with every guess
 * would never be locked out. This is a separate bean rather than a method on {@link AuthService}
 * because {@code @Transactional} only applies to calls that come through Spring's proxy -- a call
 * from inside the same class bypasses it. The counter reset on success commits here too, so a
 * caller that throws afterwards (login's {@link EmailNotVerifiedException}) doesn't undo it. The
 * ACCOUNT_LOCKED audit entry is written in {@link AuditLogService}'s own REQUIRES_NEW transaction
 * either way.
 *
 * <p><b>Known, deliberately deferred gap:</b> nonexistent-email attempts get no rate-limiting or
 * audit logging at all -- there's no customer row to attach a counter to. A real fix needs IP-based
 * rate limiting, a genuinely separate mechanism from the per-account lockout above; out of scope
 * here, revisit if abuse is ever observed.
 */
@Service
public class CredentialChecker {

  private final CustomerRepository customerRepo;
  private final PasswordEncoder pwdEncoder;
  private final AuditLogService auditLogService;
  private final int maxLoginAttempts;
  private final int lockoutDurationMinutes;

  private static final Logger logger = LoggerFactory.getLogger(CredentialChecker.class);

  public CredentialChecker(
      CustomerRepository customerRepo,
      PasswordEncoder pwdEncoder,
      AuditLogService auditLogService,
      @Value("${app.login-lockout.max-attempts}") int maxLoginAttempts,
      @Value("${app.login-lockout.duration-minutes}") int lockoutDurationMinutes) {
    this.customerRepo = customerRepo;
    this.pwdEncoder = pwdEncoder;
    this.auditLogService = auditLogService;
    this.maxLoginAttempts = maxLoginAttempts;
    this.lockoutDurationMinutes = lockoutDurationMinutes;
  }

  /**
   * Checks the credentials and applies the lockout, committing the attempt's outcome before
   * returning or throwing.
   *
   * @param email the email the caller claims; matched case-insensitively (citext)
   * @param password the raw password to check against the stored hash
   * @param ipAddress the caller's IP, recorded on the ACCOUNT_LOCKED audit entry
   * @return the customer's id, for the caller to reload the customer in its own transaction -- the
   *     entity loaded here is detached once this method's transaction commits
   * @throws InvalidCredentialsException if no account has this email, or the password is wrong and
   *     the attempt doesn't reach the lockout threshold
   * @throws AccountLockedException if the account is already locked, or this attempt locks it
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
  public UUID check(String email, String password, String ipAddress) {

    // 1. Check login credential's validity
    var customer = customerRepo.findByEmail(email).orElseThrow(this::invalidCredentials);
    var customerId = customer.getId();

    // 2. Reject if locked; reset an expired lock
    var now = Instant.now();
    var lockedUntil = customer.getLockedUntil();

    if (lockedUntil != null) {
      if (lockedUntil.isAfter(now)) {
        logger.warn("Customer {}: account is locked", customerId);
        throw new AccountLockedException();
      }
      customer.setFailedLoginCount(0);
      customer.setLockedUntil(null);
      logger.info("Customer {}: expired lock cleared", customerId);
    }

    // 3. Verify the password & set the failedLoginCounter if not verified
    if (!pwdEncoder.matches(password, customer.getPasswordHash())) {

      customer.setFailedLoginCount(customer.getFailedLoginCount() + 1);

      if (customer.getFailedLoginCount() < maxLoginAttempts) {
        throw invalidCredentials();
      }
      customer.setLockedUntil(now.plus(lockoutDurationMinutes, ChronoUnit.MINUTES));
      throw lockAccount(customerId, ipAddress);
    }

    // 4. If successful, clear brute-force counters
    customer.setFailedLoginCount(0);
    customer.setLockedUntil(null);

    return customerId;
  }

  private InvalidCredentialsException invalidCredentials() {
    // Placeholder for IP-based rate limiting on nonexistent-email login attempts --
    // see this class's Javadoc ("Known, deliberately deferred gap") for why this
    // isn't built yet. This is the natural landing spot for that logic once it is.
    return new InvalidCredentialsException();
  }

  private AccountLockedException lockAccount(UUID customerId, String ipAddress) {
    auditLogService.recordEvent(
        ActorType.CUSTOMER, customerId, AuditAction.ACCOUNT_LOCKED, "auth", customerId, ipAddress);
    return new AccountLockedException();
  }
}
