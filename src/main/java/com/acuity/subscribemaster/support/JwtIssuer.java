package com.acuity.subscribemaster.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues signed access tokens (RS256) for authenticated customers (FR-02) and staff (FR-06).
 * Verification (FR-04) is handled separately by Spring's oauth2-resource-server, configured with
 * the matching public key -- this class only ever signs, never verifies.
 *
 * <p><b>Two audiences, one key.</b> Both kinds of token are signed with the same key and carry the
 * same claims: {@code sub} (the customer's or staff user's id), {@code iat} and {@code exp}. The
 * only difference is {@code aud}: {@link JwtAudiences#CUSTOMER} or {@link JwtAudiences#STAFF}. Each
 * security chain's decoder accepts only its own audience, which keeps a staff token from being
 * accepted as a customer's, and the reverse. Callers choose with {@link #issueCustomer} or {@link
 * #issueStaff}; the audience is never a parameter they pass, so it can't be mixed up.
 *
 * <p>Neither token carries roles or permissions. A staff request's permissions are looked up from
 * the database on every request, so revoking access takes effect immediately rather than when the
 * token expires. See {@code design-docs/staff_authorization_design.md}.
 *
 * <p>Both lifetimes come from {@code app.jwt.access-token-duration-minutes} (15). Tokens issued
 * before audiences were added have no {@code aud} and are rejected once the decoders check it, so
 * those customers have to log in again, at most one token lifetime after the deploy.
 */
@Component
public class JwtIssuer {

  private final long accessTokenDurationMinutes;
  private final RSAPrivateKey signingKey;

  public JwtIssuer(
      @Value("${jwt.private-key}") String privateKeyPem,
      @Value("${app.jwt.access-token-duration-minutes}") long accessTokenDurationMinutes) {
    this.signingKey = PemKeys.parsePrivateKey(privateKeyPem);
    this.accessTokenDurationMinutes = accessTokenDurationMinutes;
  }

  /** A signed, compact JWT string, and the exact Instant it expires at. */
  public record IssuedToken(String token, Instant expiresAt) {}

  /**
   * A customer access token, accepted only by the customer security chain.
   *
   * @param customerId the authenticated customer's id, carried as {@code sub}
   * @return the signed token and when it expires
   */
  public IssuedToken issueCustomer(UUID customerId) {
    return issue(customerId, JwtAudiences.CUSTOMER);
  }

  /**
   * A staff access token, accepted only by the staff security chain. Carries no permissions; the
   * staff chain loads them from the database on each request.
   *
   * @param staffUserId the authenticated staff user's id, carried as {@code sub}
   * @return the signed token and when it expires
   */
  public IssuedToken issueStaff(UUID staffUserId) {
    return issue(staffUserId, JwtAudiences.STAFF);
  }

  /** Signs a token for {@code subject} with the given audience; shared by both public methods. */
  private IssuedToken issue(UUID subject, String audience) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(accessTokenDurationMinutes, ChronoUnit.MINUTES);

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .audience(audience)
            .subject(subject.toString())
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
}
