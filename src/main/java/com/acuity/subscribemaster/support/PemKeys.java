package com.acuity.subscribemaster.support;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses PEM-encoded RSA keys (as read from Vault) into real {@link java.security.Key} objects.
 * Extracted here because this logic was duplicated three times -- {@link JwtIssuer} (private key,
 * for signing), {@link com.acuity.subscribemaster.config.SecurityConfig} (public key, for
 * verification), and the integration tests that independently verify a signature against the same
 * public key.
 */
public final class PemKeys {

  private PemKeys() {}

  public static RSAPrivateKey parsePrivateKey(String pem) {
    try {
      var keySpec = new PKCS8EncodedKeySpec(decode(pem, "PRIVATE KEY"));
      return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("failed to parse RSA private key", e);
    }
  }

  public static RSAPublicKey parsePublicKey(String pem) {
    try {
      var keySpec = new X509EncodedKeySpec(decode(pem, "PUBLIC KEY"));
      return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(keySpec);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("failed to parse RSA public key", e);
    }
  }

  private static byte[] decode(String pem, String label) {
    String cleaned =
        pem.replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(cleaned);
  }
}
