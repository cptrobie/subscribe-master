package com.acuity.subscribemaster.idempotency;

import java.io.Serializable;

/**
 * Composite primary key of {@link IdempotencyKey}: {@code (idempotency_key, endpoint)}, matching
 * {@code idempotency_keys}' primary key ({@code V7}). Mapped with {@code @IdClass}, so the
 * component names and types must match the entity's two {@code @Id} fields exactly. A record
 * supplies the value-based {@code equals}/{@code hashCode} an id class needs.
 */
public record IdempotencyPk(String idempotencyKey, String endpoint) implements Serializable {}
