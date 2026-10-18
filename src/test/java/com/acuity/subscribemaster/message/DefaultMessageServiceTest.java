package com.acuity.subscribemaster.message;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acuity.subscribemaster.message.internal.ChannelSender;
import com.acuity.subscribemaster.message.internal.DefaultMessageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DefaultMessageServiceTest {
  @Mock private ChannelSender emailSender;
  @Mock private ChannelSender secondEmailSender;

  private DefaultMessageService messageService;

  @BeforeEach
  void setUp() {
    when(emailSender.channel()).thenReturn(MessageChannel.EMAIL);
    messageService = new DefaultMessageService(List.of(emailSender));
  }

  @Test
  void send_routesToSenderForMessageChannel() {
    // Arrange
    var msgToSend =
        new OutboundMessage(
            MessageChannel.EMAIL,
            "toWhom@example.com",
            "This is the message subject",
            "This is the body of the message.");

    // Act
    messageService.send(msgToSend);

    // Assert -- handed to the EMAIL sender exactly once, as the same, unmodified message
    verify(emailSender).send(msgToSend);
  }

  @Test
  void send_noSenderForChannel_throwsIllegalState() {
    // Arrange -- a service with no senders at all, built here rather than using setUp()'s, so the
    // valid EMAIL message below has no sender to route to
    var serviceWithNoSenders = new DefaultMessageService(List.of());
    var msgToSend =
        new OutboundMessage(
            MessageChannel.EMAIL,
            "toWhom@example.com",
            "This is the message subject",
            "This is the body of the message.");

    // Act + Assert -- a missing sender is a bug, so IllegalStateException, not a delivery failure
    assertThatThrownBy(() -> serviceWithNoSenders.send(msgToSend))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no ChannelSender registered for EMAIL");
  }

  @Test
  void constructor_twoSendersSameChannel_throwsIllegalState() {
    // Arrange -- a second, distinct sender that also claims EMAIL (emailSender's stub is in
    // setUp())
    when(secondEmailSender.channel()).thenReturn(MessageChannel.EMAIL);
    var senders = List.of(emailSender, secondEmailSender);

    // Act + Assert -- the duplicate is caught while building the map, i.e. in the constructor
    assertThatThrownBy(() -> new DefaultMessageService(senders))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("multiple ChannelSenders registered for EMAIL: ");
  }
}
