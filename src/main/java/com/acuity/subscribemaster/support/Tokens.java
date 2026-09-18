package com.acuity.subscribemaster.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Secure random token generation and one-way hashing.
 *
 * <p>Verification tokens are emailed to the customer in the clear (they are the link), but only
 * their SHA-256 hash is persisted — the same "store the hash, never the raw value" rule applied to
 * sessions and password-reset tokens (see ARCHITECTURE.md &sect;2.3). A leaked database row
 * therefore, cannot be turned back into a working verification link.
 */
public final class Tokens {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;

  private Tokens() {}

  /**
   * A new, cryptographically random raw token (256 bits), URL-safe Base64-encoded. This is the
   * value handed to the client (in an email link, or a session response) — never persisted
   * directly; see {@link #hash(String)}.
   */
  public static String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * SHA-256 hash of a raw token, as a lowercase hex string — this, not the raw value, is what gets
   * persisted. A fast hash is deliberate here, not a weakness: unlike a password (a low-entropy,
   * human-chosen secret BCrypt is designed to slow down brute-force guessing of), a token from
   * {@link #generate()} is already a high-entropy random value nobody is brute-force-guessing
   * regardless of hash speed — and this hash runs on every authenticated request, not once per
   * login, so BCrypt's deliberate slowness would add real, unnecessary latency at that frequency.
   */
  public static String hash(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a JDK-guaranteed algorithm (every compliant JVM must support it) --
      // this can only happen if the JVM itself is broken, not from any real runtime input.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
