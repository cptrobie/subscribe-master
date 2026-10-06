package com.acuity.subscribemaster.support;

/**
 * This is a Service layer Exception that does not go through the GlobalExceptionHandler which deals
 * with HTTP-layer exceptions
 */
public class EntityNotFoundException extends RuntimeException {
  public EntityNotFoundException(String message) {
    super(message);
  }
}
