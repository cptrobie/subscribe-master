package com.acuity.subscribemaster.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit test -- no Spring context, no database. The repository and transaction manager are
 * mocked; the JSON mapper, properties and {@link TransactionTemplate} are real, so serialization,
 * the HMAC request hash and the run-in-a-transaction path are actually exercised. Uses its own
 * small request/response records rather than the auth DTOs, so it tests the service, not
 * registration.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {
  private static final String KEY = "key-1";
  private static final String ENDPOINT = "POST /test";
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID OTHER_CALLER = UUID.randomUUID();
  private static final IdempotencyPk PK = new IdempotencyPk(KEY, ENDPOINT);
  private static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private static final TestRequest REQUEST = new TestRequest("test@example.com", "Password!123");

  @Mock private IdempotencyRepository idemRepo;
  @Mock private PlatformTransactionManager txManager;

  private final IdempotencyProperties idemProps =
      new IdempotencyProperties("test-secret", Duration.ofHours(24));
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private final AtomicInteger runs = new AtomicInteger();

  private IdempotencyService idemService;

  @BeforeEach
  void setup() {
    idemService =
        new IdempotencyService(idemProps, idemRepo, jsonMapper, new TransactionTemplate(txManager));
  }

  // --- no key / bad key ---

  @Test
  void noKey_runsOperation_withoutTouchingStore() {
    // Act
    var response = idemService.execute(null, ENDPOINT, REQUEST, TestBody.class, this::created);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(runs).hasValue(1);
    verifyNoInteractions(idemRepo);
  }

  @Test
  void blankKey_throwsInvalidKey_andDoesNotRun() {
    // Act & Assert
    assertThatThrownBy(
            () -> idemService.execute("  ", ENDPOINT, REQUEST, TestBody.class, this::created))
        .isInstanceOf(InvalidIdempotencyKeyException.class);

    assertThat(runs).hasValue(0);
    verifyNoInteractions(idemRepo);
  }

  @Test
  void keyOverMaxLength_throwsInvalidKey() {
    // Arrange
    var key = "a".repeat(IdempotencyService.MAX_KEY_LENGTH + 1);

    // Act & Assert
    assertThatThrownBy(
            () -> idemService.execute(key, ENDPOINT, REQUEST, TestBody.class, this::created))
        .isInstanceOf(InvalidIdempotencyKeyException.class);

    assertThat(runs).hasValue(0);
    verifyNoInteractions(idemRepo);
  }

  @Test
  void keyAtMaxLength_isAccepted() {
    // Arrange: an unstubbed findById returns Optional.empty(), i.e. a miss
    var key = "a".repeat(IdempotencyService.MAX_KEY_LENGTH);

    // Act
    var response = idemService.execute(key, ENDPOINT, REQUEST, TestBody.class, this::created);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(runs).hasValue(1);
  }

  // --- first run ---

  @Test
  void firstRun_storesStatusBodyAndExpiry() {
    // Arrange
    when(idemRepo.findById(PK)).thenReturn(Optional.empty());

    // Act
    var response = idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, this::created);

    // Assert: the stored row
    var captor = ArgumentCaptor.forClass(IdempotencyKey.class);
    verify(idemRepo).saveAndFlush(captor.capture());
    var row = captor.getValue();
    assertThat(row.getId()).isEqualTo(PK);
    assertThat(row.getResponseStatus()).isEqualTo(201);
    assertThat(row.getResponseBody()).contains("\"ok\"");
    assertThat(row.getRequestHash()).isNotBlank();
    assertThat(row.getCallerId()).isNull();
    assertThat(row.getExpiresAt())
        .isCloseTo(Instant.now().plus(idemProps.ttl()), within(5, ChronoUnit.SECONDS));

    // Assert: the caller gets the real response, not a replay
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isEqualTo(new TestBody("ok"));
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isNull();
    assertThat(runs).hasValue(1);
  }

  // --- replay ---

  @Test
  void retryWithSameRequest_replaysStoredResponse_withoutRunning() {
    // Arrange
    var row = firstRun(REQUEST);
    when(idemRepo.findById(PK)).thenReturn(Optional.of(row));

    // Act
    var response = idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, this::created);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isEqualTo(new TestBody("ok"));
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs).hasValue(0);
    verify(idemRepo, never()).saveAndFlush(any());
  }

  @Test
  void retryWithDifferentPassword_throwsMismatch() {
    // Arrange: same email, different password -- proves the password is part of the hash
    var row = firstRun(REQUEST);
    when(idemRepo.findById(PK)).thenReturn(Optional.of(row));
    var changed = new TestRequest(REQUEST.email(), "Different!456");

    // Act & Assert
    assertThatThrownBy(
            () -> idemService.execute(KEY, ENDPOINT, changed, TestBody.class, this::created))
        .isInstanceOf(IdempotencyKeyMismatchException.class);
    assertThat(runs).hasValue(0);
    verify(idemRepo, never()).saveAndFlush(any());
  }

  @Test
  void storedNullBody_replaysNullBody() {
    // Arrange
    Supplier<ResponseEntity<TestBody>> noContent =
        () -> {
          runs.incrementAndGet();
          return ResponseEntity.noContent().build();
        };
    var row = firstRun(REQUEST, noContent);
    assertThat(row.getResponseBody()).isNull();
    when(idemRepo.findById(PK)).thenReturn(Optional.of(row));

    // Act
    var response = idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, noContent);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs).hasValue(0);
  }

  @Test
  void corruptedStoredBody_throwsReplayCorrupted() {
    // Arrange: same hash as a real run, but a body that isn't valid JSON
    var good = firstRun(REQUEST);
    var corrupted =
        new IdempotencyKey(
            KEY,
            ENDPOINT,
            null,
            good.getRequestHash(),
            201,
            "not json",
            Instant.now().plus(idemProps.ttl()));
    when(idemRepo.findById(PK)).thenReturn(Optional.of(corrupted));

    // Act & Assert
    assertThatThrownBy(
            () -> idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, this::created))
        .isInstanceOf(IdempotentReplayCorruptedException.class);
    assertThat(runs).hasValue(0);
  }

  // --- expiry ---

  @Test
  void expiredRow_isDeleted_andOperationRunsAgain() {
    // Arrange
    var expired =
        new IdempotencyKey(
            KEY, ENDPOINT, null, "any-hash", 201, "{}", Instant.now().minusSeconds(1));
    when(idemRepo.findById(PK)).thenReturn(Optional.of(expired));

    // Act
    var response = idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, this::created);

    // Assert
    verify(idemRepo).deleteById(PK);
    verify(idemRepo).saveAndFlush(any(IdempotencyKey.class));
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isNull();
    assertThat(runs).hasValue(1);
  }

  // --- failures and races (step F) ---

  @Test
  void operationFails_withNoStoredRow_rethrowsOriginal_andStoresNothing() {
    // Arrange
    var boom = new IllegalStateException("boom");
    when(idemRepo.findById(PK)).thenReturn(Optional.empty());
    Supplier<ResponseEntity<TestBody>> failing =
        () -> {
          runs.incrementAndGet();
          throw boom;
        };

    // Act & Assert
    assertThatThrownBy(() -> idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, failing))
        .isSameAs(boom);
    verify(idemRepo, never()).saveAndFlush(any());
    verify(idemRepo, times(2)).findById(PK); // step D, then step F's re-look-up
    assertThat(runs).hasValue(1);
  }

  @Test
  void lostRaceOnPrimaryKey_replaysWinner() {
    // Arrange: the winner's row isn't visible at step D, but is once our insert has failed
    var winner = firstRun(REQUEST);
    when(idemRepo.findById(PK)).thenReturn(Optional.empty(), Optional.of(winner));
    when(idemRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    // Act
    var response = idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, this::created);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs).hasValue(1); // ran, but its work was rolled back
  }

  @Test
  void lostRaceWithOperationsOwnException_replaysWinner() {
    // Arrange: the winner committed between step D and the operation's own duplicate check,
    // so the operation fails with its own exception (e.g. registration's 409)
    var winner = firstRun(REQUEST);
    when(idemRepo.findById(PK)).thenReturn(Optional.empty(), Optional.of(winner));
    Supplier<ResponseEntity<TestBody>> duplicate =
        () -> {
          runs.incrementAndGet();
          throw new IllegalStateException("stand-in for AccountAlreadyExistsException");
        };

    // Act
    var response = idemService.execute(KEY, ENDPOINT, REQUEST, TestBody.class, duplicate);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isEqualTo(new TestBody("ok"));
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");
    verify(idemRepo, never()).saveAndFlush(any());
  }

  @Test
  void lostRaceWithDifferentRequest_throwsMismatch() {
    // Arrange: the winner used the same key with a different body
    var winner = firstRun(REQUEST);
    when(idemRepo.findById(PK)).thenReturn(Optional.empty(), Optional.of(winner));
    when(idemRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
    var changed = new TestRequest(REQUEST.email(), "Different!456");

    // Act & Assert: the mismatch, not the original constraint failure
    assertThatThrownBy(
            () -> idemService.execute(KEY, ENDPOINT, changed, TestBody.class, this::created))
        .isInstanceOf(IdempotencyKeyMismatchException.class);
  }

  // --- caller ---

  @Test
  void firstRun_withCaller_storesCallerId() {
    // Arrange
    when(idemRepo.findById(PK)).thenReturn(Optional.empty());

    // Act
    var response =
        idemService.execute(KEY, ENDPOINT, CALLER, REQUEST, TestBody.class, this::created);

    // Assert: the stored row
    var captor = ArgumentCaptor.forClass(IdempotencyKey.class);
    verify(idemRepo).saveAndFlush(captor.capture());
    var row = captor.getValue();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isNull();
    assertThat(runs).hasValue(1);
    assertThat(row.getCallerId()).isEqualTo(CALLER);
  }

  @Test
  void retryBySameCaller_replaysStoredResponse() {
    // Arrange
    var row = firstRun(CALLER, REQUEST);

    when(idemRepo.findById(PK)).thenReturn(Optional.of(row));

    // Act
    var response =
        idemService.execute(KEY, ENDPOINT, CALLER, REQUEST, TestBody.class, this::created);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isEqualTo(new TestBody("ok"));
    assertThat(response.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs).hasValue(0);
    verify(idemRepo, never()).saveAndFlush(any());
  }

  @Test
  void retryByDifferentCaller_throwsMismatch_evenWithSameRequest() {
    // Arrange
    var row = firstRun(CALLER, REQUEST);

    when(idemRepo.findById(PK)).thenReturn(Optional.of(row));

    // Act & Assert
    assertThatThrownBy(
            () ->
                idemService.execute(
                    KEY, ENDPOINT, OTHER_CALLER, REQUEST, TestBody.class, this::created))
        .isInstanceOf(IdempotencyKeyMismatchException.class);

    assertThat(runs).hasValue(0);
    verify(idemRepo, never()).saveAndFlush(any());
  }

  @Test
  void retryWithCaller_againstRowWithNoCaller_throwsMismatch() {
    // Arrange
    var row = firstRun(REQUEST);

    when(idemRepo.findById(PK)).thenReturn(Optional.of(row));

    // Act & Assert
    assertThatThrownBy(
            () ->
                idemService.execute(KEY, ENDPOINT, CALLER, REQUEST, TestBody.class, this::created))
        .isInstanceOf(IdempotencyKeyMismatchException.class);

    assertThat(runs).hasValue(0);
    verify(idemRepo, never()).saveAndFlush(any());
  }

  @Test
  void lostRaceByDifferentCaller_throwsMismatch() {
    // Arrange: the winner's row isn't visible at step D, but is once our insert
    // has failed...and it belongs to another caller
    var winner = firstRun(OTHER_CALLER, REQUEST);

    when(idemRepo.findById(PK)).thenReturn(Optional.empty(), Optional.of(winner));
    when(idemRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    // Act & Assert
    assertThatThrownBy(
            () ->
                idemService.execute(KEY, ENDPOINT, CALLER, REQUEST, TestBody.class, this::created))
        .isInstanceOf(IdempotencyKeyMismatchException.class);

    verify(idemRepo).saveAndFlush(any());
    assertThat(runs).hasValue(1);
  }

  // --- helpers ---

  /** The operation under test: counts its runs and returns 201 with a fixed body. */
  private ResponseEntity<TestBody> created() {
    runs.incrementAndGet();
    return ResponseEntity.status(HttpStatus.CREATED).body(new TestBody("ok"));
  }

  /** {@link #firstRun(TestRequest, Supplier)} with the default {@link #created()} operation. */
  private IdempotencyKey firstRun(TestRequest request) {
    return firstRun(request, this::created);
  }

  /** {@link #firstRun(UUID, TestRequest, Supplier)} with no caller. */
  private IdempotencyKey firstRun(
      TestRequest request, Supplier<ResponseEntity<TestBody>> operation) {
    return firstRun(null, request, operation);
  }

  /**
   * {@link #firstRun(UUID, TestRequest, Supplier)} with the default {@link #created()} operation.
   */
  private IdempotencyKey firstRun(UUID callerId, TestRequest request) {
    return firstRun(callerId, request, this::created);
  }

  /**
   * Runs a first request through an empty store and returns the row the service saved, so a test
   * can stub it back as "already stored". Captured rather than built by hand so its request hash is
   * the service's own -- recomputing the HMAC here would duplicate the code under test. The row is
   * stored with {@code callerId}; {@code null} takes the no-caller path, as registration does.
   * Clears the repository's recorded calls and the run counter afterwards, so each test only sees
   * its own calls.
   */
  private IdempotencyKey firstRun(
      UUID callerId, TestRequest request, Supplier<ResponseEntity<TestBody>> operation) {
    when(idemRepo.findById(PK)).thenReturn(Optional.empty());
    idemService.execute(KEY, ENDPOINT, callerId, request, TestBody.class, operation);
    var captor = ArgumentCaptor.forClass(IdempotencyKey.class);
    verify(idemRepo).saveAndFlush(captor.capture());
    clearInvocations(idemRepo);
    runs.set(0);
    return captor.getValue();
  }

  record TestRequest(String email, String password) {}

  record TestBody(String value) {}
}
