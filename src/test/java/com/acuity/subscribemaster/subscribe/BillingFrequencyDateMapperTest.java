package com.acuity.subscribemaster.subscribe;

import static com.acuity.subscribemaster.subscribe.BillingFrequencyDateMapper.advanceBillingDateByFrequency;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Pure unit test -- static date arithmetic only, no mocks and no Spring context. */
class BillingFrequencyDateMapperTest {

  @ParameterizedTest(name = "{0}: {1} -> {2}")
  @CsvSource({
    "WEEKLY, 2026-03-10, 2026-03-17",
    "MONTHLY, 2026-03-10, 2026-04-10",
    "QUARTERLY, 2026-03-10, 2026-06-10",
    "SEMI_ANNUAL, 2026-03-10, 2026-09-10",
    "ANNUAL, 2026-03-10, 2027-03-10"
  })
  void advance_standardFrequency_addsExactlyOneCycle(
      BillingFrequency frequency, LocalDate current, LocalDate expected) {
    assertThat(advanceBillingDateByFrequency(current, frequency, null)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}: {1} -> {2}")
  @CsvSource({
    "WEEKLY, 2026-12-28, 2027-01-04",
    "MONTHLY, 2026-12-15, 2027-01-15",
    "QUARTERLY, 2026-11-15, 2027-02-15",
    "SEMI_ANNUAL, 2026-08-15, 2027-02-15"
  })
  void advance_crossingYearBoundary_rollsIntoNextYear(
      BillingFrequency frequency, LocalDate current, LocalDate expected) {
    assertThat(advanceBillingDateByFrequency(current, frequency, null)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}: {1} -> {2}")
  @CsvSource({
    "MONTHLY, 2026-01-31, 2026-02-28",
    "MONTHLY, 2028-01-31, 2028-02-29",
    "MONTHLY, 2026-03-31, 2026-04-30",
    "QUARTERLY, 2026-11-30, 2027-02-28",
    "QUARTERLY, 2027-11-30, 2028-02-29",
    "SEMI_ANNUAL, 2026-08-31, 2027-02-28",
    "ANNUAL, 2028-02-29, 2029-02-28"
  })
  void advance_startDateBeyondTargetMonthLength_clampsToLastDayOfTargetMonth(
      BillingFrequency frequency, LocalDate current, LocalDate expected) {
    assertThat(advanceBillingDateByFrequency(current, frequency, null)).isEqualTo(expected);
  }

  // 2027-03-10 -> 2028-03-10 spans Feb 29 2028 (366 days), so a fixed 365-day step would land a
  // day early.
  @Test
  void advance_annualAcrossLeapDay_keepsSameCalendarDate() {
    var current = LocalDate.of(2027, 3, 10);

    assertThat(advanceBillingDateByFrequency(current, BillingFrequency.ANNUAL, null))
        .isEqualTo(LocalDate.of(2028, 3, 10));
  }

  @ParameterizedTest(name = "CUSTOM {0} days: {1} -> {2}")
  @CsvSource({
    "1, 2026-03-10, 2026-03-11",
    "7, 2026-03-10, 2026-03-17",
    "14, 2026-03-10, 2026-03-24",
    "30, 2026-03-10, 2026-04-09",
    "365, 2026-03-10, 2027-03-10",
    "45, 2026-12-20, 2027-02-03",
    "10, 2028-02-20, 2028-03-01"
  })
  void advance_custom_addsConfiguredNumberOfDays(
      Integer intervalDays, LocalDate current, LocalDate expected) {
    assertThat(advanceBillingDateByFrequency(current, BillingFrequency.CUSTOM, intervalDays))
        .isEqualTo(expected);
  }

  @Test
  void advance_customWithNullInterval_throwsNullPointerExceptionWithMessage() {
    var current = LocalDate.of(2026, 3, 10);

    assertThatThrownBy(() -> advanceBillingDateByFrequency(current, BillingFrequency.CUSTOM, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("billingIntervalDays required for CUSTOM frequency");
  }

  @ParameterizedTest
  @EnumSource(value = BillingFrequency.class, names = "CUSTOM", mode = EnumSource.Mode.EXCLUDE)
  void advance_standardFrequency_ignoresCustomIntervalDays(BillingFrequency frequency) {
    var current = LocalDate.of(2026, 3, 10);

    assertThat(advanceBillingDateByFrequency(current, frequency, 99))
        .isEqualTo(advanceBillingDateByFrequency(current, frequency, null));
  }

  // SubscriptionService.resume() loops until the date passes today; a frequency that failed to
  // advance would make that loop run forever.
  @ParameterizedTest
  @EnumSource(BillingFrequency.class)
  void advance_everyFrequency_alwaysMovesStrictlyForward(BillingFrequency frequency) {
    var current = LocalDate.of(2026, 1, 31);

    assertThat(advanceBillingDateByFrequency(current, frequency, 1)).isAfter(current);
  }
}
