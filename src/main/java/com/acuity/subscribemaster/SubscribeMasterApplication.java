package com.acuity.subscribemaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point. {@code @ConfigurationPropertiesScan} registers every
 * {@code @ConfigurationProperties} record in the codebase (e.g. {@code BrandingProperties}, {@code
 * EmailProperties}, {@code EmailVerificationProperties}), so each is bound and {@code @Validated}
 * at startup without being listed here individually.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SubscribeMasterApplication {

  public static void main(String[] args) {
    SpringApplication.run(SubscribeMasterApplication.class, args);
  }
}
