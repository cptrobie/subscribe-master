package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;


/** Registration response body. Never carries the password hash or the raw token. */
@Schema(name = "RegistrationResponse")
public record RegistrationResponse(
    UUID id, String email, boolean emailVerified, Instant createdAt, String message) {

        public static RegistrationResponse accepted(
            UUID id, String email, boolean emailVerified, Instant createdAt) {
                return new RegistrationResponse(
                    id,
                    email,
                    emailVerified,
                    createdAt,
                    "Account created. Check your email for a verification link, valid for 24 hours.");
            }
    }
