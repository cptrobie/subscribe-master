package com.acuity.subscribemaster.support;

import java.security.SecureRandom;

/**
 * Secure random token generation and one-way hashing.
 *
 * <p>Verification tokens are emailed to the customer in the clear (they are the
 * link), but only their SHA-256 hash is persisted — the same "store the hash,
 * never the raw value" rule applied to sessions and password-reset tokens
 * (see ARCHITECTURE.md &sect;2.3). A leaked database row therefore, cannot be
 * turned back into a working verification link.
 */
public final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private Tokens() {}

}
