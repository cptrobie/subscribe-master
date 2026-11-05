package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.message.MessageChannel;
import com.acuity.subscribemaster.message.MessageDeliveryException;
import com.acuity.subscribemaster.message.MessageService;
import com.acuity.subscribemaster.message.OutboundMessage;
import com.acuity.subscribemaster.support.BrandingProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends the verification email and audits whether the send succeeded or failed (NFR-34), for both a
 * new registration (FR-30, reacting to {@link CustomerRegisteredEvent}) and a resend (FR-40,
 * reacting to {@link VerificationEmailRequestedEvent}). Both listener methods hand off to one
 * private send helper, so a resend uses exactly the registration's link, message and audit actions
 * -- there is no second sending path. The message text is the same for both too, including its
 * welcome line.
 *
 * <p><b>Runs after the publishing transaction commits</b> ({@code AFTER_COMMIT}), so an email is
 * only ever sent for a customer and token that were actually saved -- a rolled-back registration,
 * or a resend rejected by one of its checks, never emails a dead link. Because the original
 * transaction has already ended, the audit write relies on {@link AuditLogService} running in its
 * own transaction. It runs on the request thread, so the SMTP round trip adds to the response time;
 * {@code @Async} is the later fix. It only fires for events published inside a transaction, which
 * both {@code AuthService.register()} and the resend (run inside {@code IdempotencyService}'s
 * transaction) are.
 *
 * <p><b>A delivery failure doesn't fail the request.</b> {@link MessageDeliveryException} is
 * caught, logged and audited, and registration still returns 201 (a resend, 202) -- the account and
 * token exist, and recovery is another resend (or FR-32's cleanup). Nothing else is caught: an
 * {@link IllegalStateException} from {@code MessageService} means a misconfiguration (no sender for
 * the channel) and must surface, even though it reaches the client as a 500 after the customer has
 * already been saved.
 *
 * <p>{@code EMAIL_VERIFICATION_SENT} is audited only after a successful send, so {@code audit_logs}
 * records emails that actually went out. A caught delivery failure records {@code
 * EMAIL_VERIFICATION_SEND_FAILED} instead, attributed the same way, so a failed send can be told
 * apart from one not yet attempted. The SMTP cause stays in the log warning only, since {@code
 * audit_logs} has no detail column; the warning is logged before the audit write, so the cause
 * survives even if that write fails. The link's base URL, product name and validity window all come
 * from config ({@link EmailVerificationProperties}, {@link BrandingProperties}); the window is
 * stated in whole hours, so a sub-hour {@code token-ttl} would read as "0 hours".
 */
@Component
public class VerificationEmailListener {
  private final BrandingProperties brandProps;
  private final EmailVerificationProperties verifyProps;
  private final MessageService msgService;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(VerificationEmailListener.class);

  public VerificationEmailListener(
      BrandingProperties brandProps,
      EmailVerificationProperties verifyProps,
      MessageService msgService,
      AuditLogService auditLogService) {
    this.brandProps = brandProps;
    this.verifyProps = verifyProps;
    this.msgService = msgService;
    this.auditLogService = auditLogService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onCustomerRegistered(CustomerRegisteredEvent event) {
    sendVerificationEmail(event.customerId(), event.email(), event.rawToken(), event.ipAddress());
  }

  /** Sends a resent link (FR-40) to the event's address, which may be a corrected one. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void onVerificationEmailRequested(VerificationEmailRequestedEvent event) {
    sendVerificationEmail(event.customerId(), event.email(), event.rawToken(), event.ipAddress());
  }

  /**
   * Builds the link from {@code rawToken}, sends it to {@code email}, and audits the outcome
   * against {@code customerId} -- {@code EMAIL_VERIFICATION_SENT} on success, {@code
   * EMAIL_VERIFICATION_SEND_FAILED} on a caught delivery failure.
   */
  private void sendVerificationEmail(
      UUID customerId, String email, String rawToken, String ipAddress) {
    var verifyLink =
        UriComponentsBuilder.fromUriString(verifyProps.linkBaseUrl())
            .queryParam("token", rawToken)
            .toUriString();

    var msgSubject = "Confirm your " + brandProps.productName() + " email address";
    var msgBody =
        """
        Welcome to %s!

        Please confirm your email address by opening this link within the next %d hours:

        %s

        If you didn't register, simply ignore this email.
        """
            .formatted(brandProps.productName(), verifyProps.tokenTtl().toHours(), verifyLink);
    var message = new OutboundMessage(MessageChannel.EMAIL, email, msgSubject, msgBody);

    try {
      msgService.send(message);
      auditLogService.recordEvent(
          ActorType.CUSTOMER,
          customerId,
          AuditAction.EMAIL_VERIFICATION_SENT,
          "auth",
          customerId,
          ipAddress);
    } catch (MessageDeliveryException e) {
      logger.warn("verification email failed for customer {}", customerId, e);
      auditLogService.recordEvent(
          ActorType.CUSTOMER,
          customerId,
          AuditAction.EMAIL_VERIFICATION_SEND_FAILED,
          "auth",
          customerId,
          ipAddress);
    }
  }
}
