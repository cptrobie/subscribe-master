package com.acuity.subscribemaster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class PasswordEncoderConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    // Cost factor 12, not BCryptPasswordEncoder's own default of 10 -- OWASP's
    // stated floor is 10, but the actual 2026 practical consensus (PHP's own
    // built-in default was raised from 10 to 12 this cycle) has moved past
    // that floor. Genuinely correct tuning needs benchmarking on real production
    // hardware (target ~200-500ms/hash) -- deferred until NFR-21 picks a host;
    // revisit then rather than treating 12 as permanently settled.
    return new BCryptPasswordEncoder(12);
  }
}
