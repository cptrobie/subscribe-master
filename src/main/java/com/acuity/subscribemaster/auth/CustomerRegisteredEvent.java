package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.support.EmailMasker;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by {@code AuthService.register()} once a customer and their email verification token
 * have been saved, inside the registration transaction (FR-30). {@link VerificationEmailListener}
 * reacts after that transaction commits, so a registration that rolls back never emails a link to
 * an account that doesn't exist. Public deliberately: under NFR-28, other modules are expected to
 * react to registration through this event rather than being called directly.
 *
 * <p>{@code rawToken} is the only copy of the unhashed verification token -- the database holds
 * just its hash -- and exists solely so the listener can put it in the emailed link. {@code
 * ipAddress} is carried for the audit entry and may be null.
 *
 * <p><b>{@link #toString()} is overridden deliberately -- do not remove it.</b> A record's default
 * would print the raw token and the full email address; this prints only the customer id and a
 * masked email (NFR-23, ARCHITECTURE.md's "never log a raw token" rule). The IP address is left out
 * too: it belongs in {@code audit_logs}, not application logs.
 *
 * <p><b>Caveat for NFR-28:</b> this is safe only while events stay in memory. Spring Modulith's
 * event publication registry, typically adopted with {@code @ApplicationModuleListener}, persists
 * each event as JSON so failed listeners can be retried -- which would store {@code rawToken} in
 * plain text and break the hash-only rule (ARCHITECTURE.md &sect;2.3). Before enabling it, remove
 * {@code rawToken} from this event and have the listener generate and save the token itself, in its
 * own transaction (accepting that a failure there leaves a customer without a token, recoverable
 * via FR-40's resend).
 */
public record CustomerRegisteredEvent(
    UUID customerId, String email, String rawToken, String ipAddress) {

  public CustomerRegisteredEvent {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(rawToken, "rawToken");
  }

  @Override
  public String toString() {
    return "CustomerRegisteredEvent["
        + "customerId="
        + customerId
        + ", email="
        + EmailMasker.mask(email)
        + "]";
  }
}
