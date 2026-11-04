package com.acuity.subscribemaster.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Email verification settings ({@code app.email-verification.*}), bound and validated at startup.
 *
 * <p>{@code linkBaseUrl} is the confirm endpoint the emailed link points at. It must come from
 * config, never from the incoming request's {@code Host} header, which is client-controlled
 * (host-header injection) -- prod sets it from {@code VERIFICATION_LINK_BASE_URL} with no default.
 * {@code tokenTtl} is the verification window, shared with FR-32's unverified-account cleanup.
 *
 * <p>{@code resendLimit} and {@code resendWindow} rate-limit FR-40's resend: it is refused once
 * {@code resendLimit} tokens have been issued to the account within the last {@code resendWindow}.
 * The count includes the token issued at registration, so in the first window after registering one
 * fewer resend is allowed. {@code @Positive} because a limit of 0 would refuse every resend with no
 * hint why.
 */
@Validated
@ConfigurationProperties(prefix = "app.email-verification")
public record EmailVerificationProperties(
    @NotBlank String linkBaseUrl,
    @NotNull Duration tokenTtl,
    @Positive int resendLimit,
    @NotNull Duration resendWindow) {}
