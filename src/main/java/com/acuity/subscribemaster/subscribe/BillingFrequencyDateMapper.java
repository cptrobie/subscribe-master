package com.acuity.subscribemaster.subscribe;

import java.time.LocalDate;
import java.util.Objects;

public class BillingFrequencyDateMapper {

  public static LocalDate advanceBillingDateByFrequency(
      LocalDate currentBillingDate, BillingFrequency billingFrequency, Integer customIntervalDays) {
    return switch (billingFrequency) {
      case WEEKLY -> currentBillingDate.plusWeeks(1);
      case MONTHLY -> currentBillingDate.plusMonths(1);
      case QUARTERLY -> currentBillingDate.plusMonths(3);
      case SEMI_ANNUAL -> currentBillingDate.plusMonths(6);
      case ANNUAL -> currentBillingDate.plusYears(1);
      case CUSTOM ->
          currentBillingDate.plusDays(
              Objects.requireNonNull(
                  customIntervalDays, "billingIntervalDays required for CUSTOM frequency"));
    };
  }
}
