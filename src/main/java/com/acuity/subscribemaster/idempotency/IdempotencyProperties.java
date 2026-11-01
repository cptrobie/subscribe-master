package com.acuity.subscribemaster.idempotency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Idempotency settings ({@code app.idempotency.*}), bound and validated at startup.
 *
 * <p>{@code hmacSecret} keys the HMAC-SHA256 that fingerprints each request ({@code request_hash}).
 * It's a secret, so it comes from Vault in every environment and never from a yaml file -- see the
 * README's Vault step; {@code @NotBlank} stops the app from starting without it. {@code ttl} is how
 * long a stored response can be replayed before its key is free to reuse.
 */
@Validated
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(@NotBlank String hmacSecret, @NotNull Duration ttl) {}
