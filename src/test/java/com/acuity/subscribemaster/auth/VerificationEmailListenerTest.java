package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.message.MessageChannel;
import com.acuity.subscribemaster.message.MessageDeliveryException;
import com.acuity.subscribemaster.message.MessageService;
import com.acuity.subscribemaster.message.OutboundMessage;
import com.acuity.subscribemaster.support.BrandingProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationEmailListenerTest {
  @Mock MessageService messageService;
  @Mock AuditLogService auditLogService;

  private final BrandingProperties brandProps = new BrandingProperties("Subscribe Master");
  private final EmailVerificationProperties verifyProps =
      new EmailVerificationProperties(
          "http://localhost:8080/api/v1/auth/verify-email", Duration.ofHours(24));
  private VerificationEmailListener emailListener;

  @BeforeEach
  void setup() {
    emailListener =
        new VerificationEmailListener(brandProps, verifyProps, messageService, auditLogService);
  }

  @Test
  void onCustomerRegistered_sendSucceeds_sendsVerificationLinkAndAudits() {
    // Arrange
    var customerId = UUID.randomUUID();
    var toEmailAddress = "test@example.com";
    var ipAddress = "192.168.0.1";

    var registeredEvent =
        new CustomerRegisteredEvent(customerId, toEmailAddress, "aRawToken123", ipAddress);

    // Act
    emailListener.onCustomerRegistered(registeredEvent);

    // Assert
    var captor = ArgumentCaptor.forClass(OutboundMessage.class);
    verify(messageService).send(captor.capture());
    var sent = captor.getValue();

    assertThat(sent.channel()).isEqualTo(MessageChannel.EMAIL);
    assertThat(sent.recipient()).isEqualTo(toEmailAddress);
    assertThat(sent.subject()).contains("Subscribe Master");
    assertThat(sent.body())
        .contains("http://localhost:8080/api/v1/auth/verify-email?token=aRawToken123")
        .contains("24 hours");

    verify(auditLogService, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.EMAIL_VERIFICATION_SENT,
            "auth",
            customerId,
            ipAddress);
  }

  @Test
  void onCustomerRegistered_deliveryFails_swallowsExceptionAndSkipsAudit() {
    // Arrange
    var customerId = UUID.randomUUID();
    var registeredEvent =
        new CustomerRegisteredEvent(customerId, "test@example.com", "aRawToken123", "192.168.0.1");

    doThrow(
            new MessageDeliveryException(
                "email delivery failed", new RuntimeException("smtp down")))
        .when(messageService)
        .send(any());

    // Act + Assert -- the failure is swallowed, so registration can still return 201
    assertThatCode(() -> emailListener.onCustomerRegistered(registeredEvent))
        .doesNotThrowAnyException();

    // Assert -- a send was attempted, but nothing was audited
    verify(messageService, times(1)).send(any());
    verifyNoInteractions(auditLogService);
  }
}
