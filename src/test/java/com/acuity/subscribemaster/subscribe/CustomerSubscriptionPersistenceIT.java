package com.acuity.subscribemaster.subscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the mocked unit tests cannot prove, against real Postgres: every enum value survives the
 * converters and CHECK constraints, and the schema's own CHECK constraints fire.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomerSubscriptionPersistenceIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("subscribe_master")
          .withUsername("subscribe_master")
          .withPassword("test_only_not_a_real_secret");

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private CustomerSubscriptionRepository subscriptionRepo;
  @Autowired private CustomerRepository customerRepo;

  private UUID customerId;

  @BeforeEach
  void createCustomer() {
    customerId =
        customerRepo.save(new Customer("it-" + UUID.randomUUID() + "@example.com", "hash")).getId();
  }

  @AfterEach
  void cleanUp() {
    subscriptionRepo.deleteAll();
    customerRepo.deleteAll();
  }

  private CustomerSubscription subscription(BillingFrequency frequency, Integer intervalDays) {
    return new CustomerSubscription(
        customerId,
        null,
        "Custom label",
        "account-1",
        null,
        Currency.USD,
        new BigDecimal("9.99"),
        frequency,
        intervalDays,
        LocalDate.now().plusDays(7),
        SubscriptionStatus.ACTIVE,
        Instant.parse("2026-06-01T00:00:00Z"));
  }

  @Test
  void everyMappedField_roundTripsThroughRealColumns() {
    var saved = subscriptionRepo.saveAndFlush(subscription(BillingFrequency.CUSTOM, 14));

    var loaded = subscriptionRepo.findById(saved.getId()).orElseThrow();

    assertThat(loaded.getCustomerId()).isEqualTo(customerId);
    assertThat(loaded.getProviderId()).isNull();
    assertThat(loaded.getCustomName()).isEqualTo("Custom label");
    assertThat(loaded.getAccountIdentifier()).isEqualTo("account-1");
    assertThat(loaded.getCurrency()).isEqualTo(Currency.USD);
    assertThat(loaded.getAmount()).isEqualByComparingTo("9.99");
    assertThat(loaded.getBillingFrequency()).isEqualTo(BillingFrequency.CUSTOM);
    assertThat(loaded.getBillingIntervalDays()).isEqualTo(14);
    assertThat(loaded.getNextPaymentDate()).isEqualTo(LocalDate.now().plusDays(7));
    assertThat(loaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(loaded.getStartedAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    assertThat(loaded.getCreatedAt()).isNotNull();
    assertThat(loaded.getUpdatedAt()).isNotNull();
    assertThat(loaded.getVersion()).isZero();
  }

  @ParameterizedTest
  @EnumSource(BillingFrequency.class)
  void everyBillingFrequency_isAcceptedByConverterAndCheckConstraint(BillingFrequency frequency) {
    var interval = frequency == BillingFrequency.CUSTOM ? 10 : null;

    var saved = subscriptionRepo.saveAndFlush(subscription(frequency, interval));

    assertThat(subscriptionRepo.findById(saved.getId()).orElseThrow().getBillingFrequency())
        .isEqualTo(frequency);
  }

  @ParameterizedTest
  @EnumSource(Currency.class)
  void everyCurrency_isAcceptedByCharColumnAndCheckConstraint(Currency currency) {
    var entity =
        new CustomerSubscription(
            customerId,
            null,
            "Custom label",
            null,
            null,
            currency,
            new BigDecimal("1.00"),
            BillingFrequency.MONTHLY,
            null,
            LocalDate.now().plusDays(1),
            SubscriptionStatus.ACTIVE,
            Instant.now());

    var saved = subscriptionRepo.saveAndFlush(entity);

    assertThat(subscriptionRepo.findById(saved.getId()).orElseThrow().getCurrency())
        .isEqualTo(currency);
  }

  @ParameterizedTest
  @EnumSource(SubscriptionStatus.class)
  void everyStatus_isAcceptedByCheckConstraint(SubscriptionStatus status) {
    var entity =
        new CustomerSubscription(
            customerId,
            null,
            "Custom label",
            null,
            null,
            Currency.EUR,
            new BigDecimal("1.00"),
            BillingFrequency.MONTHLY,
            null,
            LocalDate.now().plusDays(1),
            status,
            Instant.now());

    var saved = subscriptionRepo.saveAndFlush(entity);

    assertThat(subscriptionRepo.findById(saved.getId()).orElseThrow().getStatus())
        .isEqualTo(status);
  }

  @ParameterizedTest
  @EnumSource(CancellationReason.class)
  void everyCancellationReason_roundTripsAndCancelClearsNextPaymentDate(CancellationReason reason) {
    var entity = subscription(BillingFrequency.MONTHLY, null);
    entity.cancel(Instant.parse("2026-07-01T00:00:00Z"), reason);

    var saved = subscriptionRepo.saveAndFlush(entity);

    var loaded = subscriptionRepo.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(loaded.getCancellationReason()).isEqualTo(reason);
    assertThat(loaded.getCancelledAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    assertThat(loaded.getNextPaymentDate()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void nonPositiveBillingInterval_isRejectedByDatabase(int intervalDays) {
    assertThatThrownBy(
            () ->
                subscriptionRepo.saveAndFlush(subscription(BillingFrequency.CUSTOM, intervalDays)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_customer_subscriptions_interval_positive");
  }

  @Test
  void zeroIntervalOnNonCustomFrequency_isAlsoRejectedByDatabase() {
    assertThatThrownBy(
            () -> subscriptionRepo.saveAndFlush(subscription(BillingFrequency.MONTHLY, 0)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_customer_subscriptions_interval_positive");
  }

  @Test
  void customFrequencyWithoutInterval_isRejectedByDatabase() {
    assertThatThrownBy(
            () -> subscriptionRepo.saveAndFlush(subscription(BillingFrequency.CUSTOM, null)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_custom_interval");
  }

  @Test
  void subscriptionWithNeitherProviderNorCustomName_isRejectedByDatabase() {
    var nameless =
        new CustomerSubscription(
            customerId,
            null,
            null,
            null,
            null,
            Currency.USD,
            new BigDecimal("1.00"),
            BillingFrequency.MONTHLY,
            null,
            LocalDate.now().plusDays(1),
            SubscriptionStatus.ACTIVE,
            Instant.now());

    assertThatThrownBy(() -> subscriptionRepo.saveAndFlush(nameless))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_subscription_has_name");
  }
}
