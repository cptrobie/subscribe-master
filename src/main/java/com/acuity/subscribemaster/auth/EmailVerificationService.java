package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.EmailVerificationResponse;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms a customer's email address from the token in their verification link (FR-30).
 *
 * <p>Checks run in a deliberate order. A token that's already used, for a customer who's already
 * verified, is treated as success ("already verified") <i>before</i> expiry is checked: link
 * scanners (e.g. Outlook Safe Links) often open the link first, and the customer's own click --
 * possibly after the window has passed -- must not then see an error. A used token for an
 * unverified customer can't arise through this code (both are set in one transaction), so it's
 * rejected and logged as an anomaly rather than reported as verified.
 *
 * <p>Failures throw {@link InvalidVerificationTokenException} (unknown token, missing customer,
 * inconsistent state -- all just "this link doesn't work" to the user) or {@link
 * VerificationTokenExpiredException}; distinguishing expiry is safe because tokens are unguessable,
 * so it reveals nothing to a prober. Only success is audited ({@code EMAIL_VERIFIED}); failures are
 * logged at {@code info} or {@code warn}. Nothing here ever logs the raw token, its hash, or the
 * email address -- the token arrives in a URL, so it must stay out of every log.
 *
 * <p>Package-private on purpose: only {@code AuthController} calls it. {@code @Transactional} on a
 * package-private method relies on Spring 6+ and Spring Boot's default class-based proxies, and on
 * being called from another bean -- a call from within this class would bypass the transaction.
 * Both entities are loaded inside the transaction, so their changes are written on commit with no
 * explicit {@code save()}.
 */
@Service
class EmailVerificationService {
  private final CustomerRepository customerRepo;
  private final CustomerEmailVerificationTokenRepository emailVerifyRepo;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);

  public EmailVerificationService(
      CustomerRepository customerRepo,
      CustomerEmailVerificationTokenRepository emailVerifyRepo,
      AuditLogService auditLogService) {
    this.customerRepo = customerRepo;
    this.emailVerifyRepo = emailVerifyRepo;
    this.auditLogService = auditLogService;
  }

  @Transactional
  EmailVerificationResponse verify(String rawToken, String ipAddress) {
    var emailVerifyToken =
        emailVerifyRepo
            .findByTokenHash(Tokens.hash(rawToken))
            .orElseThrow(
                () -> {
                  logger.info("verification rejected: unknown token");
                  return new InvalidVerificationTokenException();
                });
    var customer =
        customerRepo
            .findById(emailVerifyToken.getCustomerId())
            .orElseThrow(
                () -> {
                  logger.warn(
                      "verification rejected: token {} has no customer", emailVerifyToken.getId());
                  return new InvalidVerificationTokenException();
                });

    if (emailVerifyToken.isUsed()) {
      if (customer.getIsEmailVerified()) {
        logger.info("customer {} verification link reused", emailVerifyToken.getCustomerId());
        return EmailVerificationResponse.alreadyVerified();
      }
      logger.warn(
          "verification rejected for customer {}: token used but email not verified",
          emailVerifyToken.getCustomerId());
      throw new InvalidVerificationTokenException();
    }

    var now = Instant.now();
    if (emailVerifyToken.isExpired(now)) {
      logger.info(
          "verification rejected for customer {}: token expired", emailVerifyToken.getCustomerId());
      throw new VerificationTokenExpiredException();
    }

    emailVerifyToken.markUsed(now);
    customer.markEmailVerified();

    logger.info("customer {} email verified", emailVerifyToken.getCustomerId());

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        emailVerifyToken.getCustomerId(),
        AuditAction.EMAIL_VERIFIED,
        "auth",
        emailVerifyToken.getCustomerId(),
        ipAddress);

    return EmailVerificationResponse.verified();
  }
}
