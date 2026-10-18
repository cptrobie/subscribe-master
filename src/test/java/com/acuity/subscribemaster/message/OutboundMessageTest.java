package com.acuity.subscribemaster.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure unit test -- no Spring, no mocks. Covers OutboundMessage's construction rules and toString.
 */
class OutboundMessageTest {

  private static final String RECIPIENT = "jane.doe@example.com";
  private static final String SUBJECT = "Verify your email";
  private static final String BODY = "https://example.test/verify?token=RAW-SECRET-TOKEN";

  @Test
  void constructor_validEmail_keepsAllComponents() {
    var message = new OutboundMessage(MessageChannel.EMAIL, RECIPIENT, SUBJECT, BODY);

    assertThat(message.channel()).isEqualTo(MessageChannel.EMAIL);
    assertThat(message.recipient()).isEqualTo(RECIPIENT);
    assertThat(message.subject()).isEqualTo(SUBJECT);
    assertThat(message.body()).isEqualTo(BODY);
  }

  @Test
  void constructor_nullChannel_throwsNullPointerNamingField() {
    assertThatThrownBy(() -> new OutboundMessage(null, RECIPIENT, SUBJECT, BODY))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("channel");
  }

  @Test
  void constructor_nullRecipient_throwsNullPointerNamingField() {
    assertThatThrownBy(() -> new OutboundMessage(MessageChannel.EMAIL, null, SUBJECT, BODY))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("recipient");
  }

  @Test
  void constructor_nullBody_throwsNullPointerNamingField() {
    assertThatThrownBy(() -> new OutboundMessage(MessageChannel.EMAIL, RECIPIENT, SUBJECT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("body");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t\n"})
  void constructor_emailWithMissingOrBlankSubject_throwsIllegalArgument(String subject) {
    assertThatThrownBy(() -> new OutboundMessage(MessageChannel.EMAIL, RECIPIENT, subject, BODY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Email messages must have a valid subject.");
  }

  @Test
  void toString_masksRecipientAndOmitsBody() {
    var message = new OutboundMessage(MessageChannel.EMAIL, RECIPIENT, SUBJECT, BODY);

    assertThat(message.toString())
        .isEqualTo("OutboundMessage[channel=EMAIL, recipient=j***@e***.com]")
        .doesNotContain(RECIPIENT)
        .doesNotContain(BODY)
        .doesNotContain("RAW-SECRET-TOKEN");
  }
}
