package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Registration request body. */
@Schema(name = "RegistrationRequest")
public record RegistrationRequest(
@Schema(example = "ada@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
@NotBlank
@Email
@Size(max = 254)
String email,
@Schema(
        description = "12–128 characters. Stored only as a BCrypt hash.",
        example = "correct horse battery staple",
        requiredMode = Schema.RequiredMode.REQUIRED)
@NotBlank
@Size(min = 12, max = 128)
String password) {}
