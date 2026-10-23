package com.acuity.subscribemaster.idempotency;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(@NotNull String hmacSecret, @NotNull Duration ttl) {}
