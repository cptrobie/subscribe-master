package com.acuity.subscribemaster.config;

import com.acuity.subscribemaster.staff.StaffJwtAuthenticationConverter;
import com.acuity.subscribemaster.support.JwtAudiences;
import com.acuity.subscribemaster.support.PemKeys;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security: two stateless JWT resource-server filter chains, one for staff and one for
 * customers, both deny-by-default (FR-06).
 *
 * <p><b>Why two chains.</b> Staff and customer tokens are signed with the same key, so the
 * signature alone can't tell them apart. {@code JwtIssuer} sets {@code aud}: {@code staff-api} on
 * staff tokens, {@code customer-api} on customer tokens. Each chain's decoder accepts only its own
 * audience. Without that, a staff token would pass the customer chain and its {@code sub} would be
 * read as a customer id. A token sent to the wrong chain gets a 401 with {@code invalid_token}.
 *
 * <ul>
 *   <li><b>Staff chain</b> ({@code @Order(1)}, {@code /api/v1/staff/**} only). Staff login, {@code
 *       /api/v1/staff/auth/**}, is public; every other staff path needs a {@code staff-api} token.
 *       {@link StaffJwtAuthenticationConverter} then loads the user's permissions from the database
 *       on every request and makes them the authorities, so access changes and deactivations apply
 *       on the next request. An unknown or deactivated staff user gets a 401.
 *   <li><b>Customer chain</b> ({@code @Order(2)}, every other path). Public: {@code
 *       /api/v1/auth/**} (FR-01 register, FR-02 login, FR-30 verify-email, FR-40
 *       resend-verification) and the OpenAPI endpoints ({@code /v3/api-docs/**}, {@code
 *       /swagger-ui/**}, {@code /swagger-ui.html}), so NFR-09's docs work in local dev. Everything
 *       else, today {@code /api/v1/subscribe/**} and the hidden Wave 6 payment scaffolding, needs a
 *       {@code customer-api} token. Controllers read the caller from the token's {@code sub} via
 *       {@code @AuthenticationPrincipal Jwt}, never from a client-supplied id, which is how FR-04's
 *       per-customer data isolation holds.
 * </ul>
 *
 * <p>The order matters: Spring uses the first chain whose matcher fits the request, and the
 * customer chain matches everything, so the staff chain must come first.
 *
 * <p><b>Tokens.</b> RS256 access tokens issued by {@code JwtIssuer} (15 minutes, {@code
 * app.jwt.access-token-duration-minutes}), verified against the public key from Vault ({@code
 * jwt.public-key}, PEM). There is deliberately no {@link JwtDecoder} bean: each chain builds its
 * own through {@link #audienceDecoder}, because two decoder beans would make Spring Boot's
 * auto-configuration ambiguous.
 *
 * <p><b>Permission checks.</b> {@code @EnableMethodSecurity} lets staff endpoints use
 * {@code @PreAuthorize("hasAuthority('some_permission')")}. Endpoints check a permission name,
 * never a role; see {@code design-docs/staff_authorization_design.md}.
 *
 * <p><b>What is deliberately absent.</b>
 *
 * <ul>
 *   <li>No session and no CSRF, in either chain: the API uses no cookies, and a browser never adds
 *       an {@code Authorization: Bearer} header on its own, so CSRF is disabled rather than
 *       configured. Each chain builds its own filters, so both disable it.
 *   <li>No Actuator rules yet: NFR-36 adds the management port with its own chain.
 * </ul>
 *
 * <p>"Deny by default, permit explicitly" is the posture to keep as endpoints are added: a new
 * public route needs an entry in its chain's {@code permitAll()} list, and the test suite should
 * prove it.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private final RSAPublicKey publicKey;

  public SecurityConfig(@Value("${jwt.public-key}") String publicKeyPem) {
    this.publicKey = PemKeys.parsePublicKey(publicKeyPem);
  }

  /**
   * A decoder for one audience: the default checks (signature, expiry, not-before) plus {@code aud}
   * containing {@code audience}. Deliberately not a bean; see the class Javadoc.
   *
   * @param audience {@link JwtAudiences#STAFF} or {@link JwtAudiences#CUSTOMER}
   */
  private JwtDecoder audienceDecoder(String audience) {
    var decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(new JwtAudienceValidator(audience)));
    return decoder;
  }

  @Bean
  @Order(1)
  SecurityFilterChain staffSecurityFilterChain(
      HttpSecurity http, StaffJwtAuthenticationConverter staffConverter) throws Exception {
    http.securityMatcher("/api/v1/staff/**")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/staff/auth/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwt ->
                        jwt.decoder(audienceDecoder(JwtAudiences.STAFF))
                            .jwtAuthenticationConverter(staffConverter)))
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain customerSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.decoder(audienceDecoder(JwtAudiences.CUSTOMER))))
        .csrf(csrf -> csrf.disable());

    return http.build();
  }
}
