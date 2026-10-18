package com.acuity.subscribemaster.support;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-deployment branding ({@code app.branding.*}), bound and validated at startup. The one place a
 * buyer rebrands the product, so customer-facing text never hardcodes the product name. See {@code
 * design-docs/white_label_branding_and_multipart_email.md} for the fuller white-label scope.
 */
@Validated
@ConfigurationProperties(prefix = "app.branding")
public record BrandingProperties(@NotBlank String productName) {}
