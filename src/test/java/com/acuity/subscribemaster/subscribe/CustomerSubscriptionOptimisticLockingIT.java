package com.acuity.subscribemaster.subscribe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * NFR-04 against real Postgres: {@code @Version} on {@code customer_subscriptions} really rejects a
 * stale write. Runs without a surrounding test transaction because the tests need independent,
 * committed transactions.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomerSubscriptionOptimisticLockingIT {

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
  @Autowired private PlatformTransactionManager txManager;

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

  private CustomerSubscription subscription() {
    return new CustomerSubscription(
        customerId,
        null,
        "Custom label",
        "account-1",
        null,
        Currency.USD,
        new BigDecimal("9.99"),
        BillingFrequency.MONTHLY,
        null,
        LocalDate.now().plusDays(7),
        SubscriptionStatus.ACTIVE,
        Instant.parse("2026-06-01T00:00:00Z"));
  }

  @Test
  void version_startsAtZeroAndIncrementsOnEveryUpdate() {
    var created = subscriptionRepo.save(subscription());
    assertThat(created.getVersion()).isZero();

    created.setAmount(new BigDecimal("10.00"));
    var afterFirstUpdate = subscriptionRepo.save(created);
    assertThat(afterFirstUpdate.getVersion()).isEqualTo(1);

    afterFirstUpdate.setAmount(new BigDecimal("11.00"));
    var afterSecondUpdate = subscriptionRepo.save(afterFirstUpdate);
    assertThat(afterSecondUpdate.getVersion()).isEqualTo(2);

    assertThat(subscriptionRepo.findById(created.getId()).orElseThrow().getVersion()).isEqualTo(2);
  }

  @Test
  void staleWriteInterleavedWithCommittedUpdate_isRejectedAndFirstWriterWins() {
    var id = subscriptionRepo.save(subscription()).getId();
    var outer = new TransactionTemplate(txManager);
    var independent = new TransactionTemplate(txManager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    assertThatThrownBy(
            () ->
                outer.executeWithoutResult(
                    status -> {
                      var stale = subscriptionRepo.findById(id).orElseThrow();

                      independent.executeWithoutResult(
                          inner -> {
                            var competing = subscriptionRepo.findById(id).orElseThrow();
                            competing.setAmount(new BigDecimal("20.00"));
                            subscriptionRepo.saveAndFlush(competing);
                          });

                      stale.setAmount(new BigDecimal("30.00"));
                      subscriptionRepo.saveAndFlush(stale);
                    }))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    var winner = subscriptionRepo.findById(id).orElseThrow();
    assertThat(winner.getAmount()).isEqualByComparingTo("20.00");
    assertThat(winner.getVersion()).isEqualTo(1);
  }

  @Test
  void twoThreadsRacingOnTheSameRow_exactlyOneSucceedsAndTheOtherIsRejected() throws Exception {
    var id = subscriptionRepo.save(subscription()).getId();
    var bothHaveLoaded = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);

    try {
      var first = pool.submit(() -> writeAfterBarrier(id, new BigDecimal("11.00"), bothHaveLoaded));
      var second =
          pool.submit(() -> writeAfterBarrier(id, new BigDecimal("22.00"), bothHaveLoaded));

      var failures =
          Stream.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
              .filter(Objects::nonNull)
              .toList();

      assertThat(failures).hasSize(1);
      assertThat(failures.get(0)).isInstanceOf(ObjectOptimisticLockingFailureException.class);

      var row = subscriptionRepo.findById(id).orElseThrow();
      assertThat(row.getVersion()).isEqualTo(1);
      assertThat(row.getAmount()).isIn(new BigDecimal("11.00"), new BigDecimal("22.00"));
    } finally {
      pool.shutdownNow();
    }
  }

  private Throwable writeAfterBarrier(UUID id, BigDecimal newAmount, CyclicBarrier barrier) {
    try {
      new TransactionTemplate(txManager)
          .executeWithoutResult(
              status -> {
                var entity = subscriptionRepo.findById(id).orElseThrow();
                awaitBoth(barrier);
                entity.setAmount(newAmount);
                subscriptionRepo.saveAndFlush(entity);
              });
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  private static void awaitBoth(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("both writers must load the row before either writes", e);
    }
  }
}
