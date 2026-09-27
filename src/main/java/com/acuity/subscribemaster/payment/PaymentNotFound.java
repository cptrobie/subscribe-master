package com.acuity.subscribemaster.payment;

public class PaymentNotFound extends RuntimeException {
  public PaymentNotFound(String message) {
    super(message);
  }
}
