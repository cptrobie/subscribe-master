package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerEmailVerificationTokenTest {

  private static final Instant EXPIRES_AT = Instant.parse("2026-10-14T12:00:00Z");

  private static CustomerEmailVerificationToken token() {
    return new CustomerEmailVerificationToken(UUID.randomUUID(), "hashedToken", EXPIRES_AT);
  }

  @Test
  void isExpired_beforeExpiry_returnsFalse() {
    assertThat(token().isExpired(EXPIRES_AT.minusSeconds(1))).isFalse();
  }

  @Test
  void isExpired_exactlyAtExpiry_returnsTrue() {
    assertThat(token().isExpired(EXPIRES_AT)).isTrue();
  }

  @Test
  void isExpired_afterExpiry_returnsTrue() {
    assertThat(token().isExpired(EXPIRES_AT.plusSeconds(1))).isTrue();
  }

  @Test
  void isUsed_newToken_returnsFalse() {
    assertThat(token().isUsed()).isFalse();
  }

  @Test
  void markUsed_unusedToken_recordsTimeAndIsUsed() {
    var token = token();
    var usedAt = EXPIRES_AT.minusSeconds(60);

    token.markUsed(usedAt);

    assertThat(token.isUsed()).isTrue();
    assertThat(token.getUsedAt()).isEqualTo(usedAt);
  }

  @Test
  void markUsed_alreadyUsed_throwsAndKeepsOriginalTime() {
    var token = token();
    var firstUse = EXPIRES_AT.minusSeconds(60);
    token.markUsed(firstUse);

    assertThatThrownBy(() -> token.markUsed(EXPIRES_AT.minusSeconds(30)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(token.getUsedAt()).isEqualTo(firstUse);
  }
}
