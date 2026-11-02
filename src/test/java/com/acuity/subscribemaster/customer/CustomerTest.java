package com.acuity.subscribemaster.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTest {

  private static Customer unverifiedCustomer() {
    return new Customer(UUID.randomUUID(), "addme@example.com", "easyPassword!#123", false, true);
  }

  private static Customer verifiedCustomer() {
    return new Customer(UUID.randomUUID(), "addme@example.com", "easyPassword!#123", true, true);
  }

  @Test
  void successfulChangeEmailOnUnverifiedCustomer_updatesEmailAddress() {
    var updateEmail = "updateme@example.com";
    var customer = unverifiedCustomer();

    customer.changeEmail(updateEmail);
    assertThat(customer.getEmail()).isEqualTo(updateEmail);
  }

  @Test
  void changeEmailOnVerifiedCustomer_throwsIllegalStateExceptionEmailAddressUnchanged() {
    var customer = verifiedCustomer();

    assertThatThrownBy(() -> customer.changeEmail("updateme@example.com"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(customer.getEmail()).isEqualTo("addme@example.com");
  }
}
