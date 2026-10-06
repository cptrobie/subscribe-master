package com.acuity.subscribemaster.payment;

import com.acuity.subscribemaster.payment.dto.PaymentResponse;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final PaymentHistoryRepository paymentHistRepo;
  private final PaymentAttemptRepository paymentAttemptRepo;
  private final int maxAttempts;

  private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

  public PaymentService(
      PaymentHistoryRepository paymentRepo,
      PaymentAttemptRepository paymentAttemptRepository,
      @Value("${app.payment.retry.max-attempts}") int maxAttempts) {
    this.paymentHistRepo = paymentRepo;
    this.paymentAttemptRepo = paymentAttemptRepository;
    this.maxAttempts = maxAttempts;
  }

  public PaymentResponse getPaymentById(UUID id) {
    var payment =
        paymentHistRepo
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + id));

    return PaymentResponse.paymentMade(
        payment.getId(),
        payment.getSubscriptionId(),
        payment.getPaymentMethodId(),
        payment.getStatus().toString(),
        payment.getAmount(),
        payment.getCurrency().toString(),
        payment.getBaseCurrencyAmount(),
        payment.getBaseCurrency().toString(),
        payment.getExchangeRateApplied(),
        payment.getScheduledAt(),
        payment.getPaidAt(),
        payment.getCreatedAt());
  }

  @Transactional
  public void recordAttempt(
      UUID paymentHistoryId, PaymentAttemptStatus outcome, String failureReason) {
    PaymentHistory payment =
        paymentHistRepo
            .findById(paymentHistoryId)
            .orElseThrow(
                () -> new EntityNotFoundException("PaymentHistory not found: " + paymentHistoryId));

    short nextAttemptNumber = (short) (payment.getAttemptCount() + 1);
    Instant now = Instant.now();

    PaymentAttempt attempt = new PaymentAttempt();
    attempt.setPaymentHistoryId(paymentHistoryId);
    attempt.setAttemptNumber(nextAttemptNumber);
    attempt.setStatus(outcome);
    attempt.setFailureReason(failureReason);
    attempt.setAttemptedAt(now);
    paymentAttemptRepo.save(attempt);

    payment.setAttemptCount(nextAttemptNumber);
    payment.setLastAttemptedAt(now);

    if (outcome == PaymentAttemptStatus.SUCCEEDED) {
      payment.setStatus(PaymentStatus.SUCCEEDED);
      payment.setPaidAt(now);
    } else {
      payment.setStatus(PaymentStatus.FAILED);
      if (nextAttemptNumber >= maxAttempts) {
        // exhausted — cross-domain call + audit + notification, all outside this transaction
      }
    }

    paymentHistRepo.save(payment);
  }
}
