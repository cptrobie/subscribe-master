package com.acuity.subscribemaster.message.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sender identity for outgoing email ({@code app.mail.*}), bound and validated at startup. Kept in
 * config, not code, so a white-label deployment changes them without a code change; {@code
 * fromName} defaults to {@code app.branding.product-name} in YAML. SMTP connection settings are
 * separate -- Spring Boot's own {@code spring.mail.*}, with credentials from Vault in prod.
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record EmailProperties(@NotBlank @Email String fromAddress, @NotBlank String fromName) {}
