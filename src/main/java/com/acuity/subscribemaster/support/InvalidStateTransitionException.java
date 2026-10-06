package com.acuity.subscribemaster.support;

public class InvalidStateTransitionException extends RuntimeException {
  public InvalidStateTransitionException(String message) {
    super(message);
  }
}
