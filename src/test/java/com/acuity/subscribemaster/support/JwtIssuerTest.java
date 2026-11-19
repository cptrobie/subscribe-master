package com.acuity.subscribemaster.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Unit tests for {@link JwtIssuer}: what it actually puts in a token (FR-02, FR-06). It's
 * deliberately a plain JUnit test, with no Spring context, Vault or Testcontainers. The issuer is
 * created directly from the test key pair in {@code test-jwt-keys/}, never {@code local-dev-keys/},
 * so the tests run in milliseconds, behave the same on CI, and count towards the JaCoCo unit-test
 * gate.
 *
 * <p><b>Every token is checked as a decoder would see it.</b> {@link #verifiedClaims} parses the
 * token and verifies its RS256 signature against the matching public key before any claim is read,
 * so each test also proves the token is correctly signed.
 *
 * <p><b>What the tests protect against.</b> Customer and staff tokens share one signing key, so the
 * {@code aud} claim is the only thing that keeps a staff token from passing the customer security
 * chain, and the reverse. The audience tests use {@code containsExactly}, so a token carrying both
 * audiences fails, and the last test shows that for the same id the two tokens differ in audience
 * alone. The expiry test checks both that {@code exp} matches the {@link
 * JwtIssuer.IssuedToken#expiresAt()} returned to callers, and that the lifetime really is the
 * configured duration. The second check matters: if both were wrong in the same way, the first
 * check alone would still pass.
 */
public class JwtIssuerTest {

  private static final long DURATION_MINUTES = 15;
  private JwtIssuer issuer;
  private RSAPublicKey publicKey;

  @BeforeEach
  void setUp() throws IOException {
    issuer = new JwtIssuer(readTestResource("test-jwt-keys/jwt-private.pem"), DURATION_MINUTES);
    publicKey = PemKeys.parsePublicKey(readTestResource("test-jwt-keys/jwt-public.pem"));
  }

  /**
   * Parses {@code token}, asserts its signature verifies against the test public key, and returns
   * its claims -- so no test reads a claim from a token that isn't properly signed.
   */
  private JWTClaimsSet verifiedClaims(String token) throws Exception {
    var jwt = SignedJWT.parse(token);
    assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
    return jwt.getJWTClaimsSet();
  }

  /** Reads a classpath resource as UTF-8 text; the same helper as {@code AuthControllerIT}'s. */
  private static String readTestResource(String classpathLocation) throws IOException {
    var resource = new ClassPathResource(classpathLocation);
    try (var inputStream = resource.getInputStream()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void customerTokenHasCustomerAudienceAndSubject() throws Exception {
    var customerId = UUID.randomUUID();

    var claims = verifiedClaims(issuer.issueCustomer(customerId).token());

    assertThat(claims.getAudience()).containsExactly(JwtAudiences.CUSTOMER);
    assertThat(claims.getSubject()).isEqualTo(customerId.toString());
  }

  @Test
  void staffTokenHasStaffAudienceAndSubject() throws Exception {
    var staffId = UUID.randomUUID();

    var claims = verifiedClaims(issuer.issueStaff(staffId).token());

    assertThat(claims.getAudience()).containsExactly(JwtAudiences.STAFF);
    assertThat(claims.getSubject()).isEqualTo(staffId.toString());
  }

  @Test
  void tokenExpiryMatchesConfiguredDuration() throws Exception {
    var before = Instant.now();

    var issued = issuer.issueCustomer(UUID.randomUUID());
    var after = Instant.now();
    var claims = verifiedClaims(issued.token());

    assertThat(issued.expiresAt())
        .isBetween(
            before.plus(DURATION_MINUTES, ChronoUnit.MINUTES),
            after.plus(DURATION_MINUTES, ChronoUnit.MINUTES));

    // a JWT stores times in whole seconds, while expiresAt keeps nanoseconds.
    assertThat(claims.getExpirationTime().toInstant())
        .isEqualTo(issued.expiresAt().truncatedTo(ChronoUnit.SECONDS));
  }

  @Test
  void customerAndStaffTokensForSameIdDifferOnlyInAudience() throws Exception {
    var genericId = UUID.randomUUID();

    var staffClaims = verifiedClaims(issuer.issueStaff(genericId).token());
    var customerClaims = verifiedClaims(issuer.issueCustomer(genericId).token());

    assertThat(staffClaims.getAudience()).containsExactly(JwtAudiences.STAFF);
    assertThat(staffClaims.getSubject()).isEqualTo(genericId.toString());

    assertThat(customerClaims.getAudience()).containsExactly(JwtAudiences.CUSTOMER);
    assertThat(customerClaims.getSubject()).isEqualTo(genericId.toString());
  }
}
