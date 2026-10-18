package com.acuity.subscribemaster.message;

import com.acuity.subscribemaster.support.EmailMasker;
import java.util.Objects;

/**
 * A single message to deliver, handed to {@link MessageService#send(OutboundMessage)}. Part of the
 * {@code message} module's public API (ARCHITECTURE.md &sect;2.4).
 *
 * <p>The caller chooses the {@code channel} and supplies the {@code recipient} -- an email address
 * for {@link MessageChannel#EMAIL}, an E.164 phone number once SMS exists (FR-37) -- so this module
 * never needs to look up a customer. {@code subject} is required for email and ignored by channels
 * that have no subject line. {@code body} is plain text; any HTML rendering or branding is the
 * email sender's concern, not the caller's (see {@code
 * design-docs/white_label_branding_and_multipart_email.md}).
 *
 * <p>Immutable and validated on construction, so a sender never receives a half-built message.
 *
 * <p><b>{@link #toString()} is overridden deliberately -- do not remove it.</b> A record's default
 * {@code toString()} prints every component, which here means the recipient's email address (PII)
 * and a body that, for verification and 2FA messages, contains a raw token or code. Logging such a
 * message would violate NFR-23 and ARCHITECTURE.md's "never log a raw token" rule, so this prints
 * only the channel and a masked recipient.
 */
public record OutboundMessage(
    MessageChannel channel, String recipient, String subject, String body) {

  public OutboundMessage {
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(body, "body");
    if (channel == MessageChannel.EMAIL && (subject == null || subject.isBlank())) {
      throw new IllegalArgumentException("Email messages must have a valid subject.");
    }
  }

  @Override
  public String toString() {
    // channel + EmailMasker.mask(recipient) -- but never the body
    return "OutboundMessage["
        + "channel="
        + channel
        + ", recipient="
        + EmailMasker.mask(recipient)
        + "]";
  }
}
