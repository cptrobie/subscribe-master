package com.acuity.subscribemaster.idempotency;

import com.acuity.subscribemaster.auditlog.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class IdempotencyService {
  private final IdempotencyProperties idemProps;
  private final IdempotencyRepository idemRepo;
  private final AuditLogService auditLogSvc;

  private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);


  public IdempotencyService(IdempotencyProperties idemProps, IdempotencyRepository idemRepo, AuditLogService auditLogService) {
    this.idemProps = idemProps;
    this.idemRepo = idemRepo;
    this.auditLogSvc = auditLogService;
  }

  public <T> ResponseEntity<T> execute(
          String key,                              // the header value, may be null
          String endpoint,                         // e.g. "POST /api/v1/auth/register"
          Object request,                          // the request DTO, used for the hash
          Class<T> responseType,                   // e.g. RegistrationResponse.class, used for replay
          Supplier<ResponseEntity<T>> operation) { // the real work, run at most once per key
    // A. no key: just run it
    // B. validate the key
    // C. hash the request, build the IdempotencyPk
    // D. look for an existing row: found -> replay or reject
    // E. not found -> run and store (inside a transaction)
    // F. if E failed on a constraint -> look again: found -> replay or reject, else rethrow
  }
}
