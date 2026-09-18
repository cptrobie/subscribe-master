package com.acuity.subscribemaster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * FR-01/FR-02's registration and login endpoints must be reachable without authentication -- that's
 * the entire point of them. Spring Security's own default (nothing permitted, everything requires
 * auth) would otherwise block them, which is exactly what happened before this bean existed: every
 * request, including the deliberately public auth endpoints, returned 401.
 *
 * <p>Everything else stays authenticated by default -- there's no real authenticated endpoint to
 * protect yet (FR-04 is Wave 1's per-user data isolation, not yet built), but "deny by default,
 * permit explicitly" is the safer posture to start from rather than "permit by default."
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(csrf -> csrf.disable());
    return http.build();
  }
}
