package com.acuity.subscribemaster.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues signed access tokens (RS256) for authenticated customers. Verification (FR-04) is handled
 * separately by Spring's oauth2-resource-server, configured with the matching public key -- this
 * class only ever signs, never verifies.
 */
@Component
public class JwtIssuer {

  private final RSAPrivateKey signingKey;
  private final long accessTokenDurationMinutes;

  public JwtIssuer(
      @Value("${jwt.private-key}") String privateKeyPem,
      @Value("${app.jwt.access-token-duration-minutes}") long accessTokenDurationMinutes) {
    this.signingKey = PemKeys.parsePrivateKey(privateKeyPem);
    this.accessTokenDurationMinutes = accessTokenDurationMinutes;
  }

  /** A signed, compact JWT string, and the exact Instant it expires at. */
  public record IssuedToken(String token, Instant expiresAt) {}

  public IssuedToken issue(UUID customerId) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(accessTokenDurationMinutes, ChronoUnit.MINUTES);

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(customerId.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .build();

    SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    try {
      signedJwt.sign(new RSASSASigner(signingKey));
    } catch (JOSEException e) {
      throw new IllegalStateException("failed to sign JWT", e);
    }

    return new IssuedToken(signedJwt.serialize(), expiresAt);
  }

  private static RSAPrivateKey parsePrivateKey(String pem) {
    String cleaned =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    try {
      var keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(cleaned));
      return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("failed to parse JWT signing key", e);
    }
  }
}
