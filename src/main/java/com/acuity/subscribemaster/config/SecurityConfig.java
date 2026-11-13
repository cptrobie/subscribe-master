package com.acuity.subscribemaster.config;

import com.acuity.subscribemaster.support.PemKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The single HTTP security filter chain: stateless JWT resource server, deny-by-default.
 *
 * <p><b>What is public.</b> {@code /api/v1/auth/**} (FR-01 register, FR-02 login, FR-30
 * verify-email, FR-40 resend-verification) must be reachable without a token -- that's the entire
 * point of them. The OpenAPI endpoints ({@code /v3/api-docs/**}, {@code /swagger-ui/**}, {@code
 * /swagger-ui.html}) are public too, so NFR-09's docs work in local dev without a token. Spring
 * Security's own default (nothing permitted) blocked all of these before this bean existed: every
 * request, including the deliberately public auth endpoints, returned 401.
 *
 * <p><b>What is protected.</b> Everything else requires a valid bearer JWT, which today means the
 * whole of {@code /api/v1/subscribe/**} ({@code SubscriptionController}) plus the hidden Wave 6
 * payment scaffolding. Tokens are RS256 access tokens issued by {@code JwtIssuer} (15 minutes,
 * {@code app.jwt.access-token-duration-minutes}) and verified by the {@link JwtDecoder} bean
 * against the public key loaded from Vault ({@code jwt.public-key}, PEM). Controllers read the
 * caller's identity from the token's {@code sub} claim via {@code @AuthenticationPrincipal Jwt} --
 * never from a client-supplied id -- which is how FR-04's per-customer data isolation holds.
 *
 * <p><b>What is deliberately absent.</b>
 *
 * <ul>
 *   <li>No role or permission checks: every authenticated principal is a customer. Staff
 *       authorization (FR-06, Wave 2) will add a second chain or authority mapping for {@code
 *       staff_users}; see {@code design-docs/staff_authorization_design.md}.
 *   <li>No session and no CSRF: the API is stateless and token-based, so CSRF is disabled rather
 *       than configured.
 *   <li>No Actuator rules yet: NFR-36 (Wave 2) adds the management port with its own chain.
 * </ul>
 *
 * <p>"Deny by default, permit explicitly" is the posture to keep as endpoints are added: a new
 * public route needs an entry in the {@code permitAll()} list, and the test suite should prove it.
 */
@Configuration
public class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(@Value("${jwt.public-key}") String publicKeyPem) {
    return NimbusJwtDecoder.withPublicKey(PemKeys.parsePublicKey(publicKeyPem)).build();
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/**",
                        // TEMPORARY (FR-06 step 4c): moves to the staff chain in step 5
                        "/api/v1/staff/auth/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .csrf(csrf -> csrf.disable());
    return http.build();
  }
}
