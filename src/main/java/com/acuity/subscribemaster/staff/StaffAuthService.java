package com.acuity.subscribemaster.staff;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.InvalidCredentialsException;
import com.acuity.subscribemaster.staff.dto.StaffLoginResponse;
import com.acuity.subscribemaster.support.EmailMasker;
import com.acuity.subscribemaster.support.JwtIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Staff login (FR-06): checks the credentials, issues a staff access token and records the login in
 * the audit log (NFR-17). Staff accounts are never self-registered, so there's no registration or
 * email-verification step here, unlike the customer {@link
 * com.acuity.subscribemaster.auth.AuthService}.
 *
 * <p>The credential check, the inactive-account rule and the lockout are all in {@link
 * StaffCredentialChecker}, which runs in its own transaction so that failed attempts are counted
 * even though it throws.
 *
 * <p>The token comes from {@link JwtIssuer#issueStaff}, so it carries the {@code staff-api}
 * audience and is rejected by the customer security chain. It carries no permissions; those are
 * looked up on every staff request.
 *
 * <p><b>Successful logins are audited</b> as {@code STAFF_LOGGED_IN}, unlike customer logins. There
 * are few staff and they're the most privileged users, so a record of who logged in, when and from
 * where is worth keeping. See {@code design-docs/staff_authorization_design.md}.
 */
@Service
public class StaffAuthService {
  private final StaffUserRepository staffRepo;
  private final JwtIssuer jwtIssuer;
  private final StaffCredentialChecker staffCredChecker;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(StaffAuthService.class);

  public StaffAuthService(
      StaffUserRepository staffRepo,
      JwtIssuer jwtIssuer,
      StaffCredentialChecker credChecker,
      AuditLogService auditLogService) {
    this.staffRepo = staffRepo;
    this.jwtIssuer = jwtIssuer;
    this.staffCredChecker = credChecker;
    this.auditLogService = auditLogService;
  }

  /**
   * Logs a staff user in.
   *
   * @param email the normalized email from {@link
   *     com.acuity.subscribemaster.staff.dto.StaffLoginRequest}
   * @param password the raw password
   * @param ipAddress the caller's address, for the audit log
   * @return the staff user's id and email, and the signed token with its expiry
   * @throws InvalidCredentialsException if the email is unknown, the account is inactive or the
   *     password is wrong
   * @throws com.acuity.subscribemaster.auth.AccountLockedException if the account is locked
   */
  public StaffLoginResponse login(String email, String password, String ipAddress) {
    var maskedEmail = EmailMasker.mask(email);

    logger.info("Staff login attempt for {}", maskedEmail);

    // Credentials, active check and lockout: StaffCredentialChecker
    // Steps 1–4 have moved to StaffCredentialChecker.check()
    var staffId = staffCredChecker.check(email, password, ipAddress);
    var staffUser = staffRepo.findById(staffId).orElseThrow(InvalidCredentialsException::new);

    // 5. Issue a signed JWT -- self-verifying, nothing to persist
    var issuedToken = jwtIssuer.issueStaff(staffId);

    // 6. Record the login
    auditLogService.recordEvent(
        ActorType.STAFF, staffId, AuditAction.STAFF_LOGGED_IN, "auth", staffId, ipAddress);
    logger.info("Staff {} login successful.", staffId);

    // 7. Build and return the response with the token in the message body
    return StaffLoginResponse.accepted(
        staffId, staffUser.getEmail(), issuedToken.token(), issuedToken.expiresAt());
  }
}
