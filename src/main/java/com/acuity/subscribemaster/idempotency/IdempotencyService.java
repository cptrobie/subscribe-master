package com.acuity.subscribemaster.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a POST operation at most once per {@code Idempotency-Key} (NFR-24), and replays the stored
 * response to any retry -- the reusable service NFR-33 builds for registration, and later consumers
 * (NFR-35, FR-12) call instead of building their own.
 *
 * <p>The header is optional: with no key, the operation simply runs. A key is scoped to its
 * endpoint ({@link IdempotencyPk}). A retry whose request matches the stored {@code request_hash}
 * gets the stored status and body; a different request under the same key is rejected as a
 * mismatch. Only successful responses are stored -- a failed operation rolls back, so its retry
 * runs again.
 *
 * <p>The request hash is an HMAC-SHA256 keyed with a server secret ({@link IdempotencyProperties}),
 * not a plain hash: a registration body contains the password, and a plain SHA-256 of it would be a
 * fast, unsalted, offline-guessable record of it.
 */
@Service
public class IdempotencyService {
  public static final int MAX_KEY_LENGTH = 255;

  private final IdempotencyProperties idemProps;
  private final IdempotencyRepository idemRepo;
  private final JsonMapper jsonMapper;
  private final TransactionTemplate txnTemplate;

  private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);

  public IdempotencyService(
      IdempotencyProperties idemProps,
      IdempotencyRepository idemRepo,
      JsonMapper jsonMapper,
      TransactionTemplate txnTemplate) {
    this.idemProps = idemProps;
    this.idemRepo = idemRepo;
    this.jsonMapper = jsonMapper;
    this.txnTemplate = txnTemplate;
  }

  /**
   * Runs {@code operation} at most once for {@code key} on {@code endpoint}, or replays the
   * response a previous run stored.
   *
   * <p>A concurrent duplicate with the same key waits for the first request's transaction, fails on
   * the primary key, and then replays the winner's stored row. The operation and the key row commit
   * in one transaction, so a losing duplicate leaves nothing behind -- no account, no key row, no
   * after-commit email.
   *
   * <p>Must not be called from inside an existing transaction: {@code runAndStore} would join it
   * instead of opening its own, so a lost race would mark the caller's transaction rollback-only
   * and the replay lookup would never see the winner's row.
   *
   * @param key the {@code Idempotency-Key} header value; {@code null} runs the operation unguarded
   * @param endpoint the method and path the key is scoped to, e.g. {@code "POST
   *     /api/v1/auth/register"}
   * @param request the request DTO, hashed to detect a key reused with a different request
   * @param responseType the response body's type, used to read a stored body back on replay
   * @param operation the real work; it should be {@code @Transactional} (default propagation) so it
   *     joins the transaction the key row is saved in
   * @return the operation's response, or the stored one marked {@code Idempotent-Replayed: true}
   * @throws InvalidIdempotencyKeyException if the key is blank or longer than 255 characters
   * @throws IdempotencyKeyMismatchException if the key was already used with a different request
   * @throws IdempotentReplayCorruptedException if a stored body can't be read back
   */
  public <T> ResponseEntity<T> execute(
      String key,
      String endpoint,
      Object request,
      Class<T> responseType,
      Supplier<ResponseEntity<T>> operation) {
    // A. no key: just run it
    if (key == null) {
      return operation.get();
    }

    // B. validate the key
    validateKey(key);

    // C. hash the request, build the IdempotencyPk
    var hash = requestHash(request);
    var pk = new IdempotencyPk(key, endpoint);

    // D. look for an existing row: found -> replay or reject
    var existing = findLive(pk);
    if (existing.isPresent()) {
      return replayOrReject(existing.get(), hash, responseType);
    }

    // E. not found -> run and store (inside a transaction)
    try {
      return runAndStore(pk, hash, operation);
    } catch (RuntimeException e) {
      // F. if E failed for any reason -> look again: found -> replay or reject, else rethrow
      return findLive(pk).map(row -> replayOrReject(row, hash, responseType)).orElseThrow(() -> e);
    }
  }

  /**
   * Runs {@code operation} and inserts its key row in one new transaction, so both commit or
   * neither does. Only a successful run reaches the insert: if the operation throws, everything
   * rolls back and nothing is stored, so a retry runs it again.
   *
   * <p>{@code saveAndFlush} sends the insert immediately rather than at commit, so a concurrent
   * duplicate fails here: usually with a {@link DataIntegrityViolationException}, on this key's
   * primary key or a constraint the operation itself hit, but also with the operation's own
   * exception if the first request committed just before the operation's own checks ran (e.g.
   * registration's {@code existsByEmail} then throws 409). Nothing is caught here: the transaction
   * is already aborted, and {@link #execute} sorts out a lost race from a real failure once the
   * rollback is done.
   */
  private <T> ResponseEntity<T> runAndStore(
      IdempotencyPk pk, String hash, Supplier<ResponseEntity<T>> operation) {
    return txnTemplate.execute(
        status -> {
          var response = operation.get(); // joins this transaction (REQUIRED)
          var json =
              response.getBody() == null ? null : jsonMapper.writeValueAsString(response.getBody());
          idemRepo.saveAndFlush(
              new IdempotencyKey(
                  pk.idempotencyKey(),
                  pk.endpoint(),
                  null, // callerId: registration has no logged-in caller
                  hash,
                  response.getStatusCode().value(),
                  json,
                  Instant.now().plus(idemProps.ttl())));
          return response;
        });
  }

  /**
   * Replays {@code row}'s stored status and body if this request's hash matches the stored one,
   * marked with an {@code Idempotent-Replayed: true} header; otherwise throws {@link
   * IdempotencyKeyMismatchException}. Used for both an ordinary retry and a concurrent duplicate
   * that lost the insert race.
   *
   * @throws IdempotentReplayCorruptedException if the stored body can't be read back as {@code
   *     responseType}
   */
  private <T> ResponseEntity<T> replayOrReject(
      IdempotencyKey row, String hash, Class<T> responseType) {
    // constant-time compare, so response timing can't reveal how much of the stored hash matched
    var isMatch =
        MessageDigest.isEqual(
            row.getRequestHash().getBytes(StandardCharsets.UTF_8),
            hash.getBytes(StandardCharsets.UTF_8));

    if (!isMatch) {
      logger.warn("Idempotency-Key reused with a different request on {}", row.getId().endpoint());
      throw new IdempotencyKeyMismatchException();
    }
    T body;
    try {
      body =
          row.getResponseBody() == null
              ? null
              : jsonMapper.readValue(row.getResponseBody(), responseType);
    } catch (JacksonException e) {
      throw new IdempotentReplayCorruptedException(e);
    }
    logger.info("Replayed stored response on {}", row.getId().endpoint());
    return ResponseEntity.status(row.getResponseStatus())
        .header("Idempotent-Replayed", "true")
        .body(body);
  }

  /**
   * The stored row for {@code pk}, if there is one that hasn't expired. An expired row is deleted
   * and reported as absent: it still holds the primary key, so leaving it would make the insert for
   * this request fail.
   */
  private Optional<IdempotencyKey> findLive(IdempotencyPk pk) {
    var found = idemRepo.findById(pk);
    if (found.isPresent() && !Instant.now().isBefore(found.get().getExpiresAt())) {
      idemRepo.deleteById(pk); // free the key so the insert in step E can succeed
      return Optional.empty();
    }
    return found;
  }

  /**
   * HMAC-SHA256 of {@code request} serialized to JSON, hex-encoded. Keyed with the server secret,
   * because a registration body contains the password (see the class Javadoc). The same DTO always
   * serializes the same way, so differences in the client's JSON formatting or field order don't
   * count as a different request.
   */
  private String requestHash(Object request) {
    try {
      var requestString = jsonMapper.writeValueAsString(request);
      var secretKey =
          new SecretKeySpec(idemProps.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(secretKey);

      return HexFormat.of().formatHex(mac.doFinal(requestString.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is JDK-guaranteed, and @NotBlank rules out an empty key at startup --
      // reaching here means a broken JVM, not bad input.
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /**
   * Rejects a key that is blank or longer than {@value #MAX_KEY_LENGTH} characters. The column is
   * {@code TEXT}, so this cap is the only thing stopping a client filling the primary-key index
   * with arbitrarily long keys.
   *
   * @throws InvalidIdempotencyKeyException mapped to 400 {@code MALFORMED_REQUEST}
   */
  private void validateKey(String key) {
    if (key.isBlank()) {
      throw new InvalidIdempotencyKeyException("Idempotency-Key must not be blank");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new InvalidIdempotencyKeyException(
          "Idempotency-Key length must be at most " + MAX_KEY_LENGTH + " characters");
    }
  }
}
