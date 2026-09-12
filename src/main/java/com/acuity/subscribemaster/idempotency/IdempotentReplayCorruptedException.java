package com.acuity.subscribemaster.idempotency;

public class IdempotentReplayCorruptedException extends RuntimeException {
    public IdempotentReplayCorruptedException(Throwable cause) {
        super("stored idempotent response could not be re-read", cause);
    }
}
