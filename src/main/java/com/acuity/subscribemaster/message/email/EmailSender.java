package com.acuity.subscribemaster.message.email;

import com.acuity.subscribemaster.message.MessageChannel;
import com.acuity.subscribemaster.message.MessageDeliveryException;
import com.acuity.subscribemaster.message.OutboundMessage;
import com.acuity.subscribemaster.message.internal.ChannelSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * The {@link MessageChannel#EMAIL} {@link ChannelSender}: maps an {@link OutboundMessage} onto a
 * {@link MimeMessage} and sends it through Spring's {@link JavaMailSender} (configured from {@code
 * spring.mail.*} -- Mailpit locally, a real SMTP provider in prod, credentials from Vault).
 *
 * <p>Internal to the {@code message} module: callers go through {@link
 * com.acuity.subscribemaster.message.MessageService}, never this class directly (ARCHITECTURE.md
 * &sect;2.4).
 *
 * <p>The sender address and display name come from {@link EmailProperties} ({@code app.mail.*}), so
 * a white-label deployment changes them in config, not code. Messages are sent as plain text; the
 * later multipart (text + HTML) refactor is confined to this class -- see {@code
 * design-docs/white_label_branding_and_multipart_email.md}. {@link MimeMessageHelper} is used
 * rather than {@code SimpleMailMessage} for exactly that reason, and to encode a non-ASCII display
 * name correctly.
 *
 * <p>Every failure -- building the message, encoding the sender name, or the SMTP send itself -- is
 * wrapped in a single {@link MessageDeliveryException}, so callers never see mail-library types.
 * Its message is deliberately generic: mail-server error text often echoes the recipient address
 * (PII, NFR-23); the original exception is kept as the cause for diagnosis.
 */
@Component
public class EmailSender implements ChannelSender {
  private final EmailProperties emailProps;
  private final JavaMailSender mailSender;

  private static final Logger logger = LoggerFactory.getLogger(EmailSender.class);

  public EmailSender(EmailProperties emailProperties, JavaMailSender mailSender) {
    this.emailProps = emailProperties;
    this.mailSender = mailSender;
  }

  @Override
  public MessageChannel channel() {
    return MessageChannel.EMAIL;
  }

  @Override
  public void send(OutboundMessage message) {
    try {
      MimeMessage mimeMessage = mailSender.createMimeMessage();
      MimeMessageHelper mimeMsgHelper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
      mimeMsgHelper.setFrom(emailProps.fromAddress(), emailProps.fromName());
      mimeMsgHelper.setTo(message.recipient());
      mimeMsgHelper.setSubject(message.subject());
      mimeMsgHelper.setText(message.body(), false);
      mailSender.send(mimeMessage);

      logger.info("email sent {}", message);

    } catch (MessagingException | UnsupportedEncodingException | MailException e) {
      // not using e.getMessage() as that may contain PII information
      throw new MessageDeliveryException("email delivery failed", e);
    }
  }
}
